package com.hebi.quickcut.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportQualityPolicyTest {
    private val hd = SourceVideoSpec(1920, 1080, 60f)

    @Test fun automaticProfileIsExactlyTheLegacyExportPath() {
        val result = ExportQualityPolicy.evaluate(listOf(hd), 15_000, ExportQualitySettings())
        assertTrue(result.supported)
        assertNull(result.plan)
        assertTrue(ExportQualitySettings().isAutomatic)
    }

    @Test fun explicit720pDoesNotUpscaleOrDistort16by9() {
        val result = ExportQualityPolicy.evaluate(listOf(hd), 15_000,
            ExportQualitySettings(resolution = ExportResolution.HD_720))
        assertNotNull(result.plan)
        assertEquals(1280, result.plan!!.width)
        assertEquals(720, result.plan!!.height)
    }

    @Test fun portrait1080pPreservesOrientation() {
        val profile = SourceVideoSpec(2160, 3840, 60f)
        val result = ExportQualityPolicy.evaluate(listOf(profile), 10_000,
            ExportQualitySettings(resolution = ExportResolution.FULL_HD_1080,
                frameRate = ExportFrameRate.FPS_30))
        assertEquals(1080, result.plan!!.width)
        assertEquals(1920, result.plan!!.height)
        assertEquals(30, result.plan!!.targetFps)
    }

    @Test fun lowResolutionVideoCannotBeUpscaledTo4k() {
        val result = ExportQualityPolicy.evaluate(listOf(hd), 15_000,
            ExportQualitySettings(resolution = ExportResolution.ULTRA_HD_2160))
        assertFalse(result.supported)
        assertTrue(result.error!!.contains("높은 해상도"))
    }

    @Test fun fpsMustBePresentAndHighEnough() {
        val input = SourceVideoSpec(1920, 1080, 24f)
        val target = ExportQualitySettings(frameRate = ExportFrameRate.FPS_60)
        assertFalse(ExportQualityPolicy.evaluate(listOf(input), 3_000, target).supported)
        assertFalse(ExportQualityPolicy.evaluate(
            listOf(input.copy(frameRate = null)), 3_000, target).supported)
        assertTrue(ExportQualityPolicy.evaluate(
            listOf(input.copy(frameRate = 59.94f)), 3_000, target).supported)
    }

    @Test fun hdrIsRejectedUntilColorConversionIsSupported() {
        val result = ExportQualityPolicy.evaluate(listOf(hd.copy(hdr = true)), 6_000,
            ExportQualitySettings(quality = ExportQualityLevel.HIGH))
        assertFalse(result.supported)
    }

    @Test fun differentSourceAspectRatiosAreNotSilentlyDistorted() {
        val result = ExportQualityPolicy.evaluate(
            listOf(hd, SourceVideoSpec(1080, 1080, 60f)), 15_000,
            ExportQualitySettings(resolution = ExportResolution.HD_720))
        assertFalse(result.supported)
    }

    @Test fun mixedPortraitAndLandscapeSourcesCannotSilentlyChangeFrameGeometry() {
        val input = listOf(SourceVideoSpec(1920, 1080, 30f),
            SourceVideoSpec(1080, 1920, 30f))
        val result = ExportQualityPolicy.evaluate(input, 20_000,
            ExportQualitySettings(quality = ExportQualityLevel.HIGH))
        assertFalse(result.supported)
        assertTrue(result.error!!.contains("방향"))
    }

    @Test fun qualityLevelsRemainDistinctAt240p() {
        val input = listOf(SourceVideoSpec(320, 240, 30f))
        val low = ExportQualityPolicy.evaluate(input, 20_000,
            ExportQualitySettings(quality = ExportQualityLevel.LOW)).plan!!
        val high = ExportQualityPolicy.evaluate(input, 20_000,
            ExportQualitySettings(quality = ExportQualityLevel.HIGH)).plan!!
        assertTrue(high.bitrate > low.bitrate)
        assertTrue(high.estimatedBytes > low.estimatedBytes)
    }

    @Test fun qualityAndFpsHavePredictableNonzeroEstimates() {
        val low = ExportQualityPolicy.evaluate(listOf(hd), 30_000,
            ExportQualitySettings(resolution = ExportResolution.HD_720,
                quality = ExportQualityLevel.LOW)).plan!!
        val high = ExportQualityPolicy.evaluate(listOf(hd), 30_000,
            ExportQualitySettings(resolution = ExportResolution.HD_720,
                frameRate = ExportFrameRate.FPS_60,
                quality = ExportQualityLevel.HIGH)).plan!!
        assertTrue(high.estimatedBytes > low.estimatedBytes)
        assertTrue(high.bitrate > low.bitrate)
        assertTrue(high.estimatedBytes >= 1_048_576)
    }

    @Test fun explicitQualityWithoutResolutionKeepsOriginalShortSide() {
        val result = ExportQualityPolicy.evaluate(listOf(hd), 1_000,
            ExportQualitySettings(quality = ExportQualityLevel.BALANCED))
        assertEquals(1080, result.plan!!.shortSide)
    }
}
