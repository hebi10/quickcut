package com.hebi.quickcut.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
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
 * first retained video sample is exactly the requested sync sample. Never retain hidden pre-roll.
 */
internal object FastMp4Trimmer {
    private const val TAG = "QuickCutExport"
    private const val MAX_SAMPLE_BYTES = 64 * 1024 * 1024

    data class Plan(
        val source: Uri,
        val videoTrack: Int,
        val audioTrack: Int?,
        val startUs: Long,
        val endUs: Long,
        val rotationDegrees: Int,
        val copyToEndOfSource: Boolean,
    )

    enum class UnavailableReason(val explanation: String) {
        NO_VIDEO("영상 트랙을 찾을 수 없어 고속 저장을 사용할 수 없습니다."),
        TRACK_LAYOUT("영상·음성 트랙이 여러 개이거나 추가 트랙이 있어 현재 고속 저장에서 지원하지 않습니다."),
        VIDEO_CODEC("현재 고속 저장은 H.264 또는 H.265 영상만 지원합니다."),
        COLOR_FORMAT("원본 복사 경로에서 색상·밝기 정보를 보존할 수 없습니다."),
        ROTATION("영상 회전 정보가 고속 저장의 지원 범위와 다릅니다."),
        AUDIO_CODEC("현재 고속 복사는 AAC 소리를 지원합니다. 다른 소리 형식은 정확한 위치로 자동 저장을 선택해 주세요."),
        AUDIO_TIMESTAMPS("이 Android 버전의 원본 복사는 선택한 소리 시작 시간을 보존할 수 없습니다. 영상 복사가 가능한 다른 고속 경로를 확인합니다."),
        KEYFRAME("자르기 시작 위치가 키프레임(재생 기준 프레임)과 맞지 않습니다."),
        UNSAFE_START("시작 지점 앞의 프레임이 재생 순서에 섞여 있어 해당 위치에서 고속 저장을 사용할 수 없습니다."),
        REORDERED_END("B프레임처럼 재생 순서가 다른 영상의 끝부분을 자르려면 정확한 위치로 자동 저장을 선택해 주세요. 끝부분을 유지하면 고속 복사가 가능할 수 있습니다."),
        SOURCE_UNREADABLE("영상 정보를 읽지 못해 고속 저장 가능 여부를 확인할 수 없습니다."),
    }

    data class Inspection(val plan: Plan?, val reason: UnavailableReason?)

    fun isAcceptableStart(requestedUs: Long, syncUs: Long, endUs: Long): Boolean =
        requestedUs >= 0L && syncUs == requestedUs && endUs - syncUs >= 100_000L

    // Android 10 MPEG4Writer adds a late track's offset to its first sample
    // duration (writeSttsBox), leaving that sample at zero. Android 11 uses an
    // empty edit to preserve the offset. Never compensate by moving input PTS.
    internal fun canMuxAudioStart(sdkInt: Int, startUs: Long, firstAudioUs: Long?): Boolean =
        startUs >= 0L && firstAudioUs != null && firstAudioUs >= startUs &&
            (sdkInt >= 30 || firstAudioUs == startUs)

    fun prepare(context: Context, clip: VideoClip): Plan? = inspect(context, clip).plan

