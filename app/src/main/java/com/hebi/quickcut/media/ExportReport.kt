package com.hebi.quickcut.media

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportResult

enum class ExportMethod {
    ORIGINAL_COPY,
    PARTIAL_REENCODE,
    VIDEO_REENCODE,
    AUDIO_REENCODE,
    UNKNOWN,
}

/** The observed processing method of a successfully published output, not a speed promise. */
data class ExportReport(
    val method: ExportMethod,
    val elapsedMs: Long,
    val optimizationResult: Int? = null,
)

@OptIn(UnstableApi::class)
internal object ExportReportMapper {
    fun method(videoProcess: Int, audioProcess: Int): ExportMethod = when {
        videoProcess == ExportResult.CONVERSION_PROCESS_TRANSMUXED_AND_TRANSCODED ->
            ExportMethod.PARTIAL_REENCODE
        videoProcess == ExportResult.CONVERSION_PROCESS_TRANSCODED ->
            ExportMethod.VIDEO_REENCODE
        videoProcess == ExportResult.CONVERSION_PROCESS_TRANSMUXED &&
            audioProcess in setOf(
                ExportResult.CONVERSION_PROCESS_TRANSCODED,
                ExportResult.CONVERSION_PROCESS_TRANSMUXED_AND_TRANSCODED,
            ) -> ExportMethod.AUDIO_REENCODE
        videoProcess == ExportResult.CONVERSION_PROCESS_TRANSMUXED &&
            audioProcess in setOf(
                ExportResult.CONVERSION_PROCESS_NA,
                ExportResult.CONVERSION_PROCESS_TRANSMUXED,
            ) -> ExportMethod.ORIGINAL_COPY
        else -> ExportMethod.UNKNOWN
    }
}
