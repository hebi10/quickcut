package com.hebi.quickcut.batch

import java.util.concurrent.CancellationException

/** A later automatic write cannot replace explicit verification of an unrecorded publication. */
internal class BatchCompletionJournal {
    var requiresRecovery: Boolean = false
        private set

    fun record(persist: () -> Unit): Boolean = try {
        persist()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        requiresRecovery = true
        false
    }

    /** Call only after checking every tracked output and durably recording that result. */
    fun recovered() { requiresRecovery = false }
}
