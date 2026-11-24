package com.hebi.quickcut.batch

import com.hebi.quickcut.media.SaveDateMode

internal data class BatchPreviewCounts(val ready: Int, val unavailable: Int)

internal object BatchPreviewEstimate {
    private fun plan(file: BatchFile, template: BatchTemplate, dateMode: SaveDateMode): BatchPlan? =
        if (file.hasUnresolvedOutput() || file.durationMs < 100L ||
            (dateMode == SaveDateMode.ORIGINAL && file.originalDate == null)) null
        else template.plan(file)

    fun counts(files: List<BatchFile>, template: BatchTemplate?, dateMode: SaveDateMode): BatchPreviewCounts? {
        if (template == null) return null
        val pending = files.filter { it.status != BatchFileStatus.SAVED }
        val ready = pending.count { plan(it, template, dateMode) is BatchPlan.Ready }
        return BatchPreviewCounts(ready, pending.size - ready)
    }

    fun fileLabel(file: BatchFile, template: BatchTemplate?, dateMode: SaveDateMode): String {
        val source = BatchTimeInput.durationText(file.durationMs)
        if (file.status == BatchFileStatus.SAVED) {
            return file.outputExpectedDurationMs?.let { duration ->
                "저장 결과 · " + source + " → " + BatchTimeInput.durationText(duration)
            } ?: "저장 완료 · 결과 길이를 확인할 수 없습니다."
        }
        if (file.hasUnresolvedOutput()) return "이전 저장 결과를 먼저 확인해 주세요."
        if (file.durationMs < 100L) return "저장 불가 · " + (file.reason ?: "영상 정보를 읽을 수 없습니다.")
        if (template == null) return "시간 설정을 확인하면 예상 결과를 표시합니다."
        if (dateMode == SaveDateMode.ORIGINAL && file.originalDate == null)
            return "저장 불가 · 원본 날짜를 확인할 수 없습니다."
        return when (val result = plan(file, template, dateMode)) {
            is BatchPlan.Ready -> "예상 결과 · " + source + " → " +
                BatchTimeInput.durationText(result.timeline.totalDurationMs)
            is BatchPlan.Skip -> "저장 불가 · " + result.reason
            null -> "예상 결과를 확인할 수 없습니다."
        }
    }
}
