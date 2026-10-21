package com.hebi.quickcut.batch

import android.app.Activity
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hebi.quickcut.editor.OriginalAction
import com.hebi.quickcut.editor.OriginalActionStatus
import com.hebi.quickcut.editor.formatTime
import com.hebi.quickcut.media.SaveDateMode
import com.hebi.quickcut.media.ExportMode
import com.hebi.quickcut.ui.exportReportText
import kotlinx.coroutines.launch

private val onSurface = Color(0xFFF4F4F5)
private val secondary = Color(0xFFB7BAC0)
private val panel = Color(0xFF151619)
private val borderColor = Color(0xFF36383E)

@Composable
fun BatchScreen(
    model: BatchViewModel,
    onSelectVideos: () -> Unit,
    onBack: () -> Unit,
    onApproveOriginals: (List<String>, Uri, OriginalAction) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    var pendingOverview by remember { mutableStateOf<BatchExportOverview?>(null) }
    var pendingRetry by remember { mutableStateOf(false) }
    var inspecting by remember { mutableStateOf(false) }
    var inspectedCount by remember { mutableIntStateOf(0) }
    var inspectedTotal by remember { mutableIntStateOf(0) }
    var showStopConfirmation by rememberSaveable { mutableStateOf(false) }
    val requestExport: (Boolean) -> Unit = { retry ->
        if (!inspecting && !model.isRunning && !model.isRecovering) {
            inspecting = true
            inspectedCount = 0
            inspectedTotal = 0
            scope.launch {
                try {
                    val checked = model.inspectExport(context.applicationContext, retry) { done, total ->
                        inspectedCount = done
                        inspectedTotal = total
                    }
                    if (checked.ready == 0) {
                        model.notifyUser("저장할 수 있는 영상이 없습니다. 설정과 파일을 확인해 주세요.")
                    } else {
                        pendingOverview = checked
                        pendingRetry = retry
                    }
                } catch (error: Exception) {
                    model.notifyUser(
                        "영상 분석에 실패했습니다. " +
                            (error.localizedMessage ?: "다시 시도해 주세요.")
                    )
                } finally {
                    inspecting = false
                }
            }
        }
    }
    DisposableEffect(model.isRunning, activity) {
        if (model.isRunning) activity?.window?.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val guardedBack = {
        if (model.isRunning) showStopConfirmation = true else onBack()
    }
    BackHandler(onBack = guardedBack)

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF0B0C0E))) {
        if (maxWidth >= 840.dp) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.width(390.dp)
                        .verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    BatchHeader(guardedBack)
                    TemplateEditor(model, onSelectVideos)
                }
                Column(Modifier.weight(1f).padding(16.dp)) {
                    BatchFileHeader(model)
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth().testTag("batch-file-list"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(model.files, key = { it.uri }) { file ->
                            BatchFileRow(
                                file, model.previewTemplate(), model.saveDateMode,
                                !model.isRunning && !model.isRecovering, model::removeVideo
                            )
                        }
                    }
                    BatchProgress(model)
                    BatchActions(model, requestExport, inspecting) { showStopConfirmation = true }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)
                    .testTag("batch-file-list"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { BatchHeader(guardedBack) }
                item { TemplateEditor(model, onSelectVideos) }
                item { BatchFileHeader(model) }
                items(model.files, key = { it.uri }) { file ->
                    BatchFileRow(
                        file, model.previewTemplate(), model.saveDateMode,
                        !model.isRunning && !model.isRecovering, model::removeVideo
                    )
                }
                item { BatchProgress(model) }
            }
            Column(Modifier.background(panel).padding(12.dp)) {
                BatchActions(model, requestExport, inspecting) { showStopConfirmation = true }
            }
            }
        }
    }

    if (showStopConfirmation) {
        AlertDialog(
            onDismissRequest = { showStopConfirmation = false },
            title = { Text("일괄 저장을 중단할까요?") },
            text = { Text("이미 저장된 영상은 유지하고 진행 중인 파일은 취소합니다.") },
            confirmButton = {
                Button(enabled = !model.isFinalizing, onClick = {
                    showStopConfirmation = false
                    model.cancel()
                }) { Text("저장 중단") }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirmation = false }) {
                    Text("계속 저장")
                }
            },
            containerColor = Color(0xFF202126),
        )
    }

    pendingOverview?.let { overview ->
        AlertDialog(
            onDismissRequest = { pendingOverview = null },
            title = { Text("자동 저장 확인") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("저장할 영상 ${overview.ready}개", fontWeight = FontWeight.SemiBold)
                    model.previewTemplate()?.let { Text(BatchTimeInput.summary(it)) }
                    Text(
                        "설정한 자르기 위치를 유지하며 가능한 가장 빠른 방식으로 저장합니다. " +
                            "고속 처리가 어려운 영상도 자동으로 이어서 저장합니다. " +
                            "영상과 기기에 따라 시간이 더 걸릴 수 있습니다.",
                        color = secondary, style = MaterialTheme.typography.bodySmall,
                    )
                    if (overview.skippedFiles.isNotEmpty()) {
                        Text("저장할 수 없는 영상 ${overview.skipped}개",
                            fontWeight = FontWeight.SemiBold)
                        overview.skippedFiles.forEach {
                            Text("· " + it, color = secondary,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (overview.adjustments.isNotEmpty()) {
                        Text("고속 저장 추천 위치", fontWeight = FontWeight.SemiBold)
                        overview.adjustments.forEach { adjustment ->
                            Text(adjustment.displayName, fontWeight = FontWeight.SemiBold)
                            Text(com.hebi.quickcut.ui.fastCutSuggestionText(adjustment.suggestion),
                                color = secondary, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("추천 위치로 저장하면 위 구간만 조정합니다. ${overview.adjustedFast}개를 고속 저장하고 " +
                            "${overview.adjustedSkips}개는 건너뜁니다. 소리는 필요하면 변환합니다.",
                            color = secondary, style = MaterialTheme.typography.bodySmall)
                        overview.unadjustableFiles.forEach {
                            Text("고속 저장 제외 · $it", color = secondary,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        model.startReviewed(context, overview, pendingRetry, ExportMode.ALLOW_REENCODE)
                        pendingOverview = null
                    }, shape = RoundedCornerShape(4.dp)) { Text("전체 자동 저장") }
                    if (overview.adjustments.isNotEmpty()) {
                        OutlinedButton(onClick = {
                            model.startReviewed(context, overview, pendingRetry, ExportMode.FAST_ONLY, true)
                            pendingOverview = null
                        }, shape = RoundedCornerShape(4.dp)) { Text("추천 위치로 ${overview.adjustedFast}개 고속 저장") }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingOverview = null }) { Text("설정으로 돌아가기") }
            },
            containerColor = Color(0xFF202126),
        )
    }
    if (inspecting) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("저장할 영상 확인 중") },
            text = {
                Text(
                    "영상과 자르기 설정을 확인하고 있습니다." +
                        if (inspectedTotal > 0) " ($inspectedCount / $inspectedTotal)" else ""
                )
            },
            confirmButton = {},
            containerColor = Color(0xFF202126),
        )
    }

    model.notification?.let { notice ->
        AlertDialog(
            onDismissRequest = model::clearNotification,
            title = { Text("안내") },
            text = { Text(notice) },
            confirmButton = { TextButton(onClick = model::clearNotification) { Text("확인") } },
            containerColor = Color(0xFF202126),
            shape = RoundedCornerShape(4.dp),
        )
    }

    if (model.originalStatus == OriginalActionStatus.CONFIRMING) {
        val permanent = model.performedOriginalAction == OriginalAction.DELETE
        val action = if (permanent) "영구 삭제" else "휴지통 이동"
        val candidateUris = model.originalCandidates.toSet()
        val selectedNames = model.files.filter { it.uri in candidateUris }
            .take(5).joinToString("\n") { "• " + it.displayName.take(60) }
        val remaining = (candidateUris.size - 5).coerceAtLeast(0)
        AlertDialog(
            onDismissRequest = model::keepOriginals,
            title = { Text("원본 " + action + " 최종 확인") },
            text = {
                Text(
                    "저장 성공 원본 " + model.originalCandidates.size + "개만 " +
                        action + " 요청합니다. 실패·건너뜀 원본은 삭제하지 않습니다.\n\n" +
                        "대상 파일:\n" + selectedNames +
                        (if (remaining > 0) "\n그 외 " + remaining + "개" else "") +
                        "\n\n영구 삭제는 복원되지 않을 수 있으며 Android 승인이 한 번 더 표시됩니다."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val output = model.savedOutputForConfirmation
                        if (output == null) {
                            model.originalApprovalUnsupported("저장 결과가 없어 삭제하지 않았습니다.")
                        } else {
                            onApproveOriginals(
                                model.originalCandidates, output, model.performedOriginalAction
                            )
                        }
                    },
                    colors = if (permanent) ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFBF4545)
                    ) else ButtonDefaults.buttonColors(),
                    shape = RoundedCornerShape(4.dp),
                ) { Text(action + " 요청") }
            },
            dismissButton = {
                TextButton(onClick = model::keepOriginals) { Text("원본 유지") }
            },
            containerColor = Color(0xFF202126),
            shape = RoundedCornerShape(4.dp),
        )
    }
}

