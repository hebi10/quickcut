package com.hebi.quickcut.ui

import android.net.Uri
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CutSpacing.md)) {
        ExportQualityThumbnail(timeline)
        Text("내보내기 화질", color = CutColors.secondaryAccent,
            style = MaterialTheme.typography.titleMedium)
        Text(
            "자동은 원본과 기존 고속 저장 방식을 유지합니다. 옵션을 변경하면 영상 재인코딩을 승인해야 합니다.",
            color = CutColors.secondaryText,
            style = MaterialTheme.typography.bodySmall,
        )
        CutChoiceDropdown(
            label = "해상도",
            selected = settings.resolution,
            options = ExportResolution.entries.map { CutChoice(it, it.label) },
            modifier = Modifier.testTag("export-quality-resolution"),
            onSelected = { onChange(settings.copy(resolution = it)) },
        )
        CutChoiceDropdown(
            label = "FPS (초당 프레임)",
            selected = settings.frameRate,
            options = ExportFrameRate.entries.map { CutChoice(it, it.label) },
            modifier = Modifier.testTag("export-quality-fps"),
            onSelected = { onChange(settings.copy(frameRate = it)) },
        )
        CutChoiceDropdown(
            label = "압축 품질",
            selected = settings.quality,
            options = ExportQualityLevel.entries.map { CutChoice(it, it.label) },
            modifier = Modifier.testTag("export-quality-bitrate"),
            onSelected = { onChange(settings.copy(quality = it)) },
        )
        when {
            settings.isAutomatic -> Text(
                "자동: 원본 설정을 최대한 유지하고 기존 빠른 저장 여부를 검사합니다.",
                style = MaterialTheme.typography.bodySmall, color = CutColors.secondaryText
            )
            evaluation == null -> Text(
                "출력 설정과 기기 인코더 지원 여부를 확인하고 있습니다.",
                style = MaterialTheme.typography.bodySmall, color = CutColors.secondaryText
            )
            evaluation.error != null -> Text(
                evaluation.error, style = MaterialTheme.typography.bodySmall,
                color = CutColors.error, modifier = Modifier.testTag("export-quality-unavailable")
            )
            evaluation.plan != null -> {
                Text(
                    "예상 출력: ${evaluation.plan.detail}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CutColors.primaryText,
                    modifier = Modifier.testTag("export-quality-estimate")
                )
                Text(
                    "예상 용량은 비트레이트 기반 근삿값입니다. 실제 크기·저장 속도는 달라질 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CutColors.secondaryText
                )
            }
        }
    }
}

@Composable
private fun ExportQualityThumbnail(timeline: EditTimeline) {
    val first = timeline.clips.firstOrNull() ?: return
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
            Text("원본 영상 미리보기", color = CutColors.secondaryText,
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
                .padding(8.dp).background(CutColors.background.copy(alpha = 0.8f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
