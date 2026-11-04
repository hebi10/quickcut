package com.hebi.quickcut.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn as AndroidOptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.EditorSessionSnapshot
import com.hebi.quickcut.editor.EditorSessionStore
import com.hebi.quickcut.editor.EditorViewModel
import com.hebi.quickcut.editor.QuickCutPage
import com.hebi.quickcut.editor.formatTime
import com.hebi.quickcut.media.MediaReader
import com.hebi.quickcut.settings.QuickCutSettings
import com.hebi.quickcut.settings.QuickTrimSettings
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@AndroidOptIn(androidx.media3.common.util.UnstableApi::class)
class StoreScenicCaptureInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var editor: EditorViewModel
    private lateinit var settings: QuickCutSettings
    private lateinit var session: EditorSessionStore
    private lateinit var previousQuick: QuickTrimSettings
    private var previousSession: EditorSessionSnapshot? = null
    private lateinit var source: File
    private val captures = JSONArray()

    @Before fun prepareNewScenicSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        settings = QuickCutSettings(context)
        previousQuick = settings.loadQuick()
        settings.saveQuick(QuickTrimSettings(0L, 0L, false))
        session = EditorSessionStore(context)
        previousSession = session.load()
        session.clear()
        source = File(context.cacheDir, "store_scenic_coast.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("quickcut_store_scenic.mp4").use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
        val metadata = MediaReader.metadata(context, Uri.fromFile(source))
        assertEquals("The imported store source must be the new eight-second video", 8_000L, metadata.durationMs)
        assertTrue("The source must contain the generated AAC audio track", metadata.hasAudio)
        checkNotNull(MediaReader.thumbnail(context, Uri.fromFile(source), 4_000L))
        compose.runOnUiThread { editor = EditorViewModel(context.applicationContext as Application) }
    }

    @After fun restoreModels() {
        if (::editor.isInitialized) compose.runOnUiThread {
            editor.discardEditing()
            ViewModelStore().apply { put("editor", editor) }.clear()
        }
        if (::settings.isInitialized) settings.saveQuick(previousQuick)
        if (::session.isInitialized) previousSession?.let(session::save) ?: session.clear()
        if (::source.isInitialized) source.delete()
    }

    @Test fun captureQuickAndDetailedWithNewScenicVideo() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.runOnUiThread {
            editor.changeQuickMode(true)
            editor.importVideos(context, listOf(Uri.fromFile(source)))
        }
        compose.waitUntil(25_000L) { editor.page == QuickCutPage.EDITOR }
        var detailed by mutableStateOf(false)
        lateinit var focusManager: FocusManager
        compose.setContent {
            focusManager = LocalFocusManager.current
            QuickCutTheme {
                Box(Modifier.fillMaxSize().background(CutColors.background).safeDrawingPadding()) {
                    if (detailed) EditorScreen(editor, {}, {})
                    else QuickTrimScreen(editor, settings, {}, {
                        editor.changeQuickMode(false)
                        detailed = true
                    }, {}, {})
                }
            }
        }
        compose.onNodeWithContentDescription("앞부분 삭제 시간(초)").performTextReplacement("4")
        compose.onNodeWithContentDescription("뒷부분 삭제 시간(초)").performTextReplacement("0.623")
        compose.runOnIdle { focusManager.clearFocus(force = true) }
        compose.runOnUiThread {
            val keyboard = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            keyboard.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.onNodeWithContentDescription("앞부분 삭제 시간(초)").assertTextContains("4")
        compose.onNodeWithContentDescription("뒷부분 삭제 시간(초)").assertTextContains("0.623")
        assertReviewedBounds()
        waitForRealPausedPreview(ExoPlayer::class.java)
        compose.onNodeWithText("영상 저장").assertIsDisplayed().assertIsEnabled()
        capture("store_scenic_quick_trim", thumbnailVisible = false)

        compose.onNodeWithText("상세 편집").assertIsDisplayed().performClick()
        waitForRealPausedPreview(CompositionPlayer::class.java)
        compose.onNodeWithText("상세 편집").assertIsDisplayed()
        compose.onNodeWithText("확대 1배").assertIsDisplayed()
        compose.waitUntil(15_000L) { timelineThumbnailHasImage() }
        assertReviewedBounds()
        capture("store_scenic_detailed", thumbnailVisible = true)

        val evidence = JSONObject().put("sourceFile", "quickcut_store_scenic.mp4")
            .put("sourceSha256", sha256(source)).put("sourceDurationMs", 8_000L)
            .put("leadingRemovedMs", 4_000L).put("trailingRemovedMs", 623L)
            .put("selectedStartMs", 4_000L).put("selectedEndMs", 7_377L)
            .put("resultDurationMs", 3_377L).put("captures", captures)
        saveEvidenceFile("evidence.json", evidence.toString(2))
        saveEvidenceFile("passed.txt", "Actual new source imported; both real player surfaces rendered and paused; timeline bounds retained.\n")
    }

    private fun assertReviewedBounds() = compose.runOnIdle {
        val clip = editor.timeline.clips.single()
        assertEquals(8_000L, clip.sourceDurationMs)
        assertEquals(4_000L, clip.startMs)
        assertEquals(7_377L, clip.endMs)
        assertEquals(3_377L, clip.durationMs)
        assertTrue(editor.errorMessage == null)
        assertTrue(editor.canStartExport)
    }

    private fun waitForRealPausedPreview(expectedType: Class<out Player>) {
        compose.waitForIdle()
        lateinit var player: Player
        compose.waitUntil(25_000L) {
            var ready = false
            compose.runOnUiThread {
                previewView()?.player?.let { candidate ->
                    if (expectedType.isInstance(candidate) && candidate.playbackState == Player.STATE_READY) {
                        player = candidate
                        ready = true
                    }
                }
            }
            ready
        }
        val frameSeen = AtomicBoolean(false)
        val firstFrameReleaseNs = AtomicLong(C.TIME_UNSET)
        val listener = VideoFrameMetadataListener { _, releaseTimeNs, _, _ ->
            if (!frameSeen.get()) {
                firstFrameReleaseNs.set(releaseTimeNs)
                frameSeen.set(true)
            }
        }
        compose.runOnIdle {
            assertSame(player, previewView()?.player)
            when (val active = player) {
                is ExoPlayer -> active.setVideoFrameMetadataListener(listener)
                is CompositionPlayer -> active.setVideoFrameMetadataListener(listener)
                else -> error("Unsupported preview player")
            }
        }
        try {
            compose.onNodeWithContentDescription("재생").performClick()
            compose.waitUntil(5_000L) {
                compose.onAllNodesWithContentDescription("일시정지").fetchSemanticsNodes().size == 1
            }
            compose.waitUntil(5_000L) {
                frameSeen.get() && firstFrameReleaseNs.get().let { it == C.TIME_UNSET || System.nanoTime() >= it }
            }
            compose.onNodeWithContentDescription("일시정지").performClick()
            compose.waitUntil(15_000L) { previewSurfaceHasImage() }
            compose.runOnIdle {
                assertSame(player, previewView()?.player)
                assertEquals(Player.STATE_READY, player.playbackState)
                assertTrue("The captured preview must be paused", !player.playWhenReady && !player.isPlaying)
                assertTrue("Preview decoding must not report an error", player.playerError == null)
            }
            assertReviewedBounds()
        } finally {
            compose.runOnUiThread {
                when (val active = player) {
                    is ExoPlayer -> active.clearVideoFrameMetadataListener(listener)
                    is CompositionPlayer -> active.setVideoFrameMetadataListener { _, _, _, _ -> }
                    else -> Unit
                }
            }
        }
    }

    private fun previewView(): PlayerView? {
        fun find(view: View): PlayerView? {
            if (view is PlayerView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                find(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        return find(compose.activity.window.decorView)
    }

    private fun previewSurfaceHasImage(): Boolean {
        var surface: SurfaceView? = null
        compose.runOnUiThread { surface = previewView()?.videoSurfaceView as? SurfaceView }
        val view = surface ?: return false
        if (!view.holder.surface.isValid || view.width <= 0 || view.height <= 0) return false
        val bitmap = Bitmap.createBitmap(256, 144, Bitmap.Config.ARGB_8888)
        val completion = CountDownLatch(1)
        val result = AtomicInteger(-1)
        val lifetime = Any()
        var finished = false
        var abandoned = false
        var requested = false
        return try {
            PixelCopy.request(view, bitmap, { status ->
                result.set(status)
                synchronized(lifetime) {
                    finished = true
                    if (abandoned) bitmap.recycle()
                }
                completion.countDown()
            }, Handler(Looper.getMainLooper()))
            requested = true
            completion.await(2L, TimeUnit.SECONDS) && result.get() == PixelCopy.SUCCESS && bitmapHasScene(bitmap)
        } catch (_: IllegalArgumentException) {
            false
        } finally {
            synchronized(lifetime) {
                abandoned = true
                if (!requested || finished) bitmap.recycle()
            }
        }
    }

    private fun bitmapHasScene(bitmap: Bitmap): Boolean {
        var nonBlack = 0
        var colored = 0
        val brightness = mutableListOf<Int>()
        for (x in 2..8) for (y in 2..8) {
            val color = bitmap.getPixel(bitmap.width * x / 10, bitmap.height * y / 10)
            val channels = listOf(Color.red(color), Color.green(color), Color.blue(color))
            if (channels.max() > 35) nonBlack++
            if (channels.max() - channels.min() > 20) colored++
            brightness += channels.sum() / 3
        }
        return nonBlack >= 35 && colored >= 10 && brightness.max() - brightness.min() >= 20
    }

    private fun timelineThumbnailHasImage(): Boolean {
        val tile = compose.onNode(hasClickAction() and hasText(formatTime(3_377L)))
            .fetchSemanticsNode().boundsInWindow
        if (tile.width <= 0f || tile.height <= 0f) return false
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return false
        return try {
            var scenePixels = 0
            for (x in 2..8) for (y in 2..5) {
                val px = (tile.left + tile.width * x / 10).toInt().coerceIn(0, screenshot.width - 1)
                val py = (tile.top + tile.height * y / 10).toInt().coerceIn(0, screenshot.height - 1)
                val color = screenshot.getPixel(px, py)
                val channels = listOf(Color.red(color), Color.green(color), Color.blue(color))
                if (channels.max() > 45 && channels.max() - channels.min() > 25) scenePixels++
            }
            scenePixels >= 10
        } finally { screenshot.recycle() }
    }

    private fun capture(name: String, thumbnailVisible: Boolean) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(300L, 5_000L)
        assertReviewedBounds()
        assertTrue("The actual video surface must contain a decoded scenic frame", previewSurfaceHasImage())
        if (thumbnailVisible) assertTrue("The actual timeline thumbnail must be loaded", timelineThumbnailHasImage())
        compose.runOnIdle {
            val player = checkNotNull(previewView()?.player)
            assertEquals(Player.STATE_READY, player.playbackState)
            assertTrue(!player.playWhenReady && !player.isPlaying)
        }
        val output = File(evidenceDirectory(), "$name.png")
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            captures.put(JSONObject().put("file", output.name).put("sha256", sha256(output))
                .put("width", bitmap.width).put("height", bitmap.height)
                .put("frameMetadataObserved", true).put("surfacePixelCopyVerified", true)
                .put("playerReady", true).put("paused", true).put("timelineThumbnailVisible", thumbnailVisible))
        } finally { bitmap.recycle() }
        copyForRunner(output)
    }

    private fun evidenceDirectory(): File {
        val directory = File(checkNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)),
            "store-scenic-capture")
        check(directory.isDirectory || directory.mkdirs())
        return directory
    }

    private fun saveEvidenceFile(name: String, value: String) {
        val file = File(evidenceDirectory(), name)
        file.writeText(value, Charsets.UTF_8)
        copyForRunner(file)
    }

    private fun copyForRunner(output: File) {
        fun shell(command: String): String {
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() }
        }
        require(output.absolutePath.matches(Regex("[A-Za-z0-9_./-]+")))
        shell("mkdir -p /data/local/tmp/quickcut-store-scenic")
        val destination = "/data/local/tmp/quickcut-store-scenic/${output.name}"
        shell("cp ${output.absolutePath} $destination")
        val copied = shell("wc -c $destination").substringBefore(' ').toLongOrNull()
        assertTrue("Evidence must survive instrumentation cleanup", output.length() > 0L && copied == output.length())
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
