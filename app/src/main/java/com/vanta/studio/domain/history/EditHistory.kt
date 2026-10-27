package com.vanta.studio.domain.history

/**
 * Linear undo/redo history of immutable states. States are lightweight project
 * descriptions (no media data), so keeping a bounded list of them is cheap.
 */
class EditHistory<T>(initial: T, private val capacity: Int = 100) {
    var current: T = initial
        private set

    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoDepth: Int get() = undoStack.size

    /** Records [next] as a new step. Returns false when nothing changed. */
    fun push(next: T): Boolean {
        if (next == current) return false
        undoStack.addLast(current)
        while (undoStack.size > capacity) undoStack.removeFirst()
        redoStack.clear()
        current = next
        return true
    }

    /** Replaces the current state without creating a history step (live drags, renames). */
    fun replace(next: T) {
        current = next
    }

    fun undo(): T? {
        val prev = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        current = prev
        return prev
    }

    fun redo(): T? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        current = next
        return next
    }

    fun reset(state: T) {
        undoStack.clear()
        redoStack.clear()
        current = state
    }
}
