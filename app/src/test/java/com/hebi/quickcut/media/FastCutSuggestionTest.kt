package com.hebi.quickcut.media

import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import org.junit.Assert.*
import org.junit.Test

class FastCutSuggestionTest {
    private val clip = VideoClip(1, "content://video/1", 20_000, 3_000, 17_000)
    private val timeline = EditTimeline(listOf(clip))

    @Test fun showsBothBoundariesAndCountsAllExtraRemovedTime() {
        val review = FastCutSuggestion.create(timeline, listOf(4_000L to 16_000L))!!
        assertEquals(2_000L, review.extraRemovedMs)
        assertEquals(listOf(clip.copy(startMs = 4_000, endMs = 16_000)), review.applyTo(timeline)!!.clips)
        assertEquals(3_000L, timeline.clips.single().startMs)
    }

    @Test fun internalDeletionExpandsOutwardAndKeepsClipOrder() {
        val requested = EditTimeline(listOf(clip.copy(startMs = 0, endMs = 7_100),
            clip.copy(id = 2, startMs = 9_100, endMs = 20_000)))
        val review = FastCutSuggestion.create(requested, listOf(0L to 6_000L, 10_000L to 20_000L))!!
        assertEquals(2_000L, review.extraRemovedMs)
        assertEquals(listOf(1L, 2L), review.adjusted.clips.map { it.id })
        assertEquals(6_000L, review.adjusted.clips[0].endMs)
        assertEquals(10_000L, review.adjusted.clips[1].startMs)
    }

    @Test fun neverRestoresDeletedFootageOrDropsAnEntireClip() {
        listOf(listOf(2_999L to 16_000L), listOf(4_000L to 17_001L),
            listOf(16_901L to 17_000L), listOf(4_000L to 4_000L),
            emptyList(), listOf(3_000L to 17_000L)).forEach {
            assertNull("Unsafe or unchanged range: $it", FastCutSuggestion.create(timeline, it))
        }
    }

    @Test fun changingAnyReviewedClipInvalidatesConsent() {
        val review = FastCutSuggestion.create(timeline, listOf(4_000L to 16_000L))!!
        listOf(clip.copy(uri = "content://video/2"), clip.copy(startMs = 2_000),
            clip.copy(endMs = 18_000), clip.copy(muted = true), clip.copy(hasAudio = false),
            clip.copy(sourceDurationMs = 21_000)).forEach {
            assertNull(review.applyTo(EditTimeline(listOf(it))))
        }
        assertNull(review.applyTo(timeline.append(listOf(clip.copy(id = 2)))))
    }

    @Test fun aLongKeyframeGapCanBeReviewedWithoutPretendingItIsOneFrame() {
        val requested = EditTimeline(listOf(clip.copy(sourceDurationMs = 60_000, endMs = 60_000)))
        val review = FastCutSuggestion.create(requested, listOf(20_000L to 60_000L))!!
        assertEquals(17_000L, review.extraRemovedMs)
    }
}
