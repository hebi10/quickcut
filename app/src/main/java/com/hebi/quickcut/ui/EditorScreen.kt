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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Reorder
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hebi.quickcut.editor.EditorViewModel
import com.hebi.quickcut.editor.TrimInput
import com.hebi.quickcut.editor.TrimInput.secondsText
import com.hebi.quickcut.editor.VideoClip
import com.hebi.quickcut.editor.formatTime
import com.hebi.quickcut.media.CompositionFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToLong

private data class ActionItem(
    val key: String,
    val text: String,
    val icon: ImageVector,
)

private val actions = listOf(
    ActionItem("trim", "자르기", Icons.Outlined.ContentCut),
    ActionItem("split", "분할", Icons.Outlined.CallSplit),
    ActionItem("delete", "삭제", Icons.Outlined.DeleteOutline),
    ActionItem("move", "순서", Icons.Outlined.Reorder),
    ActionItem("mute", "음소거", Icons.Outlined.VolumeOff),
)

@OptIn(UnstableApi::class, ExperimentalApi::class)
@Composable
fun EditorScreen(
    model: EditorViewModel,
    addVideos: () -> Unit,
    save: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val player = remember(context) { CompositionPlayer.Builder(context.applicationContext).build() }
    var showTrim by rememberSaveable { mutableStateOf(false) }
    var showMove by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                model.reportError("미리보기 재생에 실패했습니다. 다른 영상을 선택하거나 다시 시도해 주세요.")
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(model.timeline) {
        if (model.timeline.isEmpty) {
            player.stop()
        } else {
            delay(160)
            try {
                player.pause()
                player.setComposition(
                    CompositionFactory.from(model.timeline),
                    model.playPositionMs,
                )
                player.prepare()
            } catch (_: Exception) {
                model.reportError("현재 영상 구성을 미리 볼 수 없습니다.")
            }
        }
    }

    LaunchedEffect(player) {
        while (isActive) {
            delay(120)
            if (player.isPlaying) {
                model.seek(player.currentPosition)
            }
        }
    }

    val onSeek: (Long) -> Unit = { ms ->
        model.seek(ms)
        player.seekTo(model.playPositionMs)
    }
    val onSelect: (Long) -> Unit = { id ->
        if (id != model.selectedId && model.timeline.clipAt(id) != null)
            model.setTrimInputValidity(true)
        model.selectClip(id)
        player.seekTo(model.playPositionMs)
    }
    val onClipMoved: (Long, Int) -> Unit = { id, direction ->
        model.moveClip(id, direction)
    }
    val onClipTrimmed: (Long, Long, Long) -> Unit = { id, start, end ->
        model.trimClip(id, start, end)
    }
    val onAction: (String) -> Unit = { key ->
        when (key) {
            "trim" -> {
                model.setTrimInputValidity(true)
                showTrim = !showTrim
            }
            "split" -> model.splitSelected()
            "delete" -> showDelete = true
            "move" -> showMove = true
            "mute" -> model.toggleMute()
        }
    }
    val onPlay = {
        if (player.playbackState == Player.STATE_ENDED) onSeek(0)
        if (player.isPlaying) player.pause() else player.play()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        EditorHeader(
            onBack = model::requestExit,
            onAdd = addVideos,
            onSave = { if (model.canStartExport) save() },
            onUndo = model::undo,
            onRedo = model::redo,
            canUndo = model.canUndo,
            canRedo = model.canRedo,
            canSave = model.canStartExport,
        )
        if (model.timeline.isEmpty) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("영상이 없습니다. 실행 취소하거나 영상을 추가하세요.", color = CutColors.secondaryText)
                TextButton(onClick = addVideos, shape = CutShape) { Text("영상 추가") }
            }
            return@Column
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val availableWidth = maxWidth
            val compactHeight = maxHeight < 480.dp
            val wide = availableWidth >= 840.dp && !compactHeight
            val selected = model.selectedClip

            when {
                compactHeight -> {
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            PreviewPanel(
                                player = player,
                                playing = isPlaying,
                                timelineDuration = model.timeline.totalDurationMs,
                                currentPosition = model.playPositionMs,
                                onPlay = onPlay,
                                onSeek = onSeek,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                compact = true,
                            )
                            Column(
                                Modifier.width(if (availableWidth < 600.dp) 128.dp else 160.dp).fillMaxHeight()
                                    .background(CutColors.surface).verticalScroll(rememberScrollState())
                                    .padding(6.dp),
                            ) {
                                ToolButtons(
                                    selected = selected,
                                    model = model,
                                    onAction = onAction,
                                    vertical = true,
                                    showTrim = showTrim,
                                )
                                if (showTrim && selected != null) {
                                    TrimEditor(selected, model::trimSelected, model::beginTrim,
                                        model::finishTrim, model::setTrimInputValidity)
                                }
                            }
                        }
                        TimelineStrip(
                            timeline = model.timeline,
                            selectedId = model.selectedId,
                            playheadMs = model.playPositionMs,
                            onSelect = onSelect,
            onMove = onClipMoved,
            onTrim = onClipTrimmed,
            onTrimStart = model::beginTrim,
            onTrimEnd = model::finishTrim,
                            showHint = false,
                            modifier = Modifier.fillMaxWidth().height(130.dp),
                        )
                    }
                }
                wide -> {
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            PreviewPanel(
                                player = player,
                                playing = isPlaying,
                                timelineDuration = model.timeline.totalDurationMs,
                                currentPosition = model.playPositionMs,
                                onPlay = onPlay,
                                onSeek = onSeek,
                                modifier = Modifier.weight(1f).fillMaxHeight().padding(16.dp),
                                compact = false,
                            )
                            Column(
                                Modifier.width(310.dp).fillMaxHeight()
                                    .background(CutColors.surface)
                                    .verticalScroll(rememberScrollState())
                                    .padding(14.dp),
                            ) {
                                selected?.let {
                                    Text("선택 클립", color = CutColors.secondaryText, fontSize = 12.sp)
                                    Spacer(Modifier.height(8.dp))
                                    Text("클립 " + (model.timeline.clips.indexOf(it) + 1),
                                        color = CutColors.primaryText,
                                        fontWeight = FontWeight.SemiBold)
                                    Text(formatTime(it.durationMs), color = CutColors.secondaryText)
                                }
                                Spacer(Modifier.height(12.dp))
                                ToolButtons(
                                    selected = selected,
                                    model = model,
                                    onAction = onAction,
                                    vertical = true,
                                    showTrim = showTrim,
                                )
                                if (showTrim && selected != null) {
                                    Spacer(Modifier.height(8.dp))
                                    TrimEditor(selected, model::trimSelected, model::beginTrim,
                                        model::finishTrim, model::setTrimInputValidity)
                                }
                            }
                        }
                        TimelineStrip(
                            timeline = model.timeline,
                            selectedId = model.selectedId,
                            playheadMs = model.playPositionMs,
                            onSelect = onSelect,
            onMove = onClipMoved,
            onTrim = onClipTrimmed,
            onTrimStart = model::beginTrim,
            onTrimEnd = model::finishTrim,
                            modifier = Modifier.fillMaxWidth().height(155.dp),
                        )
                    }
                }
                else -> {
                    Column(Modifier.fillMaxSize()) {
                        PreviewPanel(
                            player = player,
                            playing = isPlaying,
                            timelineDuration = model.timeline.totalDurationMs,
                            currentPosition = model.playPositionMs,
                            onPlay = onPlay,
                            onSeek = onSeek,
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                            compact = false,
                        )
                        TimelineStrip(
                            timeline = model.timeline,
                            selectedId = model.selectedId,
                            playheadMs = model.playPositionMs,
                            onSelect = onSelect,
            onMove = onClipMoved,
            onTrim = onClipTrimmed,
            onTrimStart = model::beginTrim,
            onTrimEnd = model::finishTrim,
                            modifier = Modifier.fillMaxWidth().height(148.dp),
                        )
                        if (showTrim && selected != null) {
                            TrimEditor(
                                selected,
                                model::trimSelected,
                                model::beginTrim,
                                model::finishTrim,
                                model::setTrimInputValidity,
                                modifier = Modifier.fillMaxWidth()
                                    .background(CutColors.surface)
                                    .padding(horizontal = 14.dp),
                            )
                        }
                        ToolButtons(
                            selected = selected,
                            model = model,
                            onAction = onAction,
                            vertical = false,
                            showTrim = showTrim,
                        )
                    }
                }
            }
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("클립을 삭제할까요?") },
            text = { Text("선택한 구간만 편집 목록에서 제거합니다. 원본 영상은 삭제되지 않습니다.") },
            confirmButton = {
                Button(
                    onClick = {
                        model.deleteSelected()
                        showDelete = false
                    },
                    shape = RoundedCornerShape(4.dp),
                ) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }, shape = CutShape) { Text("취소") }
            },
            containerColor = CutColors.panel,
            shape = RoundedCornerShape(4.dp),
        )
    }

    if (showMove) {
        val index = model.timeline.clips.indexOfFirst { it.id == model.selectedId }
        AlertDialog(
            onDismissRequest = { showMove = false },
            title = { Text("클립 순서 변경") },
            text = { Text("선택한 클립을 앞이나 뒤로 이동합니다.") },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = index > 0,
                        onClick = { model.moveSelected(-1); showMove = false },
                        shape = RoundedCornerShape(4.dp),
                    ) { Text("앞으로") }
                    Button(
                        enabled = index >= 0 && index < model.timeline.clips.lastIndex,
                        onClick = { model.moveSelected(1); showMove = false },
                        shape = RoundedCornerShape(4.dp),
                    ) { Text("뒤로") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showMove = false }, shape = CutShape) { Text("닫기") }
            },
            containerColor = CutColors.panel,
            shape = RoundedCornerShape(4.dp),
        )
    }
}

