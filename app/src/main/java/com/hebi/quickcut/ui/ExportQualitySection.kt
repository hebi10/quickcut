package com.hebi.quickcut.ui

import android.net.Uri
import kotlin.math.roundToInt
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.formatTime
import com.hebi.quickcut.media.ExportFrameRate
import com.hebi.quickcut.media.ExportQualityInspector
import com.hebi.quickcut.media.ExportQualityLevel
import com.hebi.quickcut.media.ExportQualitySettings
import com.hebi.quickcut.media.ExportResolution
import com.hebi.quickcut.media.QualityEvaluation
import com.hebi.quickcut.media.MediaReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun rememberExportQualityEvaluation(
    timeline: EditTimeline,
    settings: ExportQualitySettings,
): QualityEvaluation? {
    val context = LocalContext.current
    var result by remember(timeline, settings) { mutableStateOf<QualityEvaluation?>(null) }
    LaunchedEffect(timeline, settings) {
        result = ExportQualityInspector.inspect(context.applicationContext, timeline, settings)
    }
    return result
}

@Composable
internal fun ExportQualitySection(
    timeline: EditTimeline,
    settings: ExportQualitySettings,
    evaluation: QualityEvaluation?,
    onChange: (ExportQualitySettings) -> Unit,
) {
    val palette = LocalCutPalette.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CutSpacing.md)) {
        ExportQualityThumbnail(timeline)
        Text("내보내기 화질", color = palette.secondaryAccent,
            style = MaterialTheme.typography.titleMedium)
        Text(
            "자동은 기존 고속 저장 방식을 유지합니다. 옵션을 변경하면 재인코딩 승인을 요청합니다.",
            color = palette.secondaryText,
            style = MaterialTheme.typography.bodySmall,
        )
        Text("해상도", style = MaterialTheme.typography.titleSmall,
            color = palette.primaryText)
        ExportOptionRow(
            prefix = "export-quality-resolution",
            options = ExportResolution.entries.map { CutChoice(it, it.label) },
            selected = settings.resolution,
            onSelected = { onChange(settings.copy(resolution = it)) },
        )
        Text("FPS (초당 프레임)", style = MaterialTheme.typography.titleSmall,
            color = palette.primaryText)
        ExportOptionRow(
            prefix = "export-quality-fps",
            options = ExportFrameRate.entries.map { CutChoice(it, it.label) },
            selected = settings.frameRate,
            onSelected = { onChange(settings.copy(frameRate = it)) },
        )
        Text("압축 품질: ${settings.quality.label}",
            style = MaterialTheme.typography.titleSmall,
            color = palette.primaryText)
        androidx.compose.material3.Slider(
            value = settings.quality.ordinal.toFloat(),
            onValueChange = { raw ->
                val index = raw.roundToInt().coerceIn(0, ExportQualityLevel.entries.lastIndex)
                val next = ExportQualityLevel.entries[index]
                if (next != settings.quality) onChange(settings.copy(quality = next))
            },
            valueRange = 0f..3f,
            steps = 2,
            modifier = Modifier.testTag("export-quality-bitrate"),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ExportQualityLevel.entries.forEach { option ->
                androidx.compose.material3.TextButton(
                    onClick = { onChange(settings.copy(quality = option)) },
                    modifier = Modifier.testTag("export-quality-quality-${option.name}")
                        .heightIn(min = 44.dp),
                ) {
                    Text(option.label,
                        color = if (settings.quality == option) palette.secondaryAccent
                            else palette.secondaryText,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        androidx.compose.material3.Surface(
            shape = CutShape, color = palette.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    settings.isAutomatic -> Text(
                        "자동 · 원본 품질 및 기존 빠른 저장 유지",
                        color = palette.primaryText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    evaluation == null -> Text(
                        "영상 특성과 기기 지원 여부를 확인하고 있습니다.",
                        color = palette.secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    evaluation.error != null -> Text(
                        evaluation.error,
                        color = palette.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("export-quality-unavailable"),
                    )
                    evaluation.plan != null -> {
                        Text(
                            "예상 출력 · ${evaluation.plan.detail}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.primaryText,
                            modifier = Modifier.testTag("export-quality-estimate"),
                        )
                        Text("예상 용량은 추정치이며 실제 결과와 차이가 날 수 있습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.secondaryText)
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> ExportOptionRow(
    prefix: String,
    options: List<CutChoice<T>>,
    selected: T,
    onSelected: (T) -> Unit,
) {
    Row(
        Modifier.f
    val palette = LocalCutPalette.currentillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val chosen = option.value == selected
            androidx.compose.material3.Surface(
                onClick = { onSelected(option.value) },
                modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 64.dp)
                    .testTag("$prefix-${option.value}")
                    .semantics { this.selected = chosen },
                color = if (chosen) palette.accentMuted else palette.surface,
                contentColor = if (chosen) palette.primaryText else palette.secondaryText,
                border = androidx.compose.foundation.BorderStroke(1.dp,
                    if (chosen) palette.accent else palette.border),
                shape = CutShape,
            ) {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center) {
                    Text(if (option.label == "자동 · 원본 기준") "자동" else option.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (chosen) palette.primaryText else palette.secondaryText)
                }
            }
        }
    }
}

@Composable
private fun ExportQualityThumbnail(timeline: EditTimeline) {
    val first = timeline.clips.firstOrNull(
    val palette = LocalCutPalette.current) ?: return
    val context = LocalContext.current
    val frame by produceState<android.graphics.Bitmap?>(null, first.uri, first.startMs) {
        value = withContext(Dispatchers.IO) {
            MediaReader.thumbnail(context.applicationContext, Uri.parse(first.uri), first.startMs)
        }
    }
    Box(
        Modifier.fillMaxWidth().height(148.dp).background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (frame == null) {
            Text("원본 영상 미리보기", color = palette.secondaryText,
                style = MaterialTheme.typography.bodySmall)
        } else {
            Image(
                bitmap = requireNotNull(frame).asImageBitmap(),
                contentDescription = "선택한 영상의 미리보기 프레임",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            formatTime(timeline.totalDurationMs),
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(8.dp).background(palette.background.copy(alpha = 0.8f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
