package com.vanta.studio

import com.vanta.studio.domain.history.EditHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {
    @Test
    fun undoRedoWalkTheStates() {
        val h = EditHistory(0)
        h.push(1); h.push(2); h.push(3)
        assertEquals(2, h.undo())
        assertEquals(1, h.undo())
        assertEquals(2, h.redo())
        assertEquals(2, h.current)
    }

    @Test
    fun newEditClearsRedo() {
        val h = EditHistory(0)
        h.push(1); h.push(2)
        h.undo()
        assertTrue(h.canRedo)
        h.push(9)
        assertFalse(h.canRedo)
        assertEquals(9, h.current)
    }

    @Test
    fun identicalStateIsNotAStep() {
        val h = EditHistory("a")
        assertFalse(h.push("a"))
        assertFalse(h.canUndo)
    }

    @Test
    fun replaceDoesNotCreateAStep() {
        val h = EditHistory(0)
        h.replace(5)
        assertEquals(5, h.current)
        assertFalse(h.canUndo)
    }

    @Test
    fun emptyUndoRedoReturnNull() {
        val h = EditHistory(0)
        assertNull(h.undo())
        assertNull(h.redo())
    }

    @Test
    fun capacityIsBounded() {
        val h = EditHistory(0, capacity = 3)
        for (i in 1..10) h.push(i)
        assertEquals(3, h.undoDepth)
        h.undo(); h.undo(); h.undo()
        assertEquals(7, h.current)
        assertNull(h.undo())
    }

    @Test
    fun resetClearsEverything() {
        val h = EditHistory(0)
        h.push(1)
        h.reset(42)
        assertEquals(42, h.current)
        assertFalse(h.canUndo || h.canRedo)
    }
}
