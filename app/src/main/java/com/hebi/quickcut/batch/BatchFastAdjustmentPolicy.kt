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
    private val suggestionSnapshot: FastCutSuggestion? = null,
) {
    val uri: String get() = requestedClip.uri
    val requestedStartMs: Long get() = requestedClip.startMs
    val extraRemovedMs: Long get() = suggestion.extraRemovedMs
    val suggestion: FastCutSuggestion get() = suggestionSnapshot ?: checkNotNull(FastCutSuggestion.create(
        EditTimeline(listOf(requestedClip)), listOf(suggestedStartMs to suggestedEndMs)))
}

object BatchFastAdjustmentPolicy {
    fun fromSuggestion(displayName: String, suggestion: FastCutSuggestion?): BatchStartAdjustment? {
        suggestion ?: return null
        val requested = suggestion.requested.clips.singleOrNull() ?: return null
        val adjusted = suggestion.adjusted.clips.single()
        return BatchStartAdjustment(displayName, requested, adjusted.startMs, adjusted.endMs, suggestion)
    }

    fun suggest(
        displayName: String,
        timeline: EditTimeline,
        suggestedStartMs: Long?,
    ): BatchStartAdjustment? {
        val clip = timeline.clips.singleOrNull() ?: return null
        val start = suggestedStartMs ?: return null
        if (start <= clip.startMs || start > Long.MAX_VALUE / 1_000L) return null
        val suggestion = FastCutSuggestion.createUs(timeline,
            listOf(start * 1_000L to clip.endUs)) ?: return null
        return fromSuggestion(displayName, suggestion)
    }

    fun apply(timeline: EditTimeline, adjustment: BatchStartAdjustment): EditTimeline? {
        if (timeline.clips.singleOrNull() != adjustment.requestedClip) return null
        return runCatching { adjustment.suggestion.applyTo(timeline) }.getOrNull()
    }
}
