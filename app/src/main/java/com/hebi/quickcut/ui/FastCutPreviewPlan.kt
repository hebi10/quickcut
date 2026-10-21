package com.hebi.quickcut.ui

import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import com.hebi.quickcut.media.FastCutSuggestion

internal enum class PreviewBoundary { START, END, JOIN }
internal data class FastCutPreviewTarget(val clipIndex: Int, val boundary: PreviewBoundary) {
    val label: String get() = when (boundary) {
        PreviewBoundary.START -> "구간 ${clipIndex + 1} 시작"
        PreviewBoundary.END -> "구간 ${clipIndex + 1} 끝"
        PreviewBoundary.JOIN -> "구간 ${clipIndex + 1} → ${clipIndex + 2} 연결"
    }
}

internal object FastCutPreviewPlan {
    private const val WINDOW_US = 1_500_000L

    fun targets(suggestion: FastCutSuggestion): List<FastCutPreviewTarget> = buildList {
        suggestion.requested.clips.zip(suggestion.adjusted.clips).forEachIndexed { index, (before, after) ->
            if (before.startUs != after.startUs) add(FastCutPreviewTarget(index, PreviewBoundary.START))
            if (before.endUs != after.endUs) add(FastCutPreviewTarget(index, PreviewBoundary.END))
            if (index < suggestion.requested.clips.lastIndex) add(FastCutPreviewTarget(index, PreviewBoundary.JOIN))
        }
    }

    fun frame(timeline: EditTimeline, target: FastCutPreviewTarget): Pair<VideoClip, Long> {
        val clip = timeline.clips[target.clipIndex]
        return clip to if (target.boundary == PreviewBoundary.START) clip.startUs else clip.endUs - 1L
    }

    fun window(timeline: EditTimeline, target: FastCutPreviewTarget): EditTimeline {
        val clip = timeline.clips[target.clipIndex]
        fun head(item: VideoClip) = item.withRangeUs(item.startUs, minOf(item.endUs, item.startUs + WINDOW_US))
        fun tail(item: VideoClip) = item.withRangeUs(maxOf(item.startUs, item.endUs - WINDOW_US), item.endUs)
        return EditTimeline(when (target.boundary) {
            PreviewBoundary.START -> listOf(head(clip))
            PreviewBoundary.END -> listOf(tail(clip))
            PreviewBoundary.JOIN -> listOf(tail(clip), head(timeline.clips[target.clipIndex + 1]))
        })
    }
}