@Composable
private fun BatchHeader(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "뒤로가기")
        }
        Text("일괄 편집", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
    }
}

@Composable
private fun TemplateEditor(model: BatchViewModel, onSelectVideos: () -> Unit) {
    val context = LocalContext.current
    val disabled = model.isRunning || model.isRecovering
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = onSelectVideos,
            enabled = !disabled && !model.isImporting,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(4.dp),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (model.isImporting) "영상 불러오는 중" else "영상 여러 개 선택 / 추가")
        }
        Text(
            "자르기 설정 · 마지막 사용값 자동 복원",
            color = onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Text("편집 방식", color = secondary, fontSize = 13.sp)
        listOf(
            BatchClipMode.REMOVE_EDGES to "앞뒤 구간 삭제",
            BatchClipMode.KEEP_FIRST to "처음 구간만 남기기",
            BatchClipMode.KEEP_LAST to "마지막 구간만 남기기",
            BatchClipMode.JOIN_EDGES to "처음 + 마지막 구간 연결",
        ).forEach { (mode, title) ->
            ChoiceOption(title, model.draftMode == mode, !disabled) {
                model.draftMode = mode
            }
        }
        val useLeading = model.draftMode.usesLeading
        val useTrailing = model.draftMode.usesTrailing
        val removing = model.draftMode == BatchClipMode.REMOVE_EDGES
        BatchDurationField(
            title = if (removing) "앞에서 삭제할 시간" else "처음부터 남길 시간",
            value = model.draftLeadingSeconds,
            onValueChange = { model.draftLeadingSeconds = it },
            enabled = !disabled && useLeading,
            used = useLeading,
            tag = "batch-leading",
        )
        BatchDurationField(
            title = if (removing) "뒤에서 삭제할 시간" else "끝에서 남길 시간",
            value = model.draftTrailingSeconds,
            onValueChange = { model.draftTrailingSeconds = it },
            enabled = !disabled && useTrailing,
            used = useTrailing,
            tag = "batch-trailing",
        )
        val template = model.previewTemplate()
        Text(
            template?.let(BatchTimeInput::summary) ?: model.draftError().orEmpty(),
            color = if (template != null) onSurface else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("batch-edit-summary"),
        )
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(enabled = !disabled) { model.draftMute = !model.draftMute },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = model.draftMute,
                onCheckedChange = { model.draftMute = it },
                enabled = !disabled,
            )
            Text("원본 소리 음소거", color = onSurface)
        }
        TextButton(
            onClick = { showAdvanced = !showAdvanced },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (showAdvanced) "템플릿·저장 옵션 접기" else "템플릿·저장 옵션 펼치기") }
        Text(
            "저장 날짜: " + (if (model.saveDateMode == SaveDateMode.CURRENT)
                "현재 날짜" else "원본 날짜") +
                " · 저장 후 원본: " + when (model.originalAction) {
                    OriginalAction.KEEP -> "유지"
                    OriginalAction.TRASH -> "휴지통"
                    OriginalAction.DELETE -> "영구 삭제"
                },
            color = secondary,
            fontSize = 12.sp,
        )
        if (showAdvanced) {
        Text("내 템플릿", color = onSurface, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { model.chooseTemplate(null) },
                enabled = !disabled,
                shape = RoundedCornerShape(4.dp),
            ) { Text("직접 설정") }
            model.templates.forEach { template ->
                OutlinedButton(
                    onClick = { model.chooseTemplate(template.id) },
                    enabled = !disabled,
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text((if (model.selectedTemplateId == template.id) "✓ " else "") +
                        template.name)
                }
            }
        }
        OutlinedTextField(
            value = model.draftName,
            onValueChange = { model.draftName = it.take(40) },
            enabled = !disabled,
            label = { Text("템플릿 이름") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { model.saveTemplate(context) },
                enabled = !disabled,
                shape = RoundedCornerShape(4.dp),
            ) { Text(if (model.selectedTemplateId == null) "템플릿 저장" else "템플릿 수정 저장") }
            if (model.selectedTemplateId != null) {
                TextButton(
                    onClick = { model.removeTemplate(context) },
                    enabled = !disabled,
                ) { Text("삭제") }
            }
        }

        Text("결과 저장 날짜", color = onSurface, fontWeight = FontWeight.SemiBold)
        ChoiceOption("현재 날짜로 저장 (최신 파일)", model.saveDateMode == SaveDateMode.CURRENT, !disabled) {
            model.saveDateMode = SaveDateMode.CURRENT
        }
        ChoiceOption("원본 날짜 유지", model.saveDateMode == SaveDateMode.ORIGINAL, !disabled) {
            model.saveDateMode = SaveDateMode.ORIGINAL
        }
        Text(
            "원본 날짜 유지는 직접 고속 복사를 지원하지 않아 저장 시간이 늘어날 수 있습니다. " +
                "원본 날짜는 촬영 메타데이터가 우선이며 없으면 파일 수정 날짜를 사용합니다. " +
                "날짜 정보가 없거나 기기가 적용을 거부하면 해당 파일은 저장하지 않습니다.",
            style = MaterialTheme.typography.bodySmall, color = secondary,
        )

        Text("저장 후 원본 처리", color = onSurface, fontWeight = FontWeight.SemiBold)
        ChoiceOption("원본 유지 (기본값)", model.originalAction == OriginalAction.KEEP, !disabled) {
            model.originalAction = OriginalAction.KEEP
        }
        ChoiceOption("저장 성공한 원본만 휴지통으로 이동",
            model.originalAction == OriginalAction.TRASH, !disabled) {
            model.originalAction = OriginalAction.TRASH
        }
        ChoiceOption("저장 성공한 원본 영구 삭제",
            model.originalAction == OriginalAction.DELETE, !disabled) {
            model.originalAction = OriginalAction.DELETE
        }
        Text(
            "원본 삭제는 자동 실행하지 않으며 완료 후 앱 확인과 Android 승인이 필요합니다.",
            style = MaterialTheme.typography.bodySmall, color = secondary,
        )

        }
    }
}

