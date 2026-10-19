package com.hebi.quickcut.media

import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeSmartCutPlanTest {
    private val source = VideoClip(1L, "content://video/1", 10_000L)

    @Test fun audibleReorderedOrOverlappingRangesPreserveRequestedCoordinates() {
        val later = source.copy(startMs = 4_500L, endMs = 8_000L)
        listOf(source.copy(id = 2L, startMs = 0L, endMs = 3_000L),
            source.copy(id = 2L, startMs = 7_000L, endMs = 9_000L)).forEach { next ->
                assertArrayEquals(longArrayOf(4_500_000L, 8_000_000L, next.startMs * 1_000L, next.endMs * 1_000L),
                    NativeSmartCutPlan.rangesUs(EditTimeline(listOf(later, next))))
            }
    }

    @Test fun preservesEveryRangeInTheRequestedOrderIncludingRepeats() {
        val first = source.copy(startMs = 3_001L, endMs = 7_500L, muted = true)
        val timeline = EditTimeline(listOf(first,
            source.copy(id = 2L, startMs = 0L, endMs = 2_000L, muted = true), first.copy(id = 3L)))
        assertArrayEquals(longArrayOf(3_001_000L, 7_500_000L, 0L, 2_000_000L,
            3_001_000L, 7_500_000L), NativeSmartCutPlan.rangesUs(timeline))
    }

    @Test fun differentSourcesOrAudioPoliciesUseTheNormalFallback() {
        listOf(source.copy(uri = "content://video/2"), source.copy(muted = true),
            source.copy(hasAudio = false), source.copy(sourceDurationMs = 11_000L))
            .forEach { other ->
                assertNull(NativeSmartCutPlan.rangesUs(EditTimeline(listOf(source, other))))
            }
    }

    @Test fun emptyOrExcessiveRangesAndTimestampOverflowAreDeclined() {
        assertNull(NativeSmartCutPlan.rangesUs(EditTimeline()))
        assertNull(NativeSmartCutPlan.rangesUs(EditTimeline(List(257) { source.copy(id = it.toLong()) })))
        assertNull(NativeSmartCutPlan.rangesUs(EditTimeline(listOf(
            VideoClip(1L, source.uri, Long.MAX_VALUE)))))
    }
}
