package com.hebi.quickcut.batch

import com.hebi.quickcut.editor.SingleExportOutputAccess
import com.hebi.quickcut.editor.SingleExportOutputState
import com.hebi.quickcut.editor.SingleExportSnapshot
import org.junit.Assert.*
import org.junit.Test

class BatchFileRecoveryTest {
    private val row = BatchFile("content://source/1", "source.mp4", 3_000L, true,
        status = BatchFileStatus.RUNNING, savedOutputUri = "content://media/external/video/media/1",
        outputExpectedDurationMs = 2_000L, outputExpectedAudio = true)

    @Test fun uncertainOutputRetainsHandleContractAndSourceMetadata() {
        val output = Output(SingleExportOutputState.UNCERTAIN)
        val recovered = BatchFileRecovery(output) { error("Must not refresh uncertain output") }.recover(row)
        assertEquals(row.savedOutputUri, recovered.savedOutputUri)
        assertEquals(row.outputExpectedDurationMs, recovered.outputExpectedDurationMs)
        assertEquals(row.durationMs, recovered.durationMs)
        assertTrue(recovered.hasUnresolvedOutput())
        assertFalse(output.removed)
    }

    @Test fun queryFailureRetainsHandleInsteadOfAllowingDuplicateWriter() {
        val output = Output(SingleExportOutputState.ABSENT).apply { failInspect = true }
        val recovered = BatchFileRecovery(output) { it }.recover(row)
        assertEquals(row.savedOutputUri, recovered.savedOutputUri)
        assertTrue(recovered.hasUnresolvedOutput())
    }

    @Test fun missingValidationContractDoesNotAdoptOrDeleteOutput() {
        val output = Output(SingleExportOutputState.VERIFIED)
        val recovered = BatchFileRecovery(output) { it }.recover(row.copy(outputExpectedAudio = null))
        assertEquals(0, output.inspections)
        assertTrue(recovered.hasUnresolvedOutput())
        assertFalse(output.removed)
    }

    @Test fun verifiedOutputIsAdoptedWithoutRefreshingOrDuplicating() {
        val output = Output(SingleExportOutputState.VERIFIED)
        val recovered = BatchFileRecovery(output) { error("No source read for published video") }.recover(row)
        assertEquals(BatchFileStatus.SAVED, recovered.status)
        assertEquals(row.savedOutputUri, recovered.savedOutputUri)
        assertFalse(recovered.hasUnresolvedOutput())
        assertFalse(output.removed)
    }

    @Test fun cleanupFailureKeepsHandleUntilDeletionIsConfirmed() {
        val output = Output(SingleExportOutputState.OWNED_PENDING).apply { failRemove = true }
        val recovered = BatchFileRecovery(output) { it }.recover(row)
        assertTrue(recovered.hasUnresolvedOutput())
        assertEquals(row.savedOutputUri, recovered.savedOutputUri)
    }

    @Test fun confirmedRemovalReleasesHandleButRequiresExplicitRetry() {
        val output = Output(SingleExportOutputState.OWNED_PENDING)
        val recovered = BatchFileRecovery(output) { it }.recover(row)
        assertTrue(output.removed)
        assertEquals(BatchFileStatus.SKIPPED, recovered.status)
        assertNull(recovered.savedOutputUri)
        assertNull(recovered.outputExpectedDurationMs)
        assertFalse(recovered.hasUnresolvedOutput())
    }

    @Test fun confirmedAbsenceClearsOutputEvenWhenOriginalCannotBeRead() {
        val recovered = BatchFileRecovery(Output(SingleExportOutputState.ABSENT)) { null }.recover(row)
        assertNull(recovered.savedOutputUri)
        assertEquals(0L, recovered.durationMs)
    }

    private class Output(private val state: SingleExportOutputState) : SingleExportOutputAccess {
        var inspections = 0
        var failInspect = false
        var failRemove = false
        var removed = false
        override fun inspect(snapshot: SingleExportSnapshot): SingleExportOutputState {
            inspections++
            if (failInspect) throw SecurityException("provider denied")
            return state
        }
        override fun removePending(snapshot: SingleExportSnapshot) {
            if (failRemove) throw java.io.IOException("cleanup unconfirmed")
            removed = true
        }
    }
}
