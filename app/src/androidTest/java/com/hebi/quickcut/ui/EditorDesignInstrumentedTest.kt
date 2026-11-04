package com.hebi.quickcut.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceView
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
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
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.batch.BatchClipMode
import com.hebi.quickcut.batch.BatchScreen
import com.hebi.quickcut.batch.BatchSessionStore
import com.hebi.quickcut.batch.BatchViewModel
import com.hebi.quickcut.editor.EditorSessionSnapshot
import com.hebi.quickcut.editor.EditorSessionStore
import com.hebi.quickcut.editor.EditorViewModel
import com.hebi.quickcut.editor.OriginalAction
import com.hebi.quickcut.editor.QuickCutPage
import com.hebi.quickcut.media.MediaReader
import com.hebi.quickcut.settings.BatchDraftSettings
import com.hebi.quickcut.settings.QuickCutSettings
import com.hebi.quickcut.settings.QuickTrimSettings
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
@AndroidOptIn(androidx.media3.common.util.UnstableApi::class)
class EditorDesignInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var editor: EditorViewModel
    private lateinit var batch: BatchViewModel
    private lateinit var settings: QuickCutSettings
    private lateinit var session: EditorSessionStore
    private lateinit var previousQuick: QuickTrimSettings
    private lateinit var previousBatch: BatchDraftSettings
    private lateinit var previousOriginal: OriginalAction
    private var previousSession: EditorSessionSnapshot? = null
    private lateinit var source: File
    private lateinit var batchStore: BatchSessionStore
    private var lastPreviewSample = JSONObject()

    @Before fun prepareModels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        settings = QuickCutSettings(context)
        previousQuick = settings.loadQuick()
        previousBatch = settings.loadBatch()
        previousOriginal = settings.loadOriginalAction()
        settings.saveQuick(QuickTrimSettings(0L, 0L, false))
        session = EditorSessionStore(context)
        previousSession = session.load()
        source = File(context.cacheDir, "editor_design_fixture.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("quickcut_keyframes.mp4").use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
        MediaMetadataRetriever().apply {
            try {
                setDataSource(context, Uri.fromFile(source))
                Log.i("QuickCutPreviewTest", "fixture width=${extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)} " +
                    "height=${extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)} " +
                    "rotation=${extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)} " +
                    "duration_ms=${extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)}")
            } finally { release() }
        }
        batchStore = BatchSessionStore(File(context.cacheDir, "editor_design_batch_session.json"))
        batchStore.clear()
        compose.runOnUiThread {
            val application = context.applicationContext as Application
            editor = EditorViewModel(application)
            batch = BatchViewModel(application, batchStore)
            batch.draftMode = BatchClipMode.REMOVE_EDGES
            batch.draftLeadingSeconds = "1"
            batch.draftTrailingSeconds = "1"
            batch.originalAction = OriginalAction.KEEP
        }
    }

    @After fun restoreModels() {
        if (::editor.isInitialized) compose.runOnUiThread {
            editor.discardEditing()
            ViewModelStore().apply { put("editor", editor); put("batch", batch) }.clear()
        }
        if (::settings.isInitialized) {
            settings.saveQuick(previousQuick)
            settings.saveBatch(previousBatch)
            settings.saveOriginalAction(previousOriginal)
        }
        if (::session.isInitialized) previousSession?.let(session::save) ?: session.clear()
        if (::batchStore.isInitialized) batchStore.clear()
        if (::source.isInitialized) source.delete()
    }

    @Test fun batchEmptySelectedAndDropdownRemainUsable() {
        showBatch(fontScale = 1f)
        compose.onNodeWithTag("batch-add-videos").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("batch-save").assertIsNotEnabled()
        compose.onNodeWithTag("batch-save-disabled-reason").assertIsDisplayed()
        capture("editor_design_batch_empty")
        compose.onNodeWithTag("batch-mode").performScrollTo().performClick()
        compose.onNodeWithText("앞뒤 구간 자르기").assertIsSelected()
        capture("editor_design_batch_menu")
        compose.onNodeWithText("앞뒤 구간 자르기").performClick()
        compose.runOnUiThread {
            batch.addVideos(InstrumentationRegistry.getInstrumentation().targetContext, listOf(Uri.fromFile(source)))
        }
        compose.waitUntil(25_000L) { batch.files.size == 1 && !batch.isImporting }
        compose.onNodeWithTag("batch-save").assertIsEnabled()
        compose.onNodeWithTag("batch-mode").performScrollTo()
        capture("editor_design_batch_selected")
        compose.onNodeWithText("템플릿·저장 옵션 펼치기").performScrollTo().performClick()
        compose.onNodeWithTag("batch-date").performScrollTo().assertIsDisplayed()
        capture("editor_design_batch_options")
    }

    @Test fun largeFontBatchKeepsSelectionAndSaveReasonReachable() {
        showBatch(fontScale = 1.6f)
        compose.onNodeWithTag("batch-add-videos").performScrollTo().assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("batch-save-disabled-reason").assertIsDisplayed()
        compose.onNodeWithTag("batch-mode").performScrollTo().performClick()
        compose.onNodeWithText("처음 + 마지막 구간 연결").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(BatchClipMode.JOIN_EDGES, batch.draftMode) }
        capture("editor_design_batch_large_font")
    }

    @Test fun quickAndDetailedShowResultTimeWithoutChangingTrimBounds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.runOnUiThread {
            editor.changeQuickMode(true)
            editor.importVideos(context, listOf(Uri.fromFile(source)))
        }
        compose.waitUntil(25_000L) { editor.page == QuickCutPage.EDITOR }
        compose.runOnUiThread { editor.trimSelected(4_000L, 7_400L) }
        checkNotNull(MediaReader.thumbnail(context, Uri.fromFile(source), 4_000L))
        var detailed by mutableStateOf(false)
        compose.setContent {
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
        waitForPreview(ExoPlayer::class.java)
        capture("editor_design_quick_trim")
        compose.onNodeWithText("영상 저장").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle {
            assertEquals(4_000L, editor.timeline.clips.single().startMs)
            assertEquals(7_400L, editor.timeline.clips.single().endMs)
        }
        compose.runOnUiThread { editor.changeQuickMode(false); detailed = true }
        waitForPreview(CompositionPlayer::class.java)
        compose.onNodeWithText("상세 편집").assertIsDisplayed()
        compose.onNodeWithText("확대 1배").assertIsDisplayed()
        capture("editor_design_detailed")
        compose.runOnIdle {
            assertEquals(4_000L, editor.timeline.clips.single().startMs)
            assertEquals(7_400L, editor.timeline.clips.single().endMs)
        }
    }

    private fun showBatch(fontScale: Float) {
        compose.setContent {
            QuickCutTheme {
                Box(Modifier.fillMaxSize().background(CutColors.background).safeDrawingPadding()) {
                    DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                        if (fontScale > 1f) {
                            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 800.dp))) {
                                BatchScreen(batch, {}, {}, { _, _, _ -> })
                            }
                        } else BatchScreen(batch, {}, {}, { _, _, _ -> })
                    }
                }
            }
        }
    }

    private fun waitForPreview(expectedPlayerType: Class<out Player>) {
        // A model change is not a recomposition barrier: discard the previous screen's view first.
        compose.waitForIdle()
        lateinit var player: Player
        compose.waitUntil(25_000L) {
            var ready = false
            compose.runOnUiThread {
                val candidate = previewView()?.player
                if (candidate != null && expectedPlayerType.isInstance(candidate) &&
                    candidate.playbackState == Player.STATE_READY) {
                    player = candidate
                    ready = true
                }
            }
            ready
        }
        val frameSeen = AtomicBoolean(false)
        val firstFrameReleaseNs = AtomicLong(C.TIME_UNSET)
        val frameListener = VideoFrameMetadataListener { _, releaseTimeNs, _, _ ->
            if (!frameSeen.get()) {
                Log.i("QuickCutPreviewTest", "elapsed_ns=${SystemClock.elapsedRealtimeNanos()} mono_ns=${System.nanoTime()} event=metadata.first " +
                    "player=${System.identityHashCode(player)} release_mono_ns=$releaseTimeNs")
                firstFrameReleaseNs.set(releaseTimeNs)
                frameSeen.set(true)
            }
        }
        compose.runOnIdle {
            assertSame("Frame listener must observe the visible preview", player, previewView()?.player)
            when (val activePlayer = player) {
                is ExoPlayer -> activePlayer.setVideoFrameMetadataListener(frameListener)
                is CompositionPlayer -> activePlayer.setVideoFrameMetadataListener(frameListener)
                else -> error("Unsupported preview player")
            }
        }
        try {
            Log.i("QuickCutPreviewTest", "elapsed_ns=${SystemClock.elapsedRealtimeNanos()} mono_ns=${System.nanoTime()} event=action.play player=${System.identityHashCode(player)}")
            compose.onNodeWithContentDescription("재생").performClick()
            compose.waitUntil(5_000L) {
                compose.onAllNodesWithContentDescription("일시정지").fetchSemanticsNodes().size == 1
            }
            // Playing can precede video output. Wait cheaply for the next frame's display time,
            // then pause before expensive pixel copies can outlive this short fixture.
            if (InstrumentationRegistry.getArguments().getString("quickcutDiagnosticVisibleBeforePause") == "true") {
                Log.i("QuickCutPreviewTest", "barrier=actual_pixels_before_pause")
                try {
                    compose.waitUntil(5_000L) {
                        compose.runOnUiThread {
                            check(player.playbackState != Player.STATE_ENDED) {
                                "Diagnostic clip ended before visible pixels; no replay or extension"
                            }
                        }
                        previewHasRenderedColor()
                    }
                    compose.runOnUiThread {
                        check(player.isPlaying && player.playbackState != Player.STATE_ENDED) {
                            "Diagnostic clip stopped before pause"
                        }
                    }
                    Log.i("QuickCutPreviewTest", "pre_pause_sample=$lastPreviewSample")
                } catch (failure: Throwable) {
                    runCatching { capturePreviewFailure(player) }
                    throw failure
                }
            } else {
                compose.waitUntil(5_000L) {
                    frameSeen.get() && firstFrameReleaseNs.get().let { releaseNs ->
                        releaseNs == C.TIME_UNSET || System.nanoTime() >= releaseNs
                    }
                }
            }
            compose.onNodeWithContentDescription("일시정지").performClick()
            Log.i("QuickCutPreviewTest", "elapsed_ns=${SystemClock.elapsedRealtimeNanos()} mono_ns=${System.nanoTime()} event=action.pause player=${System.identityHashCode(player)}")
            try {
                compose.waitUntil(15_000L) { previewHasRenderedColor() }
            } catch (failure: Throwable) {
                runCatching { capturePreviewFailure(player) }.onFailure {
                    Log.w("QuickCutPreviewTest", "Diagnostic capture failed: ${it.javaClass.simpleName}")
                }
                throw failure
            }
            compose.runOnIdle {
                assertSame("Paused preview must retain the observed player", player, previewView()?.player)
                assertTrue("Pause must retain the prepared preview", player.playbackState == Player.STATE_READY)
                assertTrue("The preview must remain paused", !player.playWhenReady)
            }
            compose.waitForIdle()
        } finally {
            compose.runOnUiThread {
                when (val activePlayer = player) {
                    is ExoPlayer -> activePlayer.clearVideoFrameMetadataListener(frameListener)
                    // CompositionPlayer has no clear API and its setter requires a non-null listener.
                    is CompositionPlayer -> activePlayer.setVideoFrameMetadataListener { _, _, _, _ -> }
                    else -> Unit
                }
            }
        }
    }

    private fun previewView(): PlayerView? {
        fun find(view: View): PlayerView? {
            if (view is PlayerView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                find(view.getChildAt(i))?.let { return it }
            }
            return null
        }
        return find(compose.activity.window.decorView)
    }

    private fun previewHasRenderedColor(): Boolean {
        var bounds: IntArray? = null
        compose.runOnUiThread {
            previewView()?.let { view ->
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                bounds = intArrayOf(location[0], location[1], view.width, view.height)
            }
        }
        val area = bounds ?: return false
        if (area[2] <= 0 || area[3] <= 0) return false
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return false
        return try {
            var colored = 0
            val pixels = JSONArray()
            for (x in 3..7) for (y in 3..7) {
                val px = (area[0] + area[2] * x / 10).coerceIn(0, bitmap.width - 1)
                val py = (area[1] + area[3] * y / 10).coerceIn(0, bitmap.height - 1)
                val value = bitmap.getPixel(px, py)
                pixels.put(value)
                val channels = listOf(Color.red(value), Color.green(value), Color.blue(value))
                if (channels.max() - channels.min() > 35 && channels.max() > 50) colored++
            }
            lastPreviewSample = JSONObject().put("sample_elapsed_ns", SystemClock.elapsedRealtimeNanos()).put("sample_mono_ns", System.nanoTime())
                .put("bounds", JSONArray(area.toList())).put("colored_pixels", colored).put("sampled_argb", pixels)
                .put("screenshot_width", bitmap.width).put("screenshot_height", bitmap.height)
            colored >= 5
        } finally { bitmap.recycle() }
    }

    private fun capturePreviewFailure(player: Player) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "preview-diagnostics")
        check(directory.isDirectory || directory.mkdirs())
        val surfaceImage = File(directory, "preview-surface.png")
        check(!surfaceImage.exists() || surfaceImage.delete()) { "Cannot remove the previous Surface diagnostic" }
        val state = lastPreviewSample.put("capture_elapsed_ns", SystemClock.elapsedRealtimeNanos()).put("capture_mono_ns", System.nanoTime())
        var outputView: SurfaceView? = null
        var outputWidth = 0
        var outputHeight = 0
        compose.runOnUiThread {
            val view = previewView()
            outputView = view?.videoSurfaceView as? SurfaceView
            outputWidth = outputView?.width ?: 0
            outputHeight = outputView?.height ?: 0
            val surface = (view?.videoSurfaceView as? SurfaceView)?.holder?.surface
            state.put("player_id", System.identityHashCode(player)).put("view_id", System.identityHashCode(view))
                .put("current_position_ms", player.currentPosition).put("playback_state", player.playbackState)
                .put("play_when_ready", player.playWhenReady).put("is_playing", player.isPlaying)
                .put("surface_description", surface?.toString()).put("surface_valid", surface?.isValid)
                .put("native_generation_source", "MediaCodec logcat surface generation")
                .put("video_width", player.videoSize.width).put("video_height", player.videoSize.height)
            for ((name, target) in listOf("player_view_bounds" to view, "surface_view_bounds" to view?.videoSurfaceView)) {
                target?.let {
                    val location = IntArray(2)
                    it.getLocationOnScreen(location)
                    state.put(name, JSONArray(listOf(location[0], location[1], it.width, it.height)))
                }
            }
            for (name in listOf("exo_shutter", "exo_image", "exo_artwork")) {
                val id = instrumentation.targetContext.resources.getIdentifier(name, "id", instrumentation.targetContext.packageName)
                val overlay = if (id != 0) view?.findViewById<View>(id) else null
                state.put(name, JSONObject().put("found", overlay != null).put("visibility", overlay?.visibility ?: -1)
                    .put("alpha", overlay?.alpha ?: -1f).put("shown", overlay?.isShown ?: false))
            }
            state.put("overlay_capture_mono_ns", System.nanoTime())
        }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "preview-failure.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
        val surfaceView = outputView
        if (surfaceView != null && outputWidth > 0 && outputHeight > 0) {
            val copy = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
            val finished = CountDownLatch(1)
            val result = AtomicInteger(-1)
            val requestTime = AtomicLong(0L)
            val callbackTime = AtomicLong(0L)
            state.put("pixel_copy_start_mono_ns", System.nanoTime())
            compose.runOnUiThread {
                try {
                    requestTime.set(System.nanoTime())
                    PixelCopy.request(surfaceView, copy, { status ->
                        result.set(status)
                        callbackTime.set(System.nanoTime())
                        finished.countDown()
                    }, Handler(Looper.getMainLooper()))
                } catch (error: Exception) {
                    result.set(-2)
                    callbackTime.set(System.nanoTime())
                    finished.countDown()
                }
            }
            check(Looper.myLooper() != Looper.getMainLooper())
            val completed = finished.await(2L, TimeUnit.SECONDS)
            state.put("pixel_copy_completed", completed).put("pixel_copy_result", result.get())
                .put("pixel_copy_request_mono_ns", requestTime.get())
                .put("pixel_copy_callback_mono_ns", callbackTime.get()).put("pixel_copy_wait_end_mono_ns", System.nanoTime())
            if (completed && result.get() == PixelCopy.SUCCESS) {
                surfaceImage.outputStream().use { check(copy.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
            if (completed) copy.recycle()
        }
        File(directory, "preview-sample.json").writeText(state.toString(2))
        Log.i("QuickCutPreviewTest", "failure_state=$state")
        fun shell(command: String) {
            val fd = instrumentation.uiAutomation.executeShellCommand(command)
            ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
        }
        shell("mkdir -p /data/local/tmp/quickcut-preview-diagnostics")
        for (name in listOf("preview-failure.png", "preview-sample.json", "preview-surface.png")) {
            val file = File(directory, name)
            if (!file.isFile) continue
            require(file.absolutePath.matches(Regex("[A-Za-z0-9_./-]+")))
            shell("cp ${file.absolutePath} /data/local/tmp/quickcut-preview-diagnostics/$name")
        }
    }

    private fun capture(name: String) {
        require(name.matches(Regex("editor_design_[a-z_]+")))
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Popup windows and platform surfaces settle after Compose's own idle state.
        instrumentation.uiAutomation.waitForIdle(300L, 5_000L)
        val directory = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "design-review")
        check(directory.isDirectory || directory.mkdirs())
        val output = File(directory, "$name.png")
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        fun shell(command: String): String {
            val fd = instrumentation.uiAutomation.executeShellCommand(command)
            return ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText().trim() }
        }
        require(output.absolutePath.matches(Regex("[A-Za-z0-9_./-]+")))
        shell("mkdir -p /data/local/tmp/quickcut-design-review")
        val destination = "/data/local/tmp/quickcut-design-review/$name.png"
        shell("cp ${output.absolutePath} $destination")
        val copied = shell("wc -c $destination").substringBefore(' ').toLongOrNull()
        assertTrue("Screenshot must survive instrumentation cleanup", output.length() > 0L && copied == output.length())
    }
}
