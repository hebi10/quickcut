package com.hebi.quickcut.batch

import android.app.Application
import android.content.Context
import android.content.ContentResolver
import android.content.ContextWrapper
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.media.ExportDiagnosticCodec
import com.hebi.quickcut.media.ExportDiagnosticHistory
import com.hebi.quickcut.media.ExportDiagnosticStatus
import com.hebi.quickcut.media.ExportDiagnosticStore
import com.hebi.quickcut.media.ExportStage
import com.hebi.quickcut.media.MediaOperationDiagnostics
import com.hebi.quickcut.media.SaveDateMode
import com.hebi.quickcut.settings.QuickCutSettings
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual batch import/review entry points, including a missing provider URI. */
@RunWith(AndroidJUnit4::class)
class BatchOperationDiagnosticsInstrumentedTest {
    @Test fun missingSourceImportRecordsPrivateSafeFailureAndRefreshKeepsItsNewDiagnosticId() = runBlocking {
        withModel { context, model, store ->
            val secret = "private-batch-source-" + UUID.randomUUID()
            val missing = Uri.parse("content://com.hebi.quickcut.missing.provider/$secret.mp4")
            val historyBefore = ExportDiagnosticStore.history(context).recent.map { it.operationId }.toSet()
            importSource(model, context, missing)
            val first = model.files.single()
            assertEquals(BatchFileStatus.SKIPPED, first.status)
            assertEquals(0L, first.durationMs)
            val firstId = checkNotNull(first.diagnosticId)
            val firstRecord = checkNotNull(ExportDiagnosticStore.record(context, firstId))
            assertFalse(historyBefore.contains(firstId))
            assertEquals(ExportStage.IMPORTING, firstRecord.stage)
            assertEquals(ExportDiagnosticStatus.FAILED, firstRecord.status)
            assertEquals(1, firstRecord.facts.sourceCount)
            assertEquals(firstId, store.load()!!.files.single().diagnosticId)
            assertFalse(first.reason.orEmpty().contains(secret))

            // The same unreadable row is refreshable. A second failure needs its
            // own record, rather than being overwritten by the old row's ID.
            importSource(model, context, missing)
            val refreshedId = checkNotNull(model.files.single().diagnosticId)
            assertNotEquals(firstId, refreshedId)
            assertEquals(refreshedId, store.load()!!.files.single().diagnosticId)
            assertEquals(ExportDiagnosticStatus.FAILED,
                ExportDiagnosticStore.record(context, refreshedId)!!.status)

            withContext(Dispatchers.Main) {
                model.draftName = "진단 확인"
                model.draftMode = BatchClipMode.KEEP_FIRST
                model.draftLeadingSeconds = "1"
                model.saveDateMode = SaveDateMode.CURRENT
                repeat(ExportDiagnosticHistory.RECENT_LIMIT + 1) {
                    val review = model.inspectExport(context, retryOnly = false)
                    assertEquals(1, review.skipped)
                }
            }
            assertTrue(ExportDiagnosticStore.history(context).recent.any {
                it.stage == ExportStage.REVIEWING && it.status == ExportDiagnosticStatus.COMPLETE
            })
            assertNotNull("Successful review must retain both import failures",
                ExportDiagnosticStore.record(context, firstId))
            assertNotNull(ExportDiagnosticStore.record(context, refreshedId))
            val persistedDiagnostics = context.getSharedPreferences("export_diagnostics", Context.MODE_PRIVATE)
                .getString("history_v1", "").orEmpty()
            for (privateText in listOf(secret, missing.toString(), "content://", ".mp4")) {
                assertFalse("Private source text must not enter diagnostic storage",
                    persistedDiagnostics.contains(privateText))
                assertFalse(firstRecord.text.contains(privateText))
            }
            // The normal recovery journal intentionally keeps the source URI;
            // diagnostic persistence must keep only the opaque operation ID.
            assertEquals(missing.toString(), store.load()!!.files.single().uri)
        }
    }

