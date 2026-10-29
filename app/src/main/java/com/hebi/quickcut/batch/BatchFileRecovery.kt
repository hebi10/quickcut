package com.hebi.quickcut.batch

import android.content.Context
import android.net.Uri
import com.hebi.quickcut.editor.MediaStoreSingleExportAccess
import com.hebi.quickcut.editor.SingleExportOutputAccess
import com.hebi.quickcut.editor.SingleExportOutputState
import com.hebi.quickcut.editor.SingleExportSnapshot
import com.hebi.quickcut.media.MediaReader
import com.hebi.quickcut.media.OriginalMediaDates

/** Shared single/batch output ownership, deletion and media validation contract. */
class BatchFileRecovery(
    private val output: SingleExportOutputAccess,
    private val refreshSource: (BatchFile) -> BatchFile?,
) {
    constructor(context: Context) : this(MediaStoreSingleExportAccess(context), { row ->
        val uri = Uri.parse(row.uri)
        val metadata = MediaReader.metadata(context, uri)
        row.copy(durationMs = metadata.durationMs, hasAudio = metadata.hasAudio,
            originalDate = OriginalMediaDates.resolve(context, uri))
    })

    fun recover(row: BatchFile): BatchFile {
        if (row.savedOutputUri != null) {
            try {
                // Older records without a complete validation contract are uncertain.
                val snapshot = SingleExportSnapshot(row.savedOutputUri,
                    requireNotNull(row.outputExpectedDurationMs),
                    requireNotNull(row.outputExpectedAudio), row.outputExpectedDateMs)
                val state = when (output.inspect(snapshot)) {
                    SingleExportOutputState.VERIFIED -> BatchOutputState.VERIFIED
                    SingleExportOutputState.UNCERTAIN -> BatchOutputState.INVALID
                    SingleExportOutputState.OWNED_PENDING -> BatchOutputState.PENDING
                    SingleExportOutputState.ABSENT -> BatchOutputState.ABSENT
                }
                when (BatchRecoveryPolicy.decide(row.status == BatchFileStatus.SAVED, state)) {
                    BatchRecoveryAction.KEEP_SAVED, BatchRecoveryAction.ADOPT_SAVED -> return row.copy(
                        status = BatchFileStatus.SAVED, progress = 100, reason = null)
                    BatchRecoveryAction.BLOCKED -> return blocked(row)
                    BatchRecoveryAction.REMOVE_PENDING -> output.removePending(snapshot)
                    BatchRecoveryAction.RETRY -> Unit
                }
            } catch (_: Exception) {
                return blocked(row)
            }
        }
        // Only confirmed absence/removal permits forgetting the old output handle.
        val refreshed = runCatching { refreshSource(row) }.getOrNull()
        return (refreshed ?: row.copy(durationMs = 0L)).copy(
            status = BatchFileStatus.SKIPPED, savedOutputUri = null, progress = null,
            outputExpectedDurationMs = null, outputExpectedAudio = null, outputExpectedDateMs = null,
            reason = if (refreshed == null) "원본에 접근할 수 없습니다. 영상을 다시 선택해 주세요."
                else "이전 작업이 중단됐습니다. 설정 확인 후 다시 시작해 주세요.",
            exportReport = null)
    }

    private fun blocked(row: BatchFile) = row.copy(status = BatchFileStatus.SKIPPED,
        reason = "이전 출력의 소유권이나 완료 상태를 확인할 수 없습니다. 파일 접근을 확인한 뒤 다시 시도해 주세요.",
        progress = null, exportReport = null)
}

internal fun BatchFile.hasUnresolvedOutput(): Boolean =
    savedOutputUri != null && status != BatchFileStatus.SAVED
