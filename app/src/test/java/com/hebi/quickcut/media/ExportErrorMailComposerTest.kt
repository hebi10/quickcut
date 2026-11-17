package com.hebi.quickcut.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportErrorMailComposerTest {
    private fun failed() = ExportDiagnosticRecord(
        operationId = "b861d451-6c53-4d0b-9cec-147e025f5a0e",
        status = ExportDiagnosticStatus.FAILED,
        stage = ExportStage.VALIDATING,
        elapsedMs = 42L,
        stageMs = mapOf(ExportStage.VALIDATING to 42L),
        facts = ExportDiagnosticFacts(
            app = "0.4.21", device = "Android Test Device", androidApi = 36,
            sourceCount = 2, sourceDurationMs = 3000L, retainedDurationUs = 2400000L,
            clipCount = 2, videoMime = "video/avc", audioMime = "audio/mp4a-latm",
        ),
        reasons = ExportDiagnostics(),
        method = "VIDEO_REENCODE",
        failureType = "IOException",
    )

    @Test fun failedRecordGeneratesPreviewableEmailWithoutRawExceptionOrSource() {
        val mail = ExportErrorMailComposer.forFailure(failed())
        assertNotNull(mail)
        val report = checkNotNull(mail)
        assertTrue(report.recipient == ExportErrorMailComposer.SUPPORT_EMAIL)
        assertTrue(report.subject.contains("0.4.21"))
        assertTrue(report.subject.contains("IOException"))
        assertTrue(report.subject.contains("b861d451"))
        assertTrue(report.body.contains("Android API 36"))
        assertTrue(report.body.contains("오류 종류: IOException"))
        assertTrue(report.body.contains("원본 수: 2"))
        assertFalse(report.body.contains("content://"))
        assertFalse(report.body.contains("/storage/"))
        assertFalse(report.body.contains("at com.hebi"))
    }

    @Test fun nonFailureOrMalformedDiagnosticsCannotBecomeAnErrorReport() {
        assertNull(ExportErrorMailComposer.forFailure(failed().copy(status = ExportDiagnosticStatus.COMPLETE)))
        assertNull(ExportErrorMailComposer.forFailure(failed().copy(status = ExportDiagnosticStatus.CANCELLED)))
        assertNull(ExportErrorMailComposer.forFailure(failed().copy(failureType = "/storage/private/source.mp4")))
        assertNull(ExportErrorMailComposer.forFailure(
            failed().copy(facts = failed().facts.copy(device = "content://private/source.mp4")),
        ))
    }
}
