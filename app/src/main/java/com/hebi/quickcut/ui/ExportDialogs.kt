package com.hebi.quickcut.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.OriginalAction
import com.hebi.quickcut.media.ExportAssessment
import com.hebi.quickcut.media.ExportQualitySettings
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
    timeline: EditTimeline = EditTimeline(),
    outputQuality: ExportQualitySettings = ExportQualitySettings(),
    onOutputQualityChange: (ExportQualitySettings) -> Unit = {},
    onHelp: (String) -> Unit = {},
) {
    val palette = LocalCutPalette.current
    val qualityEvaluation = rememberExportQualityEvaluation(timeline, outputQuality)
    var showQualityDetails by rememberSaveable { mutableStateOf(!outputQuality.isAutomatic) }
    AlertDialog(
        modifier = Modifier.fillMaxWidth().padding(horizontal = CutSpacing.lg).widthIn(max = 480.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = { QuickCutDialogHeading("저장 설정") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(CutSpacing.md),
            ) {
                Text("저장 정보", color = palette.primaryText,
                    style = MaterialTheme.typography.titleMedium)
                Text(
                    "편집 결과를 새 MP4 영상으로 갤러리에 저장합니다. " +
                        "프로젝트(편집 내용) 보관과는 다른 작업입니다.",
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { onHelp("save") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("save-help")) { Text("영상 저장 사용법") }
                Text((if (outputQuality.isAutomatic)
                    "자동 · 원본 해상도와 FPS를 유지하며 가능한 고속 저장을 사용합니다."
                else "직접 설정 · 화질 변경 시 영상 변환이 필요할 수 있습니다.") +
                    " 저장 방식은 시작 전에 확인합니다.",
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.bodySmall)
                if (!timeline.isEmpty) {
                    TextButton(onClick = { showQualityDetails = !showQualityDetails },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("export-quality-toggle")) {
                        Text(if (showQualityDetails) "고급 화질 설정 접기" else "고급 화질 설정 펼치기")
                    }
                    if (showQualityDetails) {
                        ExportQualitySection(
                            timeline = timeline,
                            settings = outputQuality,
                            evaluation = qualityEvaluation,
                            onChange = onOutputQualityChange,
                        )
                    }
                }
                Text("저장 위치", color = palette.primaryText,
                    style = MaterialTheme.typography.titleSmall)
                Text("기기 갤러리에 저장",
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.bodyMedium)
                if (!canSave) {
                    Text(
                        "편집 결과를 확인한 뒤 저장할 수 있습니다.",
                        color = palette.secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("ui05b-save-disabled-reason"),
                    )
                }
                Text("결과 영상 날짜", color = palette.primaryText,
                    style = MaterialTheme.typography.titleSmall)
                CutChoiceDropdown(
                    label = "저장 날짜",
                    selected = dateMode,
                    options = listOf(
                        CutChoice(SaveDateMode.ORIGINAL, "원본 날짜 유지"),
                        CutChoice(SaveDateMode.CURRENT, "현재 날짜로 저장 (최신 영상)"),
                    ),
                    onSelected = onDateModeChange,
                )
                if (dateMode == SaveDateMode.ORIGINAL) {
                    Text(
                        "원본 날짜를 유지하며 원본 복사·부분 처리를 우선 시도합니다. " +
                            "날짜 기록에 추가 저장 공간이 필요할 수 있습니다.",
                        color = palette.secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "여러 영상을 합치면 첫 영상 날짜를 사용합니다. 원본 날짜 정보가 없으면 " +
                        "해당 방식의 저장이 취소됩니다.",
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("원본 동영상", color = palette.primaryText,
                    style = MaterialTheme.typography.titleSmall)

                CutChoiceDropdown(
                    label = "저장 완료 후 원본 동영상 처리",
                    selected = originalAction,
                    options = listOf(
                        CutChoice(OriginalAction.KEEP, "원본 유지 (기본값)"),
                        CutChoice(OriginalAction.TRASH, "원본 휴지통으로 이동"),
                        CutChoice(OriginalAction.DELETE, "원본 영구 삭제"),
                    ),
                    onSelected = onOriginalActionChange,
                )
                if (originalAction == OriginalAction.DELETE) {
                    CutInfoSection(
                        title = "영구 삭제 주의",
                        message = "영구 삭제는 복구할 수 없습니다. 저장 성공을 확인한 뒤 " +
                            "Android 시스템 승인 화면에서만 원본 삭제를 요청합니다.",
                        caution = true,
                    )
                }
                Text(
                    "저장 실패·취소 시 원본은 유지됩니다. 원본 처리 설정은 끌 때까지 유지되며, " +
                        "저장에 성공한 뒤 Android 승인을 요청합니다.",
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = canSave &&
                (outputQuality.isAutomatic || qualityEvaluation?.plan != null), shape = CutShape,
                modifier = Modifier.heightIn(min = CutSizes.primaryButton)) { Text("저장 시작") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = CutShape,
                modifier = Modifier.heightIn(min = CutSizes.touchTarget)) { Text("취소") }
        },
        containerColor = palette.panel,
        shape = CutDialogShape,
    )
}

@Composable
internal fun OriginalActionEnableDialog(
    action: OriginalAction,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalCutPalette.current
    val permanent = action == OriginalAction.DELETE
    AlertDialog(
        modifier = Modifier.fillMaxWidth().padding(horizontal = CutSpacing.lg).widthIn(max = 480.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = { QuickCutDialogHeading("원본 자동 처리 활성화") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CutInfoSection(
                    title = if (permanent) "영구 삭제 설정" else "휴지통 이동 설정",
                    message = "저장에 성공한 원본만 " +
                        (if (permanent) "영구 삭제" else "휴지통으로 이동") +
                        "하도록 설정합니다.",
                )
                Text(
                    "이 버튼은 처리 방식만 활성화합니다. 지금 원본을 삭제하지 않으며, " +
                        "저장 결과를 확인한 후 Android 시스템 승인을 매번 요청합니다.",
                    color = palette.primaryText,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "새 작업·재편집·앱 재시작 후에도 선택한 방식은 유지됩니다. " +
                        "실패·취소·건너뛴 영상의 원본은 유지됩니다.",
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (permanent) {
                    CutInfoSection(
                        title = "원본 영구 삭제",
                        message = "삭제 승인 후 영구 삭제한 원본은 복구할 수 없습니다. " +
                            "최종 삭제 여부는 Android 확인 화면에서 결정합니다.",
                        caution = true,
                    )
                }
            }
        },
        confirmButton = { Button(onClick = onConfirm, shape = CutShape,
            modifier = Modifier.heightIn(min = CutSizes.primaryButton)) { Text("활성화") } },
        dismissButton = { TextButton(onClick = onDismiss, shape = CutShape,
            modifier = Modifier.heightIn(min = CutSizes.touchTarget)) { Text("취소") } },
        containerColor = palette.panel,
        shape = CutDialogShape,
    )
}

@Composable
internal fun ExportModeDialog(
    assessment: ExportAssessment,
    retryingFastExport: Boolean,
    canSave: Boolean,
    onAllowReencode: () -> Unit,
    onSuggestedCut: (() -> Unit)?,
    onDismiss: () -> Unit,
    onLessTrim: (() -> Unit)? = null,
) {
    val palette = LocalCutPalette.current
    AlertDialog(
        modifier = Modifier.fillMaxWidth().padding(horizontal = CutSpacing.lg).widthIn(max = 480.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = { QuickCutDialogHeading("저장 방식 선택") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (retryingFastExport) {
                    CutInfoSection(
                        title = "고속 저장 재시도",
                        message = "고속 복사가 실패했습니다. 요청한 위치를 유지하며 다른 방식으로 저장할 수 있습니다.",
                        caution = true,
                    )
                }
                CutInfoSection(
                    title = "저장 방식 분석 결과",
                    message = assessment.explanation,
                )
                Text("선택한 구간 그대로 저장", color = palette.primaryText,
                    style = MaterialTheme.typography.titleSmall)
                Text(
                    "내가 설정한 시작·끝 시간은 바뀌지 않습니다. " +
                        "필요하면 영상 또는 소리를 다시 변환하므로 저장 시간이 길어질 수 있습니다. " +
                        "실제 처리 방식은 완료 후 확인할 수 있습니다.",
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                )
                val choices = listOf(
                    Triple(assessment.suggestion, onSuggestedCut, "추천 위치로 고속 저장"),
                    Triple(assessment.lessTrimSuggestion, onLessTrim, "덜 자르고 고속 저장"),
                )
                choices.forEach { (suggestion, action, label) ->
                    if (suggestion != null && action != null) {
                        Text(if (suggestion.retainsMore) "장면을 더 남기는 추천 구간" else "장면을 더 자르는 추천 구간",
                            color = palette.primaryText, style = MaterialTheme.typography.titleSmall)
                        Text(fastCutSuggestionText(suggestion))
                        FastCutPreview(suggestion)
                        OutlinedButton(
                            onClick = action, enabled = canSave,
                            modifier = Modifier.fillMaxWidth().heightIn(min = CutSizes.primaryButton), shape = CutShape,
                        ) { Text(label) }
                    }
                }
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onAllowReencode, enabled = canSave,
                    modifier = Modifier.fillMaxWidth().heightIn(min = CutSizes.primaryButton), shape = CutShape,
                ) { Text("정확한 위치로 자동 저장") }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = CutSizes.touchTarget), shape = CutShape) {
                    Text("취소")
                }
            }
        },
        containerColor = palette.panel,
        shape = CutDialogShape,
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
internal fun TaskErrorDialog(
    message: String,
    onDismiss: () -> Unit,
    onHelp: (String) -> Unit = {},
) {
    val palette = LocalCutPalette.current
    AlertDialog(
        modifier = Modifier.fillMaxWidth().padding(horizontal = CutSpacing.lg).widthIn(max = 480.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = { QuickCutDialogHeading("작업 오류") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(CutSpacing.md),
            ) {
                CutInfoSection(
                    title = "오류 내용",
                    message = message,
                    caution = true,
                )
                Text(
                    "작업을 종료한 뒤 원본 영상이 열리는지 확인하고, " +
                        "저장·복구 상태가 불확실하면 같은 영상을 바로 다시 저장하지 말고 " +
                        "‘최근 작업 진단’에서 상태를 확인해 주세요. " +
                        "진단은 사용자가 직접 선택하기 전에는 외부로 전송하지 않습니다.",
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { onHelp("recovery") },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("error-help")) {
                    Text("오류·복구 사용법")
                }
                ExportDiagnosticButton()
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, shape = CutShape,
                modifier = Modifier.heightIn(min = CutSizes.touchTarget)) { Text("확인") }
        },
        containerColor = palette.panel,
        shape = CutDialogShape,
    )
}

@Composable
internal fun ExportInspectionDialog(progress: Int? = null, onCancel: () -> Unit) {
    val palette = LocalCutPalette.current
    AlertDialog(
        modifier = Modifier.fillMaxWidth().padding(horizontal = CutSpacing.lg).widthIn(max = 480.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onCancel,
        title = { QuickCutDialogHeading("저장 방식 분석 중") },
        text = {
            val analysisText = (progress?.let { "원본 분석 ${it.coerceIn(0, 100)}%\n" } ?: "원본 분석 준비 중\n") +
                "키프레임과 구간 연결, 저장 공간을 확인하고 있습니다. 긴 영상은 분석에 시간이 걸릴 수 있습니다."
            if (LocalDensity.current.fontScale >= 1.6f) {
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Text(analysisText)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Text(analysisText, modifier = Modifier.padding(start = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onCancel, shape = CutShape,
            modifier = Modifier.heightIn(min = CutSizes.touchTarget)) { Text("분석 취소") } },
        containerColor = palette.panel,
        shape = CutDialogShape,
    )
}

@Composable
internal fun ExportCancelDialog(canCancel: Boolean, onCancel: () -> Unit, onDismiss: () -> Unit) {
    val palette = LocalCutPalette.current
    AlertDialog(
        modifier = Modifier.fillMaxWidth().padding(horizontal = CutSpacing.lg).widthIn(max = 480.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = { QuickCutDialogHeading("영상 저장을 취소할까요?") },
        text = {
            CutInfoSection(
                title = if (canCancel) "저장 중단 확인" else "저장 완료 처리 중",
                message = if (canCancel)
                    "완료되지 않은 출력 파일은 정리하고 원본 영상은 유지합니다."
                else "갤러리에 결과물을 등록하고 있습니다. 완료 단계에서는 취소할 수 없습니다.",
                caution = canCancel,
            )
        },
        confirmButton = {
            Button(onClick = onCancel, enabled = canCancel, shape = CutShape,
                modifier = Modifier.heightIn(min = CutSizes.touchTarget)) { Text("저장 취소") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = CutShape,
                modifier = Modifier.heightIn(min = CutSizes.touchTarget)) { Text("계속 저장") }
        },
        containerColor = palette.panel,
        shape = CutDialogShape,
    )
}

