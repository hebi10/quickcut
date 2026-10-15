package com.hebi.quickcut.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hebi.quickcut.QuickCutApplication
import com.hebi.quickcut.batch.BatchScreen
import com.hebi.quickcut.batch.BatchViewModel
import com.hebi.quickcut.editor.EditorViewModel
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.TrimInput
import com.hebi.quickcut.editor.OriginalAction
import com.hebi.quickcut.editor.OriginalActionStatus
import com.hebi.quickcut.media.OriginalMediaActions
import com.hebi.quickcut.media.SaveDateMode
import com.hebi.quickcut.media.ExportMode
import com.hebi.quickcut.media.ExportPlanner
import com.hebi.quickcut.media.ExportAssessment
import com.hebi.quickcut.media.ExportReport
import com.hebi.quickcut.settings.QuickCutSettings
import com.hebi.quickcut.settings.QuickTrimSettings
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.hebi.quickcut.editor.QuickCutPage

private data class ExportReview(
    val assessment: ExportAssessment,
    val timeline: EditTimeline,
    val originalAction: OriginalAction,
    val dateMode: SaveDateMode,
    val retryingFastExport: Boolean = false,
)

@Composable
fun QuickCutApp(
    model: EditorViewModel =
        (LocalContext.current.applicationContext as QuickCutApplication).editorModel,
    batchModel: BatchViewModel =
        (LocalContext.current.applicationContext as QuickCutApplication).batchModel,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val settings = remember(context) { QuickCutSettings(context) }
    val coroutineScope = rememberCoroutineScope()
    var showBatch by rememberSaveable { mutableStateOf(false) }
    var showLastSettings by rememberSaveable { mutableStateOf(false) }
    var exportReview by remember { mutableStateOf<ExportReview?>(null) }
    var inspectingExport by remember { mutableStateOf(false) }
    var showExportCancelConfirmation by rememberSaveable { mutableStateOf(false) }
    var showSaveOptions by rememberSaveable { mutableStateOf(false) }
    var selectedSaveAction by remember { mutableStateOf(OriginalAction.KEEP) }
    var selectedSaveDateMode by rememberSaveable {
        mutableStateOf(settings.loadSaveDate())
    }
    var draftSaveDateMode by remember { mutableStateOf(selectedSaveDateMode) }
    val openSaveOptions: () -> Unit = {
        if (model.canStartExport && !inspectingExport) {
            draftSaveDateMode = selectedSaveDateMode
            showSaveOptions = true
        }
    }
    LaunchedEffect(selectedSaveDateMode) {
        settings.saveSaveDate(selectedSaveDateMode)
    }
    DisposableEffect(model, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) model.saveSessionOnStop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val beginExportAfterReview: () -> Unit = {
        if (model.canStartExport && !inspectingExport) {
            val reviewedTimeline = model.timeline
            val reviewedAction = selectedSaveAction
            val reviewedDate = selectedSaveDateMode
            inspectingExport = true
            coroutineScope.launch {
                try {
                    val assessment = ExportPlanner.analyze(
                        context.applicationContext, reviewedTimeline, reviewedDate
                    )
                    if (!model.canStartExport || model.timeline != reviewedTimeline) return@launch
                    if (assessment.fastAvailable && !model.needsReencodeConsent) {
                        model.startExport(
                            context,
                            reviewedAction,
                            reviewedDate,
                            ExportMode.FAST_ONLY,
                        )
                        selectedSaveAction = OriginalAction.KEEP
                    } else {
                        exportReview = ExportReview(assessment, reviewedTimeline,
                            reviewedAction, reviewedDate)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    model.reportError(
                        "저장 방식을 확인하지 못했습니다. " +
                            (error.localizedMessage ?: "다시 시도해 주세요.")
                    )
                } finally {
                    inspectingExport = false
                }
            }
        }
    }
    LaunchedEffect(model.needsReencodeConsent, model.page, model.timeline,
        model.isTrimInputValid, model.canStartExport) {
        if (model.needsReencodeConsent && model.canStartExport) {
            val reviewedTimeline = model.timeline
            val reviewedAction = model.originalAction
            val reviewedDate = model.savedDateMode
            try {
                val assessment = ExportPlanner.analyze(
                    context.applicationContext, reviewedTimeline, reviewedDate
                )
                if (model.needsReencodeConsent && model.canStartExport &&
                    model.timeline == reviewedTimeline) {
                    exportReview = ExportReview(assessment, reviewedTimeline,
                        reviewedAction, reviewedDate, retryingFastExport = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                model.reportError(
                    "고속 저장을 완료하지 못했습니다. 정확한 위치로 자동 저장을 다시 선택해 주세요."
                )
            }
        }
    }
    LaunchedEffect(model.page) {
        if (model.page != QuickCutPage.EDITOR) {
            exportReview = null
            showSaveOptions = false
        }
        if (model.page == QuickCutPage.PICKER) selectedSaveAction = OriginalAction.KEEP
    }
    val originalActionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        model.onOriginalSystemResult(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(model.completedUri, model.originalActionStatus) {
        if (model.page == QuickCutPage.COMPLETED &&
            model.originalActionStatus == OriginalActionStatus.READY
        ) {
            model.showOriginalDeletionConfirmation()
        }
    }
    val quickPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            model.changeQuickMode(true)
            model.importVideos(context, listOf(uri), quickDefaults = settings.loadQuick())
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            model.changeQuickMode(false)
            model.importVideos(context, uris, append = model.page == QuickCutPage.EDITOR)
        }
    }
    val batchPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        batchModel.addVideos(context, uris)
    }
    val batchOriginalLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        batchModel.originalApprovalResult(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(showBatch) {
        if (showBatch) batchModel.loadTemplates(context)
    }

    BackHandler(enabled = !showBatch && model.page != QuickCutPage.PICKER) {
        when (model.page) {
            QuickCutPage.EDITOR -> model.requestExit()
            QuickCutPage.EXPORTING -> if (!model.isFinalizing)
                showExportCancelConfirmation = true
            QuickCutPage.COMPLETED -> model.startNew()
            else -> Unit
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CutColors.background)
            .safeDrawingPadding(),
    ) {
        if (showBatch) {
            BatchScreen(
                model = batchModel,
                onSelectVideos = { batchPicker.launch(arrayOf("video/*")) },
                onBack = {
                    settings.saveSaveDate(batchModel.saveDateMode)
                    selectedSaveDateMode = batchModel.saveDateMode
                    showBatch = false
                },
                onApproveOriginals = { sources, outputUri, action ->
                    try {
                        val request = OriginalMediaActions.prepareRequest(
                            context = context,
                            originalSources = sources,
                            savedOutput = outputUri,
                            action = action,
                        )
                        batchModel.launchingOriginalApproval()
                        batchOriginalLauncher.launch(request)
                    } catch (error: Exception) {
                        batchModel.originalApprovalUnsupported(
                            "편집 결과는 저장됐지만 원본은 삭제하지 않았습니다. " +
                                (error.localizedMessage ?: "이 파일의 원본 처리가 지원되지 않습니다.")
                        )
                    }
                },
            )
        } else when (model.page) {
            QuickCutPage.PICKER -> StartScreen(
                onQuick = { quickPicker.launch(arrayOf("video/*")) },
                onSelect = { picker.launch(arrayOf("video/*")) },
                onBatch = {
                    batchModel.saveDateMode = selectedSaveDateMode
                    showBatch = true
                },
                canRestore = model.hasRestorableSession,
                onRestore = { model.restoreSavedSession(context) },
                quickSettings = settings.loadQuick(),
                dateMode = selectedSaveDateMode,
                onPreferences = { showLastSettings = true },
            )
            QuickCutPage.IMPORTING -> CenterStatus("영상 불러오는 중", "선택한 파일을 확인하고 있습니다.", null)
            QuickCutPage.EDITOR -> {
                if (model.quickMode && model.timeline.clips.size == 1) {
                    QuickTrimScreen(
                        model = model,
                        settings = settings,
                        onBack = model::requestExit,
                        onDetailedEdit = { model.changeQuickMode(false) },
                        onSave = { beginExportAfterReview() },
                        onSaveOptions = openSaveOptions,
                    )
                } else {
                    EditorScreen(
                        model = model,
                        addVideos = { picker.launch(arrayOf("video/*")) },
                        save = openSaveOptions,
                    )
                }
            }
            QuickCutPage.EXPORTING -> CenterStatus(
                title = when {
                    model.isFinalizing -> "갤러리 등록 중"
                    model.requestedExportMode == ExportMode.FAST_ONLY -> "고속 저장 중"
                    else -> "정확한 위치로 자동 저장 중"
                },
                subtitle = if (model.isFinalizing)
                    "결과물을 확인하고 갤러리에 등록하고 있습니다. 완료 단계에서는 취소할 수 없습니다."
                else if (model.requestedExportMode == ExportMode.FAST_ONLY)
                    "압축된 영상 데이터를 복사하고 있습니다. 다른 앱을 사용하거나 화면을 꺼도 저장은 계속됩니다."
                else "요청한 구간을 유지하며 저장합니다. 복사·최적화가 안 되면 재인코딩하며, 처리 시간은 영상과 기기에 따라 달라집니다.",
                progress = if (model.isFinalizing) null else model.progressPercent,
                onCancel = if (model.isFinalizing) null
                    else { { showExportCancelConfirmation = true } },
            )
            QuickCutPage.COMPLETED -> CompletedScreen(
                videoUri = model.completedUri?.toString(),
                onOpen = {
                    val uri = model.completedUri ?: return@CompletedScreen
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "video/mp4")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        model.reportError("영상을 열 수 있는 앱을 찾을 수 없습니다.")
                    } catch (_: SecurityException) {
                        model.reportError("저장된 영상에 접근할 수 없습니다.")
                    }
                },
                onNew = model::startNew,
                originalAction = model.originalAction,
                savedDateMode = model.savedDateMode,
                exportReport = model.completedExportReport,
                originalStatus = model.originalActionStatus,
                originalCount = model.originalsFromSavedTimeline.size,
                onRetryOriginalAction = model::showOriginalDeletionConfirmation,
            )
        }
    }


    if (showSaveOptions && model.page == QuickCutPage.EDITOR) {
        SaveOptionsDialog(
            originalAction = selectedSaveAction,
            onOriginalActionChange = { selectedSaveAction = it },
            dateMode = draftSaveDateMode,
            onDateModeChange = { draftSaveDateMode = it },
            canSave = model.canStartExport && !inspectingExport,
            onSave = {
                if (model.canStartExport && !inspectingExport) {
                    selectedSaveDateMode = draftSaveDateMode
                    showSaveOptions = false
                    beginExportAfterReview()
                }
            },
            onDismiss = {
                showSaveOptions = false
                selectedSaveAction = OriginalAction.KEEP
            },
        )
    }

    if (showExportCancelConfirmation && model.page == QuickCutPage.EXPORTING) {
        ExportCancelDialog(
            canCancel = !model.isFinalizing,
            onCancel = {
                showExportCancelConfirmation = false
                model.cancelExport()
            },
            onDismiss = { showExportCancelConfirmation = false },
        )
    }

    if (showLastSettings) {
        LastSettingsDialog(
            settings = settings,
            dateMode = selectedSaveDateMode,
            onDateChange = { selectedSaveDateMode = it },
            onDismiss = { showLastSettings = false },
        )
    }

    if (inspectingExport) ExportInspectionDialog()
    exportReview?.takeIf {
        model.page == QuickCutPage.EDITOR && model.timeline == it.timeline
    }?.let { review ->
        val clip = review.timeline.clips.singleOrNull()
        val suggestedStart = review.assessment.suggestedStartMs
        val suggestedRange = if (suggestedStart != null && clip != null) {
            TrimInput.range(suggestedStart, clip.endMs, clip.sourceDurationMs)
                ?.takeIf { it.startMs > clip.startMs }
        } else null
        ExportModeDialog(
            assessment = review.assessment,
            clip = clip,
            retryingFastExport = review.retryingFastExport,
            canSave = model.canStartExport,
            onAllowReencode = {
                if (model.canStartExport && model.timeline == review.timeline) {
                    exportReview = null
                    model.startExport(context, review.originalAction, review.dateMode,
                        ExportMode.ALLOW_REENCODE)
                    selectedSaveAction = OriginalAction.KEEP
                }
            },
            onSuggestedStart = if (suggestedRange != null && clip != null) {
                {
                    if (model.canStartExport && model.timeline == review.timeline) {
                        model.trimClip(clip.id, suggestedRange.startMs, suggestedRange.endMs)
                        if (model.quickMode) {
                            settings.saveQuick(QuickTrimSettings(
                                leadingMs = suggestedRange.startMs,
                                trailingMs = clip.sourceDurationMs - suggestedRange.endMs,
                                muteAudio = clip.muted,
                            ))
                        }
                        selectedSaveAction = review.originalAction
                        selectedSaveDateMode = review.dateMode
                        exportReview = null
                    }
                }
            } else null,
            onDismiss = {
                exportReview = null
                selectedSaveAction = OriginalAction.KEEP
            },
        )
    }

    if (model.page == QuickCutPage.COMPLETED &&
        model.originalActionStatus == OriginalActionStatus.CONFIRMING
    ) {
        val permanent = model.originalAction == OriginalAction.DELETE
        val originalCount = model.originalsFromSavedTimeline.size
        AlertDialog(
            onDismissRequest = model::skipOriginalDeletion,
            title = {
                Text(if (permanent) "원본 영구 삭제 확인" else "원본 휴지통 이동 확인")
            },
            text = {
                Text(
                    "편집 결과는 갤러리에 저장됐습니다.\n" +
                        "원본 동영상 " + originalCount + "개를 " +
                        (if (permanent) "영구 삭제하시겠습니까?\n삭제 후 복구할 수 없을 수 있습니다."
                         else "휴지통으로 이동하시겠습니까?\n기기 정책에 따라 나중에 복원할 수 있습니다.") +
                        "\nAndroid의 추가 승인 화면이 표시됩니다."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        try {
                            val outputUri = model.completedUri
                                ?: throw IllegalStateException("저장 결과를 확인할 수 없습니다.")
                            val request = OriginalMediaActions.prepareRequest(
                                context = context,
                                originalSources = model.originalsFromSavedTimeline,
                                savedOutput = outputUri,
                                action = model.originalAction,
                            )
                            model.markOriginalSystemPrompt()
                            originalActionLauncher.launch(request)
                        } catch (error: Exception) {
                            model.markOriginalUnsupported(
                                "편집 결과는 저장됐지만 원본은 삭제하지 않았습니다. " +
                                    (error.localizedMessage ?: "이 파일의 원본 처리가 지원되지 않습니다.")
                            )
                        }
                    },
                    shape = RoundedCornerShape(4.dp),
                    colors = if (permanent) ButtonDefaults.buttonColors(
                        containerColor = androidx.compose.ui.graphics.Color(0xFFBF4545)
                    ) else ButtonDefaults.buttonColors(),
                ) {
                    Text(if (permanent) "영구 삭제 요청" else "휴지통 이동 요청")
                }
            },
            dismissButton = {
                TextButton(onClick = model::skipOriginalDeletion, shape = CutShape) { Text("원본 유지") }
            },
            containerColor = CutColors.panel,
            shape = RoundedCornerShape(4.dp),
        )
    }

    model.errorMessage?.let { text ->
        AlertDialog(
            onDismissRequest = model::dismissError,
            title = { Text("안내") },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = model::dismissError, shape = CutShape) { Text("확인") }
            },
            containerColor = CutColors.panel,
            shape = RoundedCornerShape(4.dp),
        )
    }

    if (model.showExitConfirmation) {
        AlertDialog(
            onDismissRequest = model::hideExitConfirmation,
            title = { Text("편집을 종료할까요?") },
            text = { Text("저장하지 않은 편집 내용이 사라집니다.") },
            confirmButton = {
                Button(
                    onClick = model::discardEditing,
                    shape = RoundedCornerShape(4.dp),
                ) { Text("나가기") }
            },
            dismissButton = {
                TextButton(onClick = model::hideExitConfirmation, shape = CutShape) { Text("계속 편집") }
            },
            containerColor = CutColors.panel,
            shape = RoundedCornerShape(4.dp),
        )
    }
}

