package com.hebi.quickcut.batch

import org.junit.Assert.assertEquals
import org.junit.Test

class BatchRecoveryPolicyTest {
    @Test fun publishedVerifiedOutputIsAdoptedAfterInterruptedCommit() {
        assertEquals(BatchRecoveryAction.ADOPT_SAVED,
            BatchRecoveryPolicy.decide(false, BatchOutputState.VERIFIED))
    }

    @Test fun previouslySavedOutputIsKeptWithoutExportingItAgain() {
        assertEquals(BatchRecoveryAction.KEEP_SAVED,
            BatchRecoveryPolicy.decide(true, BatchOutputState.VERIFIED))
    }

    @Test fun pendingInterruptedOutputIsRemovedBeforeRetry() {
        assertEquals(BatchRecoveryAction.REMOVE_PENDING,
            BatchRecoveryPolicy.decide(false, BatchOutputState.PENDING))
    }

    @Test fun missingOutputRequiresExplicitRetryAndUnknownOutputRemainsBlocked() {
        assertEquals(BatchRecoveryAction.RETRY,
            BatchRecoveryPolicy.decide(true, BatchOutputState.ABSENT))
        assertEquals(BatchRecoveryAction.BLOCKED,
            BatchRecoveryPolicy.decide(false, BatchOutputState.INVALID))
    }
}
