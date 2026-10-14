package com.hebi.quickcut.batch

enum class BatchOutputState { ABSENT, PENDING, VERIFIED, INVALID }
enum class BatchRecoveryAction { KEEP_SAVED, ADOPT_SAVED, REMOVE_PENDING, RETRY }

object BatchRecoveryPolicy {
    fun decide(wasSaved: Boolean, output: BatchOutputState): BatchRecoveryAction =
        when (output) {
            BatchOutputState.VERIFIED -> if (wasSaved) BatchRecoveryAction.KEEP_SAVED
                else BatchRecoveryAction.ADOPT_SAVED
            BatchOutputState.PENDING -> BatchRecoveryAction.REMOVE_PENDING
            BatchOutputState.ABSENT, BatchOutputState.INVALID -> BatchRecoveryAction.RETRY
        }
}
