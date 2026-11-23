package com.hebi.quickcut.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.hebi.quickcut.editor.VideoClip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object KeyframeFinder {
    suspend fun snap(
        context: Context,
        clip: VideoClip,
        startBoundary: Boolean,
        forward: Boolean,
    ): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        read(context, clip) { extractor ->
            val boundary = if (startBoundary) clip.startUs else clip.endUs
            val frame = seek(extractor, boundary + if (forward) 1L else -1L, forward)
                ?: return@read null
            val start = if (startBoundary) frame else clip.startUs
            val end = if (startBoundary) clip.endUs else frame
            (start to end).takeIf { end - start >= 100_000L && end <= clip.sourceDurationMs * 1_000L }
        }
    }

    // Only nearby indexed boundaries are probed. The copy writer still
    // verifies the chosen range and can decline without encoding.
    fun copyCandidates(context: Context, clip: VideoClip, outward: Boolean): List<Pair<Long, Long>> =
        read(context, clip) { extractor ->
            val durationUs = clip.sourceDurationMs * 1_000L
            val startKey = seek(extractor, clip.startUs, forward = !outward)
            val endKey = seek(extractor, clip.endUs, forward = outward)
                ?.takeIf { it <= durationUs }
                ?: durationUs.takeIf { outward }
            val starts = listOfNotNull(clip.startUs, startKey).distinct()
            val ends = listOfNotNull(clip.endUs, endKey).distinct()
            starts.flatMap { start -> ends.map { end -> start to end } }
                .filter { (start, end) ->
                    (start != clip.startUs || end != clip.endUs) && end - start >= 100_000L
                }
                .sortedBy { (start, end) ->
                    kotlin.math.abs(start - clip.startUs) + kotlin.math.abs(end - clip.endUs)
                }
        }.orEmpty()

    private fun seek(extractor: MediaExtractor, targetUs: Long, forward: Boolean): Long? {
        if (targetUs < 0L) return null
        extractor.seekTo(targetUs, if (forward) MediaExtractor.SEEK_TO_NEXT_SYNC
            else MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val time = extractor.sampleTime
        return time.takeIf {
            it >= 0L && extractor.sampleTrackIndex >= 0 &&
                extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0 &&
                (if (forward) it >= targetUs else it <= targetUs)
        }
    }

    private fun <T> read(context: Context, clip: VideoClip, action: (MediaExtractor) -> T?): T? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, Uri.parse(clip.uri), null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return null
            extractor.selectTrack(track)
            action(extractor)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } finally {
            extractor.release()
        }
    }
}
