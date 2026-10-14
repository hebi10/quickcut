package com.hebi.quickcut.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.hebi.quickcut.editor.VideoClip
import java.io.FileDescriptor
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Copies compressed MP4 samples without decoding or re-encoding. Only use when the
 * first retained video sample is a nearby sync sample. Never retain hidden pre-roll.
 */
internal object FastMp4Trimmer {
    private const val TAG = "QuickCutExport"
    private const val MAX_START_SHIFT_US = 50_000L
    private const val MAX_SAMPLE_BYTES = 64 * 1024 * 1024

    data class Plan(
        val source: Uri,
        val videoTrack: Int,
        val audioTrack: Int?,
        val startUs: Long,
        val endUs: Long,
        val rotationDegrees: Int,
    )

    fun isAcceptableStart(requestedUs: Long, syncUs: Long, endUs: Long): Boolean =
        requestedUs >= 0L && syncUs >= requestedUs &&
            syncUs - requestedUs <= MAX_START_SHIFT_US && endUs - syncUs >= 100_000L

    fun prepare(context: Context, clip: VideoClip): Plan? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.parse(clip.uri), null)
            var videoTrack = -1
            var audioTrack = -1
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null
            for (track in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
                when {
                    mime.startsWith("video/") && videoTrack < 0 -> {
                        videoTrack = track
                        videoFormat = format
                    }
                    mime.startsWith("audio/") && audioTrack < 0 -> {
                        audioTrack = track
                        audioFormat = format
                    }
                    else -> return null
                }
            }
            val video = videoFormat ?: return null
            if (video.getString(MediaFormat.KEY_MIME) !in
                setOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)
            ) return null
            if (video.containsKey(MediaFormat.KEY_COLOR_TRANSFER) &&
                video.getInteger(MediaFormat.KEY_COLOR_TRANSFER) !=
                    MediaFormat.COLOR_TRANSFER_SDR_VIDEO
            ) return null
            val rotation = if (video.containsKey(MediaFormat.KEY_ROTATION)) {
                video.getInteger(MediaFormat.KEY_ROTATION)
            } else 0
            if (rotation !in setOf(0, 90, 180, 270)) return null

            val keepAudio = !clip.muted && clip.hasAudio
            if (keepAudio &&
                (audioFormat == null ||
                    audioFormat.getString(MediaFormat.KEY_MIME) !=
                        MediaFormat.MIMETYPE_AUDIO_AAC)
            ) return null

            val requestedUs = clip.startMs * 1_000L
            val endUs = clip.endMs * 1_000L
            extractor.selectTrack(videoTrack)
            extractor.seekTo(requestedUs, MediaExtractor.SEEK_TO_NEXT_SYNC)
            val syncUs = extractor.sampleTime
            if (extractor.sampleTrackIndex != videoTrack ||
                (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) == 0 ||
                !isAcceptableStart(requestedUs, syncUs, endUs)
            ) return null

            // Reject streams with presentation-order reversals before writing a large file.
            var previousTimeUs = syncUs
            repeat(12) {
                if (extractor.advance()) {
                    val timeUs = extractor.sampleTime
                    if (timeUs < previousTimeUs) return null
                    previousTimeUs = timeUs
                }
            }

            return Plan(
                source = Uri.parse(clip.uri),
                videoTrack = videoTrack,
                audioTrack = audioTrack.takeIf { keepAudio && it >= 0 },
                startUs = syncUs,
                endUs = endUs,
                rotationDegrees = rotation,
            )
        } catch (error: Exception) {
            Log.d(TAG, "Fast MP4 eligibility check skipped: " + error.javaClass.simpleName)
            return null
        } finally {
            extractor.release()
        }
    }

    suspend fun write(
        context: Context,
        plan: Plan,
        destination: FileDescriptor,
        onProgress: (Int) -> Unit,
    ) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        val main = Handler(Looper.getMainLooper())
        try {
            extractor.setDataSource(context, plan.source, null)
            extractor.selectTrack(plan.videoTrack)
            if (plan.audioTrack != null) extractor.selectTrack(plan.audioTrack)
            extractor.seekTo(plan.startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val writer = MediaMuxer(destination, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = writer
            val videoOutput = writer.addTrack(extractor.getTrackFormat(plan.videoTrack))
            val audioOutput = plan.audioTrack?.let {
                writer.addTrack(extractor.getTrackFormat(it))
            }
            writer.setOrientationHint(plan.rotationDegrees)
            writer.start()
            started = true

            val trackCount = if (audioOutput == null) 1 else 2
            val finished = HashSet<Int>(trackCount)
            var buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var videoSamples = 0
            var audioSamples = 0
            var sampleNumber = 0
            var lastProgressAt = 0L
            var lastProgress = -1
            var lastVideoTimeUs = -1L
            var lastAudioTimeUs = -1L
            val spanUs = plan.endUs - plan.startUs

            while (true) {
                if (sampleNumber++ % 64 == 0) currentCoroutineContext().ensureActive()
                val index = extractor.sampleTrackIndex
                if (index < 0) break
                val timeUs = extractor.sampleTime
                // Remuxing assumes timestamp order. A reordered stream needs Media3.
                if (index == plan.videoTrack) {
                    if (timeUs < lastVideoTimeUs) {
                        throw FastTrimUnavailableException("Reordered video timestamps")
                    }
                    lastVideoTimeUs = timeUs
                } else {
                    if (timeUs < lastAudioTimeUs) {
                        throw FastTrimUnavailableException("Reordered audio timestamps")
                    }
                    lastAudioTimeUs = timeUs
                }
                if (timeUs < plan.startUs) {
                    extractor.advance()
                    continue
                }
                if (timeUs >= plan.endUs) {
                    finished.add(index)
                    if (finished.size >= trackCount) break
                    extractor.advance()
                    continue
                }
                if (index in finished) {
                    extractor.advance()
                    continue
                }

                val isVideo = index == plan.videoTrack
                if (isVideo && videoSamples == 0 &&
                    (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) == 0
                ) throw FastTrimUnavailableException("First video sample is not a keyframe")
                if ((extractor.sampleFlags and
                    (MediaExtractor.SAMPLE_FLAG_ENCRYPTED or
                        MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME)) != 0
                ) {
                    throw FastTrimUnavailableException("Encrypted or partial samples are unsupported")
                }
                val sampleSize = extractor.sampleSize
                if (sampleSize <= 0L || sampleSize > MAX_SAMPLE_BYTES) {
                    throw FastTrimUnavailableException("Invalid compressed sample size")
                }
                if (sampleSize > buffer.capacity()) {
                    buffer = ByteBuffer.allocateDirect(sampleSize.toInt())
                }
                buffer.clear()
                val copied = extractor.readSampleData(buffer, 0)
                if (copied.toLong() != sampleSize) {
                    throw FastTrimUnavailableException("Compressed sample was not fully read")
                }
                val sampleFlags = if (isVideo &&
                    (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                ) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, copied, timeUs - plan.startUs, sampleFlags)
                buffer.position(0)
                buffer.limit(copied)
                writer.writeSampleData(if (isVideo) videoOutput else audioOutput
                    ?: throw FastTrimUnavailableException("Unexpected audio track"), buffer, info)
                if (isVideo) videoSamples++ else audioSamples++

                val elapsed = SystemClock.elapsedRealtime()
                val percent = (((timeUs - plan.startUs) * 100L) / spanUs)
                    .toInt().coerceIn(0, 99)
                if (percent > lastProgress && elapsed - lastProgressAt >= 250L) {
                    lastProgress = percent
                    lastProgressAt = elapsed
                    main.post { onProgress(percent) }
                }
                extractor.advance()
            }
            currentCoroutineContext().ensureActive()
            if (videoSamples == 0 || (plan.audioTrack != null && audioSamples == 0)) {
                throw FastTrimUnavailableException("Output is missing a required track")
            }
            writer.stop()
            started = false
            main.post { onProgress(100) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unsupported: IllegalStateException) {
            throw FastTrimUnavailableException("Native remux is unavailable", unsupported)
        } catch (unsupported: IllegalArgumentException) {
            throw FastTrimUnavailableException("Native remux rejected input", unsupported)
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            extractor.release()
        }
    }
}

internal class FastTrimUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