@Composable
private fun StartScreen(
    onQuick: () -> Unit,
    onSelect: () -> Unit,
    onBatch: () -> Unit,
    canRestore: Boolean,
    onRestore: () -> Unit,
    quickSettings: QuickTrimSettings,
    dateMode: SaveDateMode,
    onPreferences: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "QuickCut",
                color = CutColors.primaryText,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text("필요한 구간만 빠르게 잘라 저장하세요.",
                color = CutColors.secondaryText)
            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(CutColors.surface).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("마지막 사용 설정", color = CutColors.primaryText,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onPreferences, shape = CutShape) { Text("설정 변경") }
                }
                Text(
                    "앞 " + (quickSettings.leadingMs / 1_000.0) +
                        "초 삭제 · 뒤 " + (quickSettings.trailingMs / 1_000.0) + "초 삭제",
                    color = CutColors.primaryText,
                )
                Text(
                    (if (quickSettings.muteAudio) "음소거" else "소리 유지") +
                        " · " + (if (dateMode == SaveDateMode.CURRENT)
                            "현재 날짜 저장" else "원본 날짜 유지"),
                    color = CutColors.secondaryText,
                    fontSize = 13.sp,
                )
            }
            Button(
                onClick = onQuick,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(4.dp),
            ) {
                Text("빠른 자르기 · 영상 1개", fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onBatch,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(4.dp),
            ) { Text("일괄 편집 · 여러 영상") }
            TextButton(
                onClick = onSelect,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = CutShape,
            ) { Text("상세 편집 (분할·연결·순서 변경)") }
            if (canRestore) {
                OutlinedButton(
                    onClick = onRestore,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(4.dp),
                ) { Text("이전 편집 이어서 진행") }
            }
        }
    }
}

