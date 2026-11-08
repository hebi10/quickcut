package com.hebi.quickcut.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.ui.PlayerView
import android.net.Uri
import com.hebi.quickcut.editor.AudioAdjustment
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import com.hebi.quickcut.editor.formatTime
import com.hebi.quickcut.media.AudioWaveformReader
import com.hebi.quickcut.media.CompositionFactory
import kotlinx.coroutines.delay
import kotlin.math.roundToLong

@OptIn(UnstableApi::class, ExperimentalApi::class)
@Composable
fun AudioEditorScreen(
    clip: VideoClip,
    onApply: (AudioAdjustment) -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    val context = LocalContext.current
    var volume by rememberSaveable(clip.id) { mutableStateOf(clip.audio.volumePercent) }
    var fadeIn by rememberSaveable(clip.id) { mutableStateOf(clip.audio.fadeInMs) }
    var fadeOut by rememberSaveable(clip.id) { mutableStateOf(clip.audio.fadeOutMs) }
    val draft = AudioAdjustment(volume, fadeIn, fadeOut).bounded(clip.durationMs)
    var previewAdjustment by remember(clip.id) { mutableStateOf(draft) }
    var previewFailure by remember(clip.id) { mutableStateOf<String?>(null) }
    val player = remember(context) { CompositionPlayer.Builder(context.applicationContext).build() }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(clip.id, clip.uri, clip.startMs, clip.endMs, previewAdjustment) {
        if (!clip.hasAudio) return@LaunchedEffect
        val previousPosition = player.currentPosition.coerceIn(0L, clip.durationMs)
        player.pause()
        try {
            player.setComposition(
                CompositionFactory.from(EditTimeline(listOf(clip.copy(audio = previewAdjustment)))),
                previousPosition,
            )
            player.prepare()
            previewFailure = null
        } catch (error: Exception) {
            previewFailure = error.localizedMessage ?: "미리보기 설정을 적용하지 못했습니다."
        }
    }
    LaunchedEffect(draft) {
        delay(250)
        previewAdjustment = draft
    }
    val waveform by produceState<com.hebi.quickcut.media.AudioWaveform?>(
        null, clip.uri, clip.startMs, clip.endMs, clip.hasAudio,
    ) {
        if (clip.hasAudio) {
            value = AudioWaveformReader.read(
                context.applicationContext, Uri.parse(clip.uri),
                clip.startMs, clip.durationMs,
            )
        }
    }
    val editable = clip.hasAudio && !clip.muted
    val limitMs = minOf(clip.durationMs, 5_000L)
    val maxSeconds = (limitMs / 1_000f).coerceAtLeast(0.1f)

    Column(Modifier.fillMaxSize().background(CutColors.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "편집 화면으로")
            }
            Column(Modifier.weight(1f)) {
                Text("오디오 볼륨·페이드", color = CutColors.primaryText,
                    style = MaterialTheme.typography.titleLarge)
                Text("현재 클립 · ${formatTime(clip.durationMs)}",
                    color = CutColors.secondaryText, style = MaterialTheme.typography.bodySmall)
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.fillMaxWidth().height(200.dp).background(Color.Black)) {
                AndroidView(
                    factory = { viewContext -> PlayerView(viewContext).apply {
                        this.player = player
                        useController = true
                    } },
                    update = { it.player = player },
                    modifier = Modifier.fillMaxSize().testTag("audio-preview"),
                )
            }
            previewFailure?.let {
                Text("미리보기 오류: $it", color = CutColors.error,
                    style = MaterialTheme.typography.bodySmall)
            }

            Text("오디오 파형", color = CutColors.primaryText,
                style = MaterialTheme.typography.titleMedium)
            if (!clip.hasAudio) {
                Text("이 영상에는 오디오 트랙이 없습니다.",
                    color = CutColors.secondaryText, style = MaterialTheme.typography.bodySmall)
            } else if (waveform == null) {
                Text("파형 미리보기를 불러올 수 없습니다. 소리 조절과 저장은 계속 사용할 수 있습니다.",
                    color = CutColors.secondaryText, style = MaterialTheme.typography.bodySmall)
            } else {
                val peaks = requireNotNull(waveform)
                Canvas(Modifier.fillMaxWidth().height(76.dp)
                    .background(CutColors.surface).testTag("audio-waveform")) {
                    val center = size.height / 2f
                    val step = size.width / peaks.peaks.size
                    peaks.peaks.forEachIndexed { index, peak ->
                        val x = step * (index + 0.5f)
                        val height = (peak.coerceIn(0f, 1f) * center * 0.85f)
                            .coerceAtLeast(2.dp.toPx())
                        drawLine(CutColors.accent, Offset(x, center - height),
                            Offset(x, center + height), strokeWidth = step * 0.55f)
                    }
                }
                if (clip.durationMs > peaks.sampledDurationMs) {
                    Text("처음 ${formatTime(peaks.sampledDurationMs)} 구간의 파형입니다.",
                        color = CutColors.secondaryText, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (clip.muted) {
                Text("현재 클립이 음소거 상태입니다. 소리를 들으려면 편집 화면에서 음소거를 해제하세요.",
                    color = CutColors.secondaryText, style = MaterialTheme.typography.bodySmall)
            }
            Text("원본 음량 · ${volume}%", color = CutColors.primaryText,
                style = MaterialTheme.typography.titleMedium)
            Slider(
                value = volume.toFloat(), onValueChange = { volume = it.roundToLong().toInt() },
                valueRange = 0f..100f,
                enabled = editable,
                modifier = Modifier.fillMaxWidth().testTag("audio-volume"),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("0%", color = CutColors.secondaryText,
                    style = MaterialTheme.typography.labelSmall)
                Text("100%", color = CutColors.secondaryText,
                    style = MaterialTheme.typography.labelSmall)
            }
            Text("페이드 인 · ${formatTime(fadeIn)}", color = CutColors.primaryText,
                style = MaterialTheme.typography.titleSmall)
            Slider(
                value = (fadeIn / 1_000f).coerceIn(0f, maxSeconds),
                onValueChange = { fadeIn = ((it * 2).roundToLong() * 500L).coerceAtMost(limitMs) },
                valueRange = 0f..maxSeconds,
                enabled = editable,
                modifier = Modifier.fillMaxWidth().testTag("audio-fade-in"),
            )
            Text("페이드 아웃 · ${formatTime(fadeOut)}", color = CutColors.primaryText,
                style = MaterialTheme.typography.titleSmall)
            Slider(
                value = (fadeOut / 1_000f).coerceIn(0f, maxSeconds),
                onValueChange = { fadeOut = ((it * 2).roundToLong() * 500L).coerceAtMost(limitMs) },
                valueRange = 0f..maxSeconds,
                enabled = editable,
                modifier = Modifier.fillMaxWidth().testTag("audio-fade-out"),
            )
            Text("0.5초 간격으로 설정합니다. 원본을 변경하지 않으며 저장 시 오디오 변환이 필요할 수 있습니다.",
                color = CutColors.secondaryText, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = { volume = 100; fadeIn = 0L; fadeOut = 0L },
                enabled = editable, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("audio-reset"),
                shape = CutShape,
            ) { Text("기본 음량으로 초기화") }
        }
        Row(Modifier.fillMaxWidth().background(CutColors.surface).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                shape = CutShape) { Text("취소") }
            Button(onClick = { onApply(draft) }, enabled = editable,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("audio-apply"),
                shape = CutShape) { Text("오디오 적용") }
        }
    }
}
