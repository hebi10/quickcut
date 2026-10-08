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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hebi.quickcut.batch.BatchScreen
import com.hebi.quickcut.batch.BatchViewModel
import com.hebi.quickcut.editor.EditorViewModel
import com.hebi.quickcut.editor.OriginalAction
import com.hebi.quickcut.editor.OriginalActionStatus
import com.hebi.quickcut.media.OriginalMediaActions
import com.hebi.quickcut.media.SaveDateMode
import com.hebi.quickcut.editor.QuickCutPage

@Composable
fun QuickCutApp(
    model: EditorViewModel = viewModel(),
    batchModel: BatchViewModel = viewModel(),
) {
    val context = LocalContext.current
    var showBatch by rememberSaveable { mutableStateOf(false) }
    var showSaveOptions by rememberSaveable { mutableStateOf(false) }
    var selectedSaveAction by rememberSaveable { mutableStateOf(OriginalAction.KEEP) }
    var selectedSaveDateMode by rememberSaveable { mutableStateOf(SaveDateMode.CURRENT) }
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
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        model.importVideos(context, uris, append = model.page == QuickCutPage.EDITOR)
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
            QuickCutPage.EXPORTING -> if (!model.isFinalizing) model.cancelExport()
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
                onBack = { showBatch = false },
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
                onSelect = { picker.launch(arrayOf("video/*")) },
                onBatch = { showBatch = true },
                canRestore = model.canUndo,
                onRestore = model::undo,
            )
            QuickCutPage.IMPORTING -> CenterStatus("영상 불러오는 중", "선택한 파일을 확인하고 있습니다.", null)
            QuickCutPage.EDITOR -> EditorScreen(
                model = model,
                addVideos = { picker.launch(arrayOf("video/*")) },
                save = { showSaveOptions = true },
            )
            QuickCutPage.EXPORTING -> CenterStatus(
                title = if (model.isFinalizing) "갤러리 등록 중" else "영상 저장 중",
                subtitle = if (model.isFinalizing)
                    "결과물을 확인하고 있습니다." else "영상을 처리하고 있습니다.",
                progress = if (model.isFinalizing) null else model.progressPercent,
                onCancel = if (model.isFinalizing) null else model::cancelExport,
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
                originalStatus = model.originalActionStatus,
                originalCount = model.originalsFromSavedTimeline.size,
                onRetryOriginalAction = model::showOriginalDeletionConfirmation,
            )
        }
    }


    if (showSaveOptions && model.page == QuickCutPage.EDITOR) {
        AlertDialog(
            onDismissRequest = {
                showSaveOptions = false
                selectedSaveAction = OriginalAction.KEEP
                selectedSaveDateMode = SaveDateMode.CURRENT
            },
            title = { Text("저장 설정") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("저장 완료 후 원본 동영상 처리", color = CutColors.primaryText)
                    listOf(
                        OriginalAction.KEEP to "원본 유지 (기본값)",
                        OriginalAction.TRASH to "원본 휴지통으로 이동",
                        OriginalAction.DELETE to "원본 영구 삭제",
                    ).forEach { (action, label) ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable { selectedSaveAction = action },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedSaveAction == action,
                                onClick = null,
                            )
                            Text(label, color = CutColors.primaryText)
                        }
                    }
                    Text("저장 날짜", color = CutColors.primaryText)
                    listOf(
                        SaveDateMode.CURRENT to "현재 날짜로 저장 (최신 영상)",
                        SaveDateMode.ORIGINAL to "원본 날짜 유지",
                    ).forEach { (mode, label) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .clickable { selectedSaveDateMode = mode },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedSaveDateMode == mode,
                                onClick = null,
                            )
                            Text(label, color = CutColors.primaryText)
                        }
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
                Button(
                    onClick = {
                        showSaveOptions = false
                        model.startExport(context, selectedSaveAction, selectedSaveDateMode)
                        selectedSaveAction = OriginalAction.KEEP
                        selectedSaveDateMode = SaveDateMode.CURRENT
                    },
                    shape = RoundedCornerShape(4.dp),
                ) { Text("저장 시작") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSaveOptions = false
                    selectedSaveAction = OriginalAction.KEEP
                    selectedSaveDateMode = SaveDateMode.CURRENT
                }) { Text("취소") }
            },
            containerColor = CutColors.panel,
            shape = RoundedCornerShape(4.dp),
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
                TextButton(onClick = model::skipOriginalDeletion) { Text("원본 유지") }
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
                TextButton(onClick = model::dismissError) { Text("확인") }
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
                TextButton(onClick = model::hideExitConfirmation) { Text("계속 편집") }
            },
            containerColor = CutColors.panel,
            shape = RoundedCornerShape(4.dp),
        )
    }
}

@Composable
private fun StartScreen(
    onSelect: () -> Unit,
    onBatch: () -> Unit,
    canRestore: Boolean,
    onRestore: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Text(
            text = "QuickCut",
            color = CutColors.primaryText,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.TopStart).padding(20.dp),
        )
        Column(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp).widthIn(max = 420.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.VideoLibrary,
                contentDescription = null,
                tint = CutColors.secondaryText,
                modifier = Modifier.size(84.dp),
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onSelect,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(4.dp),
            ) {
                Text("영상 선택", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onBatch,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(4.dp),
            ) {
                Text("템플릿 · 일괄 편집", fontSize = 16.sp)
            }
            Text(
                text = "영상을 선택해 편집하세요",
                color = CutColors.secondaryText,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (canRestore) {
                OutlinedButton(
                    onClick = onRestore,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text("이전 편집 복원")
                }
            }
        }
    }
}

@Composable
private fun CenterStatus(
    title: String,
    subtitle: String,
    progress: Int?,
    onCancel: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
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
