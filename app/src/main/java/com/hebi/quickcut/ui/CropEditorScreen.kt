package com.hebi.quickcut.ui

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hebi.quickcut.editor.CropGeometry
import com.hebi.quickcut.editor.CropRatio
import com.hebi.quickcut.editor.CropRegion
import com.hebi.quickcut.editor.VideoClip
import com.hebi.quickcut.media.MediaReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class CropPreviewSource(
    val width: Int,
    val height: Int,
    val image: android.graphics.Bitmap?,
)

@Composable
fun CropEditorScreen(
    clip: VideoClip,
    onApply: (CropRegion?) -> Unit,
    onCancel: () -> Unit,
) {
    val palette = LocalCutPalette.current
    BackHandler(onBack = onCancel)
    val context = LocalContext.current
    val source by produceState<CropPreviewSource?>(null, clip.uri, clip.startMs) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                val (width, height) = try {
                    retriever.setDataSource(context.applicationContext, Uri.parse(clip.uri))
                    val originalW = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val originalH = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val rotation = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (rotation % 180 != 0) originalH to originalW
                    else originalW to originalH
                } finally {
                    retriever.release()
                }
                require(width > 0 && height > 0)
                CropPreviewSource(width, height,
                    MediaReader.thumbnail(context.applicationContext, Uri.parse(clip.uri), clip.startMs))
            }.getOrElse { CropPreviewSource(0, 0, null) }
        }
    }
    val regionSaver = listSaver<CropRegion, Float>(
        save = { listOf(it.left, it.top, it.right, it.bottom) },
        restore = { CropRegion(it[0], it[1], it[2], it[3]) },
    )
    var region by rememberSaveable(clip.id, stateSaver = regionSaver) {
        mutableStateOf(clip.crop ?: CropRegion.FULL)
    }
    val latestRegion by rememberUpdatedState(region)
    var preset by rememberSaveable(clip.id) {
        mutableStateOf(if (clip.crop == null) CropRatio.ORIGINAL else CropRatio.FREE)
    }

    Column(Modifier.fillMaxSize().background(palette.background)) {
      Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("화면 비율·크롭", color = palette.primaryText,
                    style = MaterialTheme.typography.titleLarge)
                Text("선택한 클립에만 적용", color = palette.secondaryText,
                    style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
                Icon(CutIcons.Back, contentDescription = "편집 화면으로")
            }
        }

        if (source == null) {
            Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            Text("원본 프레임 정보를 확인하고 있습니다.", color = palette.secondaryText)
        } else if (source?.width == 0 || source?.height == 0) {
            Text("영상 프레임 정보를 읽지 못했습니다. 원본 파일을 확인해 주세요.",
                color = palette.error)
            OutlinedButton(onClick = onCancel) { Text("편집 화면으로") }
        } else {
            val active = requireNotNull(source)
            Text("크롭 영역 미리보기", color = palette.primaryText,
                style = MaterialTheme.typography.titleMedium)
            BoxWithConstraints(Modifier.fillMaxWidth().height(320.dp),
                contentAlignment = Alignment.Center) {
                val aspect = active.width.toFloat() / active.height
                val frameHeight = minOf(320.dp, maxWidth / aspect)
                val frameWidth = frameHeight * aspect
                Box(
                    Modifier.size(frameWidth, frameHeight)
                        .background(Color.Black)
                        .testTag("crop-preview")
                        .pointerInput(frameWidth, frameHeight) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val updated = latestRegion.zoom(zoom)
                                region = updated.move(
                                    pan.x / size.width.coerceAtLeast(1).toFloat(),
                                    pan.y / size.height.coerceAtLeast(1).toFloat(),
                                )
                            }
                        }
                        .drawWithContent {
                            drawContent()
                            val left = region.left * size.width
                            val top = region.top * size.height
                            val right = region.right * size.width
                            val bottom = region.bottom * size.height
                            val shade = Color.Black.copy(alpha = 0.56f)
                            drawRect(shade, size = Size(size.width, top))
                            drawRect(shade, Offset(0f, top), Size(left, bottom - top))
                            drawRect(shade, Offset(right, top), Size(size.width - right, bottom - top))
                            drawRect(shade, Offset(0f, bottom), Size(size.width, size.height - bottom))
                            drawRect(palette.timelineMarker, Offset(left, top),
                                Size(right - left, bottom - top), style = Stroke(width = 3.dp.toPx()))
                            for (i in 1..2) {
                                val x = left + (right - left) * i / 3f
                                val y = top + (bottom - top) * i / 3f
                                drawLine(palette.timelineMarker.copy(alpha = 0.90f),
                                    Offset(x, top), Offset(x, bottom), strokeWidth = 1.dp.toPx())
                                drawLine(palette.timelineMarker.copy(alpha = 0.90f),
                                    Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
                            }
                            for (corner in listOf(
                                Offset(left, top), Offset(right, top),
                                Offset(left, bottom), Offset(right, bottom)
                            )) {
                                drawCircle(Color.White, radius = 5.dp.toPx(), center = corner)
                            }
                        }
                ) {
                    if (active.image != null) {
                        Image(
                            bitmap = requireNotNull(active.image).asImageBitmap(),
                            contentDescription = "원본 영상 크롭 미리보기",
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            Text("영상을 손가락으로 이동하고 두 손가락으로 확대·축소할 수 있습니다.",
                color = palette.secondaryText, style = MaterialTheme.typography.bodySmall)

            Text("화면 비율", color = palette.primaryText,
                style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CropRatio.entries.forEach { ratio ->
                    OutlinedButton(
                        onClick = {
                            preset = ratio
                            if (ratio == CropRatio.ORIGINAL) region = CropRegion.FULL
                            else if (ratio != CropRatio.FREE) {
                                region = CropGeometry.centerForRatio(active.width, active.height, ratio)
                            }
                        },
                        modifier = Modifier.width(86.dp).heightIn(min = 48.dp)
                            .testTag("crop-preset-${ratio.name}")
                            .semantics { this.selected = preset == ratio },
                        border = androidx.compose.foundation.BorderStroke(1.dp,
                            if (preset == ratio) palette.accent else palette.border),
                        shape = CutShape,
                    ) {
                        Text(ratio.label,
                            color = if (preset == ratio) palette.secondaryAccent
                                else palette.primaryText)
                    }
                }
            }
            Text("위치 및 확대", color = palette.primaryText,
                style = MaterialTheme.typography.titleSmall)
            Text("선택 범위 · 가로 ${(region.width * 100f).toInt()}% · 세로 ${(region.height * 100f).toInt()}%",
                color = palette.secondaryText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("ui05a-crop-selection"))
            BoxWithConstraints(Modifier.fillMaxWidth().testTag("ui05a-crop-controls")) {
                if (maxWidth < 400.dp || LocalDensity.current.fontScale >= 1.5f) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CropNudgeButton("축소", Modifier.weight(1f)) {
                                region = region.zoom(1f / 1.15f)
                            }
                            CropNudgeButton("확대", Modifier.weight(1f)) {
                                region = region.zoom(1.15f)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CropNudgeButton("왼쪽", Modifier.weight(1f)) {
                                region = region.move(-0.05f, 0f)
                            }
                            CropNudgeButton("오른쪽", Modifier.weight(1f)) {
                                region = region.move(0.05f, 0f)
                            }
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CropNudgeButton("축소", Modifier.weight(1f)) {
                            region = region.zoom(1f / 1.15f)
                        }
                        CropNudgeButton("왼쪽", Modifier.weight(1f)) {
                            region = region.move(-0.05f, 0f)
                        }
                        CropNudgeButton("오른쪽", Modifier.weight(1f)) {
                            region = region.move(0.05f, 0f)
                        }
                        CropNudgeButton("확대", Modifier.weight(1f)) {
                            region = region.zoom(1.15f)
                        }
                    }
                }
            }
            if (preset == CropRatio.FREE) {
                Text("자유 비율 크기", color = palette.primaryText,
                    style = MaterialTheme.typography.titleSmall)
                Text("가로 ${(region.width * 100f).toInt()}%",
                    color = palette.secondaryText)
                Slider(value = region.width, onValueChange = {
                    region = region.resize(it, region.height)
                }, valueRange = 0.05f..1f, modifier = Modifier.testTag("crop-free-width"))
                Text("세로 ${(region.height * 100f).toInt()}%",
                    color = palette.secondaryText)
                Slider(value = region.height, onValueChange = {
                    region = region.resize(region.width, it)
                }, valueRange = 0.05f..1f, modifier = Modifier.testTag("crop-free-height"))
            }
            OutlinedButton(
                onClick = {
                    preset = CropRatio.ORIGINAL
                    region = CropRegion.FULL
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("crop-reset"),
                shape = CutShape,
            ) { Text("원본 비율로 초기화") }
            Text("크롭 적용 시 영상 재인코딩이 필요하며, 저장 전에 별도로 확인합니다.",
                color = palette.secondaryText,
                style = MaterialTheme.typography.bodySmall)
        }
      }
      Row(
          Modifier.fillMaxWidth().background(palette.surface).padding(12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
          OutlinedButton(onClick = onCancel,
              modifier = Modifier.weight(1f).heightIn(min = 52.dp),
              shape = CutShape) { Text("취소") }
          Button(onClick = { onApply(region.takeUnless { it.isWholeFrame }) },
              enabled = source?.width?.let { it > 0 } == true,
              modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("crop-apply"),
              shape = CutShape) { Text("크롭 적용") }
      }
    }
}

@Composable
private fun CropNudgeButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = CutShape,
    ) {
        Text(label)
    }
}
