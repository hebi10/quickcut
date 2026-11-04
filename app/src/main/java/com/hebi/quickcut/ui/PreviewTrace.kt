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

@OptIn(UnstableApi::class)
internal object PreviewTrace {
    private fun bounds(view: View): String {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return "${location[0]},${location[1]},${view.width},${view.height}"
    }

    fun event(context: Context, player: Player, name: String, details: String = "") {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        Log.i("QuickCutPreviewTrace", "elapsed_ns=${SystemClock.elapsedRealtimeNanos()} mono_ns=${System.nanoTime()} " +
            "event=$name player=${System.identityHashCode(player)} $details")
    }

    fun bind(view: PlayerView, player: Player) {
        if (view.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
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
}
