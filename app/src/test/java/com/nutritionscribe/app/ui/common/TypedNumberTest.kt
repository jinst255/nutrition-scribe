package com.nutritionscribe.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TypedNumberTest {

    private fun fmt(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    /**
     * The owner (2026-11-06): "If you delete both the numbers to enter in your number
     * it like restores the original number and then you end up typing like twice
     * the amount of digits you want." Clearing the box cleared their override, the
     * computed 13 came back as the value, and the box refilled with it; typing 25
     * then read 1325. While the box has focus, its text is theirs.
     */
    @Test
    fun `clearing a box while typing leaves it empty, not the old number`() {
        var box = TypedNumber(fmt(13.0))
        box = box.focus(true)
        box = box.type("1")
        box = box.type("")
        assertNull("nothing set while it is empty", box.typed)
        // The value went back to the computed 13 underneath: the box stays empty.
        assertEquals("", box.shown(13.0, ::fmt))
        box = box.type("2").type("25")
        assertEquals(25.0, box.typed!!, 0.0)
        assertEquals("25", box.shown(25.0, ::fmt))
    }

    @Test
    fun `a box follows its value when nobody is typing in it`() {
        val box = TypedNumber(fmt(13.0))
        assertEquals("26", box.shown(26.0, ::fmt))
        assertEquals("", box.shown(null, ::fmt))
        // Half-typed numbers parse as far as they go; a comma is a decimal point.
        assertEquals(1.0, TypedNumber.parse("1.")!!, 0.0)
        assertEquals(1.5, TypedNumber.parse("1,5")!!, 0.0)
        assertNull(TypedNumber.parse(" "))
    }
}