@Composable
private fun BatchDurationField(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    used: Boolean,
    tag: String,
) {
    val parts = BatchTimeInput.parts(value)
    val error = if (used) BatchTimeInput.error(parts) else null
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = if (used) onSurface else secondary,
            fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = if (used) parts.minutes else "",
                onValueChange = { onValueChange(it.take(16) + ":" + parts.seconds) },
                enabled = enabled,
                label = { Text("분") },
                placeholder = { Text("0") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = error != null,
                modifier = Modifier.weight(1f).testTag("$tag-minutes")
                    .semantics { contentDescription = "$title, 분" },
                singleLine = true,
            )
            OutlinedTextField(
                value = if (used) parts.seconds else "",
                onValueChange = { onValueChange(parts.minutes + ":" + it.take(16)) },
                enabled = enabled,
                label = { Text("초") },
                placeholder = { Text("0") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = error != null,
                modifier = Modifier.weight(1f).testTag("$tag-seconds")
                    .semantics { contentDescription = "$title, 초" },
                singleLine = true,
            )
        }
        Text(
            when {
                !used -> "이 편집 방식에서는 사용하지 않습니다."
                error != null -> error
                else -> "예: 1분 5.5초 · 소수점 셋째 자리까지 입력 가능"
            },
            color = if (error != null) MaterialTheme.colorScheme.error else secondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ChoiceOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onChoose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(enabled = enabled, onClick = onChoose),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, color = if (enabled) onSurface else secondary, fontSize = 13.sp)
    }
}

