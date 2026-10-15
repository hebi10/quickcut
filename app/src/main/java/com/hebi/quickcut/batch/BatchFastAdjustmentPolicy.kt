package com.hebi.quickcut.batch

import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip

/** A reviewed change is bound to the complete requested clip, never a global template. */
data class BatchStartAdjustment(
    val displayName: String,
    val requestedClip: VideoClip,
    val suggestedStartMs: Long,
) {
    val uri: String get() = requestedClip.uri
    val requestedStartMs: Long get() = requestedClip.startMs
    val extraRemovedMs: Long get() = suggestedStartMs - requestedStartMs
}

object BatchFastAdjustmentPolicy {
    fun suggest(
        displayName: String,
        timeline: EditTimeline,
        suggestedStartMs: Long?,
    ): BatchStartAdjustment? {
        val clip = timeline.clips.singleOrNull() ?: return null
        val start = suggestedStartMs ?: return null
        if (start <= clip.startMs || start - clip.startMs > 10_000L ||
            clip.endMs - start < 100L) return null
        return BatchStartAdjustment(displayName, clip, start)
    }

    fun apply(timeline: EditTimeline, adjustment: BatchStartAdjustment): EditTimeline? {
        if (timeline.clips.singleOrNull() != adjustment.requestedClip) return null
        if (suggest(adjustment.displayName, timeline, adjustment.suggestedStartMs) == null) return null
        return timeline.copy(clips = listOf(adjustment.requestedClip.copy(
            startMs = adjustment.suggestedStartMs,
        )))
    }
}