    fun inspect(context: Context, clip: VideoClip): Inspection {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.parse(clip.uri), null)
            var videoTrack = -1
            var audioTrack = -1
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null
            for (track in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME)
                    ?: return unavailable(UnavailableReason.TRACK_LAYOUT)
                when {
                    mime.startsWith("video/") && videoTrack < 0 -> {
                        videoTrack = track
                        videoFormat = format
                    }
                    mime.startsWith("audio/") && audioTrack < 0 -> {
                        audioTrack = track
                        audioFormat = format
                    }
                    else -> return unavailable(UnavailableReason.TRACK_LAYOUT)
                }
            }
            val video = videoFormat ?: return unavailable(UnavailableReason.NO_VIDEO)
            if (video.getString(MediaFormat.KEY_MIME) !in
                setOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)
            ) return unavailable(UnavailableReason.VIDEO_CODEC)
            if (video.containsKey(MediaFormat.KEY_COLOR_TRANSFER) &&
                video.getInteger(MediaFormat.KEY_COLOR_TRANSFER) !=
                    MediaFormat.COLOR_TRANSFER_SDR_VIDEO
            ) return unavailable(UnavailableReason.COLOR_FORMAT)
            val rotation = if (video.containsKey(MediaFormat.KEY_ROTATION)) {
                video.getInteger(MediaFormat.KEY_ROTATION)
            } else 0
            if (rotation !in setOf(0, 90, 180, 270)) return unavailable(UnavailableReason.ROTATION)

            val keepAudio = !clip.muted && clip.hasAudio
            if (keepAudio &&
                (audioFormat == null ||
                    audioFormat.getString(MediaFormat.KEY_MIME) !=
                        MediaFormat.MIMETYPE_AUDIO_AAC)
            ) return unavailable(UnavailableReason.AUDIO_CODEC)

            val requestedUs = clip.startUs
            val endUs = clip.endUs
            extractor.selectTrack(videoTrack)
            extractor.seekTo(requestedUs, MediaExtractor.SEEK_TO_NEXT_SYNC)
            val syncUs = extractor.sampleTime
            if (extractor.sampleTrackIndex != videoTrack ||
                (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) == 0 ||
                !isAcceptableStart(requestedUs, syncUs, endUs)
            ) return unavailable(UnavailableReason.KEYFRAME)

            val copyToEndOfSource = clip.endUs == clip.sourceDurationMs * 1_000L
            var previousTimeUs = syncUs
            repeat(12) {
                if (extractor.advance()) {
                    val timeUs = extractor.sampleTime
                    if (timeUs < syncUs) return unavailable(UnavailableReason.UNSAFE_START)
                    if (!copyToEndOfSource && timeUs < previousTimeUs) {
                        return unavailable(UnavailableReason.REORDERED_END)
                    }
                    previousTimeUs = timeUs
                }
            }

            if (!copyToEndOfSource && !hasSafeEnd(extractor, video, endUs)) {
                return unavailable(UnavailableReason.REORDERED_END)
            }

            if (Build.VERSION.SDK_INT == 29 && keepAudio) {
                extractor.unselectTrack(videoTrack)
                extractor.selectTrack(audioTrack)
                extractor.seekTo(syncUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                // AAC priming may be negative; only a retained sample can establish
                // the output start. Exhaustion is identified by track index, not PTS.
                while (extractor.sampleTrackIndex >= 0 && extractor.sampleTime < syncUs) {
                    if (!extractor.advance()) break
                }
                val firstAudioUs = extractor.sampleTime.takeIf {
                    extractor.sampleTrackIndex == audioTrack &&
                        (copyToEndOfSource || it < endUs)
                }
                if (!canMuxAudioStart(Build.VERSION.SDK_INT, syncUs, firstAudioUs)) {
                    return unavailable(UnavailableReason.AUDIO_TIMESTAMPS)
                }
            }

            return Inspection(Plan(
                source = Uri.parse(clip.uri),
                videoTrack = videoTrack,
                audioTrack = audioTrack.takeIf { keepAudio && it >= 0 },
                startUs = syncUs,
                endUs = endUs,
                rotationDegrees = rotation,
                copyToEndOfSource = copyToEndOfSource,
            ), null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.d(TAG, "Fast MP4 eligibility check skipped: " + error.javaClass.simpleName)
            return unavailable(UnavailableReason.SOURCE_UNREADABLE)
        } finally {
            extractor.release()
        }
    }

    private fun unavailable(reason: UnavailableReason) = Inspection(null, reason)

    /**
     * Inspect the indexed GOP containing the end, rather than inferring its
     * ordering from the beginning of the clip. Stop only at EOS or a closed IDR
     * at/after the requested end. A sync flag may represent an open GOP / CRA.
     * No fence within the resource budget means use the validated native path.
     */
    private fun hasSafeEnd(extractor: MediaExtractor, format: MediaFormat, endUs: Long): Boolean {
        val budget = FastTrimBoundary.Budget(SystemClock.elapsedRealtime())
        extractor.seekTo(endUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        var previousUs = Long.MIN_VALUE
        while (extractor.sampleTrackIndex >= 0) {
            if (Thread.currentThread().isInterrupted) throw CancellationException("End inspection cancelled")
            val timeUs = extractor.sampleTime
            if (timeUs < previousUs || !budget.include(extractor.sampleSize, SystemClock.elapsedRealtime())) {
                return false
            }
            previousUs = timeUs
            if (timeUs >= endUs && isClosedSyncSample(extractor, format)) {
                return budget.withinTime(SystemClock.elapsedRealtime())
            }
            if (!extractor.advance()) break
        }
        return budget.withinTime(SystemClock.elapsedRealtime())
    }

    private fun isClosedSyncSample(extractor: MediaExtractor, format: MediaFormat): Boolean {
        val flags = extractor.sampleFlags
        if (flags and MediaExtractor.SAMPLE_FLAG_SYNC == 0 ||
            flags and (MediaExtractor.SAMPLE_FLAG_ENCRYPTED or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0
        ) return false
        val size = extractor.sampleSize
        if (size <= 0L || size > MAX_SAMPLE_BYTES) return false
        val packet = ByteBuffer.allocate(size.toInt())
        if (extractor.readSampleData(packet, 0).toLong() != size) return false
        packet.position(0)
        packet.limit(size.toInt())
        return FastTrimBoundary.isClosedRandomAccess(packet, format.getString(MediaFormat.KEY_MIME) ?: "")
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

            var buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var videoSamples = 0
            var audioSamples = 0
            var sampleNumber = 0
            var lastProgressAt = 0L
            var lastProgress = -1
            var lastVideoTimeUs = -1L
            var lastAudioTimeUs = -1L
            var endProbeBudget: FastTrimBoundary.Budget? = null
            val videoFormat = extractor.getTrackFormat(plan.videoTrack)
            val spanUs = plan.endUs - plan.startUs

            while (true) {
                if (sampleNumber++ % 64 == 0) currentCoroutineContext().ensureActive()
                val index = extractor.sampleTrackIndex
                if (index < 0) break
                val timeUs = extractor.sampleTime
                if (index == plan.videoTrack) {
                    if (videoSamples > 0 && timeUs < plan.startUs) {
                        throw FastTrimUnavailableException("Video contains frames before the retained sync sample")
                    }
                    // Keep decode order and original PTS for B-frames. Mid-stream
                    // cuts cannot safely discard their forward reference frames.
                    if (!plan.copyToEndOfSource && timeUs < lastVideoTimeUs) {
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
                if (!plan.copyToEndOfSource && timeUs >= plan.endUs) {
                    if (index == plan.videoTrack) {
                        // The first excluded PTS is not a decode-order boundary:
                        // it can be followed by a retained B-frame. Prove the
                        // boundary using this exact cursor, never a separate seek
                        // which could skip the omitted picture. Reversal above
                        // declines before any such frame can be silently lost.
                        val budget = endProbeBudget ?: FastTrimBoundary.Budget(
                            SystemClock.elapsedRealtime()).also { endProbeBudget = it }
                        if (!budget.include(extractor.sampleSize, SystemClock.elapsedRealtime())) {
                            throw FastTrimUnavailableException("Could not prove video end within lookahead budget")
                        }
                        val closedFence = isClosedSyncSample(extractor, videoFormat)
                        currentCoroutineContext().ensureActive()
                        if (!budget.withinTime(SystemClock.elapsedRealtime())) {
                            throw FastTrimUnavailableException("Video end proof exceeded lookahead time budget")
                        }
                        if (!closedFence) {
                            extractor.advance()
                            continue
                        }
                        endProbeBudget = null
                    }
                    // A shorter track may already be at EOS, so waiting for every
                    // track to cross endUs would scan the other track's entire tail.
                    // Unselect changes the current sample to a remaining track;
                    // do not advance here or that track's next sample is skipped.
                    extractor.unselectTrack(index)
                    continue
                }

                val isVideo = index == plan.videoTrack
                if (!isVideo && audioSamples == 0 &&
                    !canMuxAudioStart(Build.VERSION.SDK_INT, plan.startUs, timeUs)
                ) {
                    throw FastTrimUnavailableException("Framework muxer cannot preserve the first audio timestamp")
                }
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
            if (endProbeBudget?.withinTime(SystemClock.elapsedRealtime()) == false) {
                throw FastTrimUnavailableException("Video end proof exceeded lookahead time budget")
            }
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


