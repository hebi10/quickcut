package com.hebi.quickcut.export

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.MainActivity
import com.hebi.quickcut.QuickCutApplication
import com.hebi.quickcut.editor.EditorSessionSnapshot
import com.hebi.quickcut.editor.EditorSessionStore
import com.hebi.quickcut.editor.EditorViewModel
import com.hebi.quickcut.editor.QuickCutPage
import com.hebi.quickcut.editor.SingleExportStore
import com.hebi.quickcut.media.ExportMethod
import com.hebi.quickcut.media.ExportMode
import com.hebi.quickcut.media.FastExportReason
import com.hebi.quickcut.media.MediaReader
import com.hebi.quickcut.media.SaveDateMode
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the Media3 fallback after processing has begun, independently of native smart cut. */
@RunWith(AndroidJUnit4::class)
class BackgroundMedia3ExportInstrumentedTest {
    @get:Rule val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val power get() = context.getSystemService(PowerManager::class.java)
    private lateinit var model: EditorViewModel
    private lateinit var sessionStore: EditorSessionStore
    private var previousSession: EditorSessionSnapshot? = null
    private val sources = mutableListOf<File>()
    private var originalGalleryRows: Set<Uri>? = null
    private var originalStagingFiles: Set<String>? = null

    @Before
    fun importDistinctSourcesForAnAudibleMedia3Composition() = runBlocking {
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName, Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        assertFalse("A preceding test must finish its export", ExportWorkMonitor.isRunning)
        sessionStore = EditorSessionStore(context)
        previousSession = sessionStore.load()
        model = (context.applicationContext as QuickCutApplication).editorModel
        withTimeout(30_000L) { while (model.isRecoveringExport) delay(25L) }
        assertFalse("An unresolved previous output must not be replaced", model.hasUnresolvedExport)
        val suffix = UUID.randomUUID().toString()
        repeat(2) { index ->
            val file = File(context.cacheDir, "background_media3_${suffix}_$index.mp4")
            sources += file
            instrumentation.context.assets.open("quickcut_keyframes.mp4").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
        withContext(Dispatchers.Main) {
            model.startNew()
            // Distinct source URIs rule out both single-clip copying and native smart cut.
            // Repeated six-second ranges leave time to observe processing, without sleeps
            // or test-only delays in the actual encoder.
            model.importVideos(context, List(12) { Uri.fromFile(sources[it % 2]) })
        }
        withTimeout(30_000L) { while (model.page == QuickCutPage.IMPORTING) delay(25L) }
        assertEquals(QuickCutPage.EDITOR, model.page)
        withContext(Dispatchers.Main) {
            model.timeline.clips.forEach { model.trimClip(it.id, 500L, 6_500L) }
        }
        assertEquals(2, model.timeline.clips.map { it.uri }.distinct().size)
        assertEquals(72_000L, model.timeline.totalDurationMs)
        originalGalleryRows = galleryRows()
        originalStagingFiles = cachedMp4Files()
    }

    @After
    fun finishWorkRestoreDisplayAndRemoveOnlyTestOutputs() = runBlocking {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        if (::model.isInitialized) {
            withContext(Dispatchers.Main) { model.cancelExport() }
            awaitExportStopped()
            originalGalleryRows?.let { before ->
                (galleryRows() - before).forEach { context.contentResolver.delete(it, null, null) }
            }
            withContext(Dispatchers.Main) { model.startNew() }
        }
        sources.forEach { it.delete() }
        if (::sessionStore.isInitialized) previousSession?.let(sessionStore::save) ?: sessionStore.clear()
        wakeAndBringTaskToFront()
        Unit
    }

    @Test
    fun media3ProcessingPublishesAValidOutputAfterAppSwitchAndScreenOff() = runBlocking {
        // Catches export being tied to the visible Activity or losing its processing
        // resources during ON_STOP. Completion while still offscreen is the contract.
        startExportAndMoveOffscreenAfterProgress()
        awaitExportStopped()

        assertFalse("The display must stay off through Media3 completion", power.isInteractive)
        assertEquals("Media3 must finish offscreen: ${model.errorMessage}",
            QuickCutPage.COMPLETED, model.page)
        val output = checkNotNull(model.completedUri) { "A completed export must have a gallery URI" }
        val report = checkNotNull(model.completedExportReport) { "A completed export must report its engine" }
        assertEquals(ExportMethod.VIDEO_REENCODE, report.method)
        assertNotNull("The result must come from Media3, rather than native smart cut", report.optimizationResult)
        assertEquals(FastExportReason.MULTIPLE_SOURCES, report.diagnostics.smartCutReason)
        MediaReader.validateExport(context, output, 72_000L, expectAudio = true)
        val pending = context.contentResolver.query(output,
            arrayOf(MediaStore.Video.Media.IS_PENDING), null, null, null)?.use {
            assertTrue(it.moveToFirst())
            it.getInt(0)
        }
        assertEquals("A completed offscreen output must be published", 0, pending)
        assertEquals("An export must publish exactly one output", setOf(output),
            galleryRows() - checkNotNull(originalGalleryRows))
        assertEquals("Completed processing must remove its staged MP4", originalStagingFiles, cachedMp4Files())
    }

    @Test
    fun cancellingOffscreenMedia3ProcessingLeavesNoGalleryOutputOrStagedWriter() = runBlocking {
        // Catches cancellation being lost while offscreen or cleanup releasing the
        // foreground lease before the Media3 writer and output have been removed.
        startExportAndMoveOffscreenAfterProgress()
        withContext(Dispatchers.Main) {
            assertEquals("Cancellation must happen during processing", QuickCutPage.EXPORTING, model.page)
            assertFalse("Cancellation must precede gallery finalization", model.isFinalizing)
            assertEquals(FastExportReason.MULTIPLE_SOURCES, model.exportDiagnostics.smartCutReason)
            model.cancelExport()
        }
        awaitExportStopped()

        assertFalse("Cancellation must finish without waking the display", power.isInteractive)
        assertEquals(QuickCutPage.EDITOR, model.page)
        assertNull("A cancelled encode cannot report a published output", model.completedUri)
        assertNull(model.completedExportReport)
        assertFalse("Cancellation cleanup must resolve its output journal", model.hasUnresolvedExport)
        assertNull(SingleExportStore(context).load())
        assertEquals("Cancellation must leave neither pending nor published gallery rows",
            originalGalleryRows, galleryRows())
        assertEquals("The writer must stop before its foreground lease ends",
            originalStagingFiles, cachedMp4Files())
    }

    private suspend fun startExportAndMoveOffscreenAfterProgress() {
        activityRule.scenario.onActivity { activity ->
            model.startExport(activity, selectedDateMode = SaveDateMode.CURRENT,
                exportMode = ExportMode.ALLOW_REENCODE)
            assertEquals(QuickCutPage.EXPORTING, model.page)
        }
        var movedOffscreen = false
        withTimeout(60_000L) {
            while (!movedOffscreen) {
                // Check progress and switch in the same Main turn. A fast fixture
                // cannot complete between observing progress and backgrounding.
                activityRule.scenario.onActivity { activity ->
                    assertEquals("Export ended before genuine Media3 progress: ${model.errorMessage}",
                        QuickCutPage.EXPORTING, model.page)
                    val progress = model.progressPercent
                    if (model.exportDiagnostics.smartCutReason == FastExportReason.MULTIPLE_SOURCES &&
                        progress != null && progress in 1..90 && !model.isFinalizing) {
                        assertTrue(ExportWorkMonitor.isRunning)
                        assertTrue("The active export must allow going to Home", activity.moveTaskToBack(true))
                        activity.startActivity(Intent(Settings.ACTION_SETTINGS))
                        shell("input keyevent KEYCODE_SLEEP")
                        assertFalse("Media3 must remain in flight with the display off", power.isInteractive)
                        movedOffscreen = true
                    }
                }
                if (!movedOffscreen) delay(25L)
            }
        }
    }

    private suspend fun awaitExportStopped() {
        withTimeout(240_000L) {
            while (ExportWorkMonitor.isRunning || model.page == QuickCutPage.EXPORTING ||
                model.isRecoveringExport) delay(25L)
        }
    }

    private fun galleryRows(): Set<Uri> = context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Video.Media._ID),
        "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
        arrayOf(context.packageName), null,
    )?.use { cursor ->
        buildSet {
            while (cursor.moveToNext()) add(ContentUris.withAppendedId(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)))
        }
    } ?: error("Cannot inspect the application's gallery outputs")

    private fun cachedMp4Files(): Set<String> = checkNotNull(context.cacheDir.listFiles())
        .filter { it.isFile && it.name.endsWith(".mp4") }.map { it.name }.toSet()

    private suspend fun wakeAndBringTaskToFront() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        val component = ComponentName(context, MainActivity::class.java).flattenToShortString()
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
            Intent.FLAG_ACTIVITY_SINGLE_TOP
        shell("am start -W -n $component -f $flags")
        withTimeout(15_000L) {
            while (activityRule.scenario.state != Lifecycle.State.RESUMED) delay(25L)
        }
    }

    private fun shell(command: String) {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
}
