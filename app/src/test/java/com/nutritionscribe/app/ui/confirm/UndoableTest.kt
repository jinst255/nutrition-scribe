package com.nutritionscribe.app.ui.confirm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review L3: the × on a check-card line removed the food for good, and Back in
 * the food editor threw away typed numbers without asking. Removing now offers
 * Undo; the editor asks first when something changed.
 */
class UndoableTest {
    @Test
    fun `undo puts a removed line back where it was`() {
        val lines = mutableListOf("oats", "milk", "banana")
        val undo = Undoable.remove(lines, 1)!!
        assertEquals(listOf("oats", "banana"), lines)
        undo()
        assertEquals(listOf("oats", "milk", "banana"), lines)
    }

    @Test
    fun `undo twice adds the line only once`() {
        val lines = mutableListOf("oats", "milk")
        val undo = Undoable.remove(lines, 0)!!
        undo(); undo()
        assertEquals(listOf("oats", "milk"), lines)
    }

    @Test
    fun `undo after another removal still brings the line back`() {
        val lines = mutableListOf("a", "b", "c")
        val undoC = Undoable.remove(lines, 2)!!
        Undoable.remove(lines, 0)
        undoC()
        assertEquals(listOf("b", "c"), lines)
    }

    @Test
    fun `removing a line that isn't there offers nothing`() {
        assertNull(Undoable.remove(mutableListOf("a"), 3))
        assertNull(Undoable.remove(mutableListOf<String>(), 0))
    }

    @Test
    fun `the editor asks only when something changed`() {
        data class D(val name: String, val grams: Double)
        val start = D("oats", 40.0)
        assertFalse(Undoable.editorChanged(start, start.copy()))
        assertTrue(Undoable.editorChanged(start, start.copy(grams = 80.0)))
    }
}
