package com.hebi.quickcut.batch

import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatchFastAdjustmentPolicyTest {
    private val clip = VideoClip(1L, "content://video/1", 20_000L, 3_000L, 17_000L)
    private val timeline = EditTimeline(listOf(clip))

    @Test fun nextKeyframeRemovesOnlyAdditionalFramesAndKeepsTheRequestedEnd() {
        val adjustment = BatchFastAdjustmentPolicy.suggest("one.mp4", timeline, 4_000L)!!
        val actual = BatchFastAdjustmentPolicy.apply(timeline, adjustment)!!
        assertEquals(3_000L, adjustment.requestedStartMs)
        assertEquals(1_000L, adjustment.extraRemovedMs)
        assertEquals(4_000L, actual.clips.single().startMs)
        assertEquals(17_000L, actual.clips.single().endMs)
        assertEquals(13_000L, actual.totalDurationMs)
        assertEquals(3_000L, timeline.clips.single().startMs)
    }

    @Test fun rejectsEarlierUnchangedDistantAndTooShortSuggestions() {
        listOf(null, 2_000L, 3_000L, 13_001L, 16_901L, 17_000L).forEach { candidate ->
            assertNull("Unsafe candidate: $candidate",
                BatchFastAdjustmentPolicy.suggest("one.mp4", timeline, candidate))
        }
        assertEquals(13_000L,
            BatchFastAdjustmentPolicy.suggest("one.mp4", timeline, 13_000L)!!.suggestedStartMs)
    }

    @Test fun refusesToApplyAReviewedOffsetToAChangedClip() {
        val adjustment = BatchFastAdjustmentPolicy.suggest("one.mp4", timeline, 4_000L)!!
        listOf(
            clip.copy(uri = "content://video/2"),
            clip.copy(startMs = 2_000L),
            clip.copy(endMs = 16_000L),
            clip.copy(sourceDurationMs = 21_000L),
            clip.copy(muted = true),
            clip.copy(hasAudio = false),
        ).forEach { changed ->
            assertNull(BatchFastAdjustmentPolicy.apply(EditTimeline(listOf(changed)), adjustment))
        }
    }

    @Test fun joinedClipsNeverReceiveASingleStartAdjustment() {
        assertNull(BatchFastAdjustmentPolicy.suggest("one.mp4",
            EditTimeline(listOf(clip, clip.copy(id = 2L))), 4_000L))
    }
}
