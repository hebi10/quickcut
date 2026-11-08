package com.hebi.quickcut.segments

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.hebi.quickcut.editor.TrimInput
import com.hebi.quickcut.media.MediaReader
import com.hebi.quickcut.editor.formatTime
import com.hebi.quickcut.ui.CutColors
import com.hebi.quickcut.ui.CutShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractionScreen(
    model: ExtractionViewModel,
    onSelectVideo: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val session = model.session
    val selected = model.selected
    val player = remember(context) { ExoPlayer.Builder(context.applicationContext).build() }

    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(session?.sourceUri, selected?.range) {
        val source = session?.sourceUri
        val range = selected?.range
        player.pause()
        if (source == null || range == null) {
            player.clearMediaItems()
        } else {
            player.setMediaItem(
                MediaItem.Builder()
                    .setUri(Uri.parse(source))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(range.startMs)
                            .setEndPositionMs(range.endMs)
                            .build()
                    ).build()
            )
            player.prepare()
        }
    }
    LaunchedEffect(model.busy) { if (model.busy) player.pause() }
    BackHandler(enabled = model.busy) { }

    Column(
        Modifier.fillMaxSize().background(CutColors.background)
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack, enabled = !model.busy) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "뒤로가기")
            }
            Column(Modifier.weight(1f)) {
                Text("여러 구간 추출", style = MaterialTheme.typography.titleLarge,
                    color = CutColors.primaryText)
                Text("한 영상의 원하는 장면을 개별 MP4로 저장", style = MaterialTheme.typography.bodySmall,
                    color = CutColors.secondaryText)
            }
            OutlinedButton(onClick = onSelectVideo, enabled = !model.busy && !model.blocked,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("영상 선택") }
        }

        if (session == null) {
            Surface(shape = CutShape, color = CutColors.panel,
                border = BorderStroke(1.dp, CutColors.border)) {
                Column(Modifier.fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (model.blocked) "이전 구간 저장 기록을 확인할 수 없습니다." else "추출할 영상이 없습니다.",
                        color = if (model.blocked) CutColors.error else CutColors.primaryText)
                    Text("영상을 하나 선택하면 구간을 추가할 수 있습니다.",
                        color = CutColors.secondaryText,
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onSelectVideo, enabled = !model.importing && !model.blocked) {
                        Text("영상 선택")
                    }
                }
            }
        } else {
            Text(session.sourceName, color = CutColors.secondaryText,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium)
            BoxWithConstraints {
                val wide = maxWidth >= 760.dp
                if (wide) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ExtractionPlayer(player)
                            Text("원본 길이: ${formatTime(session.durationMs)}",
                                color = CutColors.secondaryText)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            ExtractionRangeEditor(model, selected)
                            ExtractionRows(model)
                        }
                    }
                } else {
                    ExtractionPlayer(player)
                    Text("원본 길이: ${formatTime(session.durationMs)}",
                        color = CutColors.secondaryText,
                        style = MaterialTheme.typography.bodySmall)
                    ExtractionRangeEditor(model, selected)
                    ExtractionRows(model)
                }
            }
            if (model.importing || model.inspecting || model.recovering) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.width(20.dp))
                    Text(when {
                        model.importing -> "영상 정보를 확인하는 중"
                        model.inspecting -> "구간별 저장 방식을 확인하는 중"
                        else -> "이전 저장 결과를 확인하는 중"
                    }, color = CutColors.secondaryText)
                }
            }
            if (model.exporting) {
                Text("${model.activeIndex}/${session.items.size} · ${model.currentStage.label}",
                    color = CutColors.primaryText)
                if (model.progressPercent != null) {
                    LinearProgressIndicator(
                        progress = { (model.progressPercent ?: 0) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(if (model.finalizing) "완료 파일 확인 중에는 취소할 수 없습니다."
                    else "완료된 파일은 보존되며 현재 작업은 취소할 수 있습니다.",
                    color = CutColors.secondaryText,
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = model::cancel, enabled = !model.finalizing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("저장 중단") }
            } else {
                if (model.blocked) {
                    Text("확인되지 않은 저장 파일이 있어 중복 저장을 제한했습니다.",
                        color = CutColors.error)
                    OutlinedButton(onClick = model::recoverOutputs, enabled = !model.busy) {
                        Text("저장 결과 다시 확인")
                    }
                }
                Button(
                    onClick = model::inspectBeforeSave,
                    enabled = !model.busy && !model.blocked &&
                        session.items.any { it.status != ExtractionStatus.SAVED && it.outputUri == null },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = CutShape,
                ) {
                    Text("개별 저장 · ${session.items.count {
                        it.status != ExtractionStatus.SAVED && it.outputUri == null
                    }}개")
                }
            }
        }
    }

    if (model.requiresReencodeApproval) {
        AlertDialog(
            onDismissRequest = model::declineReencode,
            title = { Text("정확한 위치로 저장") },
            text = {
                Text("일부 구간은 키프레임이 맞지 않아 재인코딩이 필요할 수 있습니다. " +
                    "원래 선택한 시작·종료 시간을 유지하며 저장하지만 처리 시간이 늘어날 수 있습니다. " +
                    "재인코딩을 허용하시겠습니까?")
            },
            confirmButton = {
                Button(onClick = model::approveReencode) { Text("재인코딩 허용 후 저장") }
            },
            dismissButton = {
                TextButton(onClick = model::declineReencode) { Text("취소") }
            },
            containerColor = CutColors.panel,
        )
    }

    model.notice?.let { message ->
        AlertDialog(
            onDismissRequest = model::dismissNotice,
            title = { Text("여러 구간 추출") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = model::dismissNotice) { Text("확인") }
            },
            containerColor = CutColors.panel,
        )
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun ExtractionPlayer(player: ExoPlayer) {
    Surface(shape = CutShape, color = Color.Black,
        border = BorderStroke(1.dp, CutColors.border)) {
        AndroidView(
            factory = { context -> PlayerView(context).apply {
                this.player = player
                useController = true
            } },
            update = { it.player = player },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExtractionRangeEditor(model: ExtractionViewModel, selected: ExtractionItem?) {
    val session = model.session ?: return
    if (selected == null) return
    val range = selected.range
    val canEdit = !model.busy && !model.blocked &&
        selected.status != ExtractionStatus.SAVED && selected.outputUri == null
    var startText by remember(range.id, range.startMs) {
        mutableStateOf(TrimInput.secondsText(range.startMs))
    }
    var endText by remember(range.id, range.endMs) {
        mutableStateOf(TrimInput.secondsText(range.endMs))
    }
    var selectedSlider by remember(range.id, range.startMs, range.endMs) {
        mutableStateOf(range.startMs.toFloat()..range.endMs.toFloat())
    }
    val startMs = TrimInput.millisValue(startText)
    val endMs = TrimInput.millisValue(endText)
    val valid = startMs != null && endMs != null &&
        TrimInput.range(startMs, endMs, session.durationMs) != null &&
        (session.items.none { it.range.id != range.id &&
            it.range.startMs == startMs && it.range.endMs == endMs })

    Surface(shape = CutShape, color = CutColors.surface,
        border = BorderStroke(1.dp, CutColors.border)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("구간 ${session.items.indexOfFirst { it.range.id == range.id } + 1} · " +
                formatTime(range.startMs) + " ~ " + formatTime(range.endMs),
                style = MaterialTheme.typography.titleMedium, color = CutColors.primaryText)
            Text("타임라인에서 범위를 조정하거나 시작·종료 시간을 직접 입력하세요.",
                color = CutColors.secondaryText,
                style = MaterialTheme.typography.bodySmall)
            SegmentOverview(session, range.id)
            RangeSlider(
                value = selectedSlider,
                onValueChange = { selectedSlider = it },
                onValueChangeFinished = {
                    if (canEdit) model.updateSegment(
                        range.id, selectedSlider.start.toLong(), selectedSlider.endInclusive.toLong())
                },
                valueRange = 0f..session.durationMs.toFloat(),
                enabled = canEdit,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = startText, onValueChange = { startText = it.take(14) },
                    label = { Text("시작(초)") }, modifier = Modifier.weight(1f),
                    singleLine = true, enabled = canEdit,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = endText, onValueChange = { endText = it.take(14) },
                    label = { Text("종료(초)") }, modifier = Modifier.weight(1f),
                    singleLine = true, enabled = canEdit,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
            if (!valid) {
                Text("0.1초 이상, 원본 길이 이내의 중복되지 않는 구간을 입력해 주세요.",
                    color = CutColors.error, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(
                onClick = { if (startMs != null && endMs != null)
                    model.updateSegment(range.id, startMs, endMs) },
                enabled = canEdit && valid,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("구간 적용") }
        }
    }
}

@Composable
private fun ExtractionRows(model: ExtractionViewModel) {
    val context = LocalContext.current
    val session = model.session ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("추출할 구간 (${session.items.size}개)",
                color = CutColors.primaryText, style = MaterialTheme.typography.titleMedium)
            Text("총 ${formatTime(session.items.sumOf { it.range.durationMs })}",
                color = CutColors.secondaryText,
                style = MaterialTheme.typography.bodySmall)
        }
        session.items.forEachIndexed { index, item ->
            val selected = item.range.id == model.selectedId
            Surface(
                onClick = { model.selectRange(item.range.id) },
                shape = CutShape,
                color = if (selected) CutColors.accentMuted else CutColors.surface,
                border = BorderStroke(1.dp,
                    if (selected) CutColors.accent else CutColors.border),
                enabled = !model.busy,
            ) {
                Row(Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ExtractionThumbnail(session.sourceUri, item.range.startMs)
                    Column(Modifier.weight(1f)) {
                        Text("구간 ${index + 1} · ${formatTime(item.range.startMs)}–${formatTime(item.range.endMs)}",
                            color = CutColors.primaryText, fontWeight = FontWeight.SemiBold)
                        Text(
                            when (item.status) {
                                ExtractionStatus.WAITING -> "저장 대기"
                                ExtractionStatus.SAVING -> "저장 중"
                                ExtractionStatus.SAVED -> "저장 완료"
                                ExtractionStatus.FAILED -> item.message ?: "저장 실패 · 재시도 가능"
                                ExtractionStatus.BLOCKED -> item.message ?: "저장 상태 확인 필요"
                            },
                            color = when (item.status) {
                                ExtractionStatus.SAVED -> CutColors.success
                                ExtractionStatus.FAILED, ExtractionStatus.BLOCKED -> CutColors.warning
                                else -> CutColors.secondaryText
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (item.status == ExtractionStatus.SAVED && item.outputUri != null) {
                        TextButton(onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(Uri.parse(item.outputUri), "video/mp4")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                })
                            } catch (_: ActivityNotFoundException) {
                                model.dismissNotice()
                            } catch (_: SecurityException) {
                                model.recoverOutputs()
                            }
                        }) { Text("열기") }
                    } else if (item.status != ExtractionStatus.BLOCKED &&
                        item.status != ExtractionStatus.SAVING &&
                        session.items.size > 1) {
                        IconButton(onClick = { model.removeSegment(item.range.id) },
                            enabled = !model.busy) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "구간 ${index + 1} 제거",
                                tint = CutColors.secondaryText)
                        }
                    }
                }
            }
        }
        OutlinedButton(
            onClick = {
                val suggestedStart = (model.selected?.range?.endMs ?: 0L)
                    .coerceAtMost(session.durationMs - TrimInput.MIN_DURATION_MS)
                model.addSegment(suggestedStart)
            },
            enabled = !model.busy && !model.blocked &&
                session.items.size < ExtractionPlanner.MAX_SEGMENTS,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("구간 추가")
        }
        Text("완료된 구간은 다시 저장하지 않습니다. 원본 영상은 삭제하지 않습니다.",
            color = CutColors.secondaryText, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(color = CutColors.border)
    }
}

@Composable
private fun SegmentOverview(session: ExtractionSession, selectedId: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("구간 타임라인", color = CutColors.secondaryText,
            style = MaterialTheme.typography.bodySmall)
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(26.dp).background(CutColors.border, CutShape)
        ) {
            val duration = session.durationMs.toFloat().coerceAtLeast(1f)
            session.items.forEach { item ->
                val left = item.range.startMs / duration
                val widthFraction = item.range.durationMs / duration
                Box(
                    Modifier.offset(x = maxWidth * left)
                        .width(maxWidth * widthFraction)
                        .fillMaxHeight()
                        .background(if (item.range.id == selectedId) CutColors.accent
                            else CutColors.secondaryAccent.copy(alpha = 0.48f))
                )
            }
        }
        Text("보라색: 현재 선택 구간 · 다른 구간과 겹쳐도 별도 파일로 저장",
            color = CutColors.secondaryText,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ExtractionThumbnail(sourceUri: String, startMs: Long) {
    val context = LocalContext.current
    val image by produceState<android.graphics.Bitmap?>(null, sourceUri, startMs) {
        value = withContext(Dispatchers.IO) {
            MediaReader.thumbnail(context.applicationContext, Uri.parse(sourceUri), startMs)
        }
    }
    if (image != null) {
        Image(
            bitmap = requireNotNull(image).asImageBitmap(),
            contentDescription = "구간 시작 장면 미리보기",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(width = 74.dp, height = 44.dp),
        )
    } else {
        Box(
            Modifier.size(width = 74.dp, height = 44.dp).background(CutColors.border),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = null,
                tint = CutColors.secondaryAccent)
        }
    }
}
