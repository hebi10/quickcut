package com.hebi.quickcut.batch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchTemplateTest {
    private val uri = "content://media/external/video/media/77"
    @Test fun removeFirstAndLastThreeSeconds() {
        val plan = BatchTemplate().plan(uri, 20_000L, true) as BatchPlan.Ready
        assertEquals(14_000L, plan.timeline.totalDurationMs)
        assertEquals(3_000L, plan.timeline.clips[0].startMs)
        assertEquals(17_000L, plan.timeline.clips[0].endMs)
    }
    @Test fun tooShortIsSkippedSafely() {
        val plan = BatchTemplate().plan(uri, 5_999L, true)
        assertTrue(plan is BatchPlan.Skip)
    }
    @Test fun keepFirstThreeSeconds() {
        val t = BatchTemplate(mode=BatchClipMode.KEEP_FIRST)
        val plan = t.plan(uri, 20_000L, true) as BatchPlan.Ready
        assertEquals(3_000L, plan.timeline.totalDurationMs)
        assertEquals(0L, plan.timeline.clips[0].startMs)
    }
    @Test fun keepLastThreeSeconds() {
        val t = BatchTemplate(mode=BatchClipMode.KEEP_LAST)
        val plan = t.plan(uri, 20_000L, true) as BatchPlan.Ready
        assertEquals(3_000L, plan.timeline.totalDurationMs)
        assertEquals(17_000L, plan.timeline.clips[0].startMs)
    }
    @Test fun keepEndsJoinsTwoDistinctSpans() {
        val t = BatchTemplate(mode=BatchClipMode.JOIN_EDGES)
        val plan = t.plan(uri, 20_000L, true) as BatchPlan.Ready
        assertEquals(6_000L, plan.timeline.totalDurationMs)
        assertEquals(2, plan.timeline.clips.size)
        assertEquals(17_000L, plan.timeline.clips[1].startMs)
    }
    @Test fun mutedTemplateIsAppliedToEveryClip() {
        val t = BatchTemplate(mode=BatchClipMode.JOIN_EDGES,muteAudio=true)
        val plan = t.plan(uri, 20_000L, true) as BatchPlan.Ready
        assertTrue(plan.timeline.clips.all { it.muted })
    }
    @Test fun invalidTemplateIsRejected() {
        assertTrue(BatchTemplate(leadingMs=0, trailingMs=0).checkValidity() != null)
        assertTrue(BatchTemplate(name="").checkValidity() != null)
    }
    @Test fun overlappingJoinIsSkipped() {
        val t = BatchTemplate(mode=BatchClipMode.JOIN_EDGES,leadingMs=8000,trailingMs=8000)
        assertTrue(t.plan(uri,10_000,true) is BatchPlan.Skip)
    }
}
