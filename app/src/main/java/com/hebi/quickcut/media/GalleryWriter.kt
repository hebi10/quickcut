package com.hebi.quickcut.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class GalleryWriter(private val context: Context) {
    suspend fun save(file: File): Uri = withContext(Dispatchers.IO) {
        require(file.isFile && file.length() > 0L) { "저장할 영상 파일이 없습니다." }
        val resolver = context.contentResolver
        val name = "QuickCut_" +
            SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".mp4"
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
