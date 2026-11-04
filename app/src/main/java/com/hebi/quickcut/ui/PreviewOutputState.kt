package com.hebi.quickcut.ui

/** Invalidates posted readiness notifications when a view or its surface is replaced. */
internal class PreviewOutputState<T : Any> {
    var current: T? = null
        private set
    var ready = false
        private set
    private var generation = 0L
    private var disposed = false

    fun bind(view: T) {
        if (disposed) return
        current = view
        generation += 1L
        ready = false
    }

    fun generation(view: T): Long? =
        if (!disposed && current === view) generation else null

    fun markReady(view: T, expectedGeneration: Long): Boolean {
        if (generation(view) != expectedGeneration || ready) return false
        ready = true
        return true
    }

    fun invalidate(view: T): Boolean {
        if (generation(view) == null) return false
        generation += 1L
        ready = false
        return true
    }

    fun release(view: T): Boolean {
        if (!invalidate(view)) return false
        current = null
        return true
    }

    fun dispose() {
        disposed = true
        generation += 1L
        current = null
        ready = false
    }
}
