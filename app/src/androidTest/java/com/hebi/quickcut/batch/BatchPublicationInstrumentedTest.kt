package com.hebi.quickcut.batch

import android.app.Application
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.MainActivity
import com.hebi.quickcut.editor.OriginalAction
import com.hebi.quickcut.editor.OriginalActionStatus
import com.hebi.quickcut.media.MediaReader
import com.hebi.quickcut.media.SaveDateMode
import com.hebi.quickcut.settings.QuickCutSettings
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real MediaStore publication plus AtomicFile sync failure at the completion boundary. */
@RunWith(AndroidJUnit4::class)
class BatchPublicationInstrumentedTest {
    @get:Rule val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test fun persistentCompletionJournalFailureRetainsPublishedVideoAndBlocksRetry() = runBlocking {
        checkJournalFault(afterPublication = true, failOnce = false)
    }

    @Test fun laterSuccessfulAutomaticWritesDoNotBypassExplicitPublicationRecovery() = runBlocking {
        checkJournalFault(afterPublication = true, failOnce = true)
    }

    @Test fun creationJournalFailureStillCleansUnpublishedOutputAndKeepsSources() = runBlocking {
        checkJournalFault(afterPublication = false, failOnce = true)
    }

    private suspend fun checkJournalFault(afterPublication: Boolean, failOnce: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = QuickCutSettings(context)
        val oldDraft = settings.loadBatch()
        val oldDate = settings.loadSaveDate()
        val oldAction = settings.loadOriginalAction()
        val provider = PublicationProvider(context.contentResolver)
        provider.attachInfo(context, ProviderInfo().apply { authority = "media"; exported = true })
        val application = ResolverApplication(context, ContentResolver.wrap(provider))
        val inputs = List(if (afterPublication) 2 else 1) {
            File.createTempFile("batch_publication_", ".mp4", context.cacheDir).also { file ->
                InstrumentationRegistry.getInstrumentation().context.assets.open("quickcut_fixture.mp4")
                    .use { source -> file.outputStream().use { source.copyTo(it) } }
            }
        }
        val sourceLengths = inputs.map(File::length)
        val base = File.createTempFile("batch_publication_journal_", ".json", context.cacheDir).apply { delete() }
        var failWrites = true
        var injectedFailures = 0
        val store = BatchSessionStore(base, sync = { stream ->
            val atBoundary = if (afterPublication) provider.published.isNotEmpty()
                else provider.outputs.isNotEmpty() && provider.published.isEmpty()
            if (failWrites && atBoundary && (!failOnce || injectedFailures == 0)) {
                injectedFailures++
                throw IOException("Injected completion sync failure")
            }
            stream.fd.sync()
        })
        val model = withContext(Dispatchers.Main) { BatchViewModel(application, store) }
        try {
            withContext(Dispatchers.Main) {
                model.draftName = "완료 기록 오류 회귀"
                model.draftMode = BatchClipMode.KEEP_FIRST
                model.draftLeadingSeconds = "2"
                model.draftMute = false
                model.saveDateMode = SaveDateMode.CURRENT
                model.originalAction = OriginalAction.TRASH
                model.addVideos(application, inputs.map(Uri::fromFile))
            }
            withTimeout(30_000L) { while (model.isImporting || model.files.size != inputs.size) delay(50L) }
            withContext(Dispatchers.Main) { model.start(application) }
            withTimeout(150_000L) { while (model.runState != BatchRunState.FINISHED) delay(50L) }
            assertTrue("Fault must be reached at the requested journal boundary", injectedFailures > 0)
            assertTrue(inputs.all(File::exists))
            assertEquals(sourceLengths, inputs.map(File::length))
            assertTrue("Unrecorded completion must never permit source deletion", model.originalCandidates.isEmpty())
            assertEquals(OriginalActionStatus.NONE, model.originalStatus)
            assertEquals("A journal failure must never trigger an automatic export retry", 1, provider.outputs.size)
            if (!afterPublication) {
                assertTrue(provider.published.isEmpty())
                assertEquals(0, context.contentResolver.query(provider.outputs.single(),
                    arrayOf(MediaStore.Video.Media._ID), null, null, null)?.use { it.count } ?: 0)
                assertEquals(0, model.successes)
                return
            }
            val output = provider.published.single()
            assertEquals(BatchFileStatus.SAVED, model.files.first().status)
            assertEquals(output.toString(), model.files.first().savedOutputUri)
            assertEquals(1, model.successes)
            assertEquals(0, model.failures)
            assertTrue(model.notification?.contains("기록") == true)
            assertTrue(model.hasUnresolvedRecovery)
            assertEquals(output.toString(), store.load()!!.files.first().savedOutputUri)
            assertEquals(2_000L, store.load()!!.files.first().outputExpectedDurationMs)
            MediaReader.validateExport(context, output, 2_000L, true)
            withContext(Dispatchers.Main) { model.start(application, retryOnly = true) }
            assertFalse("Completion warning blocks duplicate retry", model.isRunning)
            assertEquals(1, provider.outputs.size)
            withContext(Dispatchers.Main) {
                model.removeVideo(model.files.first().uri)
                model.resetBatch()
            }
            assertEquals("Removal and reset cannot discard an unverified publication handle", inputs.size, model.files.size)
            assertEquals(output.toString(), model.files.first().savedOutputUri)
            assertEquals(output.toString(), store.load()!!.files.first().savedOutputUri)
            if (!failOnce) {
                withContext(Dispatchers.Main) { model.retryRecovery() }
                withTimeout(30_000L) { while (model.isRecovering) delay(50L) }
                assertTrue("Verification alone cannot clear a still-failing journal", model.hasUnresolvedRecovery)
            }
            failWrites = false
            withContext(Dispatchers.Main) { model.retryRecovery() }
            withTimeout(30_000L) { while (model.isRecovering) delay(50L) }
            assertFalse(model.hasUnresolvedRecovery)
            assertEquals(output.toString(), model.files.first().savedOutputUri)
            assertEquals(BatchFileStatus.SAVED, model.files.first().status)
            assertTrue("Recovered outputs never gain source-deletion approval", model.originalCandidates.isEmpty())
            withContext(Dispatchers.Main) { model.start(application, retryOnly = true) }
            withTimeout(150_000L) { while (model.runState != BatchRunState.FINISHED) delay(50L) }
            assertEquals("Only the remaining source may export after explicit recovery", 2, provider.outputs.size)
            assertEquals(output.toString(), model.files.first().savedOutputUri)
            assertTrue(inputs.all(File::exists))
        } finally {
            failWrites = false
            withContext(Dispatchers.Main) {
                model.cancel()
                ViewModelStore().apply { put("publication-test", model) }.clear()
            }
            provider.outputs.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
            store.clear()
            inputs.forEach(File::delete)
            settings.saveBatch(oldDraft)
            settings.saveSaveDate(oldDate)
            settings.saveOriginalAction(oldAction)
        }
    }

    private class ResolverApplication(base: Context, private val resolver: ContentResolver) : Application() {
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getContentResolver(): ContentResolver = resolver
    }

    private class PublicationProvider(private val real: ContentResolver) : ContentProvider() {
        val outputs = mutableListOf<Uri>()
        val published = mutableListOf<Uri>()
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = real.getType(uri)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = real.insert(uri, values)?.also(outputs::add)
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = real.openFileDescriptor(uri, mode)
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
            real.query(uri, projection, selection, selectionArgs, sortOrder)
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            val count = real.update(uri, values, selection, selectionArgs)
            if (count > 0 && values?.getAsInteger(MediaStore.Video.Media.IS_PENDING) == 0) {
                real.query(uri, arrayOf(MediaStore.Video.Media.IS_PENDING), null, null, null)!!.use {
                    check(it.moveToFirst() && it.getInt(0) == 0)
                }
                published.add(uri)
            }
            return count
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            real.delete(uri, selection, selectionArgs)
    }
}
