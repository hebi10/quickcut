package com.hebi.quickcut.ui

import android.Manifest
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import com.hebi.quickcut.export.ExportWorkMonitor
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class PreviewLifecycleInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var owner: PreviewOwner
    private lateinit var player: PlaybackResources
    private lateinit var preview: EditorPreviewCoordinator
    private var position = 500L
    private var failures = 0
    private var stopped = 0
    private var workId: String? = null

    @Before fun createAnInitiallyInvisiblePreview() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        compose.runOnUiThread {
            assertFalse("A preceding test must finish its export", ExportWorkMonitor.isRunning)
            owner = PreviewOwner().apply { registry.currentState = Lifecycle.State.CREATED }
            player = PlaybackResources()
            val timeline = EditTimeline(listOf(VideoClip(1L, "content://test/preview", 10_000L)))
            preview = EditorPreviewCoordinator(player, { timeline }, { position },
                { position = it }, initiallyActive = false)
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                rememberPreviewActive(preview, onStopped = { stopped++ }, onError = { failures++ })
            }
        }
    }

    @After fun releaseTheTestLeaseAndPreview() {
        compose.runOnUiThread {
            workId?.let(ExportWorkMonitor::finish)
            owner.registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    @Test fun stoppingTheOwnerReleasesMediaAndReturningRestoresAPausedPosition() {
        compose.runOnIdle {
            assertFalse(player.resourcesAllocated)
            owner.registry.currentState = Lifecycle.State.STARTED
            assertTrue(player.resourcesAllocated)
            player.positionMs = 2_345L
            player.playing = true

            owner.registry.currentState = Lifecycle.State.CREATED
            assertFalse(player.resourcesAllocated)
            assertFalse(player.playing)
            assertEquals(2_345L, position)
            assertEquals(1, stopped)

            owner.registry.currentState = Lifecycle.State.STARTED
            assertTrue(player.resourcesAllocated)
            assertEquals(2_345L, player.positionMs)
            assertFalse("Visibility cannot automatically resume playback", player.playing)
        }
    }

    @Test fun aLiveExportPreventsReacquisitionDuringImmediateStopAndStartCallbacks() {
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.STARTED
            player.positionMs = 3_456L
            workId = ExportWorkMonitor.begin(compose.activity, "Preview resource regression", {})
            // These callbacks happen before Compose can replace an observer that
            // was installed while isRunning=false. It must read the live lease.
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.STARTED
            assertFalse(player.resourcesAllocated)
            assertEquals(3_456L, position)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse("Foreground return during export cannot prepare a preview", player.resourcesAllocated)
            ExportWorkMonitor.finish(checkNotNull(workId))
            workId = null
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("A visible preview may prepare after the export lease ends", player.resourcesAllocated)
            assertEquals(3_456L, player.positionMs)
            assertFalse(player.playing)
        }
    }

    @Test fun preparationFailureFromOnStartIsReportedWithoutCrashingLifecycleDispatch() {
        compose.runOnIdle {
            player.failPreparation = true
            owner.registry.currentState = Lifecycle.State.STARTED
            assertEquals(1, failures)
            assertFalse(player.resourcesAllocated)
            player.positionMs = 9_999L
            preview.synchronizePosition()
            assertEquals(500L, position)
        }
    }

    private class PreviewOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private class PlaybackResources : EditorPreviewPlayback {
        override var positionMs = 0L
        override var playing = false
        override val ended = false
        var resourcesAllocated = false
        var failPreparation = false
        override fun pause() { playing = false }
        override fun play() { playing = true }
        override fun stop() { resourcesAllocated = false; playing = false }
        override fun seekTo(positionMs: Long) { this.positionMs = positionMs }
        override fun replace(timeline: EditTimeline, positionMs: Long) {
            check(!failPreparation) { "Test decoder initialization failure" }
            resourcesAllocated = true
            this.positionMs = positionMs
        }
    }
}
