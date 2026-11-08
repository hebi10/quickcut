package com.hebi.quickcut.segments

import org.junit.Assert.*
import org.junit.Test

class ExtractionPlannerTest {
    @Test fun initialUsesFourSecondsOrSourceEnd() {
        assertEquals(ExtractionRange(1, 0, 4_000), ExtractionPlanner.initial(15_000).single())
        assertEquals(ExtractionRange(1, 0, 150), ExtractionPlanner.initial(150).single())
    }

    @Test fun overlapIsAllowedButExactDuplicateIsNot() {
        val first = ExtractionPlanner.initial(15_000)
        val withSecond = ExtractionPlanner.add(first, 15_000, 2_000)!!
        assertEquals(2, withSecond.size)
        assertNull(ExtractionPlanner.add(withSecond, 15_000, 2_000))
        assertEquals(ExtractionRange(2, 2_000, 6_000), withSecond.last())
    }

    @Test fun rangeCannotBeEmptyOrOutOfBoundsOrDuplicate() {
        val first = ExtractionPlanner.initial(15_000)
        assertNull(ExtractionPlanner.replace(first, 1, 10_000, 10_050, 15_000))
        assertNull(ExtractionPlanner.replace(first, 1, -10, 10_000, 15_000))
        assertNull(ExtractionPlanner.replace(first, 1, 10, 20_000, 15_000))
        assertEquals(ExtractionRange(1, 100, 5_000),
            ExtractionPlanner.replace(first, 1, 100, 5_000, 15_000)?.single())
        val second = ExtractionPlanner.add(first, 15_000, 5_000)!!
        assertNull(ExtractionPlanner.replace(second, 2, 0, 4_000, 15_000))
    }

    @Test fun exportsAreIndependentSingleClipTimelines() {
        val timeline = ExtractionPlanner.timeline("content://media/clip", 15_000, true,
            ExtractionRange(3, 5_000, 9_000))
        assertEquals(1, timeline.clips.size)
        assertEquals(5_000L, timeline.clips.single().startMs)
        assertEquals(4_000L, timeline.totalDurationMs)
    }

    @Test fun outputNamesUseStableIndexes() {
        assertEquals("movie_구간03", ExtractionPlanner.outputName("movie.mp4", 3))
        assertTrue(ExtractionPlanner.outputName("../b@a.mp4", 1).startsWith("b_a"))
    }

    @Test fun additionStopsAtLimit() {
        val segments = (1..ExtractionPlanner.MAX_SEGMENTS).map {
            ExtractionRange(it.toLong(), 0, 100)
        }
        assertNull(ExtractionPlanner.add(segments, 15_000, 0))
    }
}
