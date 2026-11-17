package com.hebi.quickcut.batch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchQueueControlsTest {
    @Test fun importingOrRemovingFilesDisablesAllQueueMutations() {
        assertFalse(canModifyBatchQueue(isRunning = false, isRecovering = false, isImporting = true))
    }

    @Test fun ongoingExportAndRecoveryAlsoBlockMutations() {
        assertFalse(canModifyBatchQueue(isRunning = true, isRecovering = false, isImporting = false))
        assertFalse(canModifyBatchQueue(isRunning = false, isRecovering = true, isImporting = false))
        assertFalse(canModifyBatchQueue(isRunning = true, isRecovering = true, isImporting = true))
    }

    @Test fun completedQueueWorkRestoresAvailableActions() {
        assertTrue(canModifyBatchQueue(isRunning = false, isRecovering = false, isImporting = false))
    }
}
