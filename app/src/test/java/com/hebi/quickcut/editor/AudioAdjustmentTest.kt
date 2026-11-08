package com.hebi.quickcut.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioAdjustmentTest {
    @Test fun defaultSettingsPreserveExistingFastPath() {
        assertTrue(AudioAdjustment.DEFAULT.isDefault)
        val clip = VideoClip(1, "content://video/1", 10_000)
        assertTrue(clip.audio.isDefault)
        assertEquals(1f, AudioEnvelope.gainAtMs(clip.audio, clip.durationMs, 5_000.0), 0.0001f)
    }

    @Test fun volumeAndFadesAreContinuousAndBounded() {
        val effect = AudioAdjustment(60, fadeInMs = 1_000L, fadeOutMs = 2_000L)
        assertEquals(0f, AudioEnvelope.gainAtMs(effect, 10_000, 0.0), 0.0001f)
        assertEquals(0.3f, AudioEnvelope.gainAtMs(effect, 10_000, 500.0), 0.0001f)
        assertEquals(0.6f, AudioEnvelope.gainAtMs(effect, 10_000, 5_000.0), 0.0001f)
        assertEquals(0.3f, AudioEnvelope.gainAtMs(effect, 10_000, 9_000.0), 0.0001f)
        assertEquals(0f, AudioEnvelope.gainAtMs(effect, 10_000, 10_000.0), 0.0001f)
    }

    @Test fun sampleTimeMatchesMillisecondEnvelope() {
        val settings = AudioAdjustment(80, fadeInMs = 400, fadeOutMs = 400)
        val sample = 24_000L
        assertEquals(
            AudioEnvelope.gainAtMs(settings, 2_000, 500.0),
            AudioEnvelope.gainAtSample(settings, 2_000, sample, 48_000),
            0.0001f,
        )
    }

    @Test fun overlappingFadesNeverClipAboveChosenVolume() {
        val effect = AudioAdjustment(40, fadeInMs = 800, fadeOutMs = 800)
        for (position in 0..1_000 step 25) {
            assertTrue(AudioEnvelope.gainAtMs(effect, 1_000, position.toDouble()) in 0f..0.4f)
        }
    }

    @Test fun splitAndTrimKeepValidAudioDurations() {
        val original = VideoClip(1, "content://test", 12_000, hasAudio = true)
        val first = EditTimeline(listOf(original)).updateAudio(
            1, AudioAdjustment(50, fadeInMs = 6_000, fadeOutMs = 5_000)
        )
        val trimmed = first.updateRange(1, 5_000, 7_000)
        assertEquals(2_000L, trimmed.clips.single().audio.fadeInMs)
        assertEquals(2_000L, trimmed.clips.single().audio.fadeOutMs)
        val split = first.split(1, 4_000, 2)
        assertEquals(2, split.clips.size)
        assertEquals(4_000L, split.clips.first().audio.fadeInMs)
        assertEquals(5_000L, split.clips.last().audio.fadeOutMs)
    }

    @Test fun muteDoesNotEraseAudioSettingsAndOtherClipsAreUntouched() {
        val a = VideoClip(1, "content://a", 10_000)
        val b = VideoClip(2, "content://b", 10_000)
        val edited = EditTimeline(listOf(a, b)).updateAudio(1, AudioAdjustment(20, 500, 0))
        val muted = edited.toggleMute(1)
        assertTrue(muted.clips.first().muted)
        assertEquals(20, muted.clips.first().audio.volumePercent)
        assertTrue(muted.clips.last().audio.isDefault)
        assertEquals(edited, muted.toggleMute(1))
    }

    @Test fun silentVolumeIsNotTheSameAsRemovingAudioTrack() {
        val zero = AudioAdjustment(0)
        assertFalse(zero.isDefault)
        assertEquals(0f, AudioEnvelope.gainAtMs(zero, 4_000, 2_000.0), 0.0001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidVolume() { AudioAdjustment(120) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeFade() { AudioAdjustment(50, -1, 0) }

    @Test(expected = IllegalArgumentException::class)
    fun audioAbsentClipCannotBeModified() {
        EditTimeline(listOf(VideoClip(1, "content://silent", 2_000, hasAudio = false)))
            .updateAudio(1, AudioAdjustment(40))
    }
}
