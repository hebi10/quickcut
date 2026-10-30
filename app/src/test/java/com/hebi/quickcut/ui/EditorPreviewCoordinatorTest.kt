package com.hebi.quickcut.ui

import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import org.junit.Assert.*
import org.junit.Test

class EditorPreviewCoordinatorTest {
    private val original = EditTimeline(listOf(VideoClip(1, "content://video/1", 10_000)))
    private var timeline = original
    private var position = 500L
    private val player = FakePlayback()
    private val preview = EditorPreviewCoordinator(player, { timeline }, { position }, { position = it })

    @Test fun pauseCapturesThePositionBetweenPollingTicks() {
        preview.replaceTimeline(timeline)
        player.positionMs = 2_345L
        player.playing = true
        preview.pause()
        assertEquals(2_345L, position)
        assertFalse(player.playing)
    }

    @Test fun splitUsesTheLatestPlaybackPosition() {
        preview.replaceTimeline(timeline)
        player.positionMs = 3_457L
        preview.pause()
        timeline = timeline.split(1, position, 2)
        assertEquals(3_457L, timeline.clips.first().durationMs)
    }

    @Test fun changedCompositionRejectsPollingAndPauseCallbacksBeforeReplacement() {
        preview.replaceTimeline(timeline)
        player.positionMs = 9_000L
        player.playing = true
        timeline = timeline.updateRange(1, 2_000, 6_000)
        position = 1_000L
        preview.synchronizePosition()
        preview.onModelChanged()
        preview.synchronizePosition()
        assertEquals(1_000L, position)
        assertFalse(player.playing)
        assertFalse(preview.replaceTimeline(original))
        assertTrue(preview.replaceTimeline(timeline))
        assertEquals(1_000L, player.positionMs)
    }

    @Test fun resetCallbacksCannotOverwriteTheReplacementStartPosition() {
        preview.replaceTimeline(timeline)
        timeline = timeline.toggleMute(1)
        position = 2_000L
        player.duringReplace = { player.positionMs = 0L; preview.synchronizePosition() }
        preview.replaceTimeline(timeline)
        assertEquals(2_000L, position)
        assertEquals(2_000L, player.positionMs)
    }

    @Test fun externalSeekUpdatesThePlayerWithoutFeedingPlaybackUpdatesBackIntoSeek() {
        preview.replaceTimeline(timeline)
        position = 4_000L
        // A player callback can race ahead of the next Compose SideEffect.
        preview.synchronizePosition()
        preview.onModelChanged()
        assertEquals(4_000L, player.positionMs)
        assertEquals(listOf(4_000L), player.seeks)
        player.positionMs = 4_123L
        preview.synchronizePosition()
        preview.onModelChanged()
        assertEquals(4_123L, position)
        assertEquals(listOf(4_000L), player.seeks)
    }

    @Test fun seekCallbacksCannotRestoreThePreviousPlaybackPosition() {
        preview.replaceTimeline(timeline)
        player.positionMs = 1_234L
        player.duringSeek = { preview.synchronizePosition() }
        preview.seek(6_000L)
        assertEquals(6_000L, position)
        assertEquals(6_000L, player.positionMs)
    }

    @Test fun endAndLifecyclePauseCaptureTheFinalPositionAndReplayStartsAtZero() {
        preview.replaceTimeline(timeline)
        player.positionMs = 10_000L
        player.ended = true
        preview.synchronizePosition()
        assertEquals(10_000L, position)
        preview.togglePlayback()
        assertEquals(0L, position)
        assertEquals(0L, player.positionMs)
        assertTrue(player.playing)
        player.positionMs = 1_111L
        preview.pause()
        assertEquals(1_111L, position)
    }

    @Test fun emptyOrFailedReplacementDoesNotAcceptPlayerPositions() {
        preview.replaceTimeline(timeline)
        timeline = EditTimeline()
        position = 0L
        preview.onModelChanged()
        assertFalse(preview.replaceTimeline(timeline))
        player.positionMs = 7_000L
        preview.synchronizePosition()
        assertEquals(0L, position)
        assertTrue(player.stopped)
        timeline = original
        player.duringReplace = { error("unsupported composition") }
        assertThrows(IllegalStateException::class.java) { preview.replaceTimeline(timeline) }
        preview.synchronizePosition()
        assertEquals(0L, position)
    }

