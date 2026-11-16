package com.hebi.quickcut.media

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ExportQualityVerificationTest {
    private val landscape = ResolvedExportQuality(
        shortSide = 720, width = 1280, height = 720,
        targetFps = 30, bitrate = 2_000_000, estimatedBytes = 1_048_576,
    )
    private val portrait = landscape.copy(width = 720, height = 1280)

    @Test fun twoAndThreePresentationTimestampsVerifyShortThirtyFpsOutput() {
        val two = ExportQualityVerification.frameRateFromTimestamps(listOf(0L, 33_333L))
        val three = ExportQualityVerification.frameRateFromTimestamps(
            listOf(66_666L, 0L, 33_333L))
        assertEquals(30f, two!!, 0.1f)
        assertEquals(30f, three!!, 0.1f)
        ExportQualityVerification.requireFrameRate(30, two)
        ExportQualityVerification.requireFrameRate(30, three)
    }

    @Test fun sixtyFpsAndFractionalClockRatesRemainWithinTheVerificationTolerance() {
        val measured = ExportQualityVerification.frameRateFromTimestamps(
            listOf(0L, 16_683L, 33_367L, 50_050L))
        assertEquals(59.94f, measured!!, 0.1f)
        ExportQualityVerification.requireFrameRate(60, measured)
    }

    @Test fun oneFrameDuplicateTimestampsAndMissingEvidenceFailClosed() {
        assertNull(ExportQualityVerification.frameRateFromTimestamps(emptyList()))
        assertNull(ExportQualityVerification.frameRateFromTimestamps(listOf(0L)))
        assertNull(ExportQualityVerification.frameRateFromTimestamps(listOf(0L, 0L)))
        assertNull(ExportQualityVerification.frameRateFromTimestamps(listOf(-1L, 33_333L)))
        assertThrows(IOException::class.java) {
            ExportQualityVerification.requireFrameRate(30, null)
        }
        assertThrows(IOException::class.java) {
            ExportQualityVerification.requireFrameRate(30, Float.NaN)
        }
        assertThrows(IOException::class.java) {
            ExportQualityVerification.requireFrameRate(30, 60f)
        }
    }

    @Test fun geometryVerifiesBothDimensionsAndQuarterTurnRotation() {
        ExportQualityVerification.requireGeometry(landscape, 1280, 720, 0)
        ExportQualityVerification.requireGeometry(portrait, 720, 1280, 0)
        ExportQualityVerification.requireGeometry(portrait, 1280, 720, 90)
        ExportQualityVerification.requireGeometry(portrait, 1280, 720, 270)
        ExportQualityVerification.requireGeometry(landscape, 720, 1280, -90)
    }

    @Test fun geometryRejectsCorrectShortSideWithWrongLongSideOrDirection() {
        assertThrows(IOException::class.java) {
            ExportQualityVerification.requireGeometry(landscape, 1000, 720, 0)
        }
        assertThrows(IOException::class.java) {
            ExportQualityVerification.requireGeometry(portrait, 1280, 720, 0)
        }
        assertThrows(IOException::class.java) {
            ExportQualityVerification.requireGeometry(landscape, 720, 1280, 0)
        }
    }

    @Test fun geometryRejectsAspectDistortionEvenWhenEachDimensionIsWithinTolerance() {
        assertThrows(IOException::class.java) {
            ExportQualityVerification.requireGeometry(landscape, 1296, 704, 0)
        }
    }

    @Test fun geometryRejectsUnknownRotationAndInvalidDimensions() {
        assertThrows(IOException::class.java) {
            ExportQualityVerification.requireGeometry(landscape, 1280, 720, 45)
        }
        assertThrows(IOException::class.java) {
            ExportQualityVerification.requireGeometry(landscape, 0, 720, 0)
        }
    }
}
