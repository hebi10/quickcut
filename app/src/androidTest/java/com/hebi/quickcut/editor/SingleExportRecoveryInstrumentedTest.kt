package com.hebi.quickcut.editor

import android.app.Application
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.media.GalleryWriter
import com.hebi.quickcut.media.ExportMode
import com.hebi.quickcut.media.MediaReader
import com.hebi.quickcut.media.OutputCleanupException
import com.hebi.quickcut.MainActivity
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class SingleExportRecoveryInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val journal get() = SingleExportStore(context)
    private val session get() = EditorSessionStore(context)
    private val outputs = mutableListOf<Uri>()
    private val models = mutableListOf<EditorViewModel>()
    private var oldSession: EditorSessionSnapshot? = null
    private var oldExport: SingleExportSnapshot? = null
    private lateinit var source: File
    private lateinit var edit: EditorSessionSnapshot

    @Before fun prepare() {
        oldSession = session.load()
        oldExport = journal.load()
        journal.clear()
        session.clear()
        source = File.createTempFile("single_export_recovery_source", ".mp4", context.cacheDir)
        InstrumentationRegistry.getInstrumentation().context.assets.open("quickcut_keyframes.mp4").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        val metadata = MediaReader.metadata(context, Uri.fromFile(source))
        edit = EditorSessionSnapshot(EditTimeline(listOf(VideoClip(1L, Uri.fromFile(source).toString(),
            metadata.durationMs, hasAudio = metadata.hasAudio))), 1L, 0L, true)
        session.save(edit)
    }

    @After fun restore() = runBlocking {
        withContext(Dispatchers.Main) {
            models.forEach { ViewModelStore().apply { put("recovery", it) }.clear() }
        }
        outputs.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        oldSession?.let(session::save) ?: session.clear()
        oldExport?.let(journal::save) ?: journal.clear()
        source.delete()
        Unit
    }

    @Test fun restartRemovesOwnedPendingOutputAndKeepsInterruptedTimeline() = runBlocking {
        val uri = createOutput(published = false)
        journal.save(record(uri))
        val model = restartedModel()
        assertFalse(exists(uri))
        assertFalse(model.hasUnresolvedExport)
        assertNull(journal.load())
        assertEquals(edit, session.load())
        assertTrue(model.hasRestorableSession)
        assertTrue(source.exists())
    }

    @Test fun silentlyFailedAtomicCommitCannotReleaseTheWriterWithAnOldNullUriRecord() {
        val base = File.createTempFile("single_export_atomic_check", ".json", context.cacheDir)
        base.delete()
        val normal = SingleExportStore(base)
        val original = SingleExportSnapshot(null, 1_000L, false, null, 1_000_000L)
        normal.save(original)
        val failedRename = object : android.util.AtomicFile(base) {
            override fun finishWrite(stream: java.io.FileOutputStream?) { stream?.close() }
        }
        try {
            val failure = runCatching {
                SingleExportStore(base, failedRename).save(original.copy(outputUri = "content://media/external/video/media/123"))
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals(original, normal.load())
        } finally { normal.clear() }
    }

    @Test fun startingExportDurablyFlushesLatestEditBeforeDebounceAndClearsOnlyAfterPublication() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val model = restartedModel()
            withContext(Dispatchers.Main) { model.restoreSavedSession(context) }
            withTimeout(20_000L) { while (model.page == QuickCutPage.IMPORTING) delay(25L) }
            assertEquals(QuickCutPage.EDITOR, model.page)
            withContext(Dispatchers.Main) {
                model.trimSelected(0L, 2_000L)
                model.startExport(context, selectedOriginalAction = OriginalAction.DELETE,
                    exportMode = ExportMode.FAST_ONLY)
                assertEquals("최신 편집은 300ms 지연 없이 저장 전에 기록되어야 합니다", 2_000L,
                    session.load()!!.timeline.clips.single().endMs)
            }
            withTimeout(60_000L) {
                while (model.page == QuickCutPage.EXPORTING || model.isRecoveringExport) delay(25L)
            }
            model.completedUri?.let(outputs::add)
            assertEquals(model.errorMessage, QuickCutPage.COMPLETED, model.page)
            assertNotNull(model.completedUri)
            assertNull(session.load())
            assertNull(journal.load())
            assertFalse(model.hasUnresolvedExport)
            assertTrue(source.exists())
        }
    }

    @Test fun publishedBeforeCompletionCallbackIsAdoptedWithoutDeletionConsent() = runBlocking {
        val uri = createOutput(published = true)
        // No completion marker: this models death just after IS_PENDING becomes 0.
        journal.save(record(uri))
        val model = restartedModel()
        assertEquals(QuickCutPage.COMPLETED, model.page)
        assertEquals(uri, model.completedUri)
        assertFalse(model.canStartExport)
        assertEquals(OriginalAction.KEEP, model.originalAction)
        assertEquals(OriginalActionStatus.NONE, model.originalActionStatus)
        assertTrue(model.originalsFromSavedTimeline.isEmpty())
        assertNull(session.load())
        assertNull(journal.load())
        assertTrue(exists(uri))
        assertTrue(source.exists())
    }

    @Test fun invalidPublishedOutputIsRetainedAndBlocksAnotherExport() = runBlocking {
        val uri = createUnverifiablePublishedOutput()
        val saved = record(uri)
        journal.save(saved)
        val model = restartedModel()
        assertTrue(model.hasUnresolvedExport)
        assertFalse(model.canStartExport)
        assertEquals(saved, journal.load())
        assertEquals(edit, session.load())
        withContext(Dispatchers.Main) { model.discardEditing() }
        assertEquals(edit, session.load())
        assertTrue(exists(uri))
    }

    @Test fun retryRecoveryAfterUserRemovesUnverifiableOutputKeepsEditingAvailable() = runBlocking {
        val uri = createUnverifiablePublishedOutput()
        journal.save(record(uri))
        val model = restartedModel()
        assertTrue(model.hasUnresolvedExport)
        context.contentResolver.delete(uri, null, null)
        withContext(Dispatchers.Main) { model.retryExportRecovery() }
        withTimeout(25_000L) { while (model.isRecoveringExport) delay(25L) }
        assertFalse(model.hasUnresolvedExport)
        assertEquals(edit, session.load())
        assertNull(journal.load())
    }

    @Test fun confirmedMissingOutputAllowsRestoringInterruptedEdit() = runBlocking {
        val uri = createOutput(published = false)
        context.contentResolver.delete(uri, null, null)
        journal.save(record(uri))
        val model = restartedModel()
        assertFalse(model.hasUnresolvedExport)
        assertEquals(edit, session.load())
        withContext(Dispatchers.Main) { model.restoreSavedSession(context) }
        withTimeout(20_000L) { while (model.page == QuickCutPage.IMPORTING) delay(25L) }
        assertEquals(edit.timeline, model.timeline)
        assertTrue(model.canStartExport)
    }

    @Test fun foreignContentUriIsNeverQueriedOrDeleted() {
        val provider = FaultProvider(context.contentResolver)
        val recovery = recovery(provider)
        val saved = record(Uri.parse("content://foreign.provider/video/42"))
        journal.save(saved)
        assertTrue(recovery.recover() is SingleExportRecoveryResult.Blocked)
        assertEquals(0, provider.queryCount)
        assertEquals(0, provider.deleteCount)
        assertEquals(saved, journal.load())
    }

    @Test fun foreignOwnerOrWrongPendingDirectoryIsNeverDeleted() {
        val uri = createOutput(published = false)
        val saved = record(uri)
        for ((owner, folder) in listOf("another.package" to "Movies/QuickCut/",
            context.packageName to "Movies/AnotherFolder/")) {
            val provider = FaultProvider(context.contentResolver).apply { rowOwner = owner; rowFolder = folder }
            journal.save(saved)
            assertTrue(recovery(provider).recover() is SingleExportRecoveryResult.Blocked)
            assertEquals(0, provider.deleteCount)
            assertEquals(saved, journal.load())
            assertTrue(exists(uri))
        }
    }

    @Test fun foreignPublishedOwnerIsNotAdoptedEvenWhenVideoIsValid() {
        val uri = createOutput(published = true)
        val saved = record(uri)
        val provider = FaultProvider(context.contentResolver).apply { rowOwner = "another.package"; rowPending = 0 }
        journal.save(saved)
        assertTrue(recovery(provider).recover() is SingleExportRecoveryResult.Blocked)
        assertEquals(saved, journal.load())
        assertTrue(exists(uri))
    }

    @Test fun nullOrDeniedQueryRetainsJournalInsteadOfTreatingOutputAsMissing() {
        val uri = createOutput(published = false)
        val saved = record(uri)
        for (denied in listOf(false, true)) {
            val provider = FaultProvider(context.contentResolver).apply {
                nullQuery = !denied; deniedQuery = denied
            }
            journal.save(saved)
            assertTrue(recovery(provider).recover() is SingleExportRecoveryResult.Blocked)
            assertEquals(saved, journal.load())
            assertEquals(0, provider.deleteCount)
        }
    }

    @Test fun zeroOrThrowingCleanupRetainsJournalUntilRemovalIsConfirmed() {
        val uri = createOutput(published = false)
        val saved = record(uri)
        for (throws in listOf(false, true)) {
            val provider = FaultProvider(context.contentResolver).apply { denyDelete = true; throwDelete = throws }
            journal.save(saved)
            assertTrue(recovery(provider).recover() is SingleExportRecoveryResult.Blocked)
            assertEquals(saved, journal.load())
            assertTrue(exists(uri))
        }
        assertEquals(SingleExportRecoveryResult.RetryAllowed,
            SingleExportRecovery(journal, MediaStoreSingleExportAccess(context)).recover())
        assertFalse(exists(uri))
    }

    @Test fun cancellationCleanupFailureRetainsCreatedOutputForRecovery() = runBlocking {
        val provider = FaultProvider(context.contentResolver).apply { denyDelete = true; throwDelete = true }
        val wrapped = wrapped(provider)
        val cancelled = CancellationException("test cancellation")
        val failure = runCatching {
            GalleryWriter(wrapped).saveDirect(null, edit.timeline.totalDurationMs,
                edit.timeline.clips.single().hasAudio, writer = { throw cancelled }, onFinalizing = {},
                onOutputCreated = { outputs.add(it); journal.save(record(it)) })
        }.exceptionOrNull()
        assertSame(cancelled, failure)
        assertTrue(failure!!.suppressed.any { it is OutputCleanupException })
        val saved = journal.load()
        assertNotNull(saved?.outputUri)
        assertTrue(SingleExportRecovery(journal, MediaStoreSingleExportAccess(wrapped)).recover()
            is SingleExportRecoveryResult.Blocked)
        assertEquals(saved, journal.load())
        assertEquals(SingleExportRecoveryResult.RetryAllowed,
            SingleExportRecovery(journal, MediaStoreSingleExportAccess(context)).recover())
        assertTrue(source.exists())
    }

    @Test fun corruptJournalBlocksRetryWithoutDiscardingInterruptedEdit() = runBlocking {
        val file = File(context.filesDir, "quickcut_single_export_v1.json")
        file.writeText("{truncated")
        val model = restartedModel()
        assertTrue(model.hasUnresolvedExport)
        assertFalse(model.canStartExport)
        assertEquals("{truncated", file.readText())
        assertEquals(edit, session.load())
    }

    @Test fun missingRequiredJournalFieldCannotBeInterpretedAsNoCreatedOutput() {
        journal.save(record(createOutput(published = false)))
        val file = File(context.filesDir, "quickcut_single_export_v1.json")
        val data = JSONObject(file.readText()).apply { remove("outputUri") }
        file.writeText(data.toString())
        assertTrue(SingleExportRecovery(journal, MediaStoreSingleExportAccess(context)).recover()
            is SingleExportRecoveryResult.Blocked)
        assertTrue(file.exists())
        assertEquals(edit, session.load())
    }

    @Test fun atomicJournalReadsLastCompleteRecordAfterInterruptedReplacement() {
        val first = record(null)
        journal.save(first)
        val base = File(context.filesDir, "quickcut_single_export_v1.json")
        File(base.path + ".new").writeText("{incomplete replacement")
        assertEquals(first, SingleExportStore(context).load())
    }

    @Test fun atomicJournalRoundTripRetainsExactMicrosecondDuration() {
        val saved = SingleExportSnapshot(null, 1_334L, false, expectedDurationUs = 1_333_334L)
        journal.save(saved)
        assertEquals(saved, SingleExportStore(context).load())
    }

    @Test fun publishedOutputMustMatchDurationAudioAndDateContract() {
        val uri = createOutput(published = true)
        val valid = record(uri)
        val mismatches = listOf(
            valid.copy(expectedDurationMs = valid.expectedDurationMs + 10_000L,
                expectedDurationUs = valid.expectedDurationUs + 10_000_000L),
            valid.copy(expectedAudio = !valid.expectedAudio),
            valid.copy(expectedDateMs = 946_684_800_000L),
        )
        for (saved in mismatches) {
            journal.save(saved)
            assertTrue(SingleExportRecovery(journal, MediaStoreSingleExportAccess(context)).recover()
                is SingleExportRecoveryResult.Blocked)
            assertEquals(saved, journal.load())
            assertTrue(exists(uri))
        }
    }

    private suspend fun restartedModel(): EditorViewModel {
        val model = withContext(Dispatchers.Main) {
            EditorViewModel(context.applicationContext as Application)
        }.also(models::add)
        withTimeout(25_000L) { while (model.isRecoveringExport) delay(25L) }
        return model
    }

    private fun record(uri: Uri?) = SingleExportSnapshot(uri?.toString(), edit.timeline.totalDurationMs,
        edit.timeline.clips.any { !it.muted && it.hasAudio })

    private fun createOutput(published: Boolean, contents: File = source): Uri {
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "QuickCut_recovery_test_${System.nanoTime()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/QuickCut")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }))
        outputs.add(uri)
        if (published) {
            context.contentResolver.openOutputStream(uri)!!.use { sink -> contents.inputStream().use { it.copyTo(sink) } }
            assertEquals(1, context.contentResolver.update(uri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null))
        }
        return uri
    }

    private fun createUnverifiablePublishedOutput(): Uri {
        val damaged = File.createTempFile("single_export_damaged_samples", ".mp4", context.cacheDir)
        try {
            source.copyTo(damaged, overwrite = true)
            // Publishing a row without a backing file makes MediaStore remove it.
            // Keep a real, indexed MP4 container and damage only its media samples.
            RandomAccessFile(damaged, "rw").use { mp4 ->
                var position = 0L
                var erased = false
                while (position + 8L <= mp4.length()) {
                    mp4.seek(position)
                    val shortSize = mp4.readInt().toLong() and 0xffff_ffffL
                    val type = ByteArray(4).also(mp4::readFully).toString(Charsets.US_ASCII)
                    val headerSize = if (shortSize == 1L) 16L else 8L
                    val size = when (shortSize) {
                        0L -> mp4.length() - position
                        1L -> mp4.readLong()
                        else -> shortSize
                    }
                    check(size >= headerSize && size <= mp4.length() - position)
                    if (type == "mdat") {
                        mp4.seek(position + headerSize)
                        var remaining = size - headerSize
                        val zeros = ByteArray(64 * 1024)
                        while (remaining > 0L) {
                            val count = minOf(remaining, zeros.size.toLong()).toInt()
                            mp4.write(zeros, 0, count)
                            remaining -= count
                        }
                        erased = true
                    }
                    position += size
                }
                check(erased) { "Fixture must contain media samples" }
            }
            val uri = createOutput(published = true, contents = damaged)
            context.contentResolver.query(uri, arrayOf(MediaStore.Video.Media.IS_PENDING), null, null, null)!!.use {
                assertTrue("Damaged output must remain an indexed video before recovery", it.moveToFirst())
                assertEquals("Fixture must already be published", 0, it.getInt(0))
            }
            val saved = record(uri)
            assertEquals("Container metadata must remain readable", saved.expectedDurationMs,
                MediaReader.metadata(context, uri).durationMs)
            assertNotNull("Damaged samples must fail validation before recovery starts", runCatching {
                MediaReader.validateExport(context, uri, saved.expectedDurationMs, saved.expectedAudio)
            }.exceptionOrNull())
            return uri
        } finally {
            damaged.delete()
        }
    }

    private fun exists(uri: Uri): Boolean = context.contentResolver.query(uri,
        arrayOf(MediaStore.Video.Media._ID), null, null, null)?.use { it.moveToFirst() } == true

    private fun recovery(provider: FaultProvider) = SingleExportRecovery(journal, MediaStoreSingleExportAccess(wrapped(provider)))

    private fun wrapped(provider: FaultProvider): Context {
        provider.attachInfo(context, ProviderInfo().apply { authority = "media"; exported = true })
        return object : ContextWrapper(context) {
            override fun getContentResolver(): ContentResolver = ContentResolver.wrap(provider)
        }
    }

    private class FaultProvider(private val real: ContentResolver) : ContentProvider() {
        var queryCount = 0
        var deleteCount = 0
        var nullQuery = false
        var deniedQuery = false
        var denyDelete = false
        var throwDelete = false
        var rowOwner: String? = null
        var rowFolder = "Movies/QuickCut/"
        var rowPending = 1
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri? = real.insert(uri, values)
        override fun getType(uri: Uri): String? = real.getType(uri)
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            queryCount++
            if (deniedQuery) throw SecurityException("test denial")
            if (nullQuery) return null
            if (rowOwner != null && projection?.firstOrNull() == MediaStore.Video.Media.IS_PENDING)
                return MatrixCursor(arrayOf(MediaStore.Video.Media.IS_PENDING,
                    MediaStore.Video.Media.OWNER_PACKAGE_NAME, MediaStore.Video.Media.RELATIVE_PATH))
                    .apply { addRow(arrayOf<Any>(rowPending, rowOwner!!, rowFolder)) }
            return real.query(uri, projection, selection, selectionArgs, sortOrder)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
            real.update(uri, values, selection, selectionArgs)
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deleteCount++
            if (throwDelete) throw IOException("test cleanup failure")
            return if (denyDelete) 0 else real.delete(uri, selection, selectionArgs)
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = real.openFileDescriptor(uri, mode)
    }
}