@Composable
private fun LastSettingsDialog(
    settings: QuickCutSettings,
    dateMode: SaveDateMode,
    onDateChange: (SaveDateMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = remember(settings) { settings.loadQuick() }
    var headText by remember(initial) {
        mutableStateOf((initial.leadingMs / 1_000.0).toString().removeSuffix(".0"))
    }
    var tailText by remember(initial) {
        mutableStateOf((initial.trailingMs / 1_000.0).toString().removeSuffix(".0"))
    }
    var mute by remember(initial) { mutableStateOf(initial.muteAudio) }
    var chosenDate by remember(dateMode) { mutableStateOf(dateMode) }
    fun timeValue(input: String): Long? =
        TrimInput.millisValue(input)?.takeIf { it <= 86_400_000L }
    val head = timeValue(headText)
    val tail = timeValue(tailText)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("마지막 사용 설정") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("새 영상 선택 시 자동 적용됩니다.", color = CutColors.secondaryText,
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = headText,
                    onValueChange = { headText = it.take(12) },
                    label = { Text("앞부분 삭제 (초)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = tailText,
                    onValueChange = { tailText = it.take(12) },
                    label = { Text("뒷부분 삭제 (초)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable { mute = !mute },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = mute, onCheckedChange = { mute = it })
                    Text("영상 음소거")
                }
                Text("저장 날짜", fontWeight = FontWeight.SemiBold)
                listOf(
                    SaveDateMode.CURRENT to "현재 날짜 저장",
                    SaveDateMode.ORIGINAL to "원본 날짜 유지",
                ).forEach { (mode, label) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clickable { chosenDate = mode },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = chosenDate == mode, onClick = null)
                        Text(label)
                    }
                }
                Text("원본 휴지통 이동·영구 삭제는 자동 저장하지 않습니다.",
                    color = CutColors.secondaryText,
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    settings.saveQuick(QuickTrimSettings(head!!, tail!!, mute))
                    onDateChange(chosenDate)
                    onDismiss()
                },
                enabled = head != null && tail != null,
                shape = RoundedCornerShape(4.dp),
            ) { Text("설정 저장") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = CutShape) { Text("취소") }
        },
        containerColor = CutColors.panel,
        shape = CutShape,
    )
}

