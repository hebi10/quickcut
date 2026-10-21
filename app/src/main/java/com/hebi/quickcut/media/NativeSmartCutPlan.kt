package com.hebi.quickcut.media

import com.hebi.quickcut.editor.EditTimeline

/** Only one source and one audio policy can share the native source index. */
internal object NativeSmartCutPlan {
    fun rangesUs(timeline: EditTimeline): LongArray? {
        if (unavailableReason(timeline) != null) return null
        return timeline.clips.flatMap { listOf(it.startUs, it.endUs) }.toLongArray()
    }

    fun unavailableReason(timeline: EditTimeline): FastExportReason? {
        if (timeline.clips.size !in 1..256) return FastExportReason.RANGE_COUNT
        val first = timeline.clips.first()
        if (timeline.clips.any {
                it.uri != first.uri || it.sourceDurationMs != first.sourceDurationMs
            }) return FastExportReason.MULTIPLE_SOURCES
        if (timeline.clips.any { it.muted != first.muted || it.hasAudio != first.hasAudio })
            return FastExportReason.MIXED_AUDIO_POLICY
        if (timeline.clips.any { it.endMs > Long.MAX_VALUE / 1_000L }) return FastExportReason.INVALID_RANGE
        return null
    }
}
