package com.hebi.quickcut.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri

data class VideoMetadata(
    val durationMs: Long,
    val hasAudio: Boolean,
)

object MediaReader {
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

    fun thumbnail(context: Context, uri: Uri, timeMs: Long): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.getScaledFrameAtTime(
                timeMs.coerceAtLeast(0L) * 1_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                320,
                180,
            )
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
}