    @Test fun inactivePreviewReleasesResourcesAndKeepsThePositionBeforeStopCallbacks() {
        preview.replaceTimeline(timeline)
        player.positionMs = 2_345L
        player.playing = true
        // Player.stop() may synchronously publish its reset state. That reset is
        // not a user seek and must not overwrite the position we are preserving.
        player.duringStop = { preview.synchronizePosition() }

        preview.setActive(false)

        assertFalse(player.resourcesAllocated)
        assertFalse(player.playing)
        assertEquals(2_345L, position)
        player.positionMs = 0L
        preview.synchronizePosition()
        assertEquals(2_345L, position)
    }

    @Test fun returningToPreviewPreparesTheSavedPositionWithoutAutomaticPlayback() {
        preview.replaceTimeline(timeline)
        player.positionMs = 3_210L
        player.playing = true
        preview.setActive(false)

        preview.setActive(true)

        assertTrue(player.resourcesAllocated)
        assertEquals(3_210L, player.positionMs)
        assertEquals(3_210L, position)
        assertFalse(player.playing)
        preview.togglePlayback()
        assertTrue(player.playing)
    }

    @Test fun backgroundTimelineChangesCannotPrepareUntilPreviewBecomesActive() {
        preview.replaceTimeline(timeline)
        preview.setActive(false)
        val preparedBeforeChange = player.preparations
        timeline = timeline.updateRange(1, 2_000L, 7_000L)
        position = 1_234L

        preview.onModelChanged()
        assertFalse(preview.replaceTimeline(timeline))
        preview.togglePlayback()
        assertFalse(player.resourcesAllocated)
        assertEquals(preparedBeforeChange, player.preparations)
        assertFalse(player.playing)

        preview.setActive(true)
        assertEquals(timeline, player.preparedTimeline)
        assertEquals(1_234L, player.positionMs)
        assertEquals(1_234L, position)
        assertEquals(preparedBeforeChange + 1, player.preparations)
        assertFalse(player.playing)
    }

    @Test fun repeatedInactiveSignalsDoNotReplaceTheSavedPositionWithStoppedPlayerState() {
        preview.replaceTimeline(timeline)
        player.positionMs = 4_567L
        preview.setActive(false)
        preview.setActive(false)
        preview.pause()
        preview.synchronizePosition()
        assertEquals(4_567L, position)
        assertEquals(1, player.stops)

        preview.setActive(true)
        val preparedOnReturn = player.preparations
        preview.setActive(true)
        assertEquals(preparedOnReturn, player.preparations)
        assertEquals(4_567L, player.positionMs)
    }

    @Test fun externalSeekWhileInactiveWinsOverTheSuspendedPlaybackPosition() {
        preview.replaceTimeline(timeline)
        player.positionMs = 2_000L
        preview.setActive(false)
        preview.seek(6_789L)

        preview.setActive(true)

        assertEquals(6_789L, position)
        assertEquals(6_789L, player.positionMs)
        assertFalse(player.playing)
    }

    private class FakePlayback : EditorPreviewPlayback {
        override var positionMs = 0L
        override var playing = false
        override var ended = false
        val seeks = mutableListOf<Long>()
        var stopped = false
        var stops = 0
        var preparations = 0
        var resourcesAllocated = false
        var preparedTimeline: EditTimeline? = null
        var duringReplace: (() -> Unit)? = null
        var duringSeek: (() -> Unit)? = null
        var duringStop: (() -> Unit)? = null
        override fun pause() { playing = false }
        override fun play() { playing = true }
        override fun stop() {
            stopped = true
            stops++
            playing = false
            resourcesAllocated = false
            positionMs = 0L
            duringStop?.invoke()
        }
        override fun seekTo(positionMs: Long) {
            duringSeek?.invoke()
            seeks += positionMs
            this.positionMs = positionMs
        }
        override fun replace(timeline: EditTimeline, positionMs: Long) {
            duringReplace?.invoke()
            this.positionMs = positionMs
            preparedTimeline = timeline
            preparations++
            resourcesAllocated = true
        }
    }
}
