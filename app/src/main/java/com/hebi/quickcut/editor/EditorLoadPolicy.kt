package com.hebi.quickcut.editor

import java.util.concurrent.CancellationException

internal data class SavedSourceMetadata(val durationMs: Long, val hasAudio: Boolean)

/** URI is kept in memory only to identify which saved source needs reconnection. */
internal class SavedSourceUnavailableException(val sourceUri: String, cause: Throwable) :
    IllegalStateException("Saved source unavailable", cause)

private fun validateSavedSource(clips: List<VideoClip>, metadata: SavedSourceMetadata) {
    require(metadata.durationMs > 0L)
    require(clips.all { clip ->
        // Compare positive durations without an overflowing abs/subtraction.
        val durationDifference = if (metadata.durationMs >= clip.sourceDurationMs)
            metadata.durationMs - clip.sourceDurationMs else clip.sourceDurationMs - metadata.durationMs
        durationDifference <= 1_500L && ceilMilliseconds(clip.endUs) <= metadata.durationMs &&
            (!clip.hasAudio || metadata.hasAudio)
    }) { "Saved source metadata mismatch" }
}

internal fun validateSavedTimeline(
    timeline: EditTimeline,
    read: (String) -> SavedSourceMetadata,
) {
    timeline.clips.groupBy { it.uri }.forEach { (uri, clips) ->
        try {
            validateSavedSource(clips, read(uri))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw SavedSourceUnavailableException(uri, error)
        }
    }
}

internal fun reconnectSavedTimeline(
    timeline: EditTimeline,
    sourceUri: String,
    replacementUri: String,
    metadata: SavedSourceMetadata,
): EditTimeline {
    val matching = timeline.clips.filter { it.uri == sourceUri }
    require(matching.isNotEmpty() && replacementUri.isNotBlank())
    // Validate the complete group that will share the replacement URI before
    // returning any persistable candidate. Full-timeline validation may stop at
    // an unrelated inaccessible source before reaching this group.
    val replacementGroup = timeline.clips.filter { it.uri == sourceUri || it.uri == replacementUri }
    validateSavedSource(replacementGroup, metadata)
    return timeline.copy(clips = timeline.clips.map {
        if (it.uri == sourceUri) it.copy(uri = replacementUri) else it
    })
}

/** Main-thread ownership: stale provider work must never commit after cancellation. */
internal class EditorLoadGeneration {
    private var generation = 0L
    private var active = false
    fun begin(): Long {
        generation++
        active = true
        return generation
    }
    fun cancel() {
        generation++
        active = false
    }
    fun isCurrent(value: Long): Boolean = active && value == generation
}
