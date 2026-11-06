package com.hebi.quickcut.batch

import android.app.Activity
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
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
import com.hebi.quickcut.media.ExportStage
import com.hebi.quickcut.media.MediaOperationDiagnostics
import com.hebi.quickcut.ui.CutChoice
import com.hebi.quickcut.ui.CutChoiceDropdown
import com.hebi.quickcut.ui.CutColors
import com.hebi.quickcut.ui.QuickCutDialogHeading
import com.hebi.quickcut.ui.CutPanelShape
import com.hebi.quickcut.ui.CutDialogShape
import com.hebi.quickcut.ui.CutShape
import com.hebi.quickcut.ui.CutInfoSection
import com.hebi.quickcut.ui.exportReportText
import com.hebi.quickcut.ui.FastCutPreview
import com.hebi.quickcut.ui.ExportDiagnosticButton
import com.hebi.quickcut.ui.fastCutSuggestionText
import com.hebi.quickcut.ui.OriginalActionEnableDialog
import kotlinx.coroutines.launch

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
                    model.notifyUser(MediaOperationDiagnostics.message(ExportStage.REVIEWING, error))
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

    BoxWithConstraints(Modifier.fillMaxSize().background(CutColors.background)) {
        val actionsMaxHeight = maxHeight * 0.45f
        if (maxWidth >= 840.dp &&
            maxWidth / LocalDensity.current.fontScale >= 700.dp) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.width(390.dp)
                        .verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    BatchHeader(guardedBack)
                    TemplateEditor(model, onSelectVideos)
                }
                Column(
                    Modifier.weight(1f).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
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
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = actionsMaxHeight)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        BatchProgress(model)
                        BatchActions(model, requestExport, inspecting) { showStopConfirmation = true }
                    }
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
            Column(
                Modifier.fillMaxWidth().heightIn(max = actionsMaxHeight)
                    .verticalScroll(rememberScrollState()).padding(12.dp),
            ) {
                BatchActions(model, requestExport, inspecting) { showStopConfirmation = true }
            }
            }
        }
    }

    if (showStopConfirmation) {
        AlertDialog(
            onDismissRequest = { showStopConfirmation = false },
            title = { QuickCutDialogHeading("일괄 저장을 중단할까요?") },
            text = {
                CutInfoSection(
                    title = "일괄 저장 중단",
                    message = "이미 저장된 영상은 유지하고 진행 중인 파일은 취소합니다.",
                    caution = true,
                )
            },
            confirmButton = {
                Button(enabled = !model.isFinalizing, onClick = {
                    showStopConfirmation = false
                    model.cancel()
                }, modifier = Modifier.heightIn(min = 48.dp), shape = CutShape) { Text("저장 중단") }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirmation = false },
                    modifier = Modifier.heightIn(min = 48.dp), shape = CutShape) {
                    Text("계속 저장")
                }
            },
            containerColor = CutColors.panel,
            shape = CutDialogShape,
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
            title = { QuickCutDialogHeading("저장할 영상 확인 중") },
            text = {
                Text(
                    "키프레임과 자르기 설정을 확인하고 있습니다. 긴 영상은 분석에 시간이 걸릴 수 있습니다." +
                        (if (inspectedTotal > 0) " ($inspectedCount / $inspectedTotal)" else "") +
                        (inspectedPercent?.let { " · 현재 영상 $it%" } ?: "")
                )
            },
            confirmButton = { TextButton(onClick = { inspectionJob?.cancel() },
                modifier = Modifier.heightIn(min = 48.dp), shape = CutShape) { Text("분석 취소") } },
            containerColor = CutColors.panel,
            shape = CutDialogShape,
        )
    }

    model.notification?.let { notice ->
        AlertDialog(
            onDismissRequest = model::clearNotification,
            title = { QuickCutDialogHeading("안내") },
            text = {
                CutInfoSection(
                    title = "작업 안내",
                    message = notice,
                    caution = true,
                )
            },
            confirmButton = { TextButton(onClick = model::clearNotification,
                modifier = Modifier.heightIn(min = 48.dp), shape = CutShape) { Text("확인") } },
            containerColor = CutColors.panel,
            shape = CutDialogShape,
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
internal fun BatchReviewDialog(
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
                .widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight(0.94f).testTag("batch-review-dialog"),
            color = CutColors.panel, contentColor = CutColors.primaryText,
            shape = CutDialogShape,
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val footerMaxHeight = maxHeight * 0.45f
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth().testTag("batch-review-list"),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("자동 저장 확인", style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold)
                                Text("정확한 위치 $exactCount · 추천 위치 $recommendedCount · 제외 $skippedCount",
                                    style = MaterialTheme.typography.bodySmall, color = CutColors.secondaryAccent)
                            }
                        }
                        item {
                            Column(
                                Modifier.fillMaxWidth().background(CutColors.surface, CutShape).padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                templateSummary?.let { Text(it) }
                                CutInfoSection(
                                    title = "저장 방식 확인",
                                    message = "정확한 위치는 요청한 구간을 유지하고 필요하면 변환합니다. " +
                                        "추천 위치는 구간을 조정해 영상 변환을 피합니다.",
                                )
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
                                    color = CutColors.secondaryText, style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = onAutomatic, enabled = overview.ready > 0,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = CutShape) {
                                    Text("전체 자동 저장")
                                }
                                if (overview.reviewedFiles.any { it.canRecommend }) {
                                    Text("추천 위치를 일괄 적용하면 ${overview.adjustedFast}개를 고속 저장하고 " +
                                        "${overview.adjustedSkips}개를 건너뜁니다. 소리는 필요할 때 변환합니다.",
                                        color = CutColors.secondaryText, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    BoxWithConstraints(
                        Modifier.fillMaxWidth().heightIn(max = footerMaxHeight)
                            .verticalScroll(rememberScrollState())
                            .background(CutColors.surface, CutShape).padding(10.dp).testTag("batch-review-footer"),
                    ) {
                        val useHorizontalActions = maxWidth / LocalDensity.current.fontScale >= 560.dp
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val recommendedAvailable = overview.reviewedFiles.any { it.canRecommend }
                            if (useHorizontalActions) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (recommendedAvailable) BatchRecommendedButton(overview, Modifier.weight(1f), onRecommended)
                                    BatchReviewSubmitButton(saveCount, overview.reviewedFiles.isNotEmpty(),
                                        Modifier.weight(1f)) { onSubmit(choices.toMap()) }
                                }
                            } else {
                                if (recommendedAvailable) BatchRecommendedButton(overview, Modifier.fillMaxWidth(), onRecommended)
                                BatchReviewSubmitButton(saveCount, overview.reviewedFiles.isNotEmpty(),
                                    Modifier.fillMaxWidth()) { onSubmit(choices.toMap()) }
                            }
                            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
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
private fun BatchRecommendedButton(overview: BatchExportOverview, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = overview.adjustedFast > 0,
        modifier = modifier.heightIn(min = 52.dp).testTag("batch-review-recommended-submit"), shape = CutShape) {
        Text("추천 위치로 ${overview.adjustedFast}개 고속 저장")
    }
}

@Composable
private fun BatchReviewSubmitButton(saveCount: Int, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = 52.dp).testTag("batch-review-submit"),
        shape = CutShape) {
        Text(if (saveCount == 0) "전체 제외 적용" else "선택한 설정으로 ${saveCount}개 저장")
    }
}

