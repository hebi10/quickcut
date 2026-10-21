package com.hebi.quickcut.media

import com.hebi.quickcut.editor.EditTimeline

/** A one-use review of specific clips; it is never a global trim preference. */
class FastCutSuggestion private constructor(
    val requested: EditTimeline,
    val adjusted: EditTimeline,
) {
    val extraRemovedMs: Long get() = requested.totalDurationMs - adjusted.totalDurationMs
    fun applyTo(current: EditTimeline): EditTimeline? = adjusted.takeIf { current == requested }

    companion object {
        fun create(requested: EditTimeline, rangesMs: List<Pair<Long, Long>>): FastCutSuggestion? {
            if (requested.isEmpty || rangesMs.size != requested.clips.size) return null
            val clips = requested.clips.mapIndexed { index, clip ->
                val (start, end) = rangesMs[index]
                // Only remove more. Never resurrect a deleted frame, silently drop a
                // clip, change its audio, or apply one file's offset to another file.
                if (start < clip.startMs || end > clip.endMs || end - start < 100L) return null
                clip.copy(startMs = start, endMs = end)
            }
            if (clips == requested.clips) return null
            return FastCutSuggestion(requested.copy(clips = requested.clips.toList()), EditTimeline(clips))
        }
    }
}
