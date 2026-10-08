package com.hebi.quickcut.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {
    private val first = VideoClip(1, "content://first", 10_000)
    private val second = first.copy(id = 2)

    private fun checkpoint(vararg clips: VideoClip, selected: Long? = null) =
        EditCheckpoint(EditTimeline(clips.toList()), selected, 0)

    @Test
    fun undoAndRedoRestorePreviousTimelineAndSelection() {
        val history = EditHistory()
        val before = checkpoint(first, selected = 1)
        val after = checkpoint(first, second, selected = 2)
        history.record(before)

        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
        assertEquals(before, history.undo(after))
        assertTrue(history.canRedo)
        assertEquals(after, history.redo(before))
    }

    @Test
    fun editingAfterUndoClearsRedo() {
        val history = EditHistory()
        val before = checkpoint(first, selected = 1)
        val after = checkpoint(first, second)
        history.record(before)
        assertEquals(before, history.undo(after))

        history.record(before)
        assertFalse(history.canRedo)
    }

    @Test
    fun historyIsBoundedAndDropsOldest() {
        val history = EditHistory(maxEntries = 2)
        val a = checkpoint(first)
        val b = checkpoint(first.copy(endMs = 8_000))
        val c = checkpoint(first.copy(endMs = 6_000))
        val d = checkpoint(second)
        history.record(a)
        history.record(b)
        history.record(c)

        assertEquals(c, history.undo(d))
        assertEquals(b, history.undo(c))
        assertNull(history.undo(b))
    }

    @Test
    fun clearRemovesAllHistory() {
        val history = EditHistory()
        history.record(checkpoint(first))
        history.clear()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test
    fun duplicateSnapshotsAreIgnored() {
        val history = EditHistory()
        val before = checkpoint(first)
        history.record(before)
        history.record(before)
        val after = checkpoint(second)
        assertEquals(before, history.undo(after))
        assertNull(history.undo(before))
    }

    @Test
    fun invalidCapacityIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { EditHistory(0) }
    }
}