@Composable
private fun BatchActions(
    model: BatchViewModel,
    requestExport: (Boolean) -> Unit,
    inspecting: Boolean,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (model.runState) {
            BatchRunState.SETUP -> {
                Button(
                    onClick = { requestExport(false) },
                    enabled = model.files.isNotEmpty() && model.previewTemplate() != null &&
                        !model.isImporting && !model.isRecovering && !inspecting,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(4.dp),
                ) { Text("일괄 편집 및 저장 시작") }
            }
            BatchRunState.RUNNING -> {
                OutlinedButton(
                    onClick = onCancel,
                    enabled = !model.isFinalizing,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(4.dp),
                ) { Text("일괄 저장 중단") }
                Text(
                    "다른 앱으로 이동하거나 화면을 꺼도 순서대로 저장합니다. " +
                        "앱이 종료되면 완료 파일을 복구하고 남은 작업은 재검토 후 다시 시작할 수 있습니다.",
                    color = secondary, style = MaterialTheme.typography.bodySmall,
                )
            }
            BatchRunState.FINISHED -> {
                Text(
                    "완료 " + model.successes + "개 · 실패 " + model.failures +
                        "개 · 건너뜀 " + model.skips + "개",
                    color = onSurface, fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { requestExport(true) },
                        enabled = !model.isRecovering && !inspecting && model.previewTemplate() != null &&
                            (model.failures > 0 || model.skips > 0),
                        shape = RoundedCornerShape(4.dp),
                    ) { Text("실패 작업 재시도") }
                    TextButton(enabled = !model.isRecovering, onClick = model::resetBatch) { Text("새 작업") }
                }
            }
        }
    }
}

