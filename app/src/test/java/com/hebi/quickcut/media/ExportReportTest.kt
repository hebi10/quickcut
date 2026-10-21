package com.hebi.quickcut.media

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportResult
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(UnstableApi::class)
class ExportReportTest {
    @Test
    fun copiedVideoWithEncodedAudioMustNotBeReportedAsAllOriginalCopy() {
        assertEquals(ExportMethod.AUDIO_REENCODE, ExportReportMapper.method(
            ExportResult.CONVERSION_PROCESS_TRANSMUXED,
            ExportResult.CONVERSION_PROCESS_TRANSCODED,
        ))
    }

    @Test
    fun partialVideoProcessingRemainsPartialEvenWhenAudioWasEncoded() {
        assertEquals(ExportMethod.PARTIAL_REENCODE, ExportReportMapper.method(
            ExportResult.CONVERSION_PROCESS_TRANSMUXED_AND_TRANSCODED,
            ExportResult.CONVERSION_PROCESS_TRANSCODED,
        ))
    }

    @Test
    fun fullyEncodedVideoMustBeDistinguishedFromPartialProcessing() {
        assertEquals(ExportMethod.VIDEO_REENCODE, ExportReportMapper.method(
            ExportResult.CONVERSION_PROCESS_TRANSCODED,
            ExportResult.CONVERSION_PROCESS_TRANSMUXED,
        ))
    }

    @Test
    fun copiedVideoWithCopiedOrRemovedAudioIsOriginalCopy() {
        for (audio in listOf(ExportResult.CONVERSION_PROCESS_TRANSMUXED, ExportResult.CONVERSION_PROCESS_NA)) {
            assertEquals(ExportMethod.ORIGINAL_COPY, ExportReportMapper.method(
                ExportResult.CONVERSION_PROCESS_TRANSMUXED, audio,
            ))
        }
    }

    @Test
    fun missingVideoOrUnknownProcessingMustNeverClaimFastCopy() {
        assertEquals(ExportMethod.UNKNOWN, ExportReportMapper.method(
            ExportResult.CONVERSION_PROCESS_NA, ExportResult.CONVERSION_PROCESS_TRANSMUXED,
        ))
        assertEquals(ExportMethod.UNKNOWN, ExportReportMapper.method(
            ExportResult.CONVERSION_PROCESS_TRANSMUXED, 99,
        ))
    }
}
