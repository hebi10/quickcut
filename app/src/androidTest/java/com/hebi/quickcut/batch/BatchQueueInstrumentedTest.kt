package com.hebi.quickcut.batch

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.OriginalAction
import com.hebi.quickcut.editor.OriginalActionStatus
import com.hebi.quickcut.media.MediaReader
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import androidx.lifecycle.ViewModelStore
import org.junit.Rule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.hebi.quickcut.MainActivity
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchQueueInstrumentedTest {
    @get:Rule val activityRule = ActivityScenarioRule(MainActivity::class.java)

    /**
     * This exercises the actual queue and MediaStore saving, not only the edit
     * planning algorithm. Each input must result in its own playable output.
     */
    @Test
    fun batchQueueExportsTwoFilesSeparatelyAndRetainsBothOriginals() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val inputs = listOf(
            File(context.cacheDir, "quickcut_batch_queue_01.mp4"),
            File(context.cacheDir, "quickcut_batch_queue_02.mp4"),
            File(context.cacheDir, "quickcut_batch_queue_03.mp4"),
        )
        val settings = com.hebi.quickcut.settings.QuickCutSettings(context)
        val oldDraft = settings.loadBatch()
        val oldDate = settings.loadSaveDate()
        val oldAction = settings.loadOriginalAction()
        settings.saveOriginalAction(OriginalAction.KEEP)
        BatchSessionStore(context).clear()
        val vm = withContext(Dispatchers.Main) { BatchViewModel(context.applicationContext as android.app.Application) }
        try {
            inputs.forEach { file ->
                InstrumentationRegistry.getInstrumentation().context.assets
                    .open("quickcut_fixture.mp4").use { source ->
                        file.outputStream().use { destination -> source.copyTo(destination) }
                    }
            }
            val beforeLengths = inputs.map { it.length() }
            val sourceDuration =
                MediaReader.metadata(context, Uri.fromFile(inputs.first())).durationMs

            withContext(Dispatchers.Main) {
                vm.draftName = "앞뒤 반초 삭제"
                vm.draftMode = BatchClipMode.REMOVE_EDGES
                vm.draftMute = false
                vm.saveDateMode = com.hebi.quickcut.media.SaveDateMode.CURRENT
                vm.draftLeadingSeconds = "0.5"
                vm.draftTrailingSeconds = "0.5"
                vm.addVideos(context, inputs.take(2).map(Uri::fromFile))
            }
            withTimeout(30_000L) {
                while (vm.isImporting || vm.files.size != 2) delay(100L)
            }
            assertEquals(2, vm.files.size)

            withContext(Dispatchers.Main) { vm.start(context) }
            withTimeout(150_000L) {
                while (vm.runState != BatchRunState.FINISHED) delay(100L)
            }
            assertEquals(2, vm.successes)
            assertEquals(0, vm.failures)
            assertEquals(0, vm.skips)
            assertEquals(2, vm.completedCount)
            val outputs = vm.files.mapNotNull(BatchFile::savedOutputUri)
            assertEquals(2, outputs.size)
            assertNotEquals(outputs[0], outputs[1])
            outputs.forEach { output ->
                val duration = MediaReader.metadata(context, Uri.parse(output)).durationMs
                assertTrue(
                    "Batch duration " + duration +
                        " differs from expected " + (sourceDuration - 1_000L),
                    abs(duration - (sourceDuration - 1_000L)) <= 300L,
                )
            }
            assertTrue(inputs.all(File::exists))
            assertEquals(beforeLengths, inputs.map(File::length))
            assertEquals(OriginalAction.KEEP, vm.originalAction)
            assertEquals(OriginalActionStatus.NONE, vm.originalStatus)

            // A second batch can append new inputs without exporting previous
            // successful files again. Only sources saved in the *second* run
            // can become original deletion candidates for that run.
            val originalOutputs = vm.files.map(BatchFile::savedOutputUri)
            withContext(Dispatchers.Main) {
                vm.originalAction = OriginalAction.TRASH
                vm.addVideos(context, listOf(Uri.fromFile(inputs[2])))
            }
            withTimeout(30_000L) {
                while (vm.isImporting || vm.files.size != 3) delay(100L)
            }
            withContext(Dispatchers.Main) { vm.start(context) }
            withTimeout(150_000L) {
                while (vm.runState != BatchRunState.FINISHED) delay(100L)
            }
            assertEquals(3, vm.successes)
            assertEquals(originalOutputs, vm.files.take(2).map(BatchFile::savedOutputUri))
            assertEquals(OriginalAction.TRASH, vm.originalAction)
            assertEquals(OriginalAction.TRASH, vm.performedOriginalAction)
            assertEquals(OriginalActionStatus.READY, vm.originalStatus)
            assertEquals(listOf(Uri.fromFile(inputs[2]).toString()), vm.originalCandidates)
            assertTrue(inputs.all(File::exists))
            assertEquals(beforeLengths, inputs.map(File::length))
        } finally {
            vm.cancel()
            vm.files.mapNotNull(BatchFile::savedOutputUri).forEach { output ->
                runCatching { context.contentResolver.delete(Uri.parse(output), null, null) }
            }
            withContext(Dispatchers.Main) { vm.resetBatch(); ViewModelStore().apply { put("test", vm) }.clear() }
            settings.saveBatch(oldDraft)
            settings.saveSaveDate(oldDate)
            settings.saveOriginalAction(oldAction)
            inputs.forEach(File::delete)
        }
    }
}

