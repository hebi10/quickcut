package com.hebi.quickcut.media

import org.junit.Assert.*
import org.junit.Test

class AnalysisBudgetReasonTest {
    @Test fun budgetDeclineSurvivesNativeAndStoredDiagnosticMapping() {
        val reason = FastExportReason.fromNativeCode(22)
        assertEquals(FastExportReason.ANALYSIS_BUDGET, reason)
        assertEquals(reason, FastExportReason.fromStoredName(reason.name))
        assertTrue(ExportDiagnostics(smartCutReason = reason).explanation().contains(reason.explanation))
        assertEquals(FastExportReason.UNKNOWN, FastExportReason.fromNativeCode(999))
    }
}