@Composable
private fun BatchFileHeader(model: BatchViewModel) {
    Text(
        "선택 영상 " + model.files.size + "개 · 원본 길이 → 저장 후 길이",
        color = onSurface, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun BatchFileRow(
    file: BatchFile,
    template: BatchTemplate?,
    dateMode: SaveDateMode,
    canRemove: Boolean,
    onRemove: (String) -> Unit,
) {
    val expected = template?.plan(file.uri, file.durationMs, file.hasAudio)
    val subtitle = when {
        file.status == BatchFileStatus.SAVED && file.outputExpectedDurationMs != null ->
            formatTime(file.durationMs) + " → " + formatTime(file.outputExpectedDurationMs)
        file.durationMs < 100 -> file.reason ?: "읽을 수 없는 영상"
        expected is BatchPlan.Skip -> expected.reason
        dateMode == SaveDateMode.ORIGINAL && file.originalDate == null -> "원본 날짜를 확인할 수 없습니다."
        expected is BatchPlan.Ready ->
            formatTime(file.durationMs) + " → " + formatTime(expected.timeline.totalDurationMs)
        else -> ""
    }
    Column(
        modifier = Modifier.fillMaxWidth().border(1.dp, borderColor)
            .background(panel).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    file.displayName, color = onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                )
                Text(subtitle, color = secondary, fontSize = 12.sp)
                file.originalDate?.let {
                    if (dateMode == SaveDateMode.ORIGINAL) {
                        Text(
                            "날짜 출처: " + it.source,
                            color = secondary, fontSize = 11.sp,
                        )
                    }
                }
            }
            if (canRemove) {
                IconButton(
                    onClick = { onRemove(file.uri) }, modifier = Modifier.size(48.dp)
                ) { Icon(Icons.Outlined.Close, contentDescription = "선택 영상 제거") }
            }
        }
        if (file.status == BatchFileStatus.RUNNING && file.progress != null) {
            LinearProgressIndicator(
                progress = { file.progress.toFloat() / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val statusText = when (file.status) {
            BatchFileStatus.WAITING -> "대기"
            BatchFileStatus.RUNNING -> "저장 중" +
                (file.progress?.let { " $it%" } ?: "")
            BatchFileStatus.SAVED -> "저장 완료"
            BatchFileStatus.FAILED -> "저장 실패"
            BatchFileStatus.SKIPPED -> "건너뜀"
        }
        Text(
            statusText + (file.reason?.let { " · $it" } ?: ""),
            color = if (file.status == BatchFileStatus.FAILED) Color(0xFFE37A7A)
                else secondary,
            fontSize = 12.sp,
        )
        if (file.status == BatchFileStatus.SAVED) {
            Text(exportReportText(file.exportReport), color = secondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun BatchProgress(model: BatchViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (model.isRecovering) Text("이전 작업과 완료 파일을 확인 중입니다.", color = secondary)
        if (model.isRunning) Text(
            if (model.isFinalizing) "파일 등록 완료 처리 중입니다. 완료 후 다음 작업을 중단할 수 있습니다."
            else "화면을 끄거나 다른 앱으로 이동해도 저장이 계속됩니다. 알림에서 진행 상태와 중단을 확인할 수 있습니다.",
            color = secondary, fontSize = 12.sp,
        )
        if (model.files.isNotEmpty()) {
            Text(
                "진행 " + model.completedCount + " / " + model.files.size,
                color = secondary, fontSize = 13.sp,
            )
            LinearProgressIndicator(
                progress = {
                    (model.completedCount.toFloat() / model.files.size).coerceIn(0f, 1f)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (model.runState == BatchRunState.FINISHED &&
            model.originalCandidates.isNotEmpty() &&
            model.performedOriginalAction != OriginalAction.KEEP
        ) {
            val actionText = if (model.performedOriginalAction == OriginalAction.TRASH) {
                "휴지통 이동"
            } else "영구 삭제"
            val status = when (model.originalStatus) {
                OriginalActionStatus.READY -> "원본 처리 선택 대기"
                OriginalActionStatus.CONFIRMING -> "앱 확인 대기"
                OriginalActionStatus.SYSTEM_PROMPT -> "Android 승인 대기"
                OriginalActionStatus.COMPLETED -> "Android 원본 처리 승인 완료"
                OriginalActionStatus.SKIPPED -> "원본 유지"
                OriginalActionStatus.UNSUPPORTED -> "지원하지 않아 원본 유지"
                OriginalActionStatus.NONE -> "원본 유지"
            }
            Text(status, color = secondary)
            if (model.originalStatus == OriginalActionStatus.READY ||
                model.originalStatus == OriginalActionStatus.SKIPPED
            ) {
                OutlinedButton(
                    onClick = model::promptOriginalApproval,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text("저장 성공 원본 " + model.originalCandidates.size + "개 " +
                        actionText + " 요청")
                }
            }
        }
    }
}


