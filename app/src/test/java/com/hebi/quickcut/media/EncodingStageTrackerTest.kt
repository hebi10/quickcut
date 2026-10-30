package com.hebi.quickcut.media

import org.junit.Assert.assertEquals
import org.junit.Test

class EncodingStageTrackerTest {
    @Test fun audioOnlyEncodingDoesNotClaimThatTheVideoIsBeingEncoded() {
        val stages = mutableListOf<ExportStage>()
        val tracker = EncodingStageTracker(stages::add)
        tracker.audioStarted()
        tracker.audioStarted()
        assertEquals(listOf(ExportStage.AUDIO_ENCODING), stages)
    }

    @Test fun laterAudioEncodersCannotHideAnActiveVideoEncoder() {
        val stages = mutableListOf<ExportStage>()
        val tracker = EncodingStageTracker(stages::add)
        tracker.audioStarted()
        tracker.videoStarted()
        tracker.audioStarted()
        tracker.videoStarted()
        assertEquals(listOf(ExportStage.AUDIO_ENCODING, ExportStage.VIDEO_ENCODING), stages)
    }

    @Test fun copyingWithoutAnEncoderNeverClaimsEncoding() {
        val stages = mutableListOf<ExportStage>()
        EncodingStageTracker(stages::add)
        assertEquals(emptyList<ExportStage>(), stages)
    }
}
