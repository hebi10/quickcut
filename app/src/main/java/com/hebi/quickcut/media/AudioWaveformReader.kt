package com.hebi.quickcut.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

data class AudioWaveform(val peaks: List<Float>, val sampledDurationMs: Long)

/**
 * Read-only, bounded audio decode. Peaks are actual PCM amplitudes, never encoded-byte guesses.
 * For long clips only the first 15 seconds are sampled; this is labeled in the UI.
 */
object AudioWaveformReader {
    suspend fun read(
        context: Context,
        uri: Uri,
        clipStartMs: Long,
        clipDurationMs: Long,
        bars: Int = 42,
    ): AudioWaveform? = withContext(Dispatchers.IO) {
        if (clipDurationMs <= 0L || bars !in 8..100) return@withContext null
        runCatching { decode(context, uri, clipStartMs, clipDurationMs, bars) }.getOrNull()
    }

    private fun decode(
        context: Context, uri: Uri, clipStartMs: Long, clipDurationMs: Long, bars: Int,
    ): AudioWaveform? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val audio = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(audio)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(audio)
            extractor.seekTo(clipStartMs * 1_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val sampledMs = minOf(clipDurationMs, 15_000L)
            val windowStartUs = clipStartMs * 1_000L
            val windowEndUs = windowStartUs + sampledMs * 1_000L
            val peaks = FloatArray(bars)
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var decoded = 0
            var sampledFrames = 0
            val deadline = SystemClock.elapsedRealtime() + 2_800L
            while (!outputEnded && SystemClock.elapsedRealtime() < deadline && decoded < 300) {
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inputIndex) ?: break
                        val timeUs = extractor.sampleTime
                        val bytes = if (timeUs in 0L until windowEndUs) {
                            extractor.readSampleData(buffer, 0)
                        } else -1
                        if (bytes < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, bytes, timeUs, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val index = decoder.dequeueOutputBuffer(info, 10_000L)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    else -> if (index >= 0) {
                        decoded++
                        try {
                            if (info.size > 0 && info.presentationTimeUs in windowStartUs until windowEndUs) {
                                val sampleIndex = ((info.presentationTimeUs - windowStartUs)
                                    * bars / (sampledMs * 1_000L)).toInt()
                                    .coerceIn(0, bars - 1)
                                val pcm = decoder.getOutputBuffer(index)
                                if (pcm != null) {
                                    pcm.position(info.offset)
                                    pcm.limit(info.offset + info.size)
                                    pcm.order(ByteOrder.nativeOrder())
                                    val outputFormat = decoder.outputFormat
                                    val encoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                        outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                    else AudioFormat.ENCODING_PCM_16BIT
                                    var peak = 0f
                                    if (encoding == AudioFormat.ENCODING_PCM_16BIT) {
                                        sampledFrames++
                                        while (pcm.remaining() >= 2) {
                                            peak = max(peak, abs(pcm.short.toInt()) / 32768f)
                                        }
                                    } else if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                        sampledFrames++
                                        while (pcm.remaining() >= 4) {
                                            peak = max(peak, abs(pcm.float).coerceAtMost(1f))
                                        }
                                    }
                                    peaks[sampleIndex] = max(peaks[sampleIndex], peak.coerceIn(0f, 1f))
                                }
                            }
                        } finally {
                            decoder.releaseOutputBuffer(index, false)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnded = true
                    }
                }
            }
            if (sampledFrames == 0) return null
            AudioWaveform(peaks.toList(), sampledMs)
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            extractor.release()
        }
    }
}
