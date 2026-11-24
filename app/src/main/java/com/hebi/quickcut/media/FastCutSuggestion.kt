package com.hebi.quickcut.media

import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.ceilMilliseconds

/** A one-use review of specific clips; it is never a global trim preference. */
class FastCutSuggestion private constructor(
    val requested: EditTimeline,
    val adjusted: EditTimeline,
) {
    val retainsMore: Boolean get() = adjusted.totalDurationUs > requested.totalDurationUs
    val extraRemovedUs: Long get() = requested.totalDurationUs - adjusted.totalDurationUs
    val extraRemovedMs: Long get() = ceilMilliseconds(extraRemovedUs)
    fun applyTo(current: EditTimeline): EditTimeline? = adjusted.takeIf { current == requested }

    companion object {
        fun create(requested: EditTimeline, rangesMs: List<Pair<Long, Long>>): FastCutSuggestion? {
            if (rangesMs.any { (start, end) -> start < 0 || end < 0 ||
                    start > Long.MAX_VALUE / 1_000L || end > Long.MAX_VALUE / 1_000L }) return null
            return createUs(requested, rangesMs.map { (start, end) -> start * 1_000L to end * 1_000L })
        }

        fun createUs(requested: EditTimeline, rangesUs: List<Pair<Long, Long>>): FastCutSuggestion? =
            createAdjusted(requested, rangesUs, outward = false)

        fun createOutwardUs(requested: EditTimeline, range: Pair<Long, Long>): FastCutSuggestion? =
            requested.clips.singleOrNull()?.let { createAdjusted(requested, listOf(range), outward = true) }

        private fun createAdjusted(
            requested: EditTimeline,
            rangesUs: List<Pair<Long, Long>>,
            outward: Boolean,
        ): FastCutSuggestion? {
            if (requested.isEmpty || rangesUs.size != requested.clips.size ||
                requested.clips.any { it.endMs > Long.MAX_VALUE / 1_000L }) return null
            val clips = requested.clips.mapIndexed { index, clip ->
                val (start, end) = rangesUs[index]
                if (start < 0L || end < start || end - start < 100_000L ||
                    ceilMilliseconds(end) > clip.sourceDurationMs) return null
                if (outward) {
                    if (start > clip.startUs || end < clip.endUs) return null
                } else if (start < clip.startUs || end > clip.endUs) return null
                clip.withRangeUs(start, end)
            }
            if (clips.zip(requested.clips).all { (after, before) ->
                    after.startUs == before.startUs && after.endUs == before.endUs }) return null
            return FastCutSuggestion(requested.copy(clips = requested.clips.toList()), EditTimeline(clips))
        }
    }
}
