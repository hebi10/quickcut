package com.hebi.quickcut.media

import java.io.IOException
import org.junit.Assert.assertThrows
import org.junit.Test

class ExportValidationTest {
    @Test
    fun acceptsTheThreeHundredMillisecondDurationBoundary() {
        ExportValidation.requireMetadata(true, 1_700L, true, 2_000L, true)
        ExportValidation.requireMetadata(true, 2_300L, true, 2_000L, true)
    }

    @Test
    fun rejectsAResultOutsideTheDurationBoundary() {
        assertThrows(IOException::class.java) {
            ExportValidation.requireMetadata(true, 1_699L, true, 2_000L, true)
        }
        assertThrows(IOException::class.java) {
            ExportValidation.requireMetadata(true, 2_301L, true, 2_000L, true)
        }
    }

    @Test
    fun rejectsMissingExpectedAudio() {
        assertThrows(IOException::class.java) {
            ExportValidation.requireMetadata(true, 2_000L, false, 2_000L, true)
        }
    }

    @Test
    fun rejectsAudioInAMutedOutput() {
        assertThrows(IOException::class.java) {
            ExportValidation.requireMetadata(true, 2_000L, true, 2_000L, false)
        }
    }

    @Test
    fun acceptsAMutedOneHundredMillisecondOutput() {
        ExportValidation.requireMetadata(true, 100L, false, 100L, false)
    }

    @Test
    fun rejectsMissingVideoAndEmptyDuration() {
        assertThrows(IOException::class.java) {
            ExportValidation.requireMetadata(false, 2_000L, true)
        }
        assertThrows(IOException::class.java) {
            ExportValidation.requireMetadata(true, 0L, true)
        }
    }

    @Test
    fun originalDateAllowsMp4SecondRounding() {
        ExportValidation.requireTimestamp(1_609_459_200_999L, 1_609_459_200_000L)
    }

    @Test
    fun rejectsMissingOrChangedOriginalDate() {
        assertThrows(IOException::class.java) {
            ExportValidation.requireTimestamp(1_609_459_200_000L, null)
        }
        assertThrows(IOException::class.java) {
            ExportValidation.requireTimestamp(1_609_459_200_000L, 1_609_459_203_001L)
        }
    }
}
