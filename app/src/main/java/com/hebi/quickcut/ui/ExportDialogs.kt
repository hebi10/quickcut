package com.hebi.quickcut.ui

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hebi.quickcut.editor.OriginalAction
import com.hebi.quickcut.media.ExportAssessment
import com.hebi.quickcut.media.ExportMethod
import com.hebi.quickcut.media.ExportReport
import com.hebi.quickcut.media.SaveDateMode

@Composable
internal fun SaveOptionsDialog(
    originalAction: OriginalAction,
    onOriginalActionChange: (OriginalAction) -> Unit,
    dateMode: SaveDateMode,
    onDateModeChange: (SaveDateMode) -> Unit,
    canSave: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("저장 설정") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("저장 완료 후 원본 동영상 처리", color = CutColors.primaryText)
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        OriginalAction.KEEP to "원본 유지 (기본값)",
                        OriginalAction.TRASH to "원본 휴지통으로 이동",
                        OriginalAction.DELETE to "원본 영구 삭제",
                    ).forEach { (action, label) ->
                        SaveChoiceRow(label, originalAction == action) { onOriginalActionChange(action) }
                    }
                }
                Text("저장 날짜", color = CutColors.primaryText)
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        SaveDateMode.CURRENT to "현재 날짜로 저장 (최신 영상)",
                        SaveDateMode.ORIGINAL to "원본 날짜 유지",
                    ).forEach { (mode, label) ->
                        SaveChoiceRow(label, dateMode == mode) { onDateModeChange(mode) }
                    }
                }
                if (dateMode == SaveDateMode.ORIGINAL) {
                    Text(
                        "원본 날짜를 유지하면서 가능한 원본 복사·부분 처리를 우선 사용합니다. " +
                            "날짜 기록을 위한 임시 저장 공간이 필요할 수 있습니다.",
                        color = CutColors.secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "여러 원본을 합친 경우 첫 영상 날짜를 사용합니다. 원본 날짜 정보가 없으면 " +
                        "해당 방식의 저장이 취소됩니다.",
                    color = CutColors.secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "저장이 실패하거나 취소되면 원본은 유지됩니다. " +
                        "활성화한 원본 처리 설정은 끌 때까지 유지되며 저장 성공 후 Android 승인이 필요합니다.",
                    color = CutColors.secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = canSave, shape = CutShape) { Text("저장 시작") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = CutShape) { Text("취소") }
        },
        containerColor = CutColors.panel,
        shape = CutShape,
    )
}

@Composable
internal fun OriginalActionEnableDialog(
    action: OriginalAction,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val permanent = action == OriginalAction.DELETE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("원본 자동 처리 활성화") },
        text = {
            Text(
                "새로 저장에 성공한 원본을 " +
                    (if (permanent) "영구 삭제" else "휴지통으로 이동") +
                    "하도록 설정합니다. 새 작업·재편집·앱 재시작 후에도 설정을 끌 때까지 유지됩니다.\n\n" +
                    "실패·취소·건너뜀 원본은 유지하며 매번 Android 승인이 필요합니다." +
                    if (permanent) "\n영구 삭제 후에는 복구하지 못할 수 있습니다." else "",
            )
        },
        confirmButton = { Button(onClick = onConfirm, shape = CutShape) { Text("활성화") } },
        dismissButton = { TextButton(onClick = onDismiss, shape = CutShape) { Text("취소") } },
        containerColor = CutColors.panel,
        shape = CutShape,
    )
}

@Composable
private fun SaveChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, color = CutColors.primaryText)
    }
}

