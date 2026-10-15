package com.hebi.quickcut.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.system.Os
import android.provider.MediaStore
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class GalleryWriter(private val context: Context) {
    suspend fun save(
        file: File,
        originalTimestampMs: Long? = null,
        outputBaseName: String? = null,
        expectedDurationMs: Long? = null,
        expectAudio: Boolean? = null,
        onFinalizing: () -> Unit = {},
        onOutputCreated: (Uri) -> Unit = {},
        onCommitted: (Uri) -> Unit = {},
    ): Uri {
        withContext(Dispatchers.IO) {
            require(file.isFile && file.length() > 0L) { "저장할 영상 파일이 없습니다." }
            MediaReader.validateExport(file, expectedDurationMs, expectAudio, originalTimestampMs)
            // The cache file already occupies its space: only the additional copy
            // is needed here. The export preflight accounted for both allocations.
            ExportStorage.requireSpace(context, file.length(), directWrite = true)
        }
        return savePending(
            outputBaseName = outputBaseName,
            originalTimestampMs = originalTimestampMs,
            expectedDurationMs = expectedDurationMs,
            expectAudio = expectAudio,
            fastValidation = false,
            writer = { uri ->
                val stream = context.contentResolver.openOutputStream(uri)
                    ?: throw IOException("갤러리 파일을 열 수 없습니다.")
                stream.use { sink ->
                    file.inputStream().use { source ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            sink.write(buffer, 0, count)
                        }
                    }
                }
            },
            onFinalizing = onFinalizing,
            onOutputCreated = onOutputCreated,
            onCommitted = onCommitted,
        )
    }

    /**
     * Native remuxing writes straight to a pending MediaStore file. No extra
     * full-size cache file or second copy is needed on this path.
     */
    suspend fun saveDirect(
        outputBaseName: String?,
        expectedDurationMs: Long,
        expectAudio: Boolean,
        writer: suspend (FileDescriptor) -> Unit,
        onFinalizing: () -> Unit,
        estimatedOutputBytes: Long? = null,
        onOutputCreated: (Uri) -> Unit = {},
        onCommitted: (Uri) -> Unit = {},
    ): Uri {
        withContext(Dispatchers.IO) {
            val estimate = estimatedOutputBytes ?: ExportStoragePolicy.estimateOutputBytes(
                listOf(ExportSourceSize(null, expectedDurationMs, expectedDurationMs)),
                directWrite = true,
            )
            ExportStorage.requireSpace(context, estimate, directWrite = true)
        }
        return savePending(
            outputBaseName = outputBaseName,
            originalTimestampMs = null,
            expectedDurationMs = expectedDurationMs,
            expectAudio = expectAudio,
            fastValidation = true,
            writer = { uri ->
                val descriptor = context.contentResolver.openFileDescriptor(uri, "rw")
                    ?: throw IOException("갤러리 파일을 열 수 없습니다.")
                descriptor.use { writer(it.fileDescriptor) }
            },
            onFinalizing = onFinalizing,
            onOutputCreated = onOutputCreated,
            onCommitted = onCommitted,
        )
    }

    private suspend fun savePending(
        outputBaseName: String?,
        originalTimestampMs: Long?,
        expectedDurationMs: Long?,
        expectAudio: Boolean?,
        fastValidation: Boolean,
        writer: suspend (Uri) -> Unit,
        onFinalizing: () -> Unit,
        onOutputCreated: (Uri) -> Unit,
        onCommitted: (Uri) -> Unit,
    ): Uri {
        val resolver = context.contentResolver
        var pendingUri: Uri? = null
        var committed = false
        try {
            val uri = withContext(Dispatchers.IO) {
                // Assign before a dispatcher switch so cancellation cannot lose the
                // cleanup handle. The journal must succeed while this URI is pending.
                val created = createPendingUri(outputBaseName)
                pendingUri = created
                runCallback { onOutputCreated(created) }
                currentCoroutineContext().ensureActive()
                writer(created)
                currentCoroutineContext().ensureActive()
                if (originalTimestampMs != null) {
                    // DATE_TAKEN is indexed from the embedded timestamp. Preserve
                    // mtime as well where the provider permits it.
                    runCatching {
                        resolver.openFileDescriptor(created, "rw")?.use { descriptor ->
                            val localPath = Os.readlink("/proc/self/fd/" + descriptor.fd)
                            File(localPath).setLastModified(originalTimestampMs)
                        }
                    }
                }
                try {
                    MediaReader.validateExport(context, created, expectedDurationMs, expectAudio, originalTimestampMs)
                } catch (error: IOException) {
                    if (fastValidation) throw FastTrimUnavailableException("Fast output validation failed", error)
                    throw error
                }
                created
            }

            // Keep the full copy and decode checks cancellable. This signal only
            // disables cancellation for the short publication/completion phase.
            runCallback(onFinalizing)
            currentCoroutineContext().ensureActive()
            // Do not combine NonCancellable with a dispatcher here: returning from
            // IO into a cancelled caller can discard the successfully committed URI.
            return withContext(NonCancellable) {
                withContext(Dispatchers.IO) {
                    val changes = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    if (resolver.update(uri, changes, null, null) < 1) {
                        throw IOException("저장된 파일을 갤러리에 등록할 수 없습니다.")
                    }
                }
                runCallback { onCommitted(uri) }
                committed = true
                uri
            }
        } catch (error: Throwable) {
            if (!committed) {
                val uri = pendingUri
                if (uri != null) {
                    val cleanupFailure = withContext(NonCancellable) {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                if (resolver.delete(uri, null, null) <= 0) {
                                    // Zero may mean another cleanup already removed it.
                                    // A null/failed query is not evidence of absence.
                                    val absent = resolver.query(uri,
                                        arrayOf(MediaStore.Video.Media._ID), null, null, null)
                                        ?.use { !it.moveToFirst() } == true
                                    if (!absent) throw IOException("미완성 출력 삭제를 확인할 수 없습니다.")
                                }
                            }.exceptionOrNull()
                        }
                    }
                    if (cleanupFailure != null) {
                        val failure = OutputCleanupException(uri, error).apply {
                            addSuppressed(cleanupFailure)
                        }
                        // Cancellation remains cancellation, but its cleanup handle is
                        // available to callers. Never expose a fast-path error here:
                        // retrying would overwrite the journal's only pending URI.
                        if (error is CancellationException) error.addSuppressed(failure)
                        else throw failure
                    }
                }
            }
            throw error
        }
    }

    private suspend fun runCallback(callback: () -> Unit) {
        withContext(Dispatchers.Main.immediate) {
            try {
                callback()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Native remux fallback must never retry a failed persistence/UI callback.
                throw IOException("저장 상태를 기록하지 못했습니다. 저장을 취소했습니다.", error)
            }
        }
    }

    private fun createPendingUri(outputBaseName: String?): Uri {
        val dateString = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val basename = outputBaseName
            ?.substringBeforeLast('.')
            ?.replace(Regex("""[^\p{L}\p{N}._-]+"""), "_")
            ?.take(56)
            ?.trim('_', '.', ' ')
            ?.takeIf { it.isNotBlank() }
        val name = (basename?.let { it + "_QuickCut_" } ?: "QuickCut_") +
            dateString + "_" + UUID.randomUUID().toString().take(6) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/QuickCut")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("갤러리에 저장할 수 없습니다.")
    }
}



/** An unpublished output still needs cleanup before another export can begin. */
class OutputCleanupException(
    val outputUri: Uri,
    cause: Throwable,
) : IOException("미완성 파일을 정리하지 못했습니다. 파일 접근을 확인하고 다시 시도해 주세요.", cause)
