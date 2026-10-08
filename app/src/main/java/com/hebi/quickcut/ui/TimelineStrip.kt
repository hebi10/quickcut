package com.hebi.quickcut.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.formatTime

@Composable
fun TimelineStrip(
    timeline: EditTimeline,
    selectedId: Long?,
    playheadMs: Long,
    onSelect: (Long) -> Unit,
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
                val isSelected = selectedId == clip.id
                val clipStart = timeline.startOf(index)
                val isActive = playheadMs >= clipStart &&
                    (playheadMs < clipStart + clip.durationMs || index == timeline.clips.lastIndex)
                val tileWidth = (clip.durationMs / 200f).coerceIn(108f, 260f).dp
                BoxWithConstraints(
                    modifier = Modifier
                        .width(tileWidth)
                        .height(86.dp)
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) CutColors.accent else CutColors.border,
                        )
                        .clickable { onSelect(clip.id) },
                ) {
                    ClipThumbnail(
                        uri = clip.uri,
                        timeMs = clip.startMs + clip.durationMs / 2,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                    )
                    if (isSelected) {
                        Box(
                            Modifier.align(Alignment.CenterStart)
                                .width(9.dp).fillMaxHeight()
                                .background(Color.White),
                        )
                        Box(
                            Modifier.align(Alignment.CenterEnd)
                                .width(9.dp).fillMaxHeight()
                                .background(Color.White),
                        )
                    }
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
                }
            }
        }
    }
}
