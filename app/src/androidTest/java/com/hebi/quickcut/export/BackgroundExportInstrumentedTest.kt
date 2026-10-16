package com.hebi.quickcut.export

import android.Manifest
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
import com.hebi.quickcut.batch.BatchClipMode
import com.hebi.quickcut.batch.BatchFileStatus
import com.hebi.quickcut.batch.BatchRunState
import com.hebi.quickcut.batch.BatchSessionSnapshot
import com.hebi.quickcut.batch.BatchSessionStore
import com.hebi.quickcut.batch.BatchViewModel
import com.hebi.quickcut.editor.EditorSessionSnapshot
import com.hebi.quickcut.editor.EditorSessionStore
import com.hebi.quickcut.editor.EditorViewModel
import com.hebi.quickcut.editor.QuickCutPage
import com.hebi.quickcut.media.ExportMode
import com.hebi.quickcut.media.MediaReader
import com.hebi.quickcut.media.SaveDateMode
import com.hebi.quickcut.settings.BatchDraftSettings
import com.hebi.quickcut.settings.QuickCutSettings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual media output while the Activity is backgrounded and the display is off. */
@RunWith(AndroidJUnit4::class)
class BackgroundExportInstrumentedTest {
    @get:Rule val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private lateinit var model: EditorViewModel
    private lateinit var source: File
    private lateinit var store: EditorSessionStore
    private var previousSession: EditorSessionSnapshot? = null
    private lateinit var batchModel: BatchViewModel
    private lateinit var batchStore: BatchSessionStore
    private var previousBatchSession: BatchSessionSnapshot? = null
    private lateinit var previousBatchDraft: BatchDraftSettings
    private lateinit var previousBatchDate: SaveDateMode
    private var secondSource: File? = null

