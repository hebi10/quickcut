package com.hebi.quickcut.batch

import com.hebi.quickcut.editor.EditTimeline

/** Durable refusal of fast saving for a semantic request, independent of template/clip IDs. */
data class BatchReencodeRefusal(
    val clips: List<BatchRefusedClip>,
    val dateMode: String,
    val originalDateMs: Long?,
) {
    fun matches(timeline: EditTimeline, dateMode: String, originalDateMs: Long?): Boolean =
        this == from(timeline, dateMode, originalDateMs)

    companion object {
        fun from(timeline: EditTimeline, dateMode: String, originalDateMs: Long?) =
            BatchReencodeRefusal(timeline.clips.map {
                BatchRefusedClip(it.uri, it.sourceDurationMs, it.startUs, it.endUs, it.muted, it.hasAudio)
            }, dateMode, originalDateMs)
    }
}

data class BatchRefusedClip(
    val uri: String,
    val durationMs: Long,
    val startUs: Long,
    val endUs: Long,
    val muted: Boolean,
    val hasAudio: Boolean,
)
