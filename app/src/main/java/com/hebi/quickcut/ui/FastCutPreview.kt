package com.hebi.quickcut.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.ui.PlayerView
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.media.CompositionFactory
import com.hebi.quickcut.media.FastCutSuggestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Preview owns no editor state: looking, playing or closing cannot approve a cut. */
@Composable
internal fun FastCutPreview(suggestion: FastCutSuggestion) {
    var expanded by remember(suggestion) { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = !expanded }, shape = CutShape) {
        Text(if (expanded) "미리보기 닫기" else "미리보기 · 요청/추천 비교")
    }
    if (expanded) FastCutPreviewContent(suggestion)
}

@OptIn(UnstableApi::class, ExperimentalApi::class)
@Composable
private fun FastCutPreviewContent(suggestion: FastCutSuggestion) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val targets = remember(suggestion) { FastCutPreviewPlan.targets(suggestion) }
    if (targets.isEmpty()) return
    var selectedIndex by remember(suggestion) { mutableStateOf(0) }
    val target = targets[selectedIndex]
    val player = remember(context, suggestion) { CompositionPlayer.Builder(context.applicationContext).build() }
    var playbackError by remember { mutableStateOf(false) }
    var playingLabel by remember { mutableStateOf<String?>(null) }
    DisposableEffect(player, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { playbackError = true }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        player.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(target) { player.pause(); playingLabel = null; playbackError = false }
    fun play(timeline: EditTimeline, label: String) {
        playbackError = false
        try {
            player.pause()
            player.setComposition(CompositionFactory.from(FastCutPreviewPlan.window(timeline, target)), 0L)
            player.prepare()
            player.play()
            playingLabel = label
        } catch (_: Exception) { playbackError = true }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = { selectedIndex-- }, enabled = selectedIndex > 0) { Text("이전") }
            Text(target.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { selectedIndex++ }, enabled = selectedIndex < targets.lastIndex) { Text("다음") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BoundaryFrame("요청", suggestion.requested, target, Modifier.weight(1f))
            BoundaryFrame("추천", suggestion.adjusted, target, Modifier.weight(1f))
        }
        Text("위 이미지는 경계 시각에 가장 가까운 화면입니다. 아래 재생으로 실제 남길 구간을 확인하세요.",
            style = MaterialTheme.typography.bodySmall, color = CutColors.secondaryText)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { play(suggestion.requested, "요청 위치") }, modifier = Modifier.weight(1f), shape = CutShape) {
                Text("요청 재생")
            }
            OutlinedButton(onClick = { play(suggestion.adjusted, "추천 위치") }, modifier = Modifier.weight(1f), shape = CutShape) {
                Text("추천 재생")
            }
        }
        if (playingLabel != null) {
            Text("$playingLabel · 최대 3초", style = MaterialTheme.typography.bodySmall)
            AndroidView(
                factory = { viewContext -> PlayerView(viewContext).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    this.player = player
                    useController = true
                } },
                update = { it.player = player },
                modifier = Modifier.fillMaxWidth().height(180.dp),
            )
        }
        if (playbackError) Text("이 기기에서 미리보기를 재생하지 못했습니다. 시간 안내를 확인한 뒤 저장 방식을 직접 선택할 수 있습니다.",
            color = CutColors.secondaryText, style = MaterialTheme.typography.bodySmall)
        Text("미리보기만으로 자르기 위치가 변경되지는 않습니다.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BoundaryFrame(label: String, timeline: EditTimeline, target: FastCutPreviewTarget, modifier: Modifier) {
    val context = LocalContext.current
    val frame = FastCutPreviewPlan.frame(timeline, target)
    var bitmap by remember(frame) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(frame) { mutableStateOf(true) }
    LaunchedEffect(frame) {
        bitmap = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(frame.first.uri))
                ensureActive()
                retriever.getScaledFrameAtTime(frame.second, MediaMetadataRetriever.OPTION_CLOSEST, 320, 180)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            finally { runCatching { retriever.release() } }
        }
        loading = false
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        val ready = bitmap
        if (ready != null) Image(ready.asImageBitmap(), "$label ${target.label}", Modifier.fillMaxWidth().height(90.dp))
        else Text(if (loading) "화면 불러오는 중" else "화면을 읽을 수 없음", style = MaterialTheme.typography.bodySmall)
        Text(preciseTime(frame.second), style = MaterialTheme.typography.bodySmall)
    }
}
