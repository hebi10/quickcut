package com.hebi.quickcut.media

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ExportDiagnosticHistoryTest {
    private fun record(status: ExportDiagnosticStatus = ExportDiagnosticStatus.COMPLETE) = ExportDiagnosticRecord(
        UUID.randomUUID().toString(), status, ExportStage.VALIDATING, 40,
        mapOf(ExportStage.ANALYZING to 10L, ExportStage.VALIDATING to 30L),
        ExportDiagnosticFacts("0.4.10", "Device", 36, 1, 1000, 1000000, 1),
        ExportDiagnostics(), null, if (status == ExportDiagnosticStatus.FAILED) "IOException" else null)

    @Test fun failureSurvivesSuccessesAndRecentAndFailureListsHaveIndependentCaps() {
        val failed = record(ExportDiagnosticStatus.FAILED)
        var history = ExportDiagnosticHistory().updated(failed)
        repeat(ExportDiagnosticHistory.RECENT_LIMIT + 3) { history = history.updated(record()) }
        assertEquals(ExportDiagnosticHistory.RECENT_LIMIT, history.recent.size)
        assertFalse(history.recent.any { it.operationId == failed.operationId })
        assertEquals(failed, history.record(failed.operationId))
        repeat(ExportDiagnosticHistory.FAILURE_LIMIT + 2) { history = history.updated(record(ExportDiagnosticStatus.FAILED)) }
        assertEquals(ExportDiagnosticHistory.FAILURE_LIMIT, history.failures.size)
        assertNull(history.record(failed.operationId))
    }

    @Test fun sameIdUpdatesRatherThanDuplicatingAndLookupSelectsExactOperation() {
        val first = record(ExportDiagnosticStatus.RUNNING)
        val second = record(ExportDiagnosticStatus.FAILED)
        val finished = first.copy(status = ExportDiagnosticStatus.COMPLETE)
        val history = ExportDiagnosticHistory().updated(first).updated(second).updated(finished).updated(second)
        assertEquals(2, history.recent.size)
        assertEquals(1, history.failures.size)
        assertEquals(finished, history.record(first.operationId))
        assertEquals(second, history.record(second.operationId))
        assertNull(history.record("content://private/secret.mp4"))
    }

    @Test fun invalidRestoredRecordsAreDiscardedAndListsAreDeduplicatedAndBounded() {
        val valid = record(ExportDiagnosticStatus.FAILED)
        val invalid = valid.copy(operationId = "content://private/secret.mp4")
        val history = ExportDiagnosticHistory.restore(
            listOf(invalid, valid, valid, valid.copy(elapsedMs = -1)), listOf(valid, valid, record()))
        assertEquals(listOf(valid), history.recent)
        assertEquals(listOf(valid), history.failures)
    }

    @Test fun reportExposesOnlySafeOperationMetadataAndAggregatedStageDurations() {
        val report = record(ExportDiagnosticStatus.FAILED).text
        assertTrue(report.contains("원본 분석 중: 10"))
        assertTrue(report.contains("저장 결과 검사 중: 30"))
        assertFalse(report.contains("content://"))
        assertFalse(report.contains("/storage/"))
    }
    @Test fun untrustedErrorAndMimeCannotBePersisted() {
        val unsafeError = record(ExportDiagnosticStatus.FAILED).copy(failureType = "secret-source.mp4")
        val unsafeMime = record().let { it.copy(facts = it.facts.copy(videoMime = "content://private/secret.mp4")) }
        val history = ExportDiagnosticHistory().updated(unsafeError).updated(unsafeMime)
        assertTrue(history.recent.isEmpty())
        assertTrue(history.failures.isEmpty())
    }

}
