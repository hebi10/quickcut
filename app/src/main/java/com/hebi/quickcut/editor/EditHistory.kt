package com.hebi.quickcut.editor

data class EditCheckpoint(
    val timeline: EditTimeline,
    val selectedId: Long?,
    val playPositionMs: Long,
)

/**
 * A bounded, in-memory edit history. An editing gesture should produce one entry.
 */
class EditHistory(private val maxEntries: Int = 40) {
    init {
        require(maxEntries > 0)
    }

    private val past = ArrayDeque<EditCheckpoint>()
    private val future = ArrayDeque<EditCheckpoint>()

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    fun record(checkpoint: EditCheckpoint) {
        if (past.lastOrNull() == checkpoint) return
        past.addLast(checkpoint)
        while (past.size > maxEntries) past.removeFirst()
        future.clear()
    }

    fun undo(current: EditCheckpoint): EditCheckpoint? {
        if (past.isEmpty()) return null
        val previous = past.removeLast()
        future.addLast(current)
        while (future.size > maxEntries) future.removeFirst()
        return previous
    }

    fun redo(current: EditCheckpoint): EditCheckpoint? {
        if (future.isEmpty()) return null
        val next = future.removeLast()
        past.addLast(current)
        while (past.size > maxEntries) past.removeFirst()
        return next
    }

    fun clear() {
        past.clear()
        future.clear()
    }
}
