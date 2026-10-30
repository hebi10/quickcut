package com.hebi.quickcut.ui

import com.hebi.quickcut.editor.EditTimeline

/** The small player surface needed to coordinate editor positions without Android state. */
internal interface EditorPreviewPlayback {
    val positionMs: Long
    val playing: Boolean
    val ended: Boolean
    fun pause()
    fun play()
    fun stop()
    fun seekTo(positionMs: Long)
    fun replace(timeline: EditTimeline, positionMs: Long)
}

/** Positions belong to the exact timeline loaded in the player, including mute and order. */
internal class EditorPreviewCoordinator(
    private val player: EditorPreviewPlayback,
    private val timeline: () -> EditTimeline,
    private val modelPosition: () -> Long,
    private val publishPosition: (Long) -> Unit,
    initiallyActive: Boolean = true,
) {
    private var loadedTimeline: EditTimeline? = null
    private var publishedPosition = 0L
    private var active = initiallyActive

    /** User pauses keep media ready; an invisible preview relinquishes its playback resources. */
    fun setActive(value: Boolean) {
        if (active == value) return
        if (!value) {
            synchronizePosition()
            active = false
            // Invalidate before player callbacks can observe any stop/release state.
            loadedTimeline = null
            player.pause()
            player.stop()
        } else {
            active = true
            replaceTimeline(timeline())
        }
    }

    fun onModelChanged() {
        if (!active) return
        val current = timeline()
        if (loadedTimeline != current) {
            // Invalidate before pause: pause/reset may synchronously emit player callbacks.
            val wasLoaded = loadedTimeline != null
            loadedTimeline = null
            if (wasLoaded) player.pause()
        } else if (modelPosition() != publishedPosition) {
            seek(modelPosition())
        }
    }

    fun replaceTimeline(replacement: EditTimeline): Boolean {
        if (!active || replacement != timeline()) return false
        if (loadedTimeline == replacement) {
            onModelChanged()
            return true
        }
        loadedTimeline = null
        player.pause()
        if (replacement.isEmpty) {
            player.stop()
            return false
        }
        val position = modelPosition().coerceIn(0L, replacement.totalDurationMs)
        // Ignore all callbacks until the complete replacement has been configured.
        player.replace(replacement, position)
        publishedPosition = position
        loadedTimeline = replacement
        return true
    }

    fun synchronizePosition() {
        val current = timeline()
        if (loadedTimeline != current) return
        // A model seek may arrive before Compose's next SideEffect. It wins over polling.
        if (modelPosition() != publishedPosition) {
            seek(modelPosition())
            return
        }
        publish(player.positionMs.coerceIn(0L, current.totalDurationMs))
    }

    fun seek(positionMs: Long) {
        val current = timeline()
        val position = positionMs.coerceIn(0L, current.totalDurationMs)
        publish(position)
        if (loadedTimeline == current) {
            // Seek/reset callbacks can still expose the pre-seek position.
            loadedTimeline = null
            player.seekTo(position)
            loadedTimeline = current
        }
    }

    fun pause() {
        synchronizePosition()
        player.pause()
    }

    fun togglePlayback() {
        if (!active || loadedTimeline != timeline()) return
        if (player.playing) {
            pause()
        } else {
            if (player.ended) seek(0L)
            player.play()
        }
    }

    private fun publish(positionMs: Long) {
        publishedPosition = positionMs
        publishPosition(positionMs)
    }
}
