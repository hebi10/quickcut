package com.hebi.quickcut.media

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingCleanupInstrumentedTest {
    @Test fun zeroDeleteMustBlockAutomaticFallbackAndRetainPendingHandle() = runBlocking {
        checkExporterCleanup(throwsOnDelete = false)
    }

    @Test fun throwingDeleteMustBlockAutomaticFallbackAndRetainPendingHandle() = runBlocking {
        checkExporterCleanup(throwsOnDelete = true)
    }

    private suspend fun checkExporterCleanup(throwsOnDelete: Boolean) {
        val real = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File.createTempFile("cleanup_source", ".mp4", real.cacheDir)
        InstrumentationRegistry.getInstrumentation().context.assets.open("quickcut_fixture.mp4").use { source ->
            input.outputStream().use { source.copyTo(it) }
        }
        val provider = FailingOutputProvider(real.contentResolver, throwsOnDelete)
        provider.attachInfo(real, ProviderInfo().apply { authority = "media"; exported = true })
        val wrapped = ResolverContext(real, ContentResolver.wrap(provider))
        val recorded = mutableListOf<Uri>()
        var committed = false
        val removed = mutableSetOf<Uri>()
        try {
            val metadata = MediaReader.metadata(real, Uri.fromFile(input))
            val clip = VideoClip(id = 1L, uri = Uri.fromFile(input).toString(),
                sourceDurationMs = metadata.durationMs, endMs = 2_000L, hasAudio = metadata.hasAudio)
            assertNotNull("Fixture must exercise native remux", FastMp4Trimmer.prepare(wrapped, clip))
            val failure = runCatching {
                VideoExporter(wrapped).export(EditTimeline(listOf(clip)), {}, {},
                    exportMode = ExportMode.ALLOW_REENCODE,
                    onOutputCreated = { recorded.add(it) }, onCommitted = { committed = true })
            }.exceptionOrNull()
            assertTrue("Injected validation failure must be reached", provider.validationDenied)
            assertTrue("Pending deletion must be attempted", provider.deleteAttempts > 0)
            assertNotNull("Unconfirmed cleanup must fail export instead of falling back", failure)
            assertFalse("Unconfirmed cleanup is not a remux eligibility error", failure is FastTrimUnavailableException)
            assertTrue(failure is OutputCleanupException)
            assertEquals(recorded.single(), (failure as OutputCleanupException).outputUri)
            assertFalse(committed)
            assertEquals("Fallback must not overwrite the journal's retained handle", 1, recorded.size)
            assertEquals(1, provider.outputs.size)
            assertEquals(provider.outputs.single(), recorded.single())
            real.contentResolver.query(recorded.single(), arrayOf(MediaStore.Video.Media.IS_PENDING), null, null, null)!!.use {
                assertTrue("Failed row must remain discoverable for recovery", it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
            // The same tracked handle can be removed once the provider recovers.
            assertEquals(1, real.contentResolver.delete(recorded.single(), null, null))
            removed.add(recorded.single())
            assertTrue("Cleanup must never remove the source", input.exists())
        } finally {
            provider.outputs.filterNot { it in removed }.forEach { real.contentResolver.delete(it, null, null) }
            input.delete()
        }
    }

    @Test fun cleanupFailurePreservesCancellation() = runBlocking {
        val real = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = FailingOutputProvider(real.contentResolver, true)
        provider.attachInfo(real, ProviderInfo().apply { authority = "media"; exported = true })
        val wrapped = ResolverContext(real, ContentResolver.wrap(provider))
        val cancellation = CancellationException("User cancelled")
        try {
            val failure = runCatching {
                GalleryWriter(wrapped).saveDirect(null, 2_000, false,
                    writer = { throw cancellation }, onFinalizing = {})
            }.exceptionOrNull()
            assertSame(cancellation, failure)
            val cleanup = failure!!.suppressed.filterIsInstance<OutputCleanupException>().single()
            assertEquals(provider.outputs.single(), cleanup.outputUri)
            assertEquals(1, provider.outputs.size)
            assertTrue(provider.deleteAttempts > 0)
        } finally {
            provider.outputs.forEach { real.contentResolver.delete(it, null, null) }
        }
    }

    @Test fun zeroDeleteAfterConfirmedRemovalPreservesOriginalFastFailure(): Unit = runBlocking {
        val real = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = FailingOutputProvider(real.contentResolver, false)
        provider.attachInfo(real, ProviderInfo().apply { authority = "media"; exported = true })
        val wrapped = ResolverContext(real, ContentResolver.wrap(provider))
        val original = FastTrimUnavailableException("Injected remux failure")
        val removed = mutableSetOf<Uri>()
        try {
            val failure = runCatching {
                GalleryWriter(wrapped).saveDirect(null, 2_000, false,
                    writer = {
                        assertEquals(1, real.contentResolver.delete(provider.outputs.single(), null, null))
                        removed.add(provider.outputs.single())
                        throw original
                    }, onFinalizing = {})
            }.exceptionOrNull()
            assertSame("Confirmed absence permits normal fallback", original, failure)
            assertEquals(1, provider.deleteAttempts)
        } finally {
            provider.outputs.filterNot { it in removed }.forEach { real.contentResolver.delete(it, null, null) }
        }
    }

    private class ResolverContext(base: Context, private val resolver: ContentResolver) : ContextWrapper(base) {
        override fun getContentResolver(): ContentResolver = resolver
    }

    /** Forward real MediaStore operations, injecting only first-output read/delete faults. */
    private class FailingOutputProvider(
        private val real: ContentResolver,
        private val throwsOnDelete: Boolean,
    ) : ContentProvider() {
        val outputs = mutableListOf<Uri>()
        var deleteAttempts = 0
        var validationDenied = false
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri? = real.insert(uri, values)?.also(outputs::add)
        override fun getType(uri: Uri): String? = real.getType(uri)
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
            real.query(uri, projection, selection, selectionArgs, sortOrder)
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
            real.update(uri, values, selection, selectionArgs)
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            if (uri == outputs.firstOrNull()) {
                deleteAttempts++
                if (throwsOnDelete) throw IOException("Injected provider cleanup failure")
                return 0
            }
            return real.delete(uri, selection, selectionArgs)
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            if (mode == "r" && uri == outputs.firstOrNull()) {
                validationDenied = true
                throw FileNotFoundException("Injected first-output validation failure")
            }
            return real.openFileDescriptor(uri, mode)
        }
    }
}
