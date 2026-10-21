package com.hebi.quickcut.editor

import com.hebi.quickcut.media.FastCutSuggestion
import com.hebi.quickcut.ui.fastCutSuggestionText
import org.junit.Assert.*
import org.junit.Test

class PreciseClipRangeTest {
    private val source = VideoClip(1, "content://video/1", 1_000)

    @Test fun exactBoundariesSurviveRoundedDisplayCoordinates() {
        val clip = source.withRangeUs(333_333, 899_999)
        assertEquals(333L, clip.startMs)
        assertEquals(900L, clip.endMs)
        assertEquals(333_333L, clip.startUs)
        assertEquals(899_999L, clip.endUs)
        assertEquals(566_666L, clip.durationUs)
        assertEquals(567L, EditTimeline(listOf(clip)).totalDurationMs)
    }

    @Test fun actualShortRangeIsDeclinedDespiteDisplayRounding() {
        assertNull(FastCutSuggestion.createUs(EditTimeline(listOf(source)), listOf(100_001L to 200_000L)))
    }

    @Test fun aSubMillisecondChangeRemainsVisibleAndInvalidatesDifferentConsent() {
        val requested = EditTimeline(listOf(source.withRangeUs(100_001, 900_000)))
        val review = FastCutSuggestion.createUs(requested, listOf(100_002L to 899_999L))!!
        assertEquals(2L, review.extraRemovedUs)
        assertTrue(fastCutSuggestionText(review).contains("00:00:00.100002"))
        assertTrue(fastCutSuggestionText(review).contains("0.000002초"))
        val sameDisplay = EditTimeline(listOf(source.withRangeUs(100_003, 900_000)))
        assertEquals(requested.clips.single().startMs, sameDisplay.clips.single().startMs)
        assertNull(review.applyTo(sameDisplay))
    }

    @Test fun explicitEditsReplacePrecisionAndSplitPreservesOuterEdges() {
        val timeline = EditTimeline(listOf(source.withRangeUs(100_001, 900_009)))
        val edited = timeline.updateRange(1, 200, 800).clips.single()
        assertNull(edited.preciseRange)
        assertEquals(200_000L, edited.startUs)
        val split = timeline.split(1, 400, 2).clips
        assertEquals(100_001L, split[0].startUs)
        assertEquals(500_001L, split[0].endUs)
        assertEquals(split[0].endUs, split[1].startUs)
        assertEquals(900_009L, split[1].endUs)
        assertEquals(timeline.totalDurationUs, EditTimeline(split).totalDurationUs)
    }

    @Test fun directCopyOfDisplayCoordinatesCannotRetainAnObsoleteRange() {
        val clip = source.withRangeUs(100_001, 900_009).copy(startMs = 200)
        assertEquals(200_000L, clip.startUs)
        assertEquals(901_000L, clip.endUs)
    }
}