@Composable
private fun BatchReviewFile(
    file: BatchReviewedFile,
    choice: BatchExportChoice,
    onChoice: (BatchExportChoice) -> Unit,
) {
    Column(Modifier.fillMaxWidth()
        .background(CutColors.surface, CutPanelShape)
        .border(1.dp, CutColors.border, CutPanelShape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(file.displayName, fontWeight = FontWeight.SemiBold)
        file.requested?.let { requested ->
            Text("요청 구간 · " + requested.clips.joinToString(" + ") {
                "${formatTime(it.startMs)}–${formatTime(it.endMs)}"
            }, color = CutColors.secondaryText, style = MaterialTheme.typography.bodySmall)
        }
        if (file.unavailableReason != null) {
            Text("저장 불가 · ${file.unavailableReason}", color = CutColors.secondaryText,
                style = MaterialTheme.typography.bodySmall)
        } else if (file.fastAvailable) {
            Text("정확한 위치로 고속 저장 가능", color = CutColors.secondaryText,
                style = MaterialTheme.typography.bodySmall)
        }
        if (file.canRecommend) {
            val suggestion = checkNotNull(file.suggestion)
            Text(fastCutSuggestionText(suggestion), color = CutColors.secondaryText,
                style = MaterialTheme.typography.bodySmall)
            Box(Modifier.fillMaxWidth().testTag("batch-review-${file.uri}-preview")) {
                FastCutPreview(suggestion)
            }
        } else if (file.requested != null && !file.fastAvailable) {
            Text("검증된 추천 위치 없음 · 정확한 위치로 자동 저장할 수 있습니다.",
                color = CutColors.secondaryText, style = MaterialTheme.typography.bodySmall)
        }
        if (file.reencodeRequired) {
            Text("이 영상은 고속 저장을 완료할 수 없어 영상 변환이 필요합니다. 정확한 요청 구간을 유지하며 변환해 저장하거나 제외해 주세요.",
                color = CutColors.secondaryText, style = MaterialTheme.typography.bodySmall)
        }
        CutChoiceDropdown("저장 방식", choice, listOf(
            CutChoice(BatchExportChoice.EXACT,
                if (file.reencodeRequired) "정확한 위치 · 영상 변환 허용" else "정확한 위치", file.requested != null),
            CutChoice(BatchExportChoice.RECOMMENDED, "추천 위치", file.canRecommend),
            CutChoice(BatchExportChoice.SKIP, "제외"),
        ), modifier = Modifier.testTag("batch-review-${file.uri}-choice"), onSelected = onChoice)
    }
}

@Composable
private fun BatchHeader(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "뒤로가기",
                tint = CutColors.accent)
        }
        Column(Modifier.weight(1f)) {
            Text("일괄 편집", fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                color = CutColors.primaryText)
            Text("여러 영상을 같은 설정으로 정리하세요.",
                color = CutColors.secondaryText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TemplateEditor(model: BatchViewModel, onSelectVideos: () -> Unit) {
    val disabled = model.isRunning || model.isRecovering
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var pendingOriginalAction by rememberSaveable { mutableStateOf<OriginalAction?>(null) }
    Column(
        modifier = Modifier.fillMaxWidth().background(CutColors.surface, CutPanelShape)
            .border(1.dp, CutColors.accent.copy(alpha = 0.35f), CutPanelShape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("1. 영상 선택", color = CutColors.secondaryAccent,
                style = MaterialTheme.typography.titleMedium)
            val addModifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .testTag("batch-add-videos")
            val addContent: @Composable () -> Unit = {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (model.isImporting) "영상 불러오는 중" else "영상 선택·추가")
            }
            if (model.files.isEmpty()) {
                Button(onClick = onSelectVideos, enabled = !disabled && !model.isImporting,
                    modifier = addModifier, shape = CutShape) { addContent() }
            } else {
                OutlinedButton(onClick = onSelectVideos, enabled = !disabled && !model.isImporting,
                    modifier = addModifier, shape = CutShape) { addContent() }
            }
            Text("선택한 영상 ${model.files.size}개", color = CutColors.secondaryText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("batch-selection-count"))
        }
        PresetEditor(model, disabled)
        Text(
            "2. 자르기 설정",
            color = CutColors.primaryText,
            fontWeight = FontWeight.SemiBold,
        )
        Text("마지막 사용값은 자동으로 복원됩니다.",
            style = MaterialTheme.typography.bodySmall, color = CutColors.secondaryText)
        CutChoiceDropdown("편집 방식", model.draftMode, listOf(
            CutChoice(BatchClipMode.REMOVE_EDGES, "앞뒤 구간 자르기"),
            CutChoice(BatchClipMode.KEEP_FIRST, "처음 구간만 남기기"),
            CutChoice(BatchClipMode.KEEP_LAST, "마지막 구간만 남기기"),
            CutChoice(BatchClipMode.JOIN_EDGES, "처음 + 마지막 구간 연결"),
        ), enabled = !disabled, modifier = Modifier.testTag("batch-mode")) { model.draftMode = it }
        Text("분·초 입력 · 예: 1분 5.5초 · 소수점 셋째 자리까지 입력 가능",
            style = MaterialTheme.typography.bodySmall, color = CutColors.secondaryText)
        val useLeading = model.draftMode.usesLeading
        val useTrailing = model.draftMode.usesTrailing
        val removing = model.draftMode == BatchClipMode.REMOVE_EDGES
        BatchDurationField(
            title = if (removing) "앞에서 자를 시간" else "처음부터 남길 시간",
            value = model.draftLeadingSeconds,
            onValueChange = { model.draftLeadingSeconds = it },
            enabled = !disabled && useLeading,
            used = useLeading,
            tag = "batch-leading",
        )
        BatchDurationField(
            title = if (removing) "뒤에서 자를 시간" else "끝에서 남길 시간",
            value = model.draftTrailingSeconds,
            onValueChange = { model.draftTrailingSeconds = it },
            enabled = !disabled && useTrailing,
            used = useTrailing,
            tag = "batch-trailing",
        )
        val template = model.previewTemplate()
        Text(
            template?.let(BatchTimeInput::summary) ?: model.draftError().orEmpty(),
            color = if (template != null) CutColors.secondaryAccent else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().testTag("batch-edit-summary"),
        )
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clip(CutShape)
                .background(if (model.draftMute) CutColors.accentMuted else CutColors.surface)
                .clickable(enabled = !disabled) { model.draftMute = !model.draftMute }
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = model.draftMute,
                onCheckedChange = { model.draftMute = it },
                enabled = !disabled,
            )
            Text(if (model.draftMute) "원본 소리: 음소거" else "원본 소리: 유지", color = CutColors.primaryText,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp))
        }
        TextButton(
            onClick = { showAdvanced = !showAdvanced },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(if (showAdvanced) "템플릿·저장 옵션 접기" else "템플릿·저장 옵션 펼치기") }
        Text(
            "저장 날짜: " + (if (model.saveDateMode == SaveDateMode.CURRENT)
                "현재 날짜" else "원본 날짜") +
                " · 저장 후 원본: " + when (model.originalAction) {
                    OriginalAction.KEEP -> "유지"
                    OriginalAction.TRASH -> "휴지통"
                    OriginalAction.DELETE -> "영구 삭제"
                },
            color = CutColors.secondaryText,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (showAdvanced) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CutChoiceDropdown("결과 저장 날짜", model.saveDateMode, listOf(
                    CutChoice(SaveDateMode.CURRENT, "현재 날짜로 저장 (최신 파일)"),
                    CutChoice(SaveDateMode.ORIGINAL, "원본 날짜 유지"),
                ), enabled = !disabled, modifier = Modifier.testTag("batch-date")) { model.saveDateMode = it }
                Text(
                    "원본 날짜를 유지하면서 가능한 원본 복사·부분 처리를 우선 사용합니다. " +
                        "원본 날짜는 촬영 메타데이터가 우선이며 없으면 파일 수정 날짜를 사용합니다. " +
                        "날짜 정보가 없거나 기기가 적용을 거부하면 해당 파일은 저장하지 않습니다.",
                    style = MaterialTheme.typography.bodySmall, color = CutColors.secondaryText,
                )

                CutChoiceDropdown("저장 후 원본 처리", model.originalAction, listOf(
                    CutChoice(OriginalAction.KEEP, "원본 유지 (기본값)"),
                    CutChoice(OriginalAction.TRASH, "저장 성공한 원본만 휴지통으로 이동"),
                    CutChoice(OriginalAction.DELETE, "저장 성공한 원본 영구 삭제"),
                ), enabled = !disabled, modifier = Modifier.testTag("batch-original-action")) { action ->
                    if (action == OriginalAction.KEEP) model.originalAction = action
                    else if (model.originalAction != action) pendingOriginalAction = action
                }
                Text(
                    "활성화한 설정은 끌 때까지 유지됩니다. 새로 저장에 성공한 원본만 Android 승인을 요청합니다.",
                    style = MaterialTheme.typography.bodySmall, color = CutColors.secondaryText,
                )
            }
        }
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
    var showManager by rememberSaveable { mutableStateOf(false) }
    val selected = model.templates.firstOrNull { it.id == model.selectedTemplateId }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("선택 템플릿: " + (selected?.name ?: "직접 설정"), color = CutColors.primaryText)
        selected?.let { Text(BatchTimeInput.summary(it), style = MaterialTheme.typography.bodySmall,
            color = CutColors.secondaryText) }
        OutlinedButton(onClick = { showManager = true }, enabled = !disabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("batch-template-manager")) {
            Text("템플릿 관리")
        }
    }
    if (showManager) Dialog(
        onDismissRequest = { showManager = false },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.padding(horizontal = 16.dp).widthIn(max = 480.dp)
            .fillMaxWidth().heightIn(max = 640.dp)
            .testTag("batch-template-dialog"), shape = CutDialogShape,
            color = CutColors.panel, contentColor = CutColors.primaryText) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("내 템플릿", style = MaterialTheme.typography.titleLarge)
                    Text("${model.templates.size}개 저장됨",
                        style = MaterialTheme.typography.bodySmall, color = CutColors.secondaryAccent)
                }
                Text("템플릿에는 자르기·음소거 설정만 저장합니다. 저장 날짜와 원본 처리 설정은 변경되지 않습니다.",
                    style = MaterialTheme.typography.bodySmall, color = CutColors.secondaryText)
                LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().testTag("batch-template-list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        OutlinedButton(onClick = { model.chooseTemplate(null) }, enabled = !disabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .testTag("batch-template-direct")) { Text("직접 설정") }
                    }
                    if (model.templates.isEmpty()) {
                        item {
                            Text("저장된 템플릿이 없습니다. 설정을 입력하고 새 템플릿을 저장하세요.",
                                color = CutColors.secondaryText,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    items(model.templates, key = { it.id }) { template ->
                        val isSelected = model.selectedTemplateId == template.id
                        Column(
                            Modifier.fillMaxWidth().background(CutColors.surface, CutShape)
                                .border(1.dp,
                                    if (isSelected) CutColors.accent else CutColors.border,
                                    CutShape).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.size(44.dp).background(CutColors.accentMuted, CutShape),
                                    contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.ContentCut, contentDescription = null,
                                        tint = CutColors.secondaryAccent)
                                }
                                Column(Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(template.name, style = MaterialTheme.typography.titleSmall,
                                        color = CutColors.primaryText, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                    Text(BatchTimeInput.summary(template),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = CutColors.secondaryText, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis)
                                }
                                if (isSelected) {
                                    Text("선택됨", style = MaterialTheme.typography.labelSmall,
                                        color = CutColors.secondaryAccent)
                                }
                            }
                            if (LocalDensity.current.fontScale >= 1.5f) {
                                OutlinedButton(onClick = { model.chooseTemplate(template.id) },
                                    enabled = !disabled,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                        .testTag("batch-template-${template.id}-choose")) {
                                    Text("이 템플릿 선택")
                                }
                                TextButton(onClick = { model.removeTemplate(context, template.id) },
                                    enabled = !disabled,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                        .testTag("batch-template-${template.id}-remove")) {
                                    Text("삭제", color = CutColors.error)
                                }
                            } else {
                                Row(Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedButton(onClick = { model.chooseTemplate(template.id) },
                                        enabled = !disabled,
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                            .testTag("batch-template-${template.id}-choose")) {
                                        Text("이 템플릿 선택")
                                    }
                                    TextButton(onClick = { model.removeTemplate(context, template.id) },
                                        enabled = !disabled,
                                        modifier = Modifier.heightIn(min = 48.dp)
                                            .testTag("batch-template-${template.id}-remove")) {
                                        Text("삭제", color = CutColors.error)
                                    }
                                }
                            }
                        }
                    }
                    item {
                        OutlinedTextField(value = model.draftName, onValueChange = { model.draftName = it.take(40) },
                            enabled = !disabled, label = { Text("템플릿 이름") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("batch-template-name"))
                    }
                    item {
                        OutlinedButton(onClick = { model.saveTemplate(context, asNew = true) },
                            enabled = !disabled && model.templates.size < 30,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("새 템플릿으로 저장") }
                        if (model.templates.size >= 30) Text("템플릿은 최대 30개까지 저장할 수 있습니다.")
                        if (model.selectedTemplateId != null) OutlinedButton(onClick = { model.saveTemplate(context) },
                            enabled = !disabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("선택 템플릿 수정 저장") }
                    }
                }
                TextButton(onClick = { showManager = false },
                    modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) { Text("닫기") }
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = if (used) CutColors.primaryText else CutColors.secondaryText,
            fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = if (used) parts.minutes else "",
                onValueChange = { onValueChange(it.take(16) + ":" + parts.seconds) },
                enabled = enabled,
                label = { Text("분") },
                placeholder = { Text("0") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = error != null,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("$tag-minutes")
                    .semantics { contentDescription = "$title, 분" },
                singleLine = true,
                shape = CutShape,
            )
            OutlinedTextField(
                value = if (used) parts.seconds else "",
                onValueChange = { onValueChange(parts.minutes + ":" + it.take(16)) },
                enabled = enabled,
                label = { Text("초") },
                placeholder = { Text("0") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = error != null,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("$tag-seconds")
                    .semantics { contentDescription = "$title, 초" },
                singleLine = true,
                shape = CutShape,
            )
        }
        if (!used || error != null) {
            Text(
                if (!used) "이 편집 방식에서는 사용하지 않습니다." else error.orEmpty(),
                color = if (error != null) MaterialTheme.colorScheme.error else CutColors.secondaryText,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun BatchActions(
    model: BatchViewModel,
    requestExport: (Boolean) -> Unit,
    inspecting: Boolean,
    onCancel: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(CutColors.surface, CutPanelShape)
            .border(1.dp, CutColors.border, CutPanelShape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (model.hasUnresolvedRecovery && !model.isRunning) {
            Text("이전 저장 파일이나 작업 기록을 확인하지 못했습니다. 파일 접근과 저장 공간을 확인한 뒤 다시 확인해 주세요.",
                color = CutColors.secondaryText, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = model::retryRecovery,
                enabled = !model.isRunning && !model.isRecovering && !model.isImporting && !inspecting,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("batch-recovery-retry"), shape = CutShape) {
                Text(if (model.isRecovering) "이전 작업 확인 중" else "이전 작업 다시 확인")
            }
        }
        when (model.runState) {
            BatchRunState.SETUP -> {
                Text("3. 저장", color = CutColors.primaryText,
                    style = MaterialTheme.typography.titleMedium)
                val disabledReason = when {
                    inspecting -> "저장할 영상을 확인 중입니다."
                    model.isImporting -> "영상을 불러온 뒤 저장할 수 있습니다."
                    model.isRecovering -> "이전 작업 확인 후 저장할 수 있습니다."
                    model.hasUnresolvedRecovery -> "이전 작업을 다시 확인한 뒤 저장해 주세요."
                    model.files.isEmpty() -> "저장할 영상을 먼저 추가해 주세요."
                    model.previewTemplate() == null -> model.draftError() ?: "자르기 설정을 확인해 주세요."
                    else -> null
                }
                Button(
                    onClick = { requestExport(false) },
                    enabled = disabledReason == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("batch-save"),
                    shape = CutShape,
                ) { Text("일괄 저장") }
                disabledReason?.let { reason ->
                    Text(reason, color = CutColors.secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("batch-save-disabled-reason"))
                }
            }
            BatchRunState.RUNNING -> {
                Text(model.currentExportStage.label, color = CutColors.secondaryText,
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = onCancel,
                    enabled = !model.isFinalizing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = CutShape,
                ) { Text("일괄 저장 중단") }
                Text(
                    "다른 앱으로 이동하거나 화면을 꺼도 순서대로 저장합니다. " +
                        "앱이 종료되면 완료 파일을 복구하고 남은 작업은 재검토 후 다시 시작할 수 있습니다.",
                    color = CutColors.secondaryText, style = MaterialTheme.typography.bodySmall,
                )
            }
            BatchRunState.FINISHED -> {
                Text(
                    "완료 " + model.successes + "개 · 실패 " + model.failures +
                        "개 · 건너뜀 " + model.skips + "개",
                    color = CutColors.primaryText, fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { requestExport(true) },
                        enabled = !model.isRecovering && !model.hasUnresolvedRecovery && !inspecting && model.previewTemplate() != null &&
                            (model.failures > 0 || model.skips > 0),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = CutShape,
                    ) { Text("실패 작업 재시도") }
                    TextButton(enabled = !model.isRecovering, onClick = model::resetBatch,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("새 작업") }
                }
                ExportDiagnosticButton()
            }
        }
    }
}

@Composable
private fun BatchFileHeader(model: BatchViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Outlined.FolderOpen, contentDescription = null,
            tint = CutColors.secondaryAccent, modifier = Modifier.size(22.dp))
        Text(
            "선택 영상 " + model.files.size + "개 · 원본 길이 → 저장 후 길이",
            color = CutColors.primaryText, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
    }
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
        modifier = Modifier.fillMaxWidth().background(CutColors.surface, CutPanelShape)
            .border(1.dp, CutColors.border, CutPanelShape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    file.displayName, color = CutColors.primaryText,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                )
                Text(subtitle, color = CutColors.secondaryText, fontSize = 12.sp)
                file.originalDate?.let {
                    if (dateMode == SaveDateMode.ORIGINAL) {
                        Text(
                            "날짜 출처: " + it.source,
                            color = CutColors.secondaryText, fontSize = 11.sp,
                        )
                    }
                }
            }
            if (canRemove) {
                IconButton(
                    onClick = { onRemove(file.uri) }, modifier = Modifier.size(48.dp)
                        .background(CutColors.panel, CutShape),
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
            color = if (file.status == BatchFileStatus.FAILED) MaterialTheme.colorScheme.error
                else CutColors.secondaryText,
            fontSize = 12.sp,
        )
        if (file.status == BatchFileStatus.SAVED) {
            Text(exportReportText(file.exportReport), color = CutColors.secondaryText, fontSize = 12.sp)
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
        if (model.isRecovering) Text("이전 작업과 완료 파일을 확인 중입니다.", color = CutColors.secondaryText)
        if (model.isRunning) Text(
            if (model.isFinalizing) "파일 등록 완료 처리 중입니다. 완료 후 다음 작업을 중단할 수 있습니다."
            else "화면을 끄거나 다른 앱으로 이동해도 저장이 계속됩니다. 알림에서 진행 상태와 중단을 확인할 수 있습니다.",
            color = CutColors.secondaryText, fontSize = 12.sp,
        )
        if (model.files.isNotEmpty()) {
            Text(
                "진행 " + model.completedCount + " / " + model.files.size,
                color = CutColors.secondaryText, fontSize = 13.sp,
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
            Text(status, color = CutColors.secondaryText)
            if (model.originalStatus == OriginalActionStatus.SKIPPED) {
                OutlinedButton(
                    onClick = model::promptOriginalApproval,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = CutShape,
                ) {
                    Text("저장 성공 원본 " + model.originalCandidates.size + "개 " +
                        actionText + " 요청")
                }
            }
        }
    }
}


