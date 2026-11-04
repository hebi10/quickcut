package com.hebi.quickcut.ui

import org.junit.Assert.*
import org.junit.Test

class PreviewOutputStateTest {
    @Test fun outputStartsUnavailableAndOnlyTheCurrentBindingCanBecomeReady() {
        val state = PreviewOutputState<Any>()
        val view = Any()
        val other = Any()
        assertFalse(state.ready)
        assertNull(state.current)
        state.bind(view)
        val generation = checkNotNull(state.generation(view))
        assertNull(state.generation(other))
        assertFalse(state.markReady(other, generation))
        assertFalse(state.ready)
        assertTrue(state.markReady(view, generation))
        assertSame(view, state.current)
        assertTrue(state.ready)
        assertFalse(state.markReady(view, generation))
    }

    @Test fun aDestroyedSurfaceRejectsItsQueuedReadyCallbackUntilTheNewGenerationIsReady() {
        val state = PreviewOutputState<Any>()
        val view = Any()
        state.bind(view)
        val old = checkNotNull(state.generation(view))
        state.markReady(view, old)
        assertTrue(state.invalidate(view))
        assertFalse(state.ready)
        assertFalse(state.markReady(view, old))
        val replacement = checkNotNull(state.generation(view))
        assertNotEquals(old, replacement)
        assertTrue(state.markReady(view, replacement))
    }

    @Test fun aReplacedViewCannotRestoreReadinessOrInvalidateItsReplacement() {
        val state = PreviewOutputState<Any>()
        val oldView = Any()
        val newView = Any()
        state.bind(oldView)
        val old = checkNotNull(state.generation(oldView))
        state.bind(newView)
        val current = checkNotNull(state.generation(newView))
        assertFalse(state.markReady(oldView, old))
        assertFalse(state.invalidate(oldView))
        assertFalse(state.release(oldView))
        assertSame(newView, state.current)
        assertTrue(state.markReady(newView, current))
        assertTrue(state.ready)
    }

    @Test fun releaseAndDisposalRejectAllPreviouslyPostedCallbacks() {
        val state = PreviewOutputState<Any>()
        val view = Any()
        state.bind(view)
        val generation = checkNotNull(state.generation(view))
        assertTrue(state.release(view))
        assertNull(state.current)
        assertFalse(state.markReady(view, generation))
        state.bind(view)
        val rebound = checkNotNull(state.generation(view))
        state.dispose()
        state.bind(view)
        assertNull(state.current)
        assertNull(state.generation(view))
        assertFalse(state.markReady(view, rebound))
        assertFalse(state.ready)
    }
}
