package com.hebi.quickcut.ui

import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import com.hebi.quickcut.media.FastCutSuggestion
import org.junit.Assert.*
import org.junit.Test

class FastCutPreviewPlanTest {
    private val source = VideoClip(1, "content://video/1", 20_000)
    @Test fun boundaryWindowsKeepPreciseOuterEdgesAndStayInsideTheSavedRange() {
        val clip = source.withRangeUs(100_001, 10_000_009)
        val timeline = EditTimeline(listOf(clip))
        val head = FastCutPreviewPlan.window(timeline, FastCutPreviewTarget(0, PreviewBoundary.START)).clips.single()
        val tail = FastCutPreviewPlan.window(timeline, FastCutPreviewTarget(0, PreviewBoundary.END)).clips.single()
        assertEquals(clip.startUs, head.startUs)
        assertEquals(clip.endUs, tail.endUs)
        assertEquals(1_500_000L, head.durationUs)
        assertEquals(1_500_000L, tail.durationUs)
        assertTrue(head.endUs <= clip.endUs)
        assertTrue(tail.startUs >= clip.startUs)
    }
    @Test fun joinUsesOnlyTheKeptSidesAndNeverIncludesDeletedFootage() {
        val clips = listOf(source.withRangeUs(0, 3_333_333),
            source.copy(id = 2).withRangeUs(6_666_667, 7_000_000))
        val window = FastCutPreviewPlan.window(EditTimeline(clips), FastCutPreviewTarget(0, PreviewBoundary.JOIN))
        assertEquals(3_333_333L, window.clips[0].endUs)
        assertEquals(6_666_667L, window.clips[1].startUs)
        assertEquals(7_000_000L, window.clips[1].endUs)
        assertTrue(window.totalDurationUs <= 3_000_000L)
    }
    @Test fun tinyBoundaryChangesAreStillSelectable() {
        val suggestion = FastCutSuggestion.createUs(EditTimeline(listOf(source)), listOf(1L to 19_999_999L))!!
        assertEquals(listOf(PreviewBoundary.START, PreviewBoundary.END),
            FastCutPreviewPlan.targets(suggestion).map { it.boundary })
        assertEquals(19_999_998L, FastCutPreviewPlan.frame(suggestion.adjusted,
            FastCutPreviewTarget(0, PreviewBoundary.END)).second)
    }
}
