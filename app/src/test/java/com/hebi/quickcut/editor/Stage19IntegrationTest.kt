package com.hebi.quickcut.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Combined edit modes must remain immutable and support one-step undo/redo. */
class Stage19IntegrationTest {
    private val crop = CropGeometry.centerForRatio(1280, 720, CropRatio.SQUARE)
    private val audio = AudioAdjustment(55, 300, 300)
    private val text = TextLayer(
        id = 1, text = "통합 테스트", startMs = 500, endMs = 2_500,
    )

    @Test fun cropGainAndTimedTextShareOneClipWithoutChangingOriginal() {
        val source = EditTimeline(listOf(VideoClip(1, "content://source", 3_000)))
        val cropped = source.updateCrop(1, crop)
        val adjusted = cropped.updateAudio(1, audio)
        val titled = adjusted.updateTextLayers(1, listOf(text))
        assertNull(source.clips.single().crop)
        assertTrue(source.clips.single().audio.isDefault)
        assertTrue(source.clips.single().textLayers.isEmpty())
        val result = titled.clips.single()
        assertEquals(crop, result.crop)
        assertEquals(audio, result.audio)
        assertEquals(listOf(text), result.textLayers)
    }

    @Test fun oldProjectsKeepDefaultsForAllNewFields() {
        val clip = VideoClip(1, "content://old-video", 3_000)
        assertNull(clip.crop)
        assertTrue(clip.audio.isDefault)
        assertTrue(clip.textLayers.isEmpty())
    }

    @Test fun splitAndTrimPreserveEachEffectWithoutLeakingToOtherClips() {
        val clip = VideoClip(
            1, "content://source", 3_000, crop = crop,
            audio = audio, textLayers = listOf(text),
        )
        val result = EditTimeline(listOf(clip)).split(1, 1_500, 2)
        assertEquals(2, result.clips.size)
        assertEquals(crop, result.clips.first().crop)
        assertEquals(crop, result.clips.last().crop)
        assertEquals(55, result.clips.last().audio.volumePercent)
        assertEquals(1_500L, result.clips.first().textLayers.single().endMs)
        assertEquals(0L, result.clips.last().textLayers.single().startMs)
        val trimmed = result.updateRange(2, 1_800L, 3_000L)
        assertEquals(1_200L, trimmed.clips.last().durationMs)
        assertEquals(crop, trimmed.clips.last().crop)
    }

    @Test fun historyUndoRestoresAllEffectsTogether() {
        val source = EditTimeline(listOf(VideoClip(1, "content://source", 3_000)))
        val checkpoint = EditCheckpoint(source, 1, 0)
        val history = EditHistory()
        history.record(checkpoint)
        val edited = source.updateCrop(1, crop).updateAudio(1, audio)
            .updateTextLayers(1, listOf(text))
        val editedCheckpoint = EditCheckpoint(edited, 1, 1_000)
        assertEquals(checkpoint, history.undo(editedCheckpoint))
        assertEquals(editedCheckpoint, history.redo(checkpoint))
    }
}
