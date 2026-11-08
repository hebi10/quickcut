package com.hebi.quickcut.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLayerTest {
    private fun layer(
        id: Long = 1L, start: Long = 3_000L, end: Long = 8_000L,
    ) = TextLayer(id, "테스트 문구", start, end)

    @Test fun textIsVisibleOnlyInsideHalfOpenRange() {
        val title = layer()
        assertFalse(title.visibleAt(2_999L))
        assertTrue(title.visibleAt(3_000L))
        assertTrue(title.visibleAt(7_999L))
        assertFalse(title.visibleAt(8_000L))
    }

    @Test fun rangeMustFitClipAndSupportKoreanMultilineText() {
        assertTrue(TextLayerPolicy.isValid(layer().copy(text = "한글\nEnglish\n123"),
            10_000L))
        assertFalse(TextLayerPolicy.isValid(layer().copy(text = ""),
            10_000L))
        assertFalse(TextLayerPolicy.isValid(layer().copy(text = "a".repeat(33)), 10_000L))
        assertFalse(TextLayerPolicy.isValid(layer().copy(text = "a\nb\nc\nd"), 10_000L))
        assertFalse(TextLayerPolicy.isValid(layer().copy(endMs = 10_001L), 10_000L))
        assertFalse(TextLayerPolicy.isValid(layer().copy(startMs = 4_000L, endMs = 4_050L),
            10_000L))
    }

    @Test fun moveClampsTextToSafeFrameMargins() {
        val initial = layer()
        val moved = initial.moved(50f, -50f)
        assertEquals(TextLayerPolicy.MAX_ANCHOR, moved.x, 0.0001f)
        assertEquals(TextLayerPolicy.MIN_ANCHOR, moved.y, 0.0001f)
        assertNotEquals(initial, moved)
    }

    @Test fun updateTextDoesNotChangeOtherClipsOrLegacyFields() {
        val first = VideoClip(1L, "content://one", 10_000L, muted = true)
        val second = VideoClip(2L, "content://two", 10_000L)
        val edited = EditTimeline(listOf(first, second)).updateTextLayers(1L, listOf(layer()))
        assertEquals(first.copy(textLayers = listOf(layer())), edited.clips[0])
        assertEquals(second, edited.clips[1])
    }

    @Test fun removingStartOfClipShiftsTextTimesSafely() {
        val clip = VideoClip(1, "content://source", 10_000, textLayers = listOf(
            layer(id = 1, start = 0L, end = 1_000L),
            layer(id = 2, start = 3_000L, end = 8_000L),
        ))
        val result = EditTimeline(listOf(clip)).updateRange(1L, 2_000L, 9_000L)
        assertEquals(1, result.clips.single().textLayers.size)
        assertEquals(1_000L, result.clips.single().textLayers.single().startMs)
        assertEquals(6_000L, result.clips.single().textLayers.single().endMs)
    }

    @Test fun splitClipDistributesTextUsingLocalTimes() {
        val clip = VideoClip(1, "content://source", 10_000, textLayers = listOf(
            layer(id = 1, start = 0L, end = 3_000L),
            layer(id = 2, start = 2_000L, end = 8_000L),
        ))
        val split = EditTimeline(listOf(clip)).split(1, 4_000L, 2L)
        assertEquals(2, split.clips.first().textLayers.size)
        assertEquals(1, split.clips.last().textLayers.size)
        val crossing = split.clips.last().textLayers.single()
        assertEquals(0L, crossing.startMs)
        assertEquals(4_000L, crossing.endMs)
    }

    @Test fun textLayerCapAndIdsAreEnforced() {
        val five = (1L..5L).map { layer(id = it) }
        TextLayerPolicy.validate(five, 10_000L)
        assertTrue(runCatching { TextLayerPolicy.validate(five + layer(id = 6L), 10_000L) }.isFailure)
        assertTrue(runCatching { TextLayerPolicy.validate(listOf(layer(), layer()), 10_000L) }.isFailure)
    }

    @Test fun initialDraftIsNotSilentlyValidBeforeUserEntersText() {
        val new = TextLayerPolicy.newLayer(emptyList(), 8_000L, 2_000L)
        assertTrue(new.text.isEmpty())
        assertFalse(TextLayerPolicy.isValid(new, 8_000L))
        assertTrue(TextLayerPolicy.isValid(new.copy(text = "새 문구"), 8_000L))
    }
}
