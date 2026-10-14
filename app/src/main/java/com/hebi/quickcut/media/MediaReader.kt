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

    fun validateExport(
        file: File,
        expectedDurationMs: Long? = null,
        expectAudio: Boolean? = null,
        expectedTimestampMs: Long? = null,
    ): VideoMetadata {
        if (!file.isFile || file.length() == 0L) throw IOException("저장된 영상이 비어 있습니다.")
        return validateExport(expectedDurationMs, expectAudio, expectedTimestampMs) {
            it.setDataSource(file.absolutePath)
        }
    }

    /** Also used to verify a journaled output after process recreation. Call off Main. */
    fun validateExport(
        context: Context,
        uri: Uri,
        expectedDurationMs: Long? = null,
        expectAudio: Boolean? = null,
        expectedTimestampMs: Long? = null,
    ): VideoMetadata = validateExport(expectedDurationMs, expectAudio, expectedTimestampMs) {
        it.setDataSource(context, uri)
    }

    private fun validateExport(
        expectedDurationMs: Long?,
        expectAudio: Boolean?,
        expectedTimestampMs: Long?,
        setSource: (MediaMetadataRetriever) -> Unit,
    ): VideoMetadata {
        val retriever = MediaMetadataRetriever()
        try {
            setSource(retriever)
            val video = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val audio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            ExportValidation.requireMetadata(video, duration, audio, expectedDurationMs, expectAudio)
            if (expectedTimestampMs != null) {
                val embeddedDate = OriginalMediaDates.parseMp4Date(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                )
                ExportValidation.requireTimestamp(expectedTimestampMs, embeddedDate)
            }
            // OPTION_CLOSEST decodes the requested region, including non-keyframes near
            // the end. Checking metadata or only the first sync frame misses damaged tails.
            for (timeMs in longArrayOf(0L, (duration - 1L).coerceAtLeast(0L))) {
                val frame = retriever.getScaledFrameAtTime(
                    timeMs.coerceAtMost(Long.MAX_VALUE / 1_000L) * 1_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    160,
                    90,
                ) ?: throw IOException("저장된 영상의 시작 또는 끝 프레임을 읽을 수 없습니다.")
                frame.recycle()
            }
            return VideoMetadata(duration, audio)
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("저장된 영상을 확인할 수 없습니다.", error)
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

