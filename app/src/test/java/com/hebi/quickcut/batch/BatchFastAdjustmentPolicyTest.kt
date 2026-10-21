package com.hebi.quickcut.batch

import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import com.hebi.quickcut.media.FastCutSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BatchFastAdjustmentPolicyTest {
    private val clip = VideoClip(1L, "content://video/1", 20_000L, 3_000L, 17_000L)
    private val timeline = EditTimeline(listOf(clip))

    @Test fun keepsTheVerifiedSuggestionObjectWithoutReconstructingIt() {
        val suggestion = FastCutSuggestion.create(timeline, listOf(4_000L to 16_000L))!!
        val adjustment = BatchFastAdjustmentPolicy.fromSuggestion("one.mp4", suggestion)!!
        assertSame(suggestion, adjustment.suggestion)
        assertSame(suggestion.adjusted, BatchFastAdjustmentPolicy.apply(timeline, adjustment))
    }

    @Test fun subMillisecondBoundariesAndExtraRemovalSurviveTheBatchSnapshot() {
        val suggestion = FastCutSuggestion.createUs(timeline,
            listOf(3_000_333L to 16_999_777L))!!
        val adjustment = BatchFastAdjustmentPolicy.fromSuggestion("one.mp4", suggestion)!!
        val actual = BatchFastAdjustmentPolicy.apply(timeline, adjustment)!!.clips.single()
        assertSame(suggestion, adjustment.suggestion)
        assertEquals(3_000_333L, actual.startUs)
        assertEquals(16_999_777L, actual.endUs)
        assertEquals(1L, adjustment.extraRemovedMs)
    }

    @Test fun legacyStartSuggestionKeepsAnAlreadyPreciseRequestedEnd() {
        val precise = EditTimeline(listOf(clip.withRangeUs(3_000_333L, 16_999_777L)))
        val adjustment = BatchFastAdjustmentPolicy.suggest("one.mp4", precise, 4_000L)!!
        val actual = BatchFastAdjustmentPolicy.apply(precise, adjustment)!!.clips.single()
        assertEquals(4_000_000L, actual.startUs)
        assertEquals(16_999_777L, actual.endUs)
    }

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

    @Test fun rejectsEarlierUnchangedAndTooShortSuggestions() {
        listOf(null, 2_000L, 3_000L, 16_901L, 17_000L).forEach { candidate ->
            assertNull("Unsafe candidate: $candidate",
                BatchFastAdjustmentPolicy.suggest("one.mp4", timeline, candidate))
        }
        assertEquals(13_001L,
            BatchFastAdjustmentPolicy.suggest("one.mp4", timeline, 13_001L)!!.suggestedStartMs)
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