    @Before
    fun importUsingTheApplicationOwnedEditor() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName, Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        assertFalse("Each export test must finish its previous work", ExportWorkMonitor.isRunning)
        store = EditorSessionStore(context)
        previousSession = store.load()
        source = File(context.cacheDir, "quickcut_background_fixture.mp4")
        instrumentation.context.assets.open("quickcut_keyframes.mp4").use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
        model = (context.applicationContext as QuickCutApplication).editorModel
        withContext(Dispatchers.Main) {
            model.startNew()
            model.importVideos(context, listOf(Uri.fromFile(source)))
        }
        withTimeout(30_000L) { while (model.page == QuickCutPage.IMPORTING) delay(50L) }
        assertEquals(QuickCutPage.EDITOR, model.page)
        withContext(Dispatchers.Main) { model.trimSelected(500L, 6_500L) }
        assertEquals(6_000L, model.timeline.totalDurationMs)
    }

    @After
    fun restoreDisplayAndCleanUp() = runBlocking {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (::batchModel.isInitialized) {
            withContext(Dispatchers.Main) { batchModel.cancel() }
            withTimeout(120_000L) { while (ExportWorkMonitor.isRunning) delay(50L) }
            batchModel.files.mapNotNull { it.savedOutputUri }.forEach { output ->
                context.contentResolver.delete(Uri.parse(output), null, null)
            }
            withContext(Dispatchers.Main) {
                batchModel.resetBatch()
                batchModel.draftName = previousBatchDraft.name
                batchModel.draftMode = previousBatchDraft.mode
                batchModel.draftLeadingSeconds = seconds(previousBatchDraft.leadingMs)
                batchModel.draftTrailingSeconds = seconds(previousBatchDraft.trailingMs)
                batchModel.draftMute = previousBatchDraft.muteAudio
                batchModel.saveDateMode = previousBatchDate
            }
            withTimeout(30_000L) { while (batchModel.isImporting) delay(50L) }
            delay(100L) // Let the application-owned draft observers settle before restoring the journal.
            QuickCutSettings(context).apply {
                saveBatch(previousBatchDraft)
                saveSaveDate(previousBatchDate)
            }
            previousBatchSession?.let(batchStore::save) ?: batchStore.clear()
        }
        if (::model.isInitialized) {
            withContext(Dispatchers.Main) { model.cancelExport() }
            withTimeout(120_000L) { while (ExportWorkMonitor.isRunning) delay(50L) }
            model.completedUri?.let { output ->
                InstrumentationRegistry.getInstrumentation().targetContext
                    .contentResolver.delete(output, null, null)
            }
            withContext(Dispatchers.Main) { model.startNew() }
        }
        if (::store.isInitialized) previousSession?.let(store::save) ?: store.clear()
        if (::source.isInitialized) source.delete()
        secondSource?.delete()
        activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        Unit
    }

    @Test
    fun actualExportFinishesOffscreenAndItsResultSurvivesActivityRecreation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val power = context.getSystemService(PowerManager::class.java)
        startWithDisplayOff { activity ->
            model.startExport(activity, selectedDateMode = SaveDateMode.CURRENT,
                exportMode = ExportMode.ALLOW_REENCODE)
            assertEquals(QuickCutPage.EXPORTING, model.page)
        }
        withTimeout(120_000L) {
            while (model.page == QuickCutPage.EXPORTING || ExportWorkMonitor.isRunning) delay(50L)
        }
        assertFalse("The display must remain off until export completion", power.isInteractive)
        assertEquals("Background work must finish instead of returning to the editor: " + model.errorMessage,
            QuickCutPage.COMPLETED, model.page)
        val output = model.completedUri
        assertNotNull("A real gallery output must be published", output)
        MediaReader.validateExport(context, output!!, 6_000L, true)
        val pending = context.contentResolver.query(output,
            arrayOf(MediaStore.Video.Media.IS_PENDING), null, null, null)?.use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }
        assertEquals("Background completion must publish the MediaStore row", 0, pending)
        assertNotNull(model.completedExportReport)

        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        activityRule.scenario.recreate()
        activityRule.scenario.onActivity { activity ->
            val retained = (activity.application as QuickCutApplication).editorModel
            assertSame("Activity recreation must use the same application-owned editor", model, retained)
            assertEquals(QuickCutPage.COMPLETED, retained.page)
            assertEquals(output, retained.completedUri)
        }
        Unit
    }

    @Test
    fun batchContinuesToTheSecondFileAndPublishesBothWhileScreenIsOff() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val power = context.getSystemService(PowerManager::class.java)
        batchStore = BatchSessionStore(context)
        previousBatchSession = batchStore.load()
        QuickCutSettings(context).let {
            previousBatchDraft = it.loadBatch()
            previousBatchDate = it.loadSaveDate()
        }
        batchModel = (context.applicationContext as QuickCutApplication).batchModel
        secondSource = File(context.cacheDir, "quickcut_background_second.mp4").also {
            source.copyTo(it, overwrite = true)
        }
        withTimeout(30_000L) { while (batchModel.isImporting || batchModel.isRecovering) delay(50L) }
        withContext(Dispatchers.Main) { batchModel.resetBatch() }
        withTimeout(30_000L) { while (batchModel.isImporting) delay(50L) }
        withContext(Dispatchers.Main) {
            batchModel.draftName = "화면 꺼진 상태에서 앞뒤 삭제"
            batchModel.draftMode = BatchClipMode.REMOVE_EDGES
            batchModel.draftLeadingSeconds = "0.5"
            batchModel.draftTrailingSeconds = "1.5"
            batchModel.draftMute = false
            batchModel.saveDateMode = SaveDateMode.CURRENT
            batchModel.addVideos(context, listOf(Uri.fromFile(source), Uri.fromFile(secondSource!!)))
        }
        withTimeout(30_000L) { while (batchModel.isImporting) delay(50L) }
        assertEquals(2, batchModel.files.size)
        startWithDisplayOff { activity ->
            batchModel.start(activity, exportMode = ExportMode.ALLOW_REENCODE)
            assertEquals(BatchRunState.RUNNING, batchModel.runState)
        }
        withTimeout(180_000L) {
            while (batchModel.isRunning || ExportWorkMonitor.isRunning) delay(50L)
        }
        assertFalse("Both sequential outputs must complete before the display is woken", power.isInteractive)
        assertEquals(2, batchModel.successes)
        assertEquals(0, batchModel.failures)
        assertEquals(0, batchModel.skips)
        val outputs = batchModel.files.map { row ->
            assertEquals(BatchFileStatus.SAVED, row.status)
            val expected = row.durationMs - 2_000L
            assertEquals(expected, row.outputExpectedDurationMs)
            val output = Uri.parse(row.savedOutputUri!!)
            MediaReader.validateExport(context, output, expected, true)
            val pending = context.contentResolver.query(output,
                arrayOf(MediaStore.Video.Media.IS_PENDING), null, null, null)?.use {
                assertTrue(it.moveToFirst())
                it.getInt(0)
            }
            assertEquals(0, pending)
            assertNotNull(row.exportReport)
            output
        }
        assertEquals("Each source needs its own completed output", 2, outputs.distinct().size)
        assertEquals(2, batchStore.load()!!.files.count { it.status == BatchFileStatus.SAVED })
    }

    private fun startWithDisplayOff(start: (MainActivity) -> Unit) {
        val power = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(PowerManager::class.java)
        activityRule.scenario.onActivity { activity ->
            start(activity)
            assertTrue(ExportWorkMonitor.isRunning)
            assertTrue("The running editor must allow going to Home", activity.moveTaskToBack(true))
            activity.startActivity(Intent(Settings.ACTION_SETTINGS))
            // Service readiness also runs on Main. Request sleep in this same
            // turn, before the service can release the exporter to do any work.
            // This avoids depending on a fixture being slower than app switching.
            shell("input keyevent KEYCODE_SLEEP")
            assertFalse("The export must start with the display off", power.isInteractive)
        }
    }

    private fun seconds(millis: Long): String =
        java.math.BigDecimal.valueOf(millis, 3).stripTrailingZeros().toPlainString()

    private fun shell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
}
