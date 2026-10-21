package com.hebi.quickcut.media

import org.junit.Assert.*
import org.junit.Test

class ExportDiagnosticFactsTest {
    @Test fun reportRecordsSpecificStageAndSafeFactsWithoutAcceptingPathsOrMessages() {
        val facts = ExportDiagnosticFacts("0.4.10", "Device Model", 36, 1, 10_800_000, 7_000_001, 2,
            videoMime = "video/hevc", width = 3840, height = 2160, frameRate = 59.94,
            audioMime = "audio/mp4a-latm", audioChannels = 6, cacheAvailableBytes = 1234)
        val text = facts.text(ExportStage.VALIDATING, "실패", 1000,
            ExportDiagnostics(smartCutReason = FastExportReason.OUTPUT_VALIDATION), null, "IOException")
        assertTrue(text.contains("저장 결과 검사 중"))
        assertTrue(text.contains("3840×2160"))
        assertTrue(text.contains("OUTPUT_VALIDATION"))
        assertTrue(text.contains("7000001"))
        assertFalse(text.contains("content://"))
    }
    @Test fun untrustedMimeCannotInjectProviderContentIntoTheReport() {
        val text = ExportDiagnosticFacts("0.4.10", "Device", 36, 1, 1000, 1000000, 1,
            videoMime = "content://private/secret.mp4\npassword").text(ExportStage.ANALYZING,
            "처리 중", 0, ExportDiagnostics(), null, null)
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("password"))
    }
}