@Composable
internal fun CenterStatus(
    title: String,
    subtitle: String,
    progress: Int?,
    onCancel: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = CutColors.primaryText, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Text(subtitle, color = CutColors.secondaryText)
        Spacer(Modifier.height(40.dp))
        if (progress == null) {
            CircularProgressIndicator(modifier = Modifier.size(112.dp))
        } else {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.size(132.dp),
                    strokeWidth = 6.dp,
                )
                Text(text = progress.toString() + "%", color = CutColors.primaryText, fontSize = 25.sp)
            }
        }
        Spacer(Modifier.height(32.dp))
        Text(
            if (progress == null) "영상 처리 및 저장을 확인하고 있습니다." else "저장 중 · " + progress + "%",
            textAlign = TextAlign.Center,
            color = CutColors.secondaryText,
        )
        if (onCancel != null) {
            Spacer(Modifier.height(48.dp))
            OutlinedButton(
                onClick = onCancel,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().height(52.dp),
            ) { Text("취소") }
        }
    }
}

@Composable
private fun CompletedScreen(
    videoUri: String?,
    onOpen: () -> Unit,
    onNew: () -> Unit,
    originalAction: OriginalAction,
    savedDateMode: SaveDateMode,
    exportReport: ExportReport?,
    originalStatus: OriginalActionStatus,
    originalCount: Int,
    onRetryOriginalAction: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.CheckCircleOutline,
            contentDescription = null,
            tint = CutColors.accent,
            modifier = Modifier.size(80.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("저장 완료", color = CutColors.primaryText, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text("영상이 갤러리에 저장되었습니다.", color = CutColors.secondaryText)
        Text(exportReportText(exportReport), color = CutColors.secondaryText)
        if (savedDateMode == SaveDateMode.ORIGINAL) {
            Text("원본 날짜 기준으로 저장했습니다.", color = CutColors.secondaryText)
        }
        Spacer(Modifier.height(28.dp))
        if (videoUri != null) {
            ClipThumbnail(
                uri = videoUri,
                timeMs = 0L,
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().height(210.dp),
            )
        }
        if (originalAction != OriginalAction.KEEP) {
            Spacer(Modifier.height(18.dp))
            val actionLabel = if (originalAction == OriginalAction.TRASH) "휴지통 이동" else "영구 삭제"
            val statusLabel = when (originalStatus) {
                OriginalActionStatus.READY,
                OriginalActionStatus.CONFIRMING -> "원본 " + originalCount + "개 " + actionLabel + " 확인 대기 중"
                OriginalActionStatus.SYSTEM_PROMPT -> "Android 원본 처리 승인 대기 중"
                OriginalActionStatus.COMPLETED -> "원본 " + originalCount + "개 " + actionLabel + " 완료"
                OriginalActionStatus.SKIPPED -> "원본은 삭제하지 않고 유지했습니다."
                OriginalActionStatus.UNSUPPORTED -> "원본 처리를 지원하지 않아 파일을 유지했습니다."
                OriginalActionStatus.NONE -> "원본 유지"
            }
            Text(
                statusLabel,
                color = CutColors.secondaryText,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            if (originalStatus == OriginalActionStatus.SKIPPED) {
                OutlinedButton(
                    onClick = onRetryOriginalAction,
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(4.dp),
                ) { Text("원본 " + actionLabel + " 다시 요청") }
            }
        }
        Spacer(Modifier.height(28.dp))
        Column(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(4.dp),
            ) { Text("갤러리에서 열기", fontWeight = FontWeight.SemiBold) }
            OutlinedButton(
                onClick = onNew,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(4.dp),
            ) { Text("새 영상 편집") }
        }
    }
}


