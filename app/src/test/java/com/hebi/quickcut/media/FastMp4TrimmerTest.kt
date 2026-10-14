package com.hebi.quickcut.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastMp4TrimmerTest {
    @Test
    fun exactKeyframeStartIsEligible() {
        assertTrue(FastMp4Trimmer.isAcceptableStart(4_000_000L, 4_000_000L, 9_000_000L))
    }

    @Test
    fun followingKeyframeWithinOneFrameIsEligible() {
        assertTrue(FastMp4Trimmer.isAcceptableStart(4_000_000L, 4_033_333L, 9_000_000L))
    }

    @Test
    fun precedingKeyframeIsNotEligibleBecauseItLeaksDeletedFrames() {
        assertFalse(FastMp4Trimmer.isAcceptableStart(4_000_000L, 3_999_999L, 9_000_000L))
    }

    @Test
    fun distantNextKeyframeMustUseAccurateFallback() {
        assertFalse(FastMp4Trimmer.isAcceptableStart(4_000_000L, 5_000_000L, 9_000_000L))
    }

    @Test
    fun tooLittleVideoAfterKeyframeMustUseFallback() {
        assertFalse(FastMp4Trimmer.isAcceptableStart(4_000_000L, 4_049_999L, 4_149_998L))
    }

    @Test
    fun startOfVideoIsEligibleWhenTheFirstFrameIsASyncSample() {
        assertTrue(FastMp4Trimmer.isAcceptableStart(0L, 0L, 3_000_000L))
    }
}
