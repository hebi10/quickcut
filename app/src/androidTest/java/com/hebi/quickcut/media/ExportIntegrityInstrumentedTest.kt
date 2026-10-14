package com.hebi.quickcut.media

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** These regressions use real MP4 bytes and MediaStore, including failed-output cleanup. */
@RunWith(AndroidJUnit4::class)
class ExportIntegrityInstrumentedTest {
    @Test
    fun metadataOnlyMp4IsRejectedWhenItsFramesCannotBeDecoded() {
        withFixture { context, file ->
            eraseMediaSamples(file)
            // Prove the container is still readable: duration-only validation is insufficient.
            assertTrue(MediaReader.metadata(context, Uri.fromFile(file)).durationMs >= 2_700L)

            val failure = runCatching { MediaReader.validateExport(file) }.exceptionOrNull()

            assertNotNull("Readable metadata must not certify missing video samples", failure)
        }
    }

    @Test
    fun copySaveRejectsUnreadableFramesBeforePublishingAndRemovesTheFailedRow() = runBlocking {
        withFixtureSuspending { context, file ->
            eraseMediaSamples(file)
            val name = "invalid_frames_" + UUID.randomUUID().toString().take(8)
            var output: Uri? = null
            try {
                val failure = runCatching {
                    output = GalleryWriter(context).save(file, outputBaseName = name)
                }.exceptionOrNull()

                assertNotNull("A save must verify readable frames before exposing its output", failure)
                assertEquals("Failed output must not remain pending or published", 0, savedRows(context, name))
            } finally {
                output?.let { context.contentResolver.delete(it, null, null) }
                deleteRows(context, name)
            }
        }
    }

    @Test
    fun directSaveUsesTheSharedThreeHundredMillisecondDurationTolerance() = runBlocking {
        withFixtureSuspending { context, file ->
            val metadata = MediaReader.metadata(context, Uri.fromFile(file))
            val output = GalleryWriter(context).saveDirect(
                outputBaseName = "duration_tolerance",
                expectedDurationMs = metadata.durationMs + 275L,
                expectAudio = metadata.hasAudio,
                writer = { descriptor -> copyToDescriptor(file, descriptor) },
                onFinalizing = {},
            )
            try {
                assertTrue(MediaReader.metadata(context, output).durationMs >= 2_700L)
            } finally {
                context.contentResolver.delete(output, null, null)
            }
        }
    }

    @Test
    fun copySaveRejectsUnexpectedDurationAndAudioBeforePublishing() = runBlocking {
        withFixtureSuspending { context, file ->
            val metadata = MediaReader.metadata(context, Uri.fromFile(file))
            val name = "copy_mismatch_" + UUID.randomUUID().toString().take(8)
            try {
                val lengthFailure = runCatching {
                    GalleryWriter(context).save(
                        file,
                        outputBaseName = name,
                        expectedDurationMs = metadata.durationMs + 301L,
                        expectAudio = metadata.hasAudio,
                    )
                }.exceptionOrNull()
                assertNotNull("Copy must reject a duration error over 300ms", lengthFailure)
                val audioFailure = runCatching {
                    GalleryWriter(context).save(
                        file,
                        outputBaseName = name,
                        expectedDurationMs = metadata.durationMs,
                        expectAudio = !metadata.hasAudio,
                    )
                }.exceptionOrNull()
                assertNotNull("Copy must reject unexpected audio", audioFailure)
                assertEquals(0, savedRows(context, name))
            } finally {
                deleteRows(context, name)
            }
        }
    }

    @Test
    fun directSaveRejectsUnreadableFramesAndRemovesItsPendingRow() = runBlocking {
        withFixtureSuspending { context, file ->
            val metadata = MediaReader.metadata(context, Uri.fromFile(file))
            eraseMediaSamples(file)
            val name = "direct_invalid_" + UUID.randomUUID().toString().take(8)
            try {
                val failure = runCatching {
                    GalleryWriter(context).saveDirect(
                        outputBaseName = name,
                        expectedDurationMs = metadata.durationMs,
                        expectAudio = metadata.hasAudio,
                        writer = { descriptor -> copyToDescriptor(file, descriptor) },
                        onFinalizing = {},
                    )
                }.exceptionOrNull()
                assertNotNull("Direct output must decode before it is published", failure)
                assertEquals(0, savedRows(context, name))
            } finally {
                deleteRows(context, name)
            }
        }
    }

