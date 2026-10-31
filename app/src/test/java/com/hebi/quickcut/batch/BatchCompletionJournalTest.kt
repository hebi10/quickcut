package com.hebi.quickcut.batch

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class BatchCompletionJournalTest {
    @Test fun successfulCompletionAllowsNormalOperation() {
        val journal = BatchCompletionJournal()
        var recorded = false
        assertTrue(journal.record { recorded = true })
        assertTrue(recorded)
        assertFalse(journal.requiresRecovery)
    }

    @Test fun failedCompletionRequiresExplicitRecoveryEvenAfterLaterSuccessfulWrites() {
        val journal = BatchCompletionJournal()
        assertFalse(journal.record { throw IOException("Injected completion sync failure") })
        assertTrue(journal.requiresRecovery)
        assertTrue(journal.record {})
        assertTrue("A later automatic write cannot approve recovery", journal.requiresRecovery)
        journal.recovered()
        assertFalse(journal.requiresRecovery)
    }

    @Test fun cancellationPropagatesWithoutBecomingARecordingWarning() {
        val journal = BatchCompletionJournal()
        val cancelled = CancellationException("Cancelled")
        val failure = runCatching { journal.record { throw cancelled } }.exceptionOrNull()
        assertSame(cancelled, failure)
        assertFalse(journal.requiresRecovery)
    }

    @Test fun fatalErrorsAreNotDowngradedToRecordingWarnings() {
        val journal = BatchCompletionJournal()
        val fatal = AssertionError("Injected fatal error")
        val failure = runCatching { journal.record { throw fatal } }.exceptionOrNull()
        assertSame(fatal, failure)
        assertFalse(journal.requiresRecovery)
    }
}
