package com.hebi.quickcut.ui

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.ui.PlayerView
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.TextLayer
import com.hebi.quickcut.editor.TextLayerAlignment
import com.hebi.quickcut.editor.TextLayerColor
import com.hebi.quickcut.editor.TextLayerPolicy
import com.hebi.quickcut.editor.TrimInput
import com.hebi.quickcut.editor.VideoClip
import com.hebi.quickcut.editor.formatTime
import com.hebi.quickcut.media.CompositionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

@OptIn(UnstableApi::class, ExperimentalApi::class)
@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(
    clip: VideoClip,
    onApply: (List<TextLayer>) -> Unit,
    onCancel: () -> Unit,
) {
    val palette = LocalCutPalette.current
    BackHandler(onBack = onCancel)
    val context = LocalContext.current
    val player = remember(context) { CompositionPlayer.Builder(context.applicationContext).build() }
    DisposableEffect(player) { onDispose { player.release() } }
    var layers by remember(clip.id, clip.uri, clip.startMs, clip.endMs) {
        mutableStateOf(clip.textLayers)
    }
    var selectedId by remember(clip.id) { mutableStateOf(layers.firstOrNull()?.id) }
    var positionMs by remember(clip.id) { mutableStateOf(0L) }
    var previewFailure by remember(clip.id) { mutableStateOf<String?>(null) }
    val current = layers.firstOrNull { it.id == selectedId }
    val valid = layers.all { TextLayerPolicy.isValid(it, clip.durationMs) }
    val previewLayers = layers.filter { TextLayerPolicy.isValid(it, clip.durationMs) }

    fun seekWhenReady(targetMs: Long) {
        if (player.playbackState == Player.STATE_READY) {
            runCatching { player.seekTo(targetMs) }
                .onFailure { previewFailure = "영상 미리보기 위치를 변경하지 못했습니다." }
        }
    }

    val aspect by produceState(16f / 9f, clip.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context.applicationContext, Uri.parse(clip.uri))
                    val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        ?.toIntOrNull() ?: 16
                    val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        ?.toIntOrNull() ?: 9
                    val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        ?.toIntOrNull() ?: 0
                    if (rotation % 180 != 0) h.toFloat() / w else w.toFloat() / h
                } finally { retriever.release() }
            }.getOrDefault(16f / 9f).coerceIn(0.4f, 2.5f)
        }
    }

    LaunchedEffect(clip.id, clip.uri, clip.startMs, clip.endMs, previewLayers) {
        delay(180)
        val at = positionMs.coerceIn(0L, clip.durationMs)
        player.pause()
        try {
            player.setComposition(
                CompositionFactory.from(EditTimeline(listOf(clip.copy(textLayers = previewLayers)))),
                at,
            )
            player.prepare()
            previewFailure = null
        } catch (error: Exception) {
            previewFailure = error.localizedMessage ?: "텍스트 미리보기를 준비하지 못했습니다."
        }
    }
    LaunchedEffect(player) {
        while (isActive) {
            if (player.isPlaying) positionMs = player.currentPosition.coerceIn(0L, clip.durationMs)
            delay(120L)
        }
    }

    fun updateLayer(update: (TextLayer) -> TextLayer) {
        val id = selectedId ?: return
        layers = layers.map { if (it.id == id) update(it) else it }
    }

    Column(Modifier.fillMaxSize().background(palette.background)) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("텍스트 오버레이", color = palette.primaryText,
                    style = MaterialTheme.typography.titleLarge)
                Text("현재 클립 · ${formatTime(clip.durationMs)}",
                    color = palette.secondaryText, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("취소")
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BoxWithConstraints(
                Modifier.fillMaxWidth().height(280.dp).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val videoHeight = minOf(280.dp, maxWidth / aspect)
                val videoWidth = videoHeight * aspect
                Box(Modifier.size(videoWidth, videoHeight).testTag("text-video-preview")) {
                    AndroidView(
                        factory = { viewContext ->
                            PlayerView(viewContext).apply {
                                this.player = player
                                useController = false
                            }
                        },
                        update = { it.player = player },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (current != null && current.visibleAt(positionMs)) {
                        Box(Modifier.fillMaxSize()
                            .pointerInput(selectedId) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    val width = size.width.coerceAtLeast(1)
                                    val height = size.height.coerceAtLeast(1)
                                    updateLayer { it.moved(
                                        drag.x / width.toFloat(), drag.y / height.toFloat(),
                                    ) }
                                }
                            }) {
                            Box(
                                Modifier.offset(
                                    x = videoWidth * current.x - 64.dp,
                                    y = videoHeight * current.y - 22.dp,
                                ).size(width = 128.dp, height = 44.dp)
                                    .border(1.dp, palette.secondaryAccent, CutShape)
                                    .testTag("text-selected-frame")
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        if (player.playbackState == Player.STATE_READY) {
                            runCatching {
                                if (player.isPlaying) player.pause() else player.play()
                            }.onFailure { previewFailure = "영상 미리보기를 재생하지 못했습니다." }
                        } else {
                            previewFailure = "영상 미리보기가 준비된 후 다시 시도해 주세요."
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("text-preview-play"),
                ) { Text(if (player.isPlaying) "정지" else "재생") }
                Text(formatTime(positionMs), color = palette.secondaryText)
                Spacer(Modifier.weight(1f))
                Text(formatTime(clip.durationMs), color = palette.secondaryText)
            }
            Slider(
                value = positionMs.toFloat(),
                onValueChange = {
                    positionMs = it.roundToLong().coerceIn(0L, clip.durationMs)
                    seekWhenReady(positionMs)
                },
                valueRange = 0f..clip.durationMs.coerceAtLeast(100L).toFloat(),
                modifier = Modifier.fillMaxWidth().testTag("text-preview-seek"),
            )
            previewFailure?.let {
                Text("미리보기 오류: $it", color = palette.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("텍스트 레이어 · ${layers.size}/${TextLayerPolicy.MAX_LAYERS}",
                color = palette.primaryText, style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                layers.forEach { item ->
                    OutlinedButton(
                        onClick = {
                            selectedId = item.id
                            positionMs = item.startMs
                            seekWhenReady(item.startMs)
                        },
                        border = BorderStroke(1.dp,
                            if (selectedId == item.id) palette.accent else palette.border),
                        modifier = Modifier.heightIn(min = 48.dp).testTag("text-layer-${item.id}"),
                        shape = CutShape,
                    ) {
                        Text(item.text.ifBlank { "새 텍스트" }.take(12),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                OutlinedButton(
                    enabled = layers.size < TextLayerPolicy.MAX_LAYERS,
                    onClick = {
                        val layer = TextLayerPolicy.newLayer(layers, clip.durationMs, positionMs)
                        layers = layers + layer
                        selectedId = layer.id
                        positionMs = layer.startMs
                        seekWhenReady(layer.startMs)
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("text-layer-add"),
                    shape = CutShape,
                ) { Text("+ 텍스트 추가") }
            }
            if (current != null) {
                var startDraft by remember(current.id, current.startMs) {
                    mutableStateOf(TrimInput.secondsText(current.startMs))
                }
                var endDraft by remember(current.id, current.endMs) {
                    mutableStateOf(TrimInput.secondsText(current.endMs))
                }
                val enteredRange = TrimInput.selectedRange(
                    startDraft, endDraft, clip.durationMs,
                )
                HorizontalDivider(color = palette.border)
                Text("텍스트 내용", color = palette.primaryText,
                    style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = current.text,
                    onValueChange = { entry ->
                        updateLayer { it.copy(text = entry.take(TextLayerPolicy.MAX_CHARS)) }
                    },
                    placeholder = { Text("영상에 표시할 문구") },
                    label = { Text("최대 80자·3줄") },
                    modifier = Modifier.fillMaxWidth().testTag("text-layer-input"),
                    minLines = 2, maxLines = 3,
                    supportingText = { Text("${current.text.length}/${TextLayerPolicy.MAX_CHARS}") },
                )
                Text("색상", color = palette.primaryText,
                    style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextLayerColor.entries.forEach { color ->
                        OutlinedButton(
                            onClick = { updateLayer { it.copy(color = color) } },
                            border = BorderStroke(1.dp,
                                if (current.color == color) palette.accent else palette.border),
                            shape = CutShape,
                            modifier = Modifier.heightIn(min = 48.dp)
                                .testTag("text-color-${color.name}"),
                        ) { Text(color.label) }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("글자 배경", color = palette.primaryText)
                    Switch(
                        checked = current.darkBackground,
                        onCheckedChange = { active ->
                            updateLayer { it.copy(darkBackground = active) }
                        },
                        modifier = Modifier.testTag("text-layer-background"),
                    )
                }
                Text("글자 크기 · ${current.size}%",
                    color = palette.primaryText, style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = current.size.toFloat(),
                    onValueChange = { size ->
                        updateLayer { it.copy(size = size.roundToLong().toInt()) }
                    },
                    valueRange = 60f..170f,
                    modifier = Modifier.testTag("text-layer-size"),
                )
                Text("정렬", color = palette.primaryText,
                    style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextLayerAlignment.entries.forEach { option ->
                        OutlinedButton(
                            onClick = { updateLayer { it.copy(alignment = option) } },
                            border = BorderStroke(1.dp,
                                if (current.alignment == option) palette.accent else palette.border),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                .testTag("text-align-${option.name}"),
                            shape = CutShape,
                        ) { Text(option.label) }
                    }
                }
                Text("표시 시간: ${formatTime(current.startMs)} ~ ${formatTime(current.endMs)}",
                    color = palette.primaryText, style = MaterialTheme.typography.titleSmall)
                RangeSlider(
                    value = current.startMs.toFloat()..current.endMs.toFloat(),
                    onValueChange = { range ->
                        val start = range.start.roundToLong()
                            .coerceIn(0L, clip.durationMs - 100L)
                        val end = range.endInclusive.roundToLong()
                            .coerceIn(start + 100L, clip.durationMs)
                        updateLayer { it.copy(startMs = start, endMs = end) }
                    },
                    valueRange = 0f..clip.durationMs.toFloat(),
                    modifier = Modifier.testTag("text-layer-timing"),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = startDraft, onValueChange = { startDraft = it.take(14) },
                        label = { Text("시작(초)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f).testTag("text-start-seconds"),
                    )
                    OutlinedTextField(
                        value = endDraft, onValueChange = { endDraft = it.take(14) },
                        label = { Text("종료(초)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f).testTag("text-end-seconds"),
                    )
                }
                OutlinedButton(
                    enabled = enteredRange != null,
                    onClick = {
                        enteredRange?.let { selectedRange ->
                            updateLayer { it.copy(
                                startMs = selectedRange.startMs,
                                endMs = selectedRange.endMs,
                            ) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("text-time-apply"),
                    shape = CutShape,
                ) { Text("표시 시간 적용") }
                Text("텍스트는 선택한 구간에서만 표시됩니다. 미리보기 위에서 드래그해 위치를 변경할 수 있습니다.",
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = {
                        layers = layers.filterNot { it.id == current.id }
                        selectedId = layers.firstOrNull()?.id
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("text-layer-remove"),
                    shape = CutShape,
                ) { Text("이 텍스트 제거") }
            }
            if (!valid) Text("텍스트는 1~80자, 최대 3줄(한 줄 32자), 최소 0.1초로 설정해 주세요.",
                color = palette.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("text-invalid"))
            Spacer(Modifier.height(10.dp))
        }
        Row(
            Modifier.fillMaxWidth().background(palette.surface).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onCancel, shape = CutShape,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("취소") }
            Button(
                onClick = { onApply(layers) },
                enabled = valid, shape = CutShape,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("text-apply"),
            ) { Text("텍스트 적용") }
        }
    }
}
