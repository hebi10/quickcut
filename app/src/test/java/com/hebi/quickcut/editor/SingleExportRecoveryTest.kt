package com.hebi.quickcut.editor

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class SingleExportRecoveryTest {
    private val record = SingleExportSnapshot("content://media/external/video/media/42", 2_000L, true)

    @Test fun noJournalLeavesEditorReady() {
        val journal = MemoryJournal(null)
        val result = SingleExportRecovery(journal, Output()).recover()
        assertEquals(SingleExportRecoveryResult.NothingToRecover, result)
    }

    @Test fun interruptedBeforeOutputCreationPreservesRetry() {
        val journal = MemoryJournal(record.copy(outputUri = null))
        assertEquals(SingleExportRecoveryResult.RetryAllowed,
            SingleExportRecovery(journal, Output()).recover())
        assertNull(journal.saved)
    }

    @Test fun confirmedMissingOutputAllowsRetryAndClearsJournal() {
        val journal = MemoryJournal(record)
        assertEquals(SingleExportRecoveryResult.RetryAllowed,
            SingleExportRecovery(journal, Output(SingleExportOutputState.ABSENT)).recover())
        assertNull(journal.saved)
    }

    @Test fun ownedPendingOutputIsRemovedBeforeRetry() {
        val journal = MemoryJournal(record)
        val output = Output(SingleExportOutputState.OWNED_PENDING)
        assertEquals(SingleExportRecoveryResult.RetryAllowed,
            SingleExportRecovery(journal, output).recover())
        assertFalse(output.exists)
        assertNull(journal.saved)
    }

    @Test fun publishedBeforeCompletionJournalIsAdoptedAndRetainedUntilSessionIsCleared() {
        val journal = MemoryJournal(record)
        val output = Output(SingleExportOutputState.VERIFIED)
        assertEquals(SingleExportRecoveryResult.Published(record),
            SingleExportRecovery(journal, output).recover())
        assertTrue(output.exists)
        assertEquals(record, journal.saved)
    }

    @Test fun foreignOrUnverifiedOutputBlocksRetryAndRetainsJournal() {
        val journal = MemoryJournal(record)
        val output = Output(SingleExportOutputState.UNCERTAIN)
        assertTrue(SingleExportRecovery(journal, output).recover() is SingleExportRecoveryResult.Blocked)
        assertTrue(output.exists)
        assertEquals(record, journal.saved)
    }

    @Test fun failedProviderQueryCannotBecomeConfirmedAbsence() {
        val journal = MemoryJournal(record)
        val output = Output().apply { readFailure = SecurityException("Access denied") }
        assertTrue(SingleExportRecovery(journal, output).recover() is SingleExportRecoveryResult.Blocked)
        assertEquals(record, journal.saved)
    }

    @Test fun failedPendingCleanupKeepsOnlyKnownOutputForRetry() {
        val journal = MemoryJournal(record)
        val output = Output(SingleExportOutputState.OWNED_PENDING).apply { removeFailure = IOException("Cleanup failed") }
        assertTrue(SingleExportRecovery(journal, output).recover() is SingleExportRecoveryResult.Blocked)
        assertEquals(record, journal.saved)
        assertTrue(output.exists)
        output.removeFailure = null
        assertEquals(SingleExportRecoveryResult.RetryAllowed, SingleExportRecovery(journal, output).recover())
        assertFalse(output.exists)
        assertNull(journal.saved)
    }

    @Test fun corruptOrUnreadableJournalBlocksRetryWithoutGuessing() {
        val journal = MemoryJournal(record).apply { readFailure = IOException("Corrupt journal") }
        assertTrue(SingleExportRecovery(journal, Output()).recover() is SingleExportRecoveryResult.Blocked)
        assertEquals(record, journal.saved)
    }

    @Test fun failureToClearJournalStillBlocksRetryAfterOutputCleanup() {
        val journal = MemoryJournal(record).apply { clearFailure = IOException("Read-only storage") }
        val output = Output(SingleExportOutputState.OWNED_PENDING)
        assertTrue(SingleExportRecovery(journal, output).recover() is SingleExportRecoveryResult.Blocked)
        assertFalse(output.exists)
        assertEquals(record, journal.saved)
        journal.clearFailure = null
        assertEquals(SingleExportRecoveryResult.RetryAllowed, SingleExportRecovery(journal, output).recover())
        assertNull(journal.saved)
    }

    private class MemoryJournal(var saved: SingleExportSnapshot?) : SingleExportJournal {
        var readFailure: Exception? = null
        var clearFailure: Exception? = null
        override fun load(): SingleExportSnapshot? { readFailure?.let { throw it }; return saved }
        override fun save(snapshot: SingleExportSnapshot) { saved = snapshot }
        override fun clear() { clearFailure?.let { throw it }; saved = null }
    }

    private class Output(private val initialState: SingleExportOutputState = SingleExportOutputState.ABSENT) : SingleExportOutputAccess {
        var exists = initialState != SingleExportOutputState.ABSENT
        var readFailure: Exception? = null
        var removeFailure: Exception? = null
        override fun inspect(snapshot: SingleExportSnapshot): SingleExportOutputState {
            readFailure?.let { throw it }
            return if (exists) initialState else SingleExportOutputState.ABSENT
        }
        override fun removePending(snapshot: SingleExportSnapshot) {
            removeFailure?.let { throw it }
            check(initialState == SingleExportOutputState.OWNED_PENDING)
            exists = false
        }
    }
}
