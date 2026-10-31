package com.hebi.quickcut.media

import android.content.Context
import java.io.FileNotFoundException

/** Optional, source-identifier-free records for failures before an export starts. */
internal object MediaOperationDiagnostics {
    fun begin(context: Context, stage: ExportStage, sourceCount: Int = 0): ExportDiagnosticSession? =
        runCatching { ExportDiagnosticStore.beginOperation(context, stage, sourceCount) }.getOrNull()

    fun recordFailure(context: Context, stage: ExportStage, error: Throwable, sourceCount: Int = 0): String? =
        runCatching {
            begin(context, stage, sourceCount)?.also { it.finish("실패", error) }?.operationId
        }.getOrNull()

    fun message(stage: ExportStage, error: Throwable): String {
        val causes = ExportFailureEvidence.boundedCauses(error)
        val evidence = ExportFailureEvidence.capture(error)
        return when {
            causes.any { it is SecurityException } || evidence?.playbackErrorCode in listOf(2006, -4) ->
                "원본 영상의 읽기 권한을 사용할 수 없습니다. 같은 원본을 다시 선택해 주세요."
            causes.any { it is FileNotFoundException } || evidence?.playbackErrorCode == 2005 ->
                "원본 영상을 읽을 수 없습니다. 저장소·연결 상태를 확인하고 같은 원본을 다시 선택해 주세요."
            stage == ExportStage.PREVIEWING ->
                "미리보기를 재생하지 못했습니다. 미리보기 다시 시도로 재생 자원을 다시 준비할 수 있습니다." +
                    (evidence?.playbackErrorCode?.let { " 오류 코드 $it." } ?: "")
            stage == ExportStage.RESTORING ->
                "이전 원본에 접근하거나 영상 정보를 확인하지 못했습니다. 편집 기록은 보관했습니다. 같은 원본을 다시 선택해 주세요."
            stage == ExportStage.REVIEWING ->
                "저장 방식을 확인하지 못했습니다. 작업 진단에서 실패 단계를 확인하고 다시 시도해 주세요."
            else -> "영상 정보를 읽지 못했습니다. 원본 접근과 연결 상태를 확인한 뒤 다시 선택해 주세요."
        }
    }
}
