package com.hebi.quickcut.batch

import android.app.Application
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.CursorWrapper
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.OriginalAction
import com.hebi.quickcut.media.GalleryWriter
import com.hebi.quickcut.media.MediaReader
import com.hebi.quickcut.media.SaveDateMode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import com.hebi.quickcut.settings.QuickCutSettings
import com.hebi.quickcut.settings.BatchDraftSettings
import androidx.lifecycle.ViewModelStore
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchJournalInstrumentedTest {
    private var model: BatchViewModel? = null
    private lateinit var oldDraft: BatchDraftSettings
    private lateinit var oldDate: SaveDateMode
    @Before fun captureSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = QuickCutSettings(context)
        oldDraft = settings.loadBatch()
        oldDate = settings.loadSaveDate()
    }
    @After fun restoreSettings() = runBlocking {
        withContext(Dispatchers.Main) { model?.let { ViewModelStore().apply { put("test", it) }.clear() } }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        BatchSessionStore(context).clear()
        QuickCutSettings(context).apply { saveBatch(oldDraft); saveSaveDate(oldDate) }
        Unit
    }

    @Test fun publishedOutputIsAdoptedAfterProcessExitBeforeSuccessWasRecorded() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = fixture(context)
        var output: Uri? = null
        try {
            output = GalleryWriter(context).save(input)
            val metadata = MediaReader.metadata(context, output)
            seed(context, input, output, metadata.durationMs, metadata.hasAudio)
            val restored = restore(context)
            assertEquals(BatchFileStatus.SAVED, restored.files.single().status)
            assertEquals(output.toString(), restored.files.single().savedOutputUri)
            assertEquals(OriginalAction.KEEP, restored.performedOriginalAction)
            assertTrue(restored.originalCandidates.isEmpty())
            withContext(Dispatchers.Main) { restored.resetBatch() }
        } finally {
            output?.let { context.contentResolver.delete(it, null, null) }
            BatchSessionStore(context).clear()
            input.delete()
        }
    }

    @Test fun movedPublishedOutputRemainsSavedAndCanBeRemovedFromQueue() = runBlocking {
        verifyMovedPublishedOutput(reset = false)
    }

    @Test fun movedPublishedOutputRemainsSavedAndCanResetForNewWork() = runBlocking {
        verifyMovedPublishedOutput(reset = true)
    }

    private suspend fun verifyMovedPublishedOutput(reset: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = fixture(context)
        var output: Uri? = null
        try {
            val saved = GalleryWriter(context).save(input)
            output = saved
            val metadata = MediaReader.metadata(context, saved)
            seed(context, input, saved, metadata.durationMs, metadata.hasAudio,
                status = BatchFileStatus.SAVED)
            assertEquals(1, context.contentResolver.update(saved, ContentValues().apply {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/QuickCutMoved")
            }, null, null))
            val restored = restore(context)
            assertEquals("Moving a published output must preserve SAVED", BatchFileStatus.SAVED,
                restored.files.single().status)
            assertEquals(saved.toString(), restored.files.single().savedOutputUri)
            assertTrue(restored.files.single().durationMs > 0L)
            withContext(Dispatchers.Main) {
                if (reset) restored.resetBatch() else restored.removeVideo(restored.files.single().uri)
            }
            withTimeout(30_000L) { while (restored.isImporting) delay(50L) }
            assertTrue("Completed entries must be removable after a move", restored.files.isEmpty())
            assertEquals(BatchRunState.SETUP, restored.runState)
            MediaReader.validateExport(context, saved, metadata.durationMs, metadata.hasAudio)
            assertTrue("Queue changes must preserve the original", input.exists())
        } finally {
            output?.let { context.contentResolver.delete(it, null, null) }
            BatchSessionStore(context).clear()
            input.delete()
        }
    }

    @Test fun pendingOutputOutsideQuickCutFolderIsProtectedFromRecoveryAndReset() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = fixture(context)
        val pending = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "QuickCut_protected_test.mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/QuickCutMoved")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            })!!
        try {
            seed(context, input, pending, 2_000L, true)
            assertProtectedTrackedOutput(context, pending)
            assertEquals(1, context.contentResolver.query(pending, arrayOf(MediaStore.Video.Media._ID),
                null, null, null)?.use { it.count })
        } finally {
            context.contentResolver.delete(pending, null, null)
            input.delete()
        }
    }

    @Test fun ownerMismatchPendingOutputIsProtectedFromRecoveryAndReset() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = fixture(context)
        val pending = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "QuickCut_owner_guard_test.mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/QuickCut")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            })!!
        val provider = OwnerMismatchProvider(context.contentResolver, pending)
        provider.attachInfo(context, ProviderInfo().apply { authority = "media"; exported = true })
        val application = ResolverApplication(context, ContentResolver.wrap(provider))
        try {
            seed(context, input, pending, 2_000L, true)
            assertProtectedTrackedOutput(context, pending, application)
            assertTrue("Owner mismatch must be observed during recovery", provider.ownerReads > 0)
            assertEquals("Owner mismatch must never delegate deletion", 0, provider.deleteAttempts)
            assertEquals(1, context.contentResolver.query(pending, arrayOf(MediaStore.Video.Media._ID),
                null, null, null)?.use { it.count })
            assertTrue(input.exists())
        } finally {
            context.contentResolver.delete(pending, null, null)
            input.delete()
        }
    }

    private class ResolverApplication(base: Context, private val resolver: ContentResolver) : Application() {
        init { attachBaseContext(base) }
        override fun getContentResolver(): ContentResolver = resolver
    }

    /** Keep real MediaStore rows visible, changing only the ownership reported for the pending output. */
    private class OwnerMismatchProvider(private val real: ContentResolver, private val output: Uri) : ContentProvider() {
        var deleteAttempts = 0
        var ownerReads = 0
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = real.getType(uri)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = real.insert(uri, values)
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
            real.update(uri, values, selection, selectionArgs)
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            if (uri == output) deleteAttempts++
            return real.delete(uri, selection, selectionArgs)
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            val cursor = real.query(uri, projection, selection, selectionArgs, sortOrder) ?: return null
            if (uri != output) return cursor
            return object : CursorWrapper(cursor) {
                override fun getString(columnIndex: Int): String? {
                    if (getColumnName(columnIndex) == MediaStore.Video.Media.OWNER_PACKAGE_NAME) {
                        ownerReads++
                        return "com.example.otherowner"
                    }
                    return super.getString(columnIndex)
                }
            }
        }
    }

    private suspend fun assertProtectedTrackedOutput(context: Context, output: Uri,
        application: Application = context.applicationContext as Application) {
        val restored = restore(context, application)
        assertEquals(BatchFileStatus.SKIPPED, restored.files.single().status)
        assertEquals(output.toString(), restored.files.single().savedOutputUri)
        withContext(Dispatchers.Main) { restored.removeVideo(restored.files.single().uri) }
        withTimeout(30_000L) { while (restored.isImporting) delay(50L) }
        assertEquals("Unsafe output must remain tracked after removal attempt", 1, restored.files.size)
        withContext(Dispatchers.Main) { restored.resetBatch() }
        withTimeout(30_000L) { while (restored.isImporting) delay(50L) }
        assertEquals("Unsafe output must remain tracked after reset attempt", 1, restored.files.size)
    }

    @Test fun incompleteOutputIsRemovedAndRemainingWorkNeedsExplicitReview() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = fixture(context)
        val pending = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "QuickCut_interrupted_test.mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/QuickCut")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            })!!
        try {
            seed(context, input, pending, 2_000L, true)
            val restored = restore(context)
            assertEquals(BatchFileStatus.SKIPPED, restored.files.single().status)
            assertNull(restored.files.single().savedOutputUri)
            assertFalse(restored.isRunning)
            assertEquals(OriginalAction.KEEP, restored.originalAction)
            assertEquals(0, context.contentResolver.query(pending, arrayOf(MediaStore.Video.Media._ID),
                null, null, null)?.use { it.count } ?: 0)
            assertTrue(input.exists())
            withContext(Dispatchers.Main) { restored.resetBatch() }
        } finally {
            runCatching { context.contentResolver.delete(pending, null, null) }
            BatchSessionStore(context).clear()
            input.delete()
        }
    }

    @Test fun inaccessibleOriginalDoesNotRestartAnInterruptedExport() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.cacheDir, "quickcut_missing_recovery.mp4")
        input.delete()
        seed(context, input, null, 2_000L, true)
        try {
            val restored = restore(context)
            assertEquals(0L, restored.files.single().durationMs)
            assertEquals(BatchFileStatus.SKIPPED, restored.files.single().status)
            assertFalse(restored.isRunning)
            assertEquals(OriginalAction.KEEP, restored.originalAction)
            InstrumentationRegistry.getInstrumentation().context.assets.open("quickcut_fixture.mp4")
                .use { source -> input.outputStream().use { target -> source.copyTo(target) } }
            withContext(Dispatchers.Main) { restored.addVideos(context, listOf(Uri.fromFile(input))) }
            withTimeout(30_000L) { while (restored.isImporting) delay(50L) }
            assertEquals(1, restored.files.size)
            assertTrue(restored.files.single().durationMs >= 3_000L)
            assertEquals(BatchFileStatus.WAITING, restored.files.single().status)
            withContext(Dispatchers.Main) { restored.resetBatch() }
        } finally { BatchSessionStore(context).clear(); input.delete() }
    }

    private fun seed(context: Context, input: File, output: Uri?, duration: Long, audio: Boolean,
        status: BatchFileStatus = BatchFileStatus.RUNNING) {
        BatchSessionStore(context).save(BatchSessionSnapshot(
            files = listOf(BatchFile(Uri.fromFile(input).toString(), input.name, 3_000L, true,
                status=status, savedOutputUri=output?.toString(),
                outputExpectedDurationMs=duration, outputExpectedAudio=audio)),
            template = BatchTemplate(name="복구 계획", mode=BatchClipMode.KEEP_FIRST, leadingMs=2_000L),
            dateMode = SaveDateMode.CURRENT, runState=BatchRunState.RUNNING,
        ))
    }

    private suspend fun restore(context: Context,
        application: Application = context.applicationContext as Application): BatchViewModel {
        val model = withContext(Dispatchers.Main) { BatchViewModel(application) }.also { this.model = it }
        withTimeout(30_000L) { while (model.isRecovering) delay(50L) }
        return model
    }

    private fun fixture(context: Context): File = File.createTempFile("quickcut_journal_", ".mp4", context.cacheDir).also {
        InstrumentationRegistry.getInstrumentation().context.assets.open("quickcut_fixture.mp4")
            .use { source -> it.outputStream().use { target -> source.copyTo(target) } }
    }
}

