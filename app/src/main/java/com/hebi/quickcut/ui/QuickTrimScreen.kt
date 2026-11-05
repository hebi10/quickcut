package com.hebi.quickcut.ui

import android.view.ViewGroup
import android.view.View
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hebi.quickcut.editor.EditorViewModel
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.TrimInput
import com.hebi.quickcut.editor.TrimInput.millisValue
import com.hebi.quickcut.editor.TrimInput.secondsText
import com.hebi.quickcut.editor.VideoClip
import com.hebi.quickcut.editor.formatTime
import com.hebi.quickcut.media.ExportStage
import com.hebi.quickcut.media.MediaOperationDiagnostics
import kotlinx.coroutines.CancellationException
import com.hebi.quickcut.media.CompositionFactory
import com.hebi.quickcut.settings.QuickCutSettings
import com.hebi.quickcut.settings.QuickTrimSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(UnstableApi::class)
@Composable
fun QuickTrimScreen(
    model: EditorViewModel,
    settings: QuickCutSettings,
    onBack: () -> Unit,
    onDetailedEdit: () -> Unit,
    onSave: () -> Unit,
    onSaveOptions: () -> Unit,
) {
    val clip = model.timeline.clips.singleOrNull() ?: return
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentLifecycleOwner by rememberUpdatedState(lifecycleOwner)
    val player = remember(context) { ExoPlayer.Builder(context.applicationContext).build() }
    var isPlaying by remember(player) { mutableStateOf(false) }
    val output = remember(player) { PreviewOutputState<PlayerView>() }
    val preview = remember(player, model) {
        EditorPreviewCoordinator(
            object : EditorPreviewPlayback {
                override val positionMs get() = player.currentPosition
                override val playing get() = player.isPlaying
                override val ended get() = player.playbackState == Player.STATE_ENDED
                override fun pause() = player.pause()
                override fun play() = player.play()
                override fun stop() = player.stop()
                override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
                override fun replace(timeline: EditTimeline, positionMs: Long) {
                    player.setMediaItem(CompositionFactory.mediaItem(timeline.clips.single()))
                    player.seekTo(positionMs)
                    player.prepare()
                }
            },
            timeline = { quickTrimPreviewTimeline(model.timeline) },
            modelPosition = { model.playPositionMs },
            publishPosition = model::seek,
            initiallyActive = false,
            canPrepare = {
                val view = output.current
                val surface = view?.videoSurfaceView as? SurfaceView
                view != null && surface != null && output.ready && view.player === player &&
                    view.isAttachedToWindow && surface.isAttachedToWindow && surface.holder.surface.isValid &&
                    previewCanLoadMedia(currentLifecycleOwner)
            },
        )
    }
    var previewFailure by remember(player) { mutableStateOf<String?>(null) }
    val reportPreviewFailure: (Exception) -> Unit = { error ->
        preview.playbackFailed()
        MediaOperationDiagnostics.recordFailure(context, ExportStage.PREVIEWING, error,
            model.timeline.clips.map { it.uri }.distinct().size)
        previewFailure = MediaOperationDiagnostics.message(ExportStage.PREVIEWING, error)
    }
    val outputChanged: () -> Unit = {
        try {
            preview.outputChanged()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            reportPreviewFailure(error)
        }
    }
    val retryPreview: () -> Unit = {
        if (previewCanLoadMedia(lifecycleOwner)) {
            try {
                // Clear before prepare, so a synchronous new error remains visible.
                previewFailure = null
                preview.retryPlayback()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                reportPreviewFailure(error)
            }
        }
    }
    val previewActive = rememberPreviewActive(preview,
        onStopped = model::saveSessionOnStop, onError = reportPreviewFailure)
    SideEffect { preview.onModelChanged() }
    DisposableEffect(player, model, preview) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                reportPreviewFailure(error)
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (player.playerError != null) preview.playbackFailed()
                else if (!playing) preview.synchronizePosition()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) previewFailure = null
            }
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (player.playerError != null) preview.playbackFailed()
                else preview.synchronizePosition()
            }
        }
        player.addListener(listener)
        onDispose {
            output.dispose()
            preview.setActive(false)
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(preview) {
        while (isActive) {
            delay(120L)
            if (player.isPlaying) preview.synchronizePosition()
        }
    }
    val timeline = quickTrimPreviewTimeline(model.timeline)
    LaunchedEffect(preview, timeline, previewActive) {
        if (!previewActive) return@LaunchedEffect
        delay(200L)
        try {
            if (!previewCanLoadMedia(lifecycleOwner)) {
                preview.setActive(false)
                return@LaunchedEffect
            }
            preview.replaceTimeline(timeline)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            reportPreviewFailure(error)
        }
    }
    SideEffect { player.volume = if (clip.muted) 0f else 1f }

    var leadingText by rememberSaveable(clip.id, clip.uri, clip.startMs, clip.endMs) {
        mutableStateOf(secondsText(clip.startMs))
    }
    var trailingText by rememberSaveable(clip.id, clip.uri, clip.startMs, clip.endMs) {
        mutableStateOf(secondsText(clip.sourceDurationMs - clip.endMs))
    }

    fun updateTrim(leading: String, trailing: String) {
        val range = TrimInput.removedRange(leading, trailing, clip.sourceDurationMs)
        model.setTrimInputValidity(range != null)
        if (range == null) return
        model.trimClip(clip.id, range.startMs, range.endMs)
        settings.saveQuick(QuickTrimSettings(
            range.startMs, clip.sourceDurationMs - range.endMs, clip.muted,
        ))
    }

    fun changeLeading(text: String) {
        leadingText = text
        updateTrim(leadingText, trailingText)
    }

    fun changeTrailing(text: String) {
        trailingText = text
        updateTrim(leadingText, trailingText)
    }

    val valid = TrimInput.removedRange(leadingText, trailingText, clip.sourceDurationMs) != null
    SideEffect { model.setTrimInputValidity(valid) }
    val canSave = valid && model.canStartExport
    val saveValidated: (() -> Unit) -> Unit = { action ->
        val range = TrimInput.removedRange(leadingText, trailingText, clip.sourceDurationMs)
        model.setTrimInputValidity(range != null)
        // Valid edits already update the timeline. Saving an unchanged rounded
        // display must preserve a previously reviewed precise boundary.
        if (range != null && (range.startMs != clip.startMs || range.endMs != clip.endMs)) {
            updateTrim(leadingText, trailingText)
        }
        if (model.canStartExport) {
            preview.pause()
            action()
        }
    }
    val openDetailed = {
        // A malformed raw draft never changes the timeline. Detailed editing starts from
        // the last applied range and validates its own fields when opened.
        leadingText = secondsText(clip.startMs)
        trailingText = secondsText(clip.sourceDurationMs - clip.endMs)
        model.setTrimInputValidity(true)
        preview.pause()
        onDetailedEdit()
    }
    val onPlay: () -> Unit = {
        if (previewFailure != null) retryPreview()
        else if (player.isPlaying) player.pause()
        else preview.togglePlayback()
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(CutColors.background)) {
        val fontScale = LocalDensity.current.fontScale
        val compactHeader = maxWidth < 400.dp || (fontScale >= 1.5f && maxWidth < 600.dp)
        val sideBySide = maxWidth >= 480.dp && maxWidth > maxHeight
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth()
                .background(Brush.horizontalGradient(listOf(CutColors.panel, CutColors.background)))
                .padding(horizontal = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "뒤로가기")
                    }
                    Icon(Icons.Outlined.ContentCut, contentDescription = null,
                        tint = CutColors.secondaryAccent, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("빠른 자르기", color = CutColors.primaryText,
                        fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    if (!compactHeader) DetailedEditButton(openDetailed)
                }
                if (compactHeader) {
                    DetailedEditButton(openDetailed,
                        Modifier.fillMaxWidth().padding(bottom = 8.dp))
                }
            }
        previewFailure?.let { message ->
            PreviewRecoveryNotice(message, onRetry = retryPreview)
        }
            if (sideBySide) {
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    QuickPreview(player, isPlaying, clip.durationMs, model.playPositionMs,
                        onPlay, preview::seek, Modifier.weight(1f).fillMaxHeight(), output, outputChanged)
                    QuickTrimControls(
                        clip, leadingText, trailingText, valid, canSave,
                        ::changeLeading, ::changeTrailing,
                        onToggleMute = {
                            model.toggleMute()
                            settings.saveQuick(settings.loadQuick().copy(muteAudio = !clip.muted))
                        },
                        onSave = { saveValidated(onSave) },
                        onSaveOptions = { saveValidated(onSaveOptions) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            } else {
                QuickPreview(player, isPlaying, clip.durationMs, model.playPositionMs,
                    onPlay, preview::seek, Modifier.fillMaxWidth().weight(1f), output, outputChanged)
                QuickTrimControls(
                    clip, leadingText, trailingText, valid, canSave,
                    ::changeLeading, ::changeTrailing,
                    onToggleMute = {
                        model.toggleMute()
                        settings.saveQuick(settings.loadQuick().copy(muteAudio = !clip.muted))
                    },
                    onSave = { saveValidated(onSave) },
                    onSaveOptions = { saveValidated(onSaveOptions) },
                    modifier = Modifier.fillMaxWidth().weight(1.4f),
                )
            }
        }
    }
}

