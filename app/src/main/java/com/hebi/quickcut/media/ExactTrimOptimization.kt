package com.hebi.quickcut.media

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackAwareSeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import com.hebi.quickcut.editor.VideoClip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Media3 1.11.1 may move a trim start to the next sync sample when the initial
 * segment has no video sample or is shorter than one source audio packet.
 * Inspect the same MP4 sample tables before enabling that optimization. Unknown
 * metadata and reordered samples use normal clipping with the original times.
 */
@OptIn(UnstableApi::class)
internal object ExactTrimOptimization {
    suspend fun canOptimize(context: Context, clip: VideoClip): Boolean {
        val extractor = Mp4Extractor(
            SubtitleParser.Factory.UNSUPPORTED,
            Mp4Extractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
        )
        val source = DefaultDataSource(context, false)
        val output = MetadataOutput()
        try {
            currentCoroutineContext().ensureActive()
            val length = source.open(DataSpec.Builder().setUri(clip.uri).build())
            if (length == 0L) return false
            var input = DefaultExtractorInput(source, 0L, length)
            if (!extractor.sniff(input)) return false
            extractor.init(output)
            val seekPosition = PositionHolder()
            while (!output.seekMapReceived) {
                currentCoroutineContext().ensureActive()
                when (extractor.read(input, seekPosition)) {
                    Extractor.RESULT_SEEK -> {
                        source.close()
                        val remaining = source.open(
                            DataSpec.Builder().setUri(clip.uri).setPosition(seekPosition.position).build()
                        )
                        val total = if (remaining == C.LENGTH_UNSET.toLong()) remaining
                            else remaining + seekPosition.position
                        input = DefaultExtractorInput(source, seekPosition.position, total)
                    }
                    Extractor.RESULT_END_OF_INPUT -> if (!output.seekMapReceived) return false
                }
            }
            currentCoroutineContext().ensureActive()
            if (output.videoIds.size != 1 || output.audioIds.size > 1) return false
            val videoId = output.videoIds.single()
            if (output.formats[videoId] == null) return false
            val audioRate = if (output.audioIds.isEmpty()) {
                null
            } else {
                output.formats[output.audioIds.single()]?.sampleRate ?: return false
            }
            val seekMap = output.seekMap ?: return false
            val requestedUs = clip.startMs * 1_000L
            val seekPoints = seekMap.getSeekPoints(requestedUs, videoId)
            val nextSyncUs = when {
                seekPoints.first.timeUs == requestedUs -> seekPoints.first.timeUs
                seekPoints.second.timeUs >= requestedUs -> seekPoints.second.timeUs
                else -> return false
            }
            return isSafeToOptimize(
                requestedUs,
                clip.endMs * 1_000L,
                nextSyncUs,
                extractor.getSampleTimestampsUs(videoId),
                audioRate,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.d("QuickCutExport", "Exact trim optimization unavailable: " + error.javaClass.simpleName)
            return false
        } finally {
            DataSourceUtil.closeQuietly(source)
            extractor.release()
        }
    }

    /** sourceAudioSampleRate describes the source even when the output is muted. */
    fun isSafeToOptimize(
        requestedStartUs: Long,
        requestedEndUs: Long,
        nextSyncUs: Long,
        videoSampleTimesUs: LongArray,
        sourceAudioSampleRate: Int?,
    ): Boolean {
        if (requestedStartUs < 0L || requestedEndUs <= requestedStartUs ||
            nextSyncUs < requestedStartUs || nextSyncUs >= requestedEndUs ||
            videoSampleTimesUs.isEmpty()
        ) return false
        if (sourceAudioSampleRate != null && sourceAudioSampleRate <= 0) return false
        var previousUs = -1L
        var hasRetainedSampleBeforeSync = false
        var containsSyncTimestamp = false
        for (timeUs in videoSampleTimesUs) {
            // Decline reordering instead of applying binary search to unsorted PTS.
            if (timeUs < 0L || timeUs <= previousUs) return false
            if (timeUs >= requestedStartUs && timeUs < nextSyncUs) hasRetainedSampleBeforeSync = true
            if (timeUs == nextSyncUs) containsSyncTimestamp = true
            previousUs = timeUs
        }
        if (!containsSyncTimestamp) return false
        if (nextSyncUs == requestedStartUs) return true // Media3 cannot shift this start.
        val audioPacketUs = sourceAudioSampleRate?.let { 1_024_000_000L / it } ?: 0L
        return hasRetainedSampleBeforeSync && nextSyncUs - requestedStartUs > audioPacketUs
    }

    private class MetadataOutput : ExtractorOutput {
        val videoIds = linkedSetOf<Int>()
        val audioIds = linkedSetOf<Int>()
        val formats = mutableMapOf<Int, Format>()
        var seekMapReceived = false
        var seekMap: TrackAwareSeekMap? = null

        override fun track(id: Int, type: Int): TrackOutput {
            when (type) {
                C.TRACK_TYPE_VIDEO -> videoIds.add(id)
                C.TRACK_TYPE_AUDIO -> audioIds.add(id)
            }
            return object : TrackOutput by DiscardingTrackOutput() {
                override fun format(format: Format) {
                    formats[id] = format
                }
            }
        }

        override fun endTracks() = Unit

        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap as? TrackAwareSeekMap
            seekMapReceived = true
        }
    }
}
