package com.hebi.quickcut.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import java.io.File
import java.io.IOException

data class VideoMetadata(
    val durationMs: Long,
    val hasAudio: Boolean,
)

object MediaReader {
    private val thumbnailCache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun metadata(context: Context, uri: Uri): VideoMetadata {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            require(hasVideo && duration >= 100L) { "편집할 수 없는 영상입니다." }
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            return VideoMetadata(duration, hasAudio)
        } finally {
            retriever.release()
        }
    }

    fun validateExport(file: File) {
        if (!file.isFile || file.length() == 0L) throw IOException("저장된 영상이 비어 있습니다.")
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val video = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (!video || duration <= 0L) throw IOException("저장된 영상을 재생할 수 없습니다.")
        } finally {
            retriever.release()
        }
    }

    fun thumbnail(context: Context, uri: Uri, timeMs: Long): Bitmap? {
        val snapped = timeMs.coerceAtLeast(0L) / 2_000L * 2_000L
        val key = uri.toString() + ":" + snapped
        thumbnailCache.get(key)?.let { return it }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.getScaledFrameAtTime(
                snapped * 1_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                320,
                180,
            )?.also { frame -> thumbnailCache.put(key, frame) }
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
}