    @Test fun rejectedReviewRecordsFailureAndCancellationRemainsCancellation() = runBlocking {
        withModel { context, model, _ ->
            withContext(Dispatchers.Main) { model.draftName = "" }
            val failure = runCatching {
                withContext(Dispatchers.Main) { model.inspectExport(context, retryOnly = false) }
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            val failed = ExportDiagnosticStore.history(context).recent.first()
            assertEquals(ExportStage.REVIEWING, failed.stage)
            assertEquals(ExportDiagnosticStatus.FAILED, failed.status)
            assertEquals("IllegalArgumentException", failed.failureType)
            val safeMessage = MediaOperationDiagnostics.message(ExportStage.REVIEWING, failure!!)
            assertFalse(safeMessage.contains(failure.message.orEmpty()))

            val missing = Uri.parse("content://com.hebi.quickcut.missing.provider/cancel-review.mp4")
            importSource(model, context, missing)
            val cancelled = CancellationException("content://private/cancellation-message")
            val cancelledFailure = runCatching {
                withContext(Dispatchers.Main) {
                    model.draftName = "취소 진단"
                    model.draftMode = BatchClipMode.KEEP_FIRST
                    model.draftLeadingSeconds = "1"
                    model.inspectExport(context, retryOnly = false,
                        onProgress = { _, _ -> throw cancelled })
                }
            }.exceptionOrNull()
            assertSame(cancelled, cancelledFailure)
            val cancelledRecord = ExportDiagnosticStore.history(context).recent.first()
            assertEquals(ExportStage.REVIEWING, cancelledRecord.stage)
            assertEquals(ExportDiagnosticStatus.CANCELLED, cancelledRecord.status)
            assertNotEquals(failed.operationId, cancelledRecord.operationId)
            assertNotNull(ExportDiagnosticStore.record(context, failed.operationId))
            assertFalse(cancelledRecord.text.contains("content://private"))
        }
    }

    @Test fun unexpectedImportPersistenceFailureRecordsSafeOuterFailure() = runBlocking {
        withModel(failPersistence = true) { context, model, _ ->
            val missing = Uri.parse("content://com.hebi.quickcut.missing.provider/outer-import-private.mp4")
            importSource(model, context, missing)
            val record = ExportDiagnosticStore.history(context).recent.first()
            assertEquals(ExportStage.IMPORTING, record.stage)
            assertEquals(ExportDiagnosticStatus.FAILED, record.status)
            assertEquals("IOException", record.failureType)
            assertEquals(1, record.facts.sourceCount)
            assertEquals(MediaOperationDiagnostics.message(ExportStage.IMPORTING,
                IOException("ignored")), model.notification)
            assertFalse(model.notification.orEmpty().contains("private-journal"))
            assertFalse(record.text.contains("private-journal"))
            assertTrue(model.hasUnresolvedRecovery)
        }
    }

    @Test fun importCancellationIsNotRecordedAsAnUnreadableSource() = runBlocking {
        withModel { context, model, _ ->
            val before = ExportDiagnosticCodec.encode(ExportDiagnosticStore.history(context))
            val cancelled = CancellationException("content://private/cancelled-source")
            val cancelledContext = object : ContextWrapper(context) {
                override fun getContentResolver(): ContentResolver = throw cancelled
            }
            withContext(Dispatchers.Main) {
                model.addVideos(cancelledContext, listOf(Uri.parse("content://unused/source")))
            }
            withTimeout(30_000L) { while (model.isImporting) delay(30L) }
            assertTrue(model.files.isEmpty())
            assertNull(model.notification)
            assertEquals(before, ExportDiagnosticCodec.encode(ExportDiagnosticStore.history(context)))
        }
    }

    private suspend fun importSource(model: BatchViewModel, context: Context, uri: Uri) {
        withContext(Dispatchers.Main) { model.addVideos(context, listOf(uri)) }
        withTimeout(30_000L) { while (model.isImporting) delay(30L) }
        assertEquals(1, model.files.size)
    }

    private suspend fun withModel(
        failPersistence: Boolean = false,
        block: suspend (Context, BatchViewModel, BatchSessionStore) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = QuickCutSettings(context)
        val previousDraft = settings.loadBatch()
        val previousDate = settings.loadSaveDate()
        val preferences = context.getSharedPreferences("export_diagnostics", Context.MODE_PRIVATE)
        val previousHistory = preferences.getString("history_v1", null)
        val previousLatest = preferences.getString("latest", null)
        preferences.edit().putString("history_v1", ExportDiagnosticCodec.encode(ExportDiagnosticHistory()))
            .remove("latest").commit()
        val base = File.createTempFile("batch_operation_diagnostics_", ".json", context.cacheDir).apply { delete() }
        val store = BatchSessionStore(base, sync = { stream ->
            if (failPersistence) throw IOException("/storage/private-journal-secret.json")
            stream.fd.sync()
        })
        val model = withContext(Dispatchers.Main) { BatchViewModel(context.applicationContext as Application, store) }
        try { block(context, model, store) }
        finally {
            withContext(Dispatchers.Main) { ViewModelStore().apply { put("batch-diagnostics", model) }.clear() }
            store.clear()
            settings.saveBatch(previousDraft)
            settings.saveSaveDate(previousDate)
            preferences.edit().putString("history_v1", previousHistory).putString("latest", previousLatest).commit()
        }
    }
}
