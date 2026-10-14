package com.hebi.quickcut.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import com.hebi.quickcut.editor.OriginalAction
import com.hebi.quickcut.editor.TrimInput
import com.hebi.quickcut.editor.VideoClip
import com.hebi.quickcut.media.ExportAssessment
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
                listOf(
                    OriginalAction.KEEP to "원본 유지 (기본값)",
                    OriginalAction.TRASH to "원본 휴지통으로 이동",
                    OriginalAction.DELETE to "원본 영구 삭제",
                ).forEach { (action, label) ->
                    SaveChoiceRow(label, originalAction == action) { onOriginalActionChange(action) }
                }
                Text("저장 날짜", color = CutColors.primaryText)
                listOf(
                    SaveDateMode.CURRENT to "현재 날짜로 저장 (최신 영상)",
                    SaveDateMode.ORIGINAL to "원본 날짜 유지",
                ).forEach { (mode, label) ->
                    SaveChoiceRow(label, dateMode == mode) { onDateModeChange(mode) }
                }
                if (dateMode == SaveDateMode.ORIGINAL) {
                    Text(
                        "원본 날짜 유지에서는 직접 고속 복사를 지원하지 않습니다. " +
                            "정밀 저장으로 날짜를 기록합니다.",
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
                        "휴지통 이동·영구 삭제는 저장 후 별도 확인과 Android 승인이 필요합니다.",
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
private fun SaveChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, color = CutColors.primaryText)
    }
}

@Composable
internal fun ExportModeDialog(
    assessment: ExportAssessment,
    clip: VideoClip?,
    retryingFastExport: Boolean,
    canSave: Boolean,
    onAllowReencode: () -> Unit,
    onSuggestedStart: (() -> Unit)?,
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
                    Text("고속 저장 중 호환성 문제가 발생했습니다. 재인코딩을 허용할지 선택해 주세요.")
                }
                Text(assessment.explanation)
                Text(
                    "정밀 저장은 영상 전체 또는 일부 구간을 다시 인코딩할 수 있어 " +
                        "긴 영상에서는 저장 시간이 늘어날 수 있습니다.",
                    color = CutColors.secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                )
                val startMs = assessment.suggestedStartMs
                if (startMs != null && clip != null && startMs > clip.startMs) {
                    Text(
                        "대안: 앞부분을 " + TrimInput.secondsText(startMs) +
                            "초까지 삭제하면 빠른 저장이 가능할 수 있습니다. " +
                            "이 경우 원래보다 더 많은 영상이 삭제됩니다.",
                        color = CutColors.secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onAllowReencode, enabled = canSave,
                    modifier = Modifier.fillMaxWidth(), shape = CutShape,
                ) { Text("정밀 저장 (재인코딩 허용)") }
                if (onSuggestedStart != null) {
                    OutlinedButton(
                        onClick = onSuggestedStart, enabled = canSave,
                        modifier = Modifier.fillMaxWidth(), shape = CutShape,
                    ) { Text("빠른 저장 위치로 변경 후 확인") }
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

@Composable
internal fun ExportInspectionDialog() {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("저장 방식 분석 중") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                Text("코덱과 자르기 위치, 저장 공간을 확인하고 있습니다.",
                    modifier = Modifier.padding(start = 12.dp))
            }
        },
        confirmButton = {},
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
