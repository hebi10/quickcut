package com.hebi.quickcut.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import com.hebi.quickcut.editor.formatTime
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun TimelineStrip(
    timeline: EditTimeline,
    selectedId: Long?,
    playheadMs: Long,
    onSelect: (Long) -> Unit,
    onMove: (Long, Int) -> Unit,
    onTrim: (Long, Long, Long) -> Unit,
    onTrimStart: () -> Unit,
    onTrimEnd: () -> Unit,
    showHint: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(modifier.background(CutColors.surface).padding(vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("00:00", fontSize = 11.sp, color = CutColors.mutedText)
            Text(
                "전체 " + formatTime(timeline.totalDurationMs),
                fontSize = 11.sp,
                color = CutColors.mutedText,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            timeline.clips.forEachIndexed { index, clip ->
                key(clip.id) {
                    val selected = selectedId == clip.id
                    val clipStart = timeline.startOf(index)
                    val isActive = playheadMs >= clipStart &&
                        (playheadMs < clipStart + clip.durationMs || index == timeline.clips.lastIndex)
                    val tileWidth = if (selected) 190.dp
                        else (clip.durationMs / 200f).coerceIn(108f, 260f).dp
                    val tilePx = with(LocalDensity.current) { tileWidth.toPx() }
                    val currentClip by rememberUpdatedState(clip)
                    val currentMove by rememberUpdatedState(onMove)
                    val currentTrim by rememberUpdatedState(onTrim)
                    var dragOffsetPx by remember(clip.id) { mutableFloatStateOf(0f) }

                    BoxWithConstraints(
                        modifier = Modifier
                            .offset { IntOffset(dragOffsetPx.roundToInt(), 0) }
                            .width(tileWidth).height(86.dp)
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) CutColors.accent else CutColors.border,
                            )
                            .pointerInput(clip.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragEnd = {
                                        if (abs(dragOffsetPx) >= tilePx * 0.3f) {
                                            currentMove(clip.id, if (dragOffsetPx > 0f) 1 else -1)
                                        }
                                        dragOffsetPx = 0f
                                    },
                                    onDragCancel = { dragOffsetPx = 0f },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffsetPx += dragAmount.x
                                    },
                                )
                            }
                            .clickable { onSelect(clip.id) },
                    ) {
                        ClipThumbnail(
                            uri = clip.uri,
                            timeMs = clip.startMs + clip.durationMs / 2L,
                            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                        )
                        if (isActive) {
                            val fraction = ((playheadMs - clipStart).toFloat() / clip.durationMs)
                                .coerceIn(0f, 1f)
                            Box(
                                Modifier.offset(x = maxWidth * fraction)
                                    .width(2.dp).fillMaxHeight().background(Color.White),
                            )
                        }
                        Text(
                            formatTime(clip.durationMs),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.align(Alignment.BottomStart)
                                .background(Color.Black.copy(alpha = 0.75f))
                                .padding(horizontal = 5.dp, vertical = 3.dp),
                        )
                        if (clip.muted) {
                            Text(
                                "무음",
                                color = Color.White,
                                fontSize = 11.sp,
                                modifier = Modifier.align(Alignment.TopEnd)
                                    .background(Color.Black.copy(alpha = 0.75f))
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                        if (selected) {
                            TrimHandle(
                                clipId = clip.id,
                                start = true,
                                modifier = Modifier.align(Alignment.CenterStart),
                                onDragStart = onTrimStart,
                                onDragEnd = onTrimEnd,
                                onDragAmount = { dx ->
                                    val c = currentClip
                                    val deltaMs = (dx / tilePx * c.durationMs).roundToLong()
                                    val next = (c.startMs + deltaMs)
                                        .coerceIn(0L, c.endMs - 100L)
                                    if (next != c.startMs) currentTrim(c.id, next, c.endMs)
                                },
                            )
                            TrimHandle(
                                clipId = clip.id,
                                start = false,
                                modifier = Modifier.align(Alignment.CenterEnd),
                                onDragStart = onTrimStart,
                                onDragEnd = onTrimEnd,
                                onDragAmount = { dx ->
                                    val c = currentClip
                                    val deltaMs = (dx / tilePx * c.durationMs).roundToLong()
                                    val next = (c.endMs + deltaMs)
                                        .coerceIn(c.startMs + 100L, c.sourceDurationMs)
                                    if (next != c.endMs) currentTrim(c.id, c.startMs, next)
                                },
                            )
                        }
                    }
                }
            }
        }
        if (showHint) {
            Text(
                "클립 선택 · 가장자리 드래그로 자르기 · 길게 눌러 순서 변경",
                color = CutColors.mutedText,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp),
            )
        }
    }
}

@Composable
private fun TrimHandle(
    clipId: Long,
    start: Boolean,
    modifier: Modifier,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDragAmount: (Float) -> Unit,
) {
    val updatedCallback by rememberUpdatedState(onDragAmount)
    val updatedStart by rememberUpdatedState(onDragStart)
    val updatedEnd by rememberUpdatedState(onDragEnd)
    Box(
        modifier = modifier
            .width(48.dp).fillMaxHeight()
            .pointerInput(clipId, start) {
                detectDragGestures(
                    onDragStart = { updatedStart() },
                    onDragEnd = { updatedEnd() },
                    onDragCancel = { updatedEnd() },
                ) { change, dragAmount ->
                    change.consume()
                    updatedCallback(dragAmount.x)
                }
            },
        contentAlignment = if (start) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Box(
            modifier = Modifier.width(8.dp).fillMaxHeight().background(Color.White),
        )
    }
}
