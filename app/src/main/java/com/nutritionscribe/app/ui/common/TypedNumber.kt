package com.nutritionscribe.app.ui.common

/**
 * The text of a number box. While it has focus the text is the person's own, and
 * a value changing underneath never rewrites it: clearing a box to retype it put
 * the old number straight back, and "25" typed after it read "1325". Without
 * focus, the box shows its value.
 */
data class TypedNumber(val text: String, val focused: Boolean = false) {

    /** The number typed so far, or null while the box is empty or not a number. */
    val typed: Double? get() = parse(text)

    fun type(t: String) = copy(text = t)

    fun focus(on: Boolean) = copy(focused = on)

    /** What the box shows: their text while they type, the value otherwise. */
    fun shown(value: Double?, format: (Double) -> String): String = if (focused) text else value?.let(format).orEmpty()

    companion object {
        fun parse(t: String): Double? = t.trim().replace(',', '.').takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    }
}
