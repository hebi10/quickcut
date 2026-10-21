package com.hebi.quickcut.batch

import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import com.hebi.quickcut.media.FastCutSuggestion

/** A reviewed change is bound to the complete requested clip, never a global template. */
data class BatchStartAdjustment(
    val displayName: String,
    val requestedClip: VideoClip,
    val suggestedStartMs: Long,
    val suggestedEndMs: Long = requestedClip.endMs,
) {
    val uri: String get() = requestedClip.uri
    val requestedStartMs: Long get() = requestedClip.startMs
    val extraRemovedMs: Long get() = suggestedStartMs - requestedStartMs + requestedClip.endMs - suggestedEndMs
    val suggestion: FastCutSuggestion get() = checkNotNull(FastCutSuggestion.create(
        EditTimeline(listOf(requestedClip)), listOf(suggestedStartMs to suggestedEndMs)))
}

object BatchFastAdjustmentPolicy {
    fun fromSuggestion(displayName: String, suggestion: FastCutSuggestion?): BatchStartAdjustment? {
        suggestion ?: return null
        val requested = suggestion.requested.clips.singleOrNull() ?: return null
        val adjusted = suggestion.adjusted.clips.single()
        return BatchStartAdjustment(displayName, requested, adjusted.startMs, adjusted.endMs)
    }

    fun suggest(
        displayName: String,
        timeline: EditTimeline,
        suggestedStartMs: Long?,
    ): BatchStartAdjustment? {
        val clip = timeline.clips.singleOrNull() ?: return null
        val start = suggestedStartMs ?: return null
        if (start <= clip.startMs ||
            clip.endMs - start < 100L) return null
        return BatchStartAdjustment(displayName, clip, start)
    }

    fun apply(timeline: EditTimeline, adjustment: BatchStartAdjustment): EditTimeline? {
        if (timeline.clips.singleOrNull() != adjustment.requestedClip) return null
        return FastCutSuggestion.create(timeline,
            listOf(adjustment.suggestedStartMs to adjustment.suggestedEndMs))?.applyTo(timeline)
    }
}
