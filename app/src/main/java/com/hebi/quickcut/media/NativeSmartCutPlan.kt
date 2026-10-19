package com.hebi.quickcut.media

import com.hebi.quickcut.editor.EditTimeline

/** Only one source and one audio policy can share the native source index. */
internal object NativeSmartCutPlan {
    fun rangesUs(timeline: EditTimeline): LongArray? {
        if (timeline.clips.size !in 1..256) return null
        val first = timeline.clips.first()
        if (timeline.clips.any {
                it.uri != first.uri || it.sourceDurationMs != first.sourceDurationMs ||
                    it.muted != first.muted || it.hasAudio != first.hasAudio ||
                    it.endMs > Long.MAX_VALUE / 1_000L
            }) return null
        return timeline.clips.flatMap { listOf(it.startMs * 1_000L, it.endMs * 1_000L) }.toLongArray()
    }
}
