package com.hebi.quickcut.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftwareVideoDecoderPolicyTest {
    @Test
    fun android10PlatformOmxSoftwareDecoderSurvivesMissingSoftwareFlag() {
        assertTrue(isSoftwareVideoDecoder(29, "OMX.google.h264.decoder", false, false, false))
        assertTrue(isSoftwareVideoDecoder(29, "OMX.google.hevc.decoder", false, false, false))
    }

    @Test
    fun advertisedSoftwareDecoderRemainsAvailableOnEverySupportedApi() {
        for (sdk in listOf(29, 30, 35, 36)) {
            assertTrue(isSoftwareVideoDecoder(sdk, "c2.android.avc.decoder", true, false, false))
            assertTrue(isSoftwareVideoDecoder(sdk, "c2.vendor.software.decoder", true, false, true))
        }
    }

    @Test
    fun missingFlagCompatibilityIsLimitedToAndroid10() {
        for (sdk in listOf(28, 30, 35, 36)) {
            assertFalse(isSoftwareVideoDecoder(sdk, "OMX.google.h264.decoder", false, false, false))
        }
    }

    @Test
    fun unknownOrVendorNamesCannotUseMissingFlagCompatibility() {
        for (name in listOf("OMX.android.goldfish.h264.decoder", "OMX.vendor.h264.decoder",
            "c2.android.avc.decoder", "c2.vendor.avc.decoder", "OMX.googleish.h264.decoder", "")) {
            assertFalse(isSoftwareVideoDecoder(29, name, false, false, false))
        }
    }

    @Test
    fun canonicalNameCannotOverrideHardwareOrVendorClassification() {
        assertFalse(isSoftwareVideoDecoder(29, "OMX.google.h264.decoder", false, true, false))
        assertFalse(isSoftwareVideoDecoder(29, "OMX.google.h264.decoder", false, false, true))
        assertFalse(isSoftwareVideoDecoder(29, "OMX.google.h264.decoder", false, true, true))
    }
}
