package com.hebi.quickcut.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns

data class SavedVideoTechnicalInfo(
    val width: Int,
    val height: Int,
    val frameRate: Float?,
    val fileBytes: Long?,
)

object SavedVideoTechnicalReader {
    fun read(context: Context, uri: Uri): SavedVideoTechnicalInfo {
        val retriever = MediaMetadataRetriever()
        val (width, height) = try {
            retriever.setDataSource(context, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: error("출력 영상 가로 크기 확인 실패")
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: error("출력 영상 세로 크기 확인 실패")
            width to height
        } finally {
            retriever.release()
        }
        val frameRate = runCatching { estimateFrameRate(context, uri) }.getOrNull()
        val size = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst() && index >= 0 && !cursor.isNull(index))
                        cursor.getLong(index).takeIf { it > 0L }
                    else null
                }
        }.getOrNull()
        return SavedVideoTechnicalInfo(width, height, frameRate, size)
    }

    private fun estimateFrameRate(context: Context, uri: Uri): Float? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: return null
            extractor.selectTrack(track)
            var firstUs = -1L
            var lastUs = -1L
            var count = 0
            while (count < 160) {
                val time = extractor.sampleTime
                if (time < 0L) break
                if (firstUs < 0L) firstUs = time
                lastUs = time
                count++
                if (!extractor.advance()) break
            }
            if (count < 4 || lastUs <= firstUs) return null
            return ((count - 1) * 1_000_000.0 / (lastUs - firstUs)).toFloat()
        } finally {
            extractor.release()
        }
    }
}
