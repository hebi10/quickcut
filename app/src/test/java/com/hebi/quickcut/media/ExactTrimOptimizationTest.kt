package com.hebi.quickcut.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactTrimOptimizationTest {
    @Test
    fun retainedNonKeyframeWithEnoughAudioRoomCanUsePartialEncoding() {
        assertTrue(ExactTrimOptimization.isSafeToOptimize(
            1_950_000L, 6_000_000L, 2_000_000L,
            longArrayOf(0L, 1_933_333L, 1_966_666L, 2_000_000L, 2_033_333L), 44_100,
        ))
    }

    @Test
    fun lowFrameRateMustNotSnapToNextSyncWhenNoRetainedFramePrecedesIt() {
        assertFalse(ExactTrimOptimization.isSafeToOptimize(
            1_100_000L, 6_000_000L, 2_000_000L,
            longArrayOf(0L, 1_000_000L, 2_000_000L, 3_000_000L), null,
        ))
    }

    @Test
    fun sourceAudioAtEightKHzNeedsMoreThanOneHundredMillisecondsEvenForMutedOutput() {
        // The source rate, not the output's mute setting, drives Media3's threshold.
        assertFalse(ExactTrimOptimization.isSafeToOptimize(
            1_900_000L, 6_000_000L, 2_000_000L,
            longArrayOf(0L, 1_933_333L, 1_966_666L, 2_000_000L), 8_000,
        ))
        assertTrue(ExactTrimOptimization.isSafeToOptimize(
            1_800_000L, 6_000_000L, 2_000_000L,
            longArrayOf(0L, 1_933_333L, 1_966_666L, 2_000_000L), 8_000,
        ))
    }

    @Test
    fun anAudioPacketAtTheThresholdMustNotCauseStartRounding() {
        assertFalse(ExactTrimOptimization.isSafeToOptimize(
            1_980_000L, 6_000_000L, 2_000_000L,
            longArrayOf(0L, 1_990_000L, 2_000_000L), 48_000,
        ))
        assertTrue(ExactTrimOptimization.isSafeToOptimize(
            1_980_000L, 6_000_000L, 2_000_000L,
            longArrayOf(0L, 1_990_000L, 2_000_000L), null,
        ))
    }

    @Test
    fun reorderedOrDuplicatePresentationTimesRequireNormalClipping() {
        for (times in listOf(
            longArrayOf(0L, 1_966_666L, 2_000_000L, 2_066_666L, 2_033_333L),
            longArrayOf(0L, 1_966_666L, 2_000_000L, 2_000_000L),
        )) assertFalse(ExactTrimOptimization.isSafeToOptimize(
            1_950_000L, 6_000_000L, 2_000_000L, times, 44_100,
        ))
    }

    @Test
    fun exactSyncStartCannotBeMovedByTheOptimization() {
        assertTrue(ExactTrimOptimization.isSafeToOptimize(
            2_000_000L, 6_000_000L, 2_000_000L,
            longArrayOf(0L, 1_966_666L, 2_000_000L), 44_100,
        ))
    }

    @Test
    fun unknownAudioOrInconsistentMetadataUsesNormalClipping() {
        assertFalse(ExactTrimOptimization.isSafeToOptimize(
            1_950_000L, 6_000_000L, 2_000_000L,
            longArrayOf(0L, 1_966_666L, 2_000_000L), -1,
        ))
        assertFalse(ExactTrimOptimization.isSafeToOptimize(
            1_950_000L, 6_000_000L, 2_000_000L,
            longArrayOf(0L, 1_966_666L, 2_033_333L), 44_100,
        ))
        assertFalse(ExactTrimOptimization.isSafeToOptimize(
            1_950_000L, 2_000_000L, 2_000_000L,
            longArrayOf(0L, 1_966_666L, 2_000_000L), 44_100,
        ))
    }
}
