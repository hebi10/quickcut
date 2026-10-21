package com.hebi.quickcut.ui

import com.hebi.quickcut.media.ExportMethod
import com.hebi.quickcut.media.ExportReport
import com.hebi.quickcut.media.ExportDiagnostics
import com.hebi.quickcut.media.FastExportReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportReportTextTest {
    @Test
    fun fullEncodingShowsBothObservedExclusions() {
        val text = exportReportText(ExportReport(ExportMethod.VIDEO_REENCODE, 100L,
            diagnostics = ExportDiagnostics(FastExportReason.KEYFRAME, FastExportReason.UNSAFE_SPLICE)))
        assertTrue(text.contains("원본 복사 제한: 자르기 시작 위치가 키프레임"))
        assertTrue(text.contains("고속 부분 처리 제한: 구간 연결 지점에서 프레임이 누락"))
        assertFalse(text.contains("기록 없음"))
    }

    @Test
    fun successfulPartialProcessingDoesNotDisplayFailedAlternativeAsAFullFallback() {
        val text = exportReportText(ExportReport(ExportMethod.PARTIAL_REENCODE, 100L,
            diagnostics = ExportDiagnostics(FastExportReason.KEYFRAME, FastExportReason.UNKNOWN)))
        assertTrue(text.contains("원본 복사 제한:"))
        assertFalse(text.contains("고속 부분 처리 제한:"))
    }

    @Test
    fun unknownFutureDiagnosticRemainsUnknownAndMissingHistoryStaysMissing() {
        assertEquals(FastExportReason.UNKNOWN, FastExportReason.fromNativeCode(999L))
        assertEquals(FastExportReason.UNKNOWN, FastExportReason.fromStoredName("FUTURE_REASON"))
        assertEquals(null, FastExportReason.fromStoredName(null))
        assertTrue(ExportDiagnostics(smartCutReason = FastExportReason.UNKNOWN)
            .explanation().contains("원인 확인 불가"))
    }

    @Test
    fun olderFullEncodingResultDoesNotInventAnExclusionReason() {
        val text = exportReportText(ExportReport(ExportMethod.VIDEO_REENCODE, 100L))
        assertTrue(text.contains("전체 영상 재인코딩"))
        assertTrue(text.contains("고속 처리 제외 사유: 기록 없음"))
        assertFalse(text.contains("키프레임"))
    }

    @Test
    fun successfulCopyDoesNotClaimThatFastProcessingWasUnavailable() {
        val text = exportReportText(ExportReport(ExportMethod.ORIGINAL_COPY, 100L))
        assertTrue(text.contains("원본 데이터 복사"))
        assertFalse(text.contains("제외 사유"))
    }
}
