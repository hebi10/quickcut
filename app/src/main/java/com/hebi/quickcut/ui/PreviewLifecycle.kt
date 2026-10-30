package com.hebi.quickcut.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hebi.quickcut.export.ExportWorkMonitor

/** Read at the point of preparation too: a delayed effect can outlive the visible Activity. */
internal fun previewCanLoadMedia(owner: LifecycleOwner): Boolean =
    owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !ExportWorkMonitor.isRunning

/** Preview ownership follows visibility; exports keep their independent foreground-service lease. */
@Composable
internal fun rememberPreviewActive(
    preview: EditorPreviewCoordinator,
    onStopped: () -> Unit = {},
    onError: (Exception) -> Unit = {},
): Boolean {
    val owner = LocalLifecycleOwner.current
    val stopped by rememberUpdatedState(onStopped)
    val failed by rememberUpdatedState(onError)
    var active by remember(preview, owner) { mutableStateOf(false) }
    // Subscribe to export changes as well as lifecycle changes. The observer below
    // deliberately reads the live value rather than capturing this render's value.
    val exporting = ExportWorkMonitor.isRunning
    DisposableEffect(preview, owner, exporting) {
        fun update() {
            active = previewCanLoadMedia(owner)
            try {
                preview.setActive(active)
            } catch (error: Exception) {
                // Preparing from ON_START has the same failure boundary as a
                // normal timeline replacement; a preview failure cannot crash resume.
                failed(error)
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            update()
            if (event == Lifecycle.Event.ON_STOP) stopped()
        }
        owner.lifecycle.addObserver(observer)
        update()
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return active
}
