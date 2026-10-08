package com.hebi.quickcut.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EditTimelineTest {
    private val source = VideoClip(id = 1, uri = "content://clip", sourceDurationMs = 30_000)

    @Test fun trimUpdatesDurationWithoutChangingSource() {
        val result = EditTimeline(listOf(source)).updateRange(1, 1_000, 12_000)
        assertEquals(11_000L, result.totalDurationMs)
        assertEquals(30_000L, result.clips.first().sourceDurationMs)
    }

    @Test fun splitProducesTwoContinuousClips() {
        val result = EditTimeline(listOf(source)).split(1, 10_000, newId = 2)
        assertEquals(2, result.clips.size)
        assertEquals(10_000L, result.clips[0].endMs)
        assertEquals(result.clips[0].endMs, result.clips[1].startMs)
        assertEquals(30_000L, result.totalDurationMs)
    }

    @Test fun splitRejectsEdge() {
        assertThrows(IllegalArgumentException::class.java) {
            EditTimeline(listOf(source)).split(1, 0, newId = 2)
        }
    }

    @Test fun moveChangesClipOrder() {
        val second = source.copy(id = 2)
        val result = EditTimeline(listOf(source, second)).move(1, 1)
        assertEquals(listOf(2L, 1L), result.clips.map { it.id })
    }

    @Test fun deleteCanProduceEmptyTimeline() {
        val result = EditTimeline(listOf(source)).delete(1)
        assertTrue(result.isEmpty)
        assertEquals(0, result.totalDurationMs)
    }

    @Test fun muteChangesOnlySelectedClip() {
        val second = source.copy(id = 2)
        val result = EditTimeline(listOf(source, second)).toggleMute(2)
        assertFalse(result.clips[0].muted)
        assertTrue(result.clips[1].muted)
    }

    @Test fun globalPositionMapsAcrossClips() {
        val second = source.copy(id = 2, startMs = 5_000, endMs = 15_000)
        val plan = EditTimeline(listOf(source, second))
        assertEquals(TimelinePosition(1, 3_000), plan.positionFor(33_000))
        assertEquals(TimelinePosition(1, 10_000), plan.positionFor(999_000))
    }

    @Test fun timecodeIsStable() {
        assertEquals("01:23.4", formatTime(83_400))
        assertEquals("01:01:01.0", formatTime(3_661_000))
    }
}
