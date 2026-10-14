package com.hebi.quickcut.ui

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hebi.quickcut.editor.EditorViewModel
import com.hebi.quickcut.editor.VideoClip
import com.hebi.quickcut.editor.formatTime
import com.hebi.quickcut.media.CompositionFactory
import com.hebi.quickcut.settings.QuickCutSettings
import com.hebi.quickcut.settings.QuickTrimSettings
import kotlinx.coroutines.delay
import kotlin.math.roundToLong

@OptIn(UnstableApi::class)
@Composable
fun QuickTrimScreen(
    model: EditorViewModel,
    settings: QuickCutSettings,
    onBack: () -> Unit,
    onDetailedEdit: () -> Unit,
    onSave: () -> Unit,
) {
    val clip = model.timeline.clips.singleOrNull() ?: return
    val context = LocalContext.current
    val player = remember(context) { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(clip.uri, clip.startMs, clip.endMs) {
        delay(200L)
        val position = player.currentPosition.coerceIn(0L, clip.durationMs)
        player.pause()
        player.setMediaItem(CompositionFactory.mediaItem(clip))
        player.prepare()
        player.seekTo(position)
    }

    var leadingText by rememberSaveable(clip.id) {
        mutableStateOf(secondsText(clip.startMs))
    }
    var trailingText by rememberSaveable(clip.id) {
        mutableStateOf(secondsText(clip.sourceDurationMs - clip.endMs))
    }

    fun updateTrim(leading: String, trailing: String) {
        val head = millisValue(leading) ?: return
        val tail = millisValue(trailing) ?: return
        if (head + tail >= clip.sourceDurationMs - 100L) return
        model.trimClip(clip.id, head, clip.sourceDurationMs - tail)
        settings.saveQuick(QuickTrimSettings(head, tail))
    }

    fun changeLeading(text: String) {
        leadingText = text.take(12)
        updateTrim(leadingText, trailingText)
    }

    fun changeTrailing(text: String) {
        trailingText = text.take(12)
        updateTrim(leadingText, trailingText)
    }

    val head = millisValue(leadingText)
    val tail = millisValue(trailingText)
    val valid = head != null && tail != null &&
        head + tail <= clip.sourceDurationMs - 100L

    BoxWithConstraints(Modifier.fillMaxSize().background(CutColors.background)) {
        val compact = maxHeight < 640.dp
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "뒤로가기")
                }
                Text("빠른 자르기", color = CutColors.primaryText,
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onDetailedEdit) {
                    Icon(Icons.Outlined.Settings, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("상세 편집")
                }
            }

            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .heightIn(min = 120.dp).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = true
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            this.player = player
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        }
                    },
                    update = { it.player = player },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(max = if (compact) 310.dp else 370.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("원본 " + formatTime(clip.sourceDurationMs),
                        color = CutColors.secondaryText, fontSize = 13.sp)
                    Text("결과 " + formatTime(clip.durationMs),
                        color = CutColors.primaryText, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold)
                }
                TrimSecondsRow(
                    label = "앞부분 삭제",
                    value = leadingText,
                    enabled = true,
                    onValueChange = ::changeLeading,
                    onStep = { delta ->
                        changeLeading(secondsText(
                            ((millisValue(leadingText) ?: 0L) + delta).coerceAtLeast(0L)
                        ))
                    },
                )
                TrimSecondsRow(
                    label = "뒷부분 삭제",
                    value = trailingText,
                    enabled = true,
                    onValueChange = ::changeTrailing,
                    onStep = { delta ->
                        changeTrailing(secondsText(
                            ((millisValue(trailingText) ?: 0L) + delta).coerceAtLeast(0L)
                        ))
                    },
                )
                if (!valid) {
                    Text("시간을 확인해 주세요. 결과 영상은 최소 0.1초 이상이어야 합니다.",
                        color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(if (clip.muted) "원본 소리: 음소거" else "원본 소리: 유지",
                        color = CutColors.secondaryText, fontSize = 13.sp)
                    OutlinedButton(onClick = model::toggleMute) {
                        Text(if (clip.muted) "소리 켜기" else "음소거")
                    }
                }
                Text(
                    "마지막 자르기 값은 자동 저장됩니다. 고속 저장 여부는 저장 전에 확인합니다.",
                    color = CutColors.secondaryText, fontSize = 12.sp,
                )
                Button(
                    onClick = onSave,
                    enabled = valid,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text("영상 저장", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun TrimSecondsRow(
    label: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onStep: (Long) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, color = CutColors.primaryText, fontSize = 14.sp,
            modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { onStep(-1_000L) }, enabled = enabled) {
            Text("−")
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("초") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(105.dp),
        )
        OutlinedButton(onClick = { onStep(1_000L) }, enabled = enabled) {
            Text("+")
        }
    }
}

private fun secondsText(valueMs: Long): String =
    (valueMs / 1_000.0).toString().removeSuffix(".0")

private fun millisValue(text: String): Long? {
    val number = text.trim().replace(',', '.').toDoubleOrNull()
        ?: return null
    if (!number.isFinite() || number !in 0.0..86_400.0) return null
    return (number * 1_000.0).roundToLong()
}