@Composable
internal fun ExportModeDialog(
    assessment: ExportAssessment,
    retryingFastExport: Boolean,
    canSave: Boolean,
    onAllowReencode: () -> Unit,
    onSuggestedCut: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("저장 방식 선택") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (retryingFastExport) {
                    Text("고속 복사를 완료하지 못했습니다. 요청한 위치를 유지하며 다른 처리 방식으로 저장할 수 있습니다.")
                }
                Text(assessment.explanation)
                Text(
                    "정확한 위치로 자동 저장은 요청한 구간을 유지합니다. 원본 데이터 복사와 " +
                        "일부 구간 최적화를 우선 시도하고, 필요하면 전체 재인코딩을 허용합니다. " +
                        "처리 시간은 영상과 기기에 따라 달라지며 완료 후 실제 처리 방식을 표시합니다.",
                    color = CutColors.secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                )
                assessment.suggestion?.let { suggestion ->
                    Text("고속 저장 추천 위치", style = MaterialTheme.typography.titleSmall)
                    Text(fastCutSuggestionText(suggestion))
                    FastCutPreview(suggestion)
                    Text("위 시간은 원본 영상 기준입니다. 키프레임 간격에 따라 차이가 날 수 있습니다. " +
                        "추천 위치에 동의하면 구간을 변경하고 영상 재인코딩 없이 저장합니다. 소리는 필요하면 변환합니다.",
                        color = CutColors.secondaryText, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onSuggestedCut != null) {
                    Button(
                        onClick = onSuggestedCut, enabled = canSave,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = CutShape,
                    ) { Text("추천 위치로 고속 저장") }
                    OutlinedButton(
                        onClick = onAllowReencode, enabled = canSave,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = CutShape,
                    ) { Text("정확한 위치로 자동 저장") }
                } else {
                    Button(
                        onClick = onAllowReencode, enabled = canSave,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = CutShape,
                    ) { Text("정확한 위치로 자동 저장") }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), shape = CutShape) {
                    Text("취소")
                }
            }
        },
        containerColor = CutColors.panel,
        shape = CutShape,
    )
}

internal fun exportReportText(report: ExportReport?): String {
    val method = when (report?.method ?: ExportMethod.UNKNOWN) {
        ExportMethod.ORIGINAL_COPY -> "원본 데이터 복사"
        ExportMethod.PARTIAL_REENCODE -> "일부 구간 재인코딩"
        ExportMethod.VIDEO_REENCODE -> "전체 영상 재인코딩"
        ExportMethod.AUDIO_REENCODE -> "소리 재인코딩"
        ExportMethod.UNKNOWN -> "확인할 수 없음"
    }
    val elapsed = report?.let {
        " · " + String.format(java.util.Locale.getDefault(), "%.1f", it.elapsedMs / 1_000.0) + "초"
    }.orEmpty()
    val reasons = when (report?.method) {
        ExportMethod.ORIGINAL_COPY, null -> ""
        ExportMethod.PARTIAL_REENCODE, ExportMethod.AUDIO_REENCODE ->
            report.diagnostics.explanation(includeSmartCut = false)
        else -> report.diagnostics.explanation().ifBlank {
            if (report.method == ExportMethod.VIDEO_REENCODE) "고속 처리 제외 사유: 기록 없음" else ""
        }
    }
    return "처리 방식: " + method + elapsed + if (reasons.isBlank()) "" else "\n" + reasons
}

@Composable
internal fun ExportInspectionDialog(progress: Int? = null, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("저장 방식 분석 중") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                Text((progress?.let { "원본 분석 ${it.coerceIn(0, 100)}%\n" } ?: "원본 분석 준비 중\n") +
                    "키프레임과 구간 연결, 저장 공간을 확인하고 있습니다. 긴 영상은 분석에 시간이 걸릴 수 있습니다.",
                    modifier = Modifier.padding(start = 12.dp))
            }
        },
        confirmButton = { TextButton(onClick = onCancel, shape = CutShape) { Text("분석 취소") } },
        containerColor = CutColors.panel,
        shape = CutShape,
    )
}

@Composable
internal fun ExportCancelDialog(canCancel: Boolean, onCancel: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("영상 저장을 취소할까요?") },
        text = {
            Text(if (canCancel) "완료되지 않은 출력 파일은 정리하고 원본 영상은 유지합니다."
                else "갤러리에 결과물을 등록하고 있습니다. 완료 단계에서는 취소할 수 없습니다.")
        },
        confirmButton = {
            Button(onClick = onCancel, enabled = canCancel, shape = CutShape) { Text("저장 취소") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = CutShape) { Text("계속 저장") }
        },
        containerColor = CutColors.panel,
        shape = CutShape,
    )
}