    @Test
    fun directSaveRejectsUnexpectedAudioAndRemovesItsPendingRow() = runBlocking {
        withFixtureSuspending { context, file ->
            val metadata = MediaReader.metadata(context, Uri.fromFile(file))
            val name = "direct_audio_" + UUID.randomUUID().toString().take(8)
            try {
                val failure = runCatching {
                    GalleryWriter(context).saveDirect(
                        outputBaseName = name,
                        expectedDurationMs = metadata.durationMs,
                        expectAudio = !metadata.hasAudio,
                        writer = { descriptor -> copyToDescriptor(file, descriptor) },
                        onFinalizing = {},
                    )
                }.exceptionOrNull()
                assertNotNull("Direct output must match the expected audio", failure)
                assertEquals(0, savedRows(context, name))
            } finally {
                deleteRows(context, name)
            }
        }
    }

    @Test
    fun cancellationFromFinalizingRemovesTheUnpublishedOutput() = runBlocking {
        withFixtureSuspending { context, file ->
            val metadata = MediaReader.metadata(context, Uri.fromFile(file))
            val name = "cancel_pending_" + UUID.randomUUID().toString().take(8)
            var committed = false
            lateinit var exportJob: Job
            try {
                exportJob = launch {
                    GalleryWriter(context).saveDirect(
                        outputBaseName = name,
                        expectedDurationMs = metadata.durationMs,
                        expectAudio = metadata.hasAudio,
                        writer = { descriptor -> copyToDescriptor(file, descriptor) },
                        onFinalizing = { exportJob.cancel() },
                        onCommitted = { committed = true },
                    )
                }
                exportJob.join()
                assertTrue(exportJob.isCancelled)
                assertTrue("A cancelled pending output cannot be committed", !committed)
                assertEquals(0, savedRows(context, name))
            } finally {
                deleteRows(context, name)
            }
        }
    }

    @Test
    fun cancellationFromCommittedCallbackRetainsTheRecordedPublishedOutput() = runBlocking {
        withFixtureSuspending { context, file ->
            val metadata = MediaReader.metadata(context, Uri.fromFile(file))
            val name = "cancel_committed_" + UUID.randomUUID().toString().take(8)
            var created: Uri? = null
            var committed: Uri? = null
            lateinit var exportJob: Job
            try {
                exportJob = launch {
                    GalleryWriter(context).saveDirect(
                        outputBaseName = name,
                        expectedDurationMs = metadata.durationMs,
                        expectAudio = metadata.hasAudio,
                        writer = { descriptor -> copyToDescriptor(file, descriptor) },
                        onFinalizing = {},
                        onOutputCreated = { uri ->
                            assertEquals(Looper.getMainLooper(), Looper.myLooper())
                            assertEquals(1, pendingValue(context, uri))
                            created = uri
                        },
                        onCommitted = { uri ->
                            assertEquals(Looper.getMainLooper(), Looper.myLooper())
                            assertEquals(created, uri)
                            assertEquals(0, pendingValue(context, uri))
                            committed = uri
                            exportJob.cancel()
                        },
                    )
                }
                exportJob.join()
                assertTrue(exportJob.isCancelled)
                assertNotNull("Publication must reach its completion journal even when cancelled", committed)
                assertEquals("A recorded good output must survive cancellation", 1, savedRows(context, name))
                MediaReader.validateExport(context, committed!!, metadata.durationMs, metadata.hasAudio)
            } finally {
                deleteRows(context, name)
            }
        }
    }

    @Test
    fun failedCommittedJournalRemovesThePublishedOutput() = runBlocking {
        withFixtureSuspending { context, file ->
            val metadata = MediaReader.metadata(context, Uri.fromFile(file))
            val name = "failed_commit_" + UUID.randomUUID().toString().take(8)
            val journalFailure = IOException("Completion journal unavailable")
            try {
                val failure = runCatching {
                    GalleryWriter(context).saveDirect(
                        outputBaseName = name,
                        expectedDurationMs = metadata.durationMs,
                        expectAudio = metadata.hasAudio,
                        writer = { descriptor -> copyToDescriptor(file, descriptor) },
                        onFinalizing = {},
                        onCommitted = { throw journalFailure },
                    )
                }.exceptionOrNull()
                assertEquals(journalFailure, failure?.cause)
                assertEquals("A published file without a successful journal must be removed", 0, savedRows(context, name))
            } finally {
                deleteRows(context, name)
            }
        }
    }