@Composable
private fun DetailedEditButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp),
        shape = CutShape, border = BorderStroke(1.dp, CutColors.border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = CutColors.secondaryText)) {
        Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("상세 편집")
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun QuickPreview(
    player: ExoPlayer,
    playing: Boolean,
    timelineDuration: Long,
    currentPosition: Long,
    onPlay: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier,
    output: PreviewOutputState<PlayerView>,
    outputChanged: () -> Unit,
) {
    Column(modifier.padding(horizontal = 12.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth().clip(CutShape).background(Color.Black)
            .border(1.dp, CutColors.border, CutShape), contentAlignment = Alignment.Center) {
            key(player) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            this.player = player
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            bindQuickPreviewOutput(this, player, output, outputChanged)
                        }
                    },
                    update = { it.player = player },
                    onRelease = { if (output.release(it)) outputChanged() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        PreviewPlaybackControls(playing, timelineDuration, currentPosition, onPlay, onSeek)
    }
}


@OptIn(UnstableApi::class)
private fun bindQuickPreviewOutput(
    view: PlayerView,
    player: ExoPlayer,
    output: PreviewOutputState<PlayerView>,
    outputChanged: () -> Unit,
) {
    val surface = view.videoSurfaceView as SurfaceView
    output.bind(view)
    outputChanged()
    fun scheduleReady() {
        val generation = output.generation(view) ?: return
        view.post {
            if (view.player !== player || !view.isAttachedToWindow || !surface.isAttachedToWindow ||
                !surface.holder.surface.isValid) return@post
            if (output.markReady(view, generation)) outputChanged()
        }
    }
    fun invalidate() { if (output.invalidate(view)) outputChanged() }
    view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = scheduleReady()
        override fun onViewDetachedFromWindow(v: View) = invalidate()
    })
    surface.holder.addCallback(object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = scheduleReady()
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = scheduleReady()
        override fun surfaceDestroyed(holder: SurfaceHolder) = invalidate()
    })
    scheduleReady()
}

