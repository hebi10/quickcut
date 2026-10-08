package com.hebi.quickcut.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hebi.quickcut.editor.EditorViewModel
import com.hebi.quickcut.editor.QuickCutPage

@Composable
fun QuickCutApp(model: EditorViewModel = viewModel()) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        model.importVideos(context, uris, append = model.page == QuickCutPage.EDITOR)
    }

    BackHandler(enabled = model.page != QuickCutPage.PICKER) {
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
        when (model.page) {
            QuickCutPage.PICKER -> StartScreen(
                onSelect = { picker.launch(arrayOf("video/*")) },
                canRestore = model.canUndo,
                onRestore = model::undo,
            )
            QuickCutPage.IMPORTING -> CenterStatus("영상 불러오는 중", "선택한 파일을 확인하고 있습니다.", null)
            QuickCutPage.EDITOR -> EditorScreen(
                model = model,
                addVideos = { picker.launch(arrayOf("video/*")) },
                save = { model.startExport(context) },
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
            )
        }
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
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
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
        Spacer(Modifier.height(28.dp))
        if (videoUri != null) {
            ClipThumbnail(
                uri = videoUri,
                timeMs = 0L,
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().height(210.dp),
            )
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
