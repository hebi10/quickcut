package com.hebi.quickcut.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException

@OptIn(UnstableApi::class)
internal object PreviewTrace {
    fun enabled(context: Context) = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun bounds(view: View): String {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return "${location[0]},${location[1]},${view.width},${view.height}"
    }

    fun event(context: Context, player: Player, name: String, details: String = "") {
        if (!enabled(context)) return
        Log.i("QuickCutPreviewTrace", "elapsed_ns=${SystemClock.elapsedRealtimeNanos()} mono_ns=${System.nanoTime()} " +
            "event=$name player=${System.identityHashCode(player)} $details")
    }

    fun bind(view: PlayerView, player: Player, surfacePrepare: FirstSurfacePrepare? = null) {
        if (!enabled(view.context)) return
        surfacePrepare?.bind(view)
        val identity = "view=${System.identityHashCode(view)}"
        event(view.context, player, "view.factory", "$identity bounds=${bounds(view)}")
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = event(v.context, player, "view.attach", identity)
            override fun onViewDetachedFromWindow(v: View) = event(v.context, player, "view.detach", identity)
        })
        val output = view.videoSurfaceView
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            event(view.context, player, "view.layout", "$identity bounds=${bounds(view)} " +
                "output_bounds=${output?.let(::bounds)} video=${player.videoSize.width}x${player.videoSize.height}")
        }
        if (output is SurfaceView) {
            fun trace(name: String, holder: SurfaceHolder) = event(view.context, player, name,
                "$identity output=${System.identityHashCode(output)} " +
                    "surface=${System.identityHashCode(holder.surface)} descriptor=${holder.surface} frame=${holder.surfaceFrame} " +
                    "valid=${holder.surface.isValid} view_bounds=${bounds(view)} output_bounds=${bounds(output)}")
            trace("surface.initial", output.holder)
            output.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = trace("surface.created", holder)
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                    trace("surface.changed.$width.$height", holder)
                override fun surfaceDestroyed(holder: SurfaceHolder) = trace("surface.destroyed", holder)
            })
        }
    }

    class FirstSurfacePrepare(
        private val context: Context,
        private val player: Player,
        private val canPrepare: () -> Boolean,
        private val prepare: () -> Unit,
    ) {
        private var generation = 0L
        private var pending = 0L
        private var firstPrepared = false
        private var disposed = false
        private var view: PlayerView? = null
        private var stillRelevant: (() -> Boolean)? = null
        lateinit var onFailure: (Exception) -> Unit

        fun request(relevant: () -> Boolean) {
            if (disposed) return
            generation += 1L
            if (firstPrepared) {
                prepare()
                return
            }
            pending = generation
            stillRelevant = relevant
            event(context, player, "prepare.deferred", "media_generation=$generation")
            schedule()
        }

        fun bind(outputView: PlayerView) {
            val surface = outputView.videoSurfaceView
            check(surface is SurfaceView)
            view = outputView
            outputView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = schedule()
                override fun onViewDetachedFromWindow(v: View) = Unit
            })
            surface.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = schedule()
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = schedule()
                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            })
            schedule()
        }

        private fun schedule() {
            val outputView = view ?: return
            val mediaGeneration = pending
            if (disposed || firstPrepared || mediaGeneration == 0L) return
            val mediaItem = player.currentMediaItem
            val relevant = stillRelevant ?: return
            outputView.post {
                val surface = outputView.videoSurfaceView as? SurfaceView ?: return@post
                if (disposed || firstPrepared || pending != mediaGeneration || generation != mediaGeneration ||
                    view !== outputView || player.currentMediaItem !== mediaItem || outputView.player !== player ||
                    !surface.isAttachedToWindow || !outputView.isAttachedToWindow || !surface.holder.surface.isValid || !canPrepare() || !relevant()) {
                    return@post
                }
                pending = 0L
                firstPrepared = true
                event(context, player, "prepare.surface_ready", "media_generation=$mediaGeneration " +
                    "view=${System.identityHashCode(outputView)} surface=${System.identityHashCode(surface.holder.surface)} " +
                    "attached=true surface_valid=true lifecycle_ready=true")
                try {
                    prepare()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    onFailure(error)
                }
            }
        }

        fun dispose() {
            disposed = true
            generation += 1L
            pending = 0L
            stillRelevant = null
            view = null
            event(context, player, "prepare.deferred_dispose", "media_generation=$generation")
        }
    }
}
