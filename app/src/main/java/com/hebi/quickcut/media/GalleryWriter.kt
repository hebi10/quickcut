package com.hebi.quickcut.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.system.Os
import android.system.StructTimeval
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class GalleryWriter(private val context: Context) {
    suspend fun save(
        file: File,
        originalTimestampMs: Long? = null,
        outputBaseName: String? = null,
    ): Uri = withContext(Dispatchers.IO) {
        require(file.isFile && file.length() > 0L) { "저장할 영상 파일이 없습니다." }
        val resolver = context.contentResolver
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
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("갤러리에 저장할 수 없습니다.")
        try {
            val stream = resolver.openOutputStream(uri)
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
            currentCoroutineContext().ensureActive()
            // MediaStore DATE_MODIFIED and DATE_TAKEN are read-only derived columns.
            // Preserve the underlying file's mtime instead of trying to write them.
            if (originalTimestampMs != null) {
                val timestamp = StructTimeval.fromMillis(originalTimestampMs)
                val handle = resolver.openFileDescriptor(uri, "rw")
                    ?: throw IOException("원본 날짜를 저장 파일에 적용하지 못했습니다.")
                handle.use {
                    Os.futimes(it.fileDescriptor, timestamp, timestamp)
                }
            }
            val changes = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            if (resolver.update(uri, changes, null, null) < 1) {
                throw IOException("저장된 파일을 갤러리에 등록할 수 없습니다.")
            }
            uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}