@Composable
private fun QuickTrimControls(
    clip: VideoClip,
    leadingText: String,
    trailingText: String,
    valid: Boolean,
    canSave: Boolean,
    onLeadingChange: (String) -> Unit,
    onTrailingChange: (String) -> Unit,
    onToggleMute: () -> Unit,
    onSave: () -> Unit,
    onSaveOptions: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.background(CutColors.background)) {
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(CutColors.accentMuted, CutPanelShape)
                        .border(1.dp, CutColors.accent.copy(alpha = 0.65f), CutPanelShape)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text("원본 " + formatTime(clip.sourceDurationMs),
                        color = CutColors.secondaryText, fontSize = 13.sp)
                    Text("남길 원본 구간 " + formatTime(clip.startMs) + " – " + formatTime(clip.endMs),
                        color = CutColors.secondaryText, fontSize = 13.sp)
                    Text("결과 " + formatTime(clip.durationMs),
                        color = CutColors.secondaryAccent, fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold)
                }
                Text("자르기 범위", color = CutColors.secondaryAccent,
                    style = MaterialTheme.typography.titleSmall)
                TrimSecondsRow(
                    label = "앞부분 삭제",
                    value = leadingText,
                    onValueChange = onLeadingChange,
                    onStep = { delta ->
                        onLeadingChange(secondsText(
                            ((millisValue(leadingText) ?: clip.startMs) + delta)
                                .coerceIn(0L, clip.sourceDurationMs)
                        ))
                    },
                )
                TrimSecondsRow(
                    label = "뒷부분 삭제",
                    value = trailingText,
                    onValueChange = onTrailingChange,
                    onStep = { delta ->
                        onTrailingChange(secondsText(
                            ((millisValue(trailingText) ?: (clip.sourceDurationMs - clip.endMs)) + delta)
                                .coerceIn(0L, clip.sourceDurationMs)
                        ))
                    },
                )
                if (!valid) {
                    Text("시간을 확인해 주세요. 결과 영상은 최소 0.1초 이상이어야 합니다.",
                        color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(if (clip.muted) "원본 소리: 음소거" else "원본 소리: 유지",
                        color = CutColors.secondaryText, fontSize = 13.sp)
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        onClick = onToggleMute,
                        shape = CutShape,
                        border = BorderStroke(1.dp, CutColors.border),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = CutColors.primaryText,
                            containerColor = CutColors.panel,
                        ),
                    ) {
                        Icon(if (clip.muted) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
                            contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (clip.muted) "소리 복원" else "음소거")
                    }
                }
                Text(
                    "마지막 자르기 값은 자동 저장됩니다. 고속 저장 여부는 저장 전에 확인합니다.",
                    color = CutColors.secondaryText, fontSize = 12.sp,
                )
                OutlinedButton(
                    onClick = onSaveOptions,
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = CutShape,
                ) { Text("저장 옵션 · 날짜 및 원본 처리") }
        }
        Button(
                onClick = onSave,
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .heightIn(min = 52.dp),
                shape = CutShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CutColors.accent, contentColor = CutColors.onAccent,
                ),
            ) {
                Text("영상 저장", fontWeight = FontWeight.SemiBold)
        }
        }
}

@Composable
private fun TrimSecondsRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onStep: (Long) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = CutColors.primaryText, fontSize = 14.sp,
            modifier = Modifier.fillMaxWidth())
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        OutlinedButton(
            onClick = { onStep(-1_000L) },
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.width(52.dp).height(56.dp).semantics {
                contentDescription = "$label 1초 감소"
            },
            shape = CutShape,
            border = BorderStroke(1.dp, CutColors.border),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = CutColors.panel, contentColor = CutColors.accent,
            ),
        ) {
            Text("−")
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            suffix = { Text("초") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            shape = CutShape,
            modifier = Modifier.weight(1f).heightIn(min = 56.dp).semantics {
                contentDescription = "$label 시간(초)"
            },
        )
        OutlinedButton(
            onClick = { onStep(1_000L) },
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.width(52.dp).height(56.dp).semantics {
                contentDescription = "$label 1초 증가"
            },
            shape = CutShape,
            border = BorderStroke(1.dp, CutColors.border),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = CutColors.panel, contentColor = CutColors.accent,
            ),
        ) {
            Text("+")
        }
        }
    }
}


