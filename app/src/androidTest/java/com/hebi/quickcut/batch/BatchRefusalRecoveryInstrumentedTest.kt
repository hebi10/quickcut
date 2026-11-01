package com.hebi.quickcut.batch

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.SingleExportOutputAccess
import com.hebi.quickcut.editor.SingleExportOutputState
import com.hebi.quickcut.editor.SingleExportSnapshot
import com.hebi.quickcut.media.SaveDateMode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchRefusalRecoveryInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val template = BatchTemplate("refusal", "원본 구간", BatchClipMode.KEEP_FIRST, 2_000, 0, false)
    private fun row(): BatchFile {
        val row = BatchFile("content://source/1", "source.mp4", 5_000, true,
            status = BatchFileStatus.SKIPPED)
        val timeline = (template.plan(row.uri, row.durationMs, row.hasAudio) as BatchPlan.Ready).timeline
        return row.copy(reencodeRefusal = BatchReencodeRefusal.from(timeline, "CURRENT", null))
    }

    @Test fun journalRoundTripRestoresRefusalAndLegacyJournalHasNoMarker() {
        val base = File.createTempFile("refusal", ".json", context.cacheDir).apply { delete() }
        val store = BatchSessionStore(base)
        try {
            val snapshot = BatchSessionSnapshot(listOf(row()), template, SaveDateMode.CURRENT, BatchRunState.FINISHED)
            store.save(snapshot)
            assertEquals(snapshot, store.load())
            val json = org.json.JSONObject(base.readText())
            json.getJSONArray("files").getJSONObject(0).remove("reencodeRefusal")
            base.writeText(json.toString())
            assertNull(store.load()!!.files.single().reencodeRefusal)
        } finally { store.clear() }
    }

    @Test fun pendingUncertainAndSavedOutputsKeepRefusalUntilOwnershipRecoveryAllowsRetry() {
        val refusalRow = row().copy(savedOutputUri = "content://output/1",
            outputExpectedDurationMs = 2_000, outputExpectedAudio = true)
        for (state in SingleExportOutputState.entries) {
            var removed = false
            val output = object : SingleExportOutputAccess {
                override fun inspect(snapshot: SingleExportSnapshot) = state
                override fun removePending(snapshot: SingleExportSnapshot) { removed = true }
            }
            val recovered = BatchFileRecovery(output) { it }.recover(refusalRow)
            assertEquals(refusalRow.reencodeRefusal, recovered.reencodeRefusal)
            when (state) {
                SingleExportOutputState.UNCERTAIN -> assertTrue(recovered.hasUnresolvedOutput())
                SingleExportOutputState.VERIFIED -> {
                    assertEquals(BatchFileStatus.SAVED, recovered.status)
                    assertEquals(refusalRow.savedOutputUri, recovered.savedOutputUri)
                }
                else -> assertNull(recovered.savedOutputUri)
            }
            assertEquals(state == SingleExportOutputState.OWNED_PENDING, removed)
        }
        val output = object : SingleExportOutputAccess {
            override fun inspect(snapshot: SingleExportSnapshot) = SingleExportOutputState.OWNED_PENDING
            override fun removePending(snapshot: SingleExportSnapshot) { error("cannot verify removal") }
        }
        assertTrue(BatchFileRecovery(output) { it }.recover(refusalRow).hasUnresolvedOutput())
    }

    @Test fun recreatedModelRequiresExactReviewAndChangedRequestCanBeAssessedAgain() = runBlocking {
        val input = File.createTempFile("refusal_source", ".mp4", context.cacheDir)
        val base = File.createTempFile("refusal_model", ".json", context.cacheDir).apply { delete() }
        val store = BatchSessionStore(base)
        val settings = com.hebi.quickcut.settings.QuickCutSettings(context)
        val oldDraft = settings.loadBatch(); val oldDate = settings.loadSaveDate()
        var model: BatchViewModel? = null
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("quickcut_keyframes.mp4")
                .use { src -> input.outputStream().use { src.copyTo(it) } }
            val uri = android.net.Uri.fromFile(input)
            val metadata = com.hebi.quickcut.media.MediaReader.metadata(context, uri)
            val source = row().copy(uri = uri.toString(), durationMs = metadata.durationMs, hasAudio = metadata.hasAudio,
                originalDate = com.hebi.quickcut.media.OriginalMediaDates.resolve(context, uri))
            val timeline = (template.plan(source.uri, source.durationMs, source.hasAudio) as BatchPlan.Ready).timeline
            store.save(BatchSessionSnapshot(listOf(source.copy(reencodeRefusal =
                BatchReencodeRefusal.from(timeline, "CURRENT", source.originalDate?.epochMs))), template, SaveDateMode.CURRENT, BatchRunState.FINISHED))
            val restored = withContext(Dispatchers.Main) { BatchViewModel(context.applicationContext as Application, store) }
            model = restored
            withTimeout(30_000) { while (restored.isRecovering) delay(25) }
            val overview = withContext(Dispatchers.Main) { restored.inspectExport(context, true) }
            val reviewed = overview.reviewedFiles.single()
            assertTrue(reviewed.reencodeRequired)
            assertFalse(reviewed.fastAvailable)
            assertFalse(reviewed.canRecommend)
            assertEquals(0, overview.adjustedFast)
            assertEquals(BatchExportChoice.EXACT, BatchExportChoicePolicy.defaults(listOf(reviewed))[source.uri])
            assertNull(BatchExportChoicePolicy.resolve(listOf(reviewed), mapOf(source.uri to BatchExportChoice.RECOMMENDED)))
            val exact = BatchExportChoicePolicy.resolve(listOf(reviewed), mapOf(source.uri to BatchExportChoice.EXACT))!!.single()
            assertEquals(timeline, exact.timeline)
            withContext(Dispatchers.Main) { restored.draftLeadingSeconds = "1" }
            assertFalse(restored.isReviewCurrent(overview, true))
            val changed = withContext(Dispatchers.Main) { restored.inspectExport(context, true) }
            assertFalse(changed.reviewedFiles.single().reencodeRequired)
            assertTrue(input.exists())
            assertTrue(restored.originalCandidates.isEmpty())
        } finally {
            withContext(Dispatchers.Main) { model?.let { ViewModelStore().apply { put("refusal", it) }.clear() } }
            store.clear(); input.delete()
            settings.saveBatch(oldDraft); settings.saveSaveDate(oldDate)
        }
    }
}
