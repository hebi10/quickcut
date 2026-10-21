package com.hebi.quickcut.editor

data class SingleExportSnapshot(
    val outputUri: String?,
    val expectedDurationMs: Long,
    val expectedAudio: Boolean,
    val expectedDateMs: Long? = null,
    val expectedDurationUs: Long = Math.multiplyExact(expectedDurationMs, 1_000L),
) {
    init {
        require(outputUri == null || outputUri.isNotBlank())
        require(expectedDurationMs >= 100L)
        require(expectedDurationUs >= 100_000L && expectedDurationMs == expectedDurationUs / 1_000L +
            if (expectedDurationUs % 1_000L == 0L) 0L else 1L)
        require(expectedDateMs == null || expectedDateMs > 0L)
    }
}

interface SingleExportJournal {
    fun load(): SingleExportSnapshot?
    fun save(snapshot: SingleExportSnapshot)
    fun clear()
}

enum class SingleExportOutputState { ABSENT, OWNED_PENDING, VERIFIED, UNCERTAIN }

interface SingleExportOutputAccess {
    fun inspect(snapshot: SingleExportSnapshot): SingleExportOutputState
    /** Must verify ownership again and confirm removal; otherwise throw. */
    fun removePending(snapshot: SingleExportSnapshot)
}

sealed interface SingleExportRecoveryResult {
    data object NothingToRecover : SingleExportRecoveryResult
    data object RetryAllowed : SingleExportRecoveryResult
    data class Published(val snapshot: SingleExportSnapshot) : SingleExportRecoveryResult
    data class Blocked(val message: String) : SingleExportRecoveryResult
}

class SingleExportRecovery(
    private val journal: SingleExportJournal,
    private val output: SingleExportOutputAccess,
) {
    fun recover(): SingleExportRecoveryResult = try {
        val snapshot = journal.load()
        if (snapshot == null) {
            SingleExportRecoveryResult.NothingToRecover
        } else {
            val state = if (snapshot.outputUri == null) SingleExportOutputState.ABSENT
                else output.inspect(snapshot)
            when (state) {
                SingleExportOutputState.VERIFIED -> SingleExportRecoveryResult.Published(snapshot)
                SingleExportOutputState.UNCERTAIN -> SingleExportRecoveryResult.Blocked(
                    "이전 저장 파일의 접근 권한이나 소유자를 확인할 수 없습니다. 파일 접근을 확인한 뒤 다시 확인해 주세요.",
                )
                SingleExportOutputState.ABSENT, SingleExportOutputState.OWNED_PENDING -> {
                    if (state == SingleExportOutputState.OWNED_PENDING) output.removePending(snapshot)
                    journal.clear()
                    SingleExportRecoveryResult.RetryAllowed
                }
            }
        }
    } catch (_: Exception) {
        SingleExportRecoveryResult.Blocked(
            "이전 저장 확인 또는 미완성 파일 정리를 완료하지 못했습니다. 저장 공간과 파일 접근을 확인한 뒤 다시 확인해 주세요.",
        )
    }
}
