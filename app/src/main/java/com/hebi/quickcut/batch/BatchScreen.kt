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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hebi.quickcut.editor.OriginalAction
import com.hebi.quickcut.editor.OriginalActionStatus
import com.hebi.quickcut.editor.formatTime
import com.hebi.quickcut.media.SaveDateMode
import com.hebi.quickcut.media.ExportMode
import com.hebi.quickcut.ui.exportReportText
import com.hebi.quickcut.ui.FastCutPreview
import com.hebi.quickcut.ui.ExportDiagnosticButton
import com.hebi.quickcut.ui.fastCutSuggestionText
import com.hebi.quickcut.ui.OriginalActionEnableDialog
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
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    var pendingOverview by remember { mutableStateOf<BatchExportOverview?>(null) }
    var pendingRetry by remember { mutableStateOf(false) }
    var inspecting by remember { mutableStateOf(false) }
    var inspectionJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var inspectedCount by remember { mutableIntStateOf(0) }
    var inspectedTotal by remember { mutableIntStateOf(0) }
    var inspectedPercent by remember { mutableStateOf<Int?>(null) }
    var showStopConfirmation by rememberSaveable { mutableStateOf(false) }
    val requestExport: (Boolean) -> Unit = { retry ->
        if (!inspecting && !model.isRunning && !model.isRecovering && !model.hasUnresolvedRecovery) {
            inspecting = true
            inspectedCount = 0
            inspectedTotal = 0
            inspectedPercent = null
            inspectionJob = scope.launch {
                try {
                    val checked = model.inspectExport(context.applicationContext, retry,
                        onFileProgress = { inspectedPercent = it }) { done, total ->
                        inspectedCount = done
                        inspectedTotal = total
                    }
                    pendingOverview = checked
                    pendingRetry = retry
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
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
                                file, model.previewTemplate(), model.saveDateMode, model.currentExportStage,
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
                        file, model.previewTemplate(), model.saveDateMode, model.currentExportStage,
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
        LaunchedEffect(overview, model.reviewGeneration) {
            if (!model.isReviewCurrent(overview, pendingRetry)) {
                pendingOverview = null
                model.notifyUser("설정이나 파일이 변경됐습니다. 저장 방식을 다시 분석해 주세요.")
            }
        }
        BatchReviewDialog(
            overview = overview,
            templateSummary = model.previewTemplate()?.let(BatchTimeInput::summary),
            onDismiss = { pendingOverview = null },
            onSubmit = { choices ->
                model.startReviewed(context, overview, pendingRetry, choices)
                pendingOverview = null
            },
            onAutomatic = {
                model.startReviewed(context, overview, pendingRetry, ExportMode.ALLOW_REENCODE)
                pendingOverview = null
            },
            onRecommended = {
                model.startReviewed(context, overview, pendingRetry, ExportMode.FAST_ONLY, true)
                pendingOverview = null
            },
        )
    }
    if (inspecting) {
        AlertDialog(
            onDismissRequest = { inspectionJob?.cancel() },
            title = { Text("저장할 영상 확인 중") },
            text = {
                Text(
                    "키프레임과 자르기 설정을 확인하고 있습니다. 긴 영상은 분석에 시간이 걸릴 수 있습니다." +
                        (if (inspectedTotal > 0) " ($inspectedCount / $inspectedTotal)" else "") +
                        (inspectedPercent?.let { " · 현재 영상 $it%" } ?: "")
                )
            },
            confirmButton = { TextButton(onClick = { inspectionJob?.cancel() }) { Text("분석 취소") } },
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

    LaunchedEffect(model.originalStatus, lifecycleState) {
        if (model.originalStatus == OriginalActionStatus.READY &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            val output = model.savedOutputForConfirmation
            if (output == null) {
                model.originalApprovalUnsupported("저장 결과가 없어 삭제하지 않았습니다.")
            } else {
                model.launchingOriginalApproval()
                onApproveOriginals(model.originalCandidates, output, model.performedOriginalAction)
            }
        }
    }
}

@Composable
private fun BatchReviewDialog(
    overview: BatchExportOverview,
    templateSummary: String?,
    onDismiss: () -> Unit,
    onSubmit: (Map<String, BatchExportChoice>) -> Unit,
    onAutomatic: () -> Unit,
    onRecommended: () -> Unit,
) {
    var choices by remember(overview) {
        mutableStateOf(BatchExportChoicePolicy.defaults(overview.reviewedFiles))
    }
    val exactCount = choices.values.count { it == BatchExportChoice.EXACT }
    val recommendedCount = choices.values.count { it == BatchExportChoice.RECOMMENDED }
    val skippedCount = choices.values.count { it == BatchExportChoice.SKIP }
    val saveCount = exactCount + recommendedCount
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                .widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight(0.94f),
            color = Color(0xFF202126), contentColor = onSurface,
            shape = RoundedCornerShape(4.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("자동 저장 확인", style = MaterialTheme.typography.titleLarge)
                Text("정확한 위치 $exactCount · 추천 위치 $recommendedCount · 제외 $skippedCount",
                    style = MaterialTheme.typography.bodySmall, color = secondary)
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("batch-review-list"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            templateSummary?.let { Text(it) }
                            Text("파일마다 저장할 위치를 선택해 주세요. 정확한 위치는 원래 구간을 유지하며 " +
                                "필요하면 자동 변환합니다. 추천 위치는 표시된 구간으로 조정해 영상 변환 없이 저장합니다.",
                                color = secondary, style = MaterialTheme.typography.bodySmall)
                            Text("저장 가능한 영상 ${overview.ready}개 · 저장할 수 없는 영상 ${overview.skipped}개",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    items(overview.reviewedFiles, key = { it.uri }) { file ->
                        BatchReviewFile(
                            file = file,
                            choice = choices.getValue(file.uri),
                            onChoice = { choice -> choices = choices + (file.uri to choice) },
                        )
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("전체 자동 저장은 위 선택과 관계없이 저장 가능한 모든 영상을 정확한 위치로 저장합니다.",
                                color = secondary, style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = onAutomatic, enabled = overview.ready > 0,
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(4.dp)) {
                                Text("전체 자동 저장")
                            }
                            if (overview.reviewedFiles.any { it.canRecommend }) {
                                Text("아래 단축 버튼은 위 추천 구간을 모두 적용합니다. " +
                                    "${overview.adjustedFast}개를 고속 저장하고 ${overview.adjustedSkips}개는 건너뜁니다. " +
                                    "이미 고속 저장 가능한 구간은 유지하며 소리는 필요하면 변환합니다.",
                                    color = secondary, style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = onRecommended, modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(4.dp)) {
                                    Text("추천 위치로 ${overview.adjustedFast}개 고속 저장")
                                }
                            }
                        }
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth >= 480.dp) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            BatchReviewSubmitButton(saveCount, overview.reviewedFiles.isNotEmpty(),
                                Modifier.weight(1f)) { onSubmit(choices.toMap()) }
                            TextButton(onClick = onDismiss) { Text("설정으로 돌아가기") }
                        }
                    } else {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            BatchReviewSubmitButton(saveCount, overview.reviewedFiles.isNotEmpty(),
                                Modifier.fillMaxWidth()) { onSubmit(choices.toMap()) }
                            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                                Text("설정으로 돌아가기")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BatchReviewSubmitButton(saveCount: Int, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.testTag("batch-review-submit"),
        shape = RoundedCornerShape(4.dp)) {
        Text(if (saveCount == 0) "전체 제외 적용" else "선택한 설정으로 ${saveCount}개 저장")
    }
}

@Composable
private fun BatchReviewFile(
    file: BatchReviewedFile,
    choice: BatchExportChoice,
    onChoice: (BatchExportChoice) -> Unit,
) {
    Column(Modifier.fillMaxWidth().selectableGroup()
        .border(1.dp, borderColor, RoundedCornerShape(4.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(file.displayName, fontWeight = FontWeight.SemiBold)
        file.requested?.let { requested ->
            Text("요청 구간 · " + requested.clips.joinToString(" + ") {
                "${formatTime(it.startMs)}–${formatTime(it.endMs)}"
            }, color = secondary, style = MaterialTheme.typography.bodySmall)
        }
        if (file.unavailableReason != null) {
            Text("저장 불가 · ${file.unavailableReason}", color = secondary,
                style = MaterialTheme.typography.bodySmall)
        } else if (file.fastAvailable) {
            Text("정확한 위치로 고속 저장 가능", color = secondary,
                style = MaterialTheme.typography.bodySmall)
        }
        if (file.canRecommend) {
            val suggestion = checkNotNull(file.suggestion)
            Text(fastCutSuggestionText(suggestion), color = secondary,
                style = MaterialTheme.typography.bodySmall)
            Box(Modifier.fillMaxWidth().testTag("batch-review-${file.uri}-preview")) {
                FastCutPreview(suggestion)
            }
        } else if (file.requested != null && !file.fastAvailable) {
            Text("검증된 추천 위치 없음 · 정확한 위치로 자동 저장할 수 있습니다.",
                color = secondary, style = MaterialTheme.typography.bodySmall)
        }
        BatchReviewChoice("정확한 위치", choice == BatchExportChoice.EXACT,
            file.requested != null, "batch-review-${file.uri}-exact") { onChoice(BatchExportChoice.EXACT) }
        BatchReviewChoice("추천 위치", choice == BatchExportChoice.RECOMMENDED,
            file.canRecommend, "batch-review-${file.uri}-recommended") { onChoice(BatchExportChoice.RECOMMENDED) }
        BatchReviewChoice("제외", choice == BatchExportChoice.SKIP,
            true, "batch-review-${file.uri}-skip") { onChoice(BatchExportChoice.SKIP) }
    }
}

@Composable
private fun BatchReviewChoice(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    onSelect: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp)
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
        .testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, enabled = enabled, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label, color = if (enabled) onSurface else secondary.copy(alpha = 0.6f))
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
    val disabled = model.isRunning || model.isRecovering
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var pendingOriginalAction by rememberSaveable { mutableStateOf<OriginalAction?>(null) }
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
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        Text("결과 저장 날짜", color = onSurface, fontWeight = FontWeight.SemiBold)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceOption("현재 날짜로 저장 (최신 파일)", model.saveDateMode == SaveDateMode.CURRENT, !disabled) {
                model.saveDateMode = SaveDateMode.CURRENT
            }
            ChoiceOption("원본 날짜 유지", model.saveDateMode == SaveDateMode.ORIGINAL, !disabled) {
                model.saveDateMode = SaveDateMode.ORIGINAL
            }
        }
        Text(
            "원본 날짜를 유지하면서 가능한 원본 복사·부분 처리를 우선 사용합니다. " +
                "원본 날짜는 촬영 메타데이터가 우선이며 없으면 파일 수정 날짜를 사용합니다. " +
                "날짜 정보가 없거나 기기가 적용을 거부하면 해당 파일은 저장하지 않습니다.",
            style = MaterialTheme.typography.bodySmall, color = secondary,
        )

        Text("저장 후 원본 처리", color = onSurface, fontWeight = FontWeight.SemiBold)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceOption("원본 유지 (기본값)", model.originalAction == OriginalAction.KEEP, !disabled) {
                model.originalAction = OriginalAction.KEEP
            }
            ChoiceOption("저장 성공한 원본만 휴지통으로 이동",
                model.originalAction == OriginalAction.TRASH, !disabled) {
                if (model.originalAction != OriginalAction.TRASH) pendingOriginalAction = OriginalAction.TRASH
            }
            ChoiceOption("저장 성공한 원본 영구 삭제",
                model.originalAction == OriginalAction.DELETE, !disabled) {
                if (model.originalAction != OriginalAction.DELETE) pendingOriginalAction = OriginalAction.DELETE
            }
        }
        Text(
            "활성화한 설정은 끌 때까지 유지됩니다. 새로 저장에 성공한 원본만 Android 승인을 요청합니다.",
            style = MaterialTheme.typography.bodySmall, color = secondary,
        )

        }
        PresetEditor(model, disabled)
    }
    pendingOriginalAction?.let { action ->
        OriginalActionEnableDialog(action,
            onConfirm = {
                if (!disabled) model.originalAction = action
                pendingOriginalAction = null
            },
            onDismiss = { pendingOriginalAction = null },
        )
    }
}


@Composable
private fun PresetEditor(model: BatchViewModel, disabled: Boolean) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { model.saveTemplate(context, asNew = true) },
                enabled = !disabled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(4.dp),
            ) { Text("새 템플릿으로 저장") }
            if (model.selectedTemplateId != null) {
                OutlinedButton(
                    onClick = { model.saveTemplate(context) },
                    enabled = !disabled,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(4.dp),
                ) { Text("선택 템플릿 수정 저장") }
                TextButton(
                    onClick = { model.removeTemplate(context) },
                    enabled = !disabled,
                ) { Text("삭제") }
            }
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
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton,
                onClick = onChoose),
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
        if (model.hasUnresolvedRecovery && !model.isRunning) {
            Text("이전 저장 파일이나 작업 기록을 확인하지 못했습니다. 파일 접근과 저장 공간을 확인한 뒤 다시 확인해 주세요.",
                color = secondary, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = model::retryRecovery,
                enabled = !model.isRunning && !model.isRecovering && !model.isImporting && !inspecting,
                modifier = Modifier.fillMaxWidth().testTag("batch-recovery-retry")) {
                Text(if (model.isRecovering) "이전 작업 확인 중" else "이전 작업 다시 확인")
            }
        }
        when (model.runState) {
            BatchRunState.SETUP -> {
                Button(
                    onClick = { requestExport(false) },
                    enabled = model.files.isNotEmpty() && model.previewTemplate() != null &&
                        !model.isImporting && !model.isRecovering && !model.hasUnresolvedRecovery && !inspecting,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(4.dp),
                ) { Text("일괄 편집 및 저장 시작") }
            }
            BatchRunState.RUNNING -> {
                Text(model.currentExportStage.label, color = secondary,
                    style = MaterialTheme.typography.bodySmall)
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
                        enabled = !model.isRecovering && !model.hasUnresolvedRecovery && !inspecting && model.previewTemplate() != null &&
                            (model.failures > 0 || model.skips > 0),
                        shape = RoundedCornerShape(4.dp),
                    ) { Text("실패 작업 재시도") }
                    TextButton(enabled = !model.isRecovering, onClick = model::resetBatch) { Text("새 작업") }
                }
                ExportDiagnosticButton()
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
    stage: com.hebi.quickcut.media.ExportStage,
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
            BatchFileStatus.RUNNING -> stage.label +
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
        file.diagnosticId?.let { operationId ->
            ExportDiagnosticButton(operationId = operationId, label = "저장 진단")
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
            if (model.originalStatus == OriginalActionStatus.SKIPPED) {
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