@Composable
private fun EditorHeader(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onSave: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    canSave: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "편집 종료")
        }
        Text("편집", color = CutColors.primaryText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Undo, contentDescription = "실행 취소")
        }
        IconButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Redo, contentDescription = "다시 실행")
        }
        IconButton(onClick = onAdd, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Add, contentDescription = "영상 추가")
        }
        TextButton(onClick = onSave, enabled = canSave,
            modifier = Modifier.heightIn(min = 48.dp), shape = CutShape) {
            Text("저장", color = if (canSave) CutColors.accent else CutColors.mutedText,
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(UnstableApi::class, ExperimentalApi::class)
@Composable
private fun PreviewPanel(
    player: CompositionPlayer,
    playing: Boolean,
    timelineDuration: Long,
    currentPosition: Long,
    onPlay: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier,
    compact: Boolean,
) {
    Column(modifier.background(CutColors.background)) {
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        this.player = player
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { view -> view.player = player },
                modifier = Modifier.fillMaxSize(),
            )
            if (!playing) {
                IconButton(
                    onClick = onPlay,
                    modifier = Modifier.size(if (compact) 54.dp else 72.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                ) {
                    Icon(
                        Icons.Outlined.PlayArrow,
                        contentDescription = "재생",
                        tint = Color.White,
                        modifier = Modifier.size(if (compact) 32.dp else 42.dp),
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(if (compact) 45.dp else 60.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(onClick = onPlay, modifier = Modifier.size(48.dp)) {
                Icon(
                    if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (playing) "일시정지" else "재생",
                )
            }
            if (!compact) Text(formatTime(currentPosition), fontSize = 12.sp)
            Slider(
                value = currentPosition.toFloat().coerceIn(0f, timelineDuration.coerceAtLeast(1L).toFloat()),
                onValueChange = { onSeek(it.roundToLong()) },
                valueRange = 0f..timelineDuration.coerceAtLeast(1L).toFloat(),
                modifier = Modifier.weight(1f),
            )
            Text(
                formatTime(timelineDuration),
                color = CutColors.secondaryText,
                fontSize = 12.sp,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}

@Composable
private fun ToolButtons(
    selected: VideoClip?,
    model: EditorViewModel,
    onAction: (String) -> Unit,
    vertical: Boolean,
    showTrim: Boolean,
) {
    if (vertical) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            actions.forEach { item ->
                val active = item.key == "trim" && showTrim
                val enabled = selected != null && (item.key != "split" || model.canSplitSelected)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .border(1.dp, if (active) CutColors.accent else CutColors.border)
                        .background(CutColors.panel)
                        .clickable(enabled = enabled) { onAction(item.key) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        if (item.key == "mute" && selected?.muted == true) Icons.Outlined.VolumeUp else item.icon,
                        contentDescription = null,
                        tint = if (enabled) {
                            if (active) CutColors.accent else CutColors.primaryText
                        } else CutColors.mutedText,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        if (item.key == "mute" && selected?.muted == true) "소리 켜기" else item.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (enabled) CutColors.primaryText else CutColors.mutedText,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .background(CutColors.background)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            actions.forEach { item ->
                val active = item.key == "trim" && showTrim
                val enabled = selected != null && (item.key != "split" || model.canSplitSelected)
                Column(
                    modifier = Modifier.width(76.dp).height(78.dp)
                        .border(1.dp, if (active) CutColors.accent else CutColors.border)
                        .background(CutColors.surface)
                        .clickable(enabled = enabled) { onAction(item.key) },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        if (item.key == "mute" && selected?.muted == true) Icons.Outlined.VolumeUp else item.icon,
                        contentDescription = null,
                        tint = if (!enabled) CutColors.mutedText
                            else if (active) CutColors.accent else CutColors.primaryText,
                        modifier = Modifier.size(23.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (item.key == "mute" && selected?.muted == true) "소리 켜기" else item.text,
                        color = if (!enabled) CutColors.mutedText
                            else if (active) CutColors.accent else CutColors.primaryText,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrimEditor(
    clip: VideoClip,
    onTrim: (Long, Long) -> Unit,
    onBeginTrim: () -> Unit,
    onEndTrim: () -> Unit,
    onValidityChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var startText by rememberSaveable(clip.id, clip.uri, clip.startMs, clip.endMs) {
        mutableStateOf(secondsText(clip.startMs))
    }
    var endText by rememberSaveable(clip.id, clip.uri, clip.startMs, clip.endMs) {
        mutableStateOf(secondsText(clip.endMs))
    }
    fun inputRange(start: String, end: String) {
        val range = TrimInput.selectedRange(start, end, clip.sourceDurationMs)
        onValidityChange(range != null)
        if (range == null) return
        onBeginTrim()
        onTrim(range.startMs, range.endMs)
        onEndTrim()
    }
    val valid = TrimInput.selectedRange(startText, endText, clip.sourceDurationMs) != null
    SideEffect { onValidityChange(valid) }
    Column(modifier.padding(vertical = 8.dp)) {
        Text("자르기 범위", color = CutColors.primaryText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        RangeSlider(
            value = clip.startMs.toFloat()..clip.endMs.toFloat(),
            onValueChange = { range ->
                val start = range.start.roundToLong().coerceIn(0L, clip.sourceDurationMs)
                val end = range.endInclusive.roundToLong().coerceIn(0L, clip.sourceDurationMs)
                if (TrimInput.range(start, end, clip.sourceDurationMs) != null) {
                    startText = secondsText(start)
                    endText = secondsText(end)
                    onValidityChange(true)
                    onBeginTrim()
                    onTrim(start, end)
                }
            },
            valueRange = 0f..clip.sourceDurationMs.toFloat(),
            onValueChangeFinished = onEndTrim,
            enabled = clip.sourceDurationMs >= TrimInput.MIN_DURATION_MS,
            modifier = Modifier.fillMaxWidth().testTag("detailed-trim-range"),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("시작 " + formatTime(clip.startMs), color = CutColors.secondaryText, fontSize = 12.sp)
            Text("끝 " + formatTime(clip.endMs), color = CutColors.secondaryText, fontSize = 12.sp)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val changeStart: (String) -> Unit = {
                startText = it
                inputRange(startText, endText)
            }
            val changeEnd: (String) -> Unit = {
                endText = it
                inputRange(startText, endText)
            }
            if (maxWidth < 320.dp || LocalDensity.current.fontScale >= 1.5f) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TrimTimeField(startText, changeStart, "시작 시간 (초)", !valid,
                        Modifier.fillMaxWidth())
                    TrimTimeField(endText, changeEnd, "종료 시간 (초)", !valid,
                        Modifier.fillMaxWidth())
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TrimTimeField(startText, changeStart, "시작 시간 (초)", !valid,
                        Modifier.weight(1f))
                    TrimTimeField(endText, changeEnd, "종료 시간 (초)", !valid,
                        Modifier.weight(1f))
                }
            }
        }
        if (!valid) {
            Text("시간을 확인해 주세요. 결과 영상은 최소 0.1초 이상이어야 합니다.",
                color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
    }
}

@Composable
private fun TrimTimeField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isError: Boolean,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier,
    )
}