    @Test
    fun failedCreationJournalRemovesThePendingOutput() = runBlocking {
        withFixtureSuspending { context, file ->
            val name = "failed_create_" + UUID.randomUUID().toString().take(8)
            val journalFailure = IOException("Creation journal unavailable")
            try {
                val failure = runCatching {
                    GalleryWriter(context).save(
                        file,
                        outputBaseName = name,
                        onOutputCreated = { throw journalFailure },
                    )
                }.exceptionOrNull()
                assertEquals(journalFailure, failure?.cause)
                assertEquals(0, savedRows(context, name))
            } finally {
                deleteRows(context, name)
            }
        }
    }

    @Test
    fun failedCompletionCallbackDoesNotStartAnAutomaticReencode() = runBlocking {
        withFixtureSuspending { context, file ->
            val metadata = MediaReader.metadata(context, Uri.fromFile(file))
            val name = "no_callback_retry_" + UUID.randomUUID().toString().take(8)
            val journalFailure = IllegalStateException("Completion journal unavailable")
            var createdCount = 0
            try {
                val failure = runCatching {
                    VideoExporter(context).export(
                        timeline = EditTimeline(listOf(VideoClip(
                            id = 1L,
                            uri = Uri.fromFile(file).toString(),
                            sourceDurationMs = metadata.durationMs,
                            endMs = 2_000L,
                            hasAudio = metadata.hasAudio,
                        ))),
                        onProgress = {},
                        onFinalizing = {},
                        outputBaseName = name,
                        exportMode = ExportMode.ALLOW_REENCODE,
                        onOutputCreated = { createdCount++ },
                        onCommitted = { throw journalFailure },
                    )
                }.exceptionOrNull()
                assertEquals(journalFailure, failure?.cause)
                assertEquals("Persistence failure must not be mistaken for remux incompatibility", 1, createdCount)
                assertEquals(0, savedRows(context, name))
            } finally {
                deleteRows(context, name)
            }
        }
    }

    @Test
    fun copySaveRejectsChangedOriginalTimestampBeforePublishing() = runBlocking {
        withFixtureSuspending { context, file ->
            val original = OriginalMediaDates.resolve(context, Uri.fromFile(file))
                ?: throw AssertionError("Fixture must have an original MP4 creation date")
            val name = "wrong_original_date_" + UUID.randomUUID().toString().take(8)
            try {
                val failure = runCatching {
                    GalleryWriter(context).save(
                        file,
                        originalTimestampMs = original.epochMs + 60_000L,
                        outputBaseName = name,
                    )
                }.exceptionOrNull()
                assertNotNull("A changed original date cannot be published", failure)
                assertEquals(0, savedRows(context, name))
            } finally {
                deleteRows(context, name)
            }
        }
    }

    private fun pendingValue(context: Context, uri: Uri): Int? =
        context.contentResolver.query(uri, arrayOf(MediaStore.Video.Media.IS_PENDING), null, null, null)
            ?.use { if (it.moveToFirst()) it.getInt(0) else null }

    private fun withFixture(block: (Context, File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = copyFixture(context)
        try {
            block(context, file)
        } finally {
            file.delete()
        }
    }

    private suspend fun withFixtureSuspending(block: suspend (Context, File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = copyFixture(context)
        try {
            block(context, file)
        } finally {
            file.delete()
        }
    }

    private fun copyFixture(context: Context): File =
        File.createTempFile("export_integrity_", ".mp4", context.cacheDir).also { file ->
            InstrumentationRegistry.getInstrumentation().context.assets
                .open("quickcut_fixture.mp4").use { source ->
                    file.outputStream().use { destination -> source.copyTo(destination) }
                }
        }

    /** Retain atom sizes and moov metadata while replacing every compressed sample. */
    private fun eraseMediaSamples(file: File) {
        var erased = false
        RandomAccessFile(file, "rw").use { mp4 ->
            var position = 0L
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
        }
        check(erased) { "Fixture has no media-sample atom" }
    }

    private fun copyToDescriptor(file: File, descriptor: FileDescriptor) {
        ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(descriptor)).use { sink ->
            file.inputStream().use { source -> source.copyTo(sink) }
        }
    }

    private fun savedRows(context: Context, prefix: String): Int =
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID),
            MediaStore.Video.Media.DISPLAY_NAME + " LIKE ?",
            arrayOf(prefix + "_QuickCut_%"),
            null,
        )?.use { it.count } ?: 0

    private fun deleteRows(context: Context, prefix: String) {
        context.contentResolver.delete(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.DISPLAY_NAME + " LIKE ?",
            arrayOf(prefix + "_QuickCut_%"),
        )
    }
}
