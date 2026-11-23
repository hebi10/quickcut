package com.hebi.quickcut.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hebi.quickcut.editor.VideoClip
import com.hebi.quickcut.media.KeyframeFinder
import kotlinx.coroutines.launch

@Composable
internal fun KeyframeTrimControls(
    clip: VideoClip,
    enabled: Boolean,
    onSnap: (VideoClip, Long, Long) -> Boolean,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var message by remember(clip) { mutableStateOf<String?>(null) }
    fun snap(start: Boolean, forward: Boolean) {
        if (checking) return
        checking = true
        message = null
        scope.launch {
            try {
                val range = KeyframeFinder.snap(context, clip, start, forward)
                if (range == null) {
                    message = "이 방향에 이동할 수 있는 키프레임이 없습니다."
                } else {
                    onSnap(clip, range.first, range.second)
                }
            } finally {
                checking = false
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("키프레임 이동", style = MaterialTheme.typography.titleSmall)
        for ((title, start) in listOf("시작" to true, "끝" to false)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { snap(start, false) },
                    enabled = enabled && !checking,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = CutShape,
                ) { Text("$title 이전") }
                OutlinedButton(
                    onClick = { snap(start, true) },
                    enabled = enabled && !checking,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = CutShape,
                ) { Text("$title 다음") }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
