package com.nutritionscribe.app.ui.confirm

/**
 * Taking a line off the check card used to be final (review L3): one slip of
 * the × and the food was gone, with no way back but saying it again. Now the
 * removal hands back a way to put the line back where it was.
 */
object Undoable {
    /** Removes [i] and returns what puts it back (at the same place, or the end if the list got shorter). */
    fun <T> remove(list: MutableList<T>, i: Int): (() -> Unit)? {
        if (i !in list.indices) return null
        val item = list.removeAt(i)
        var done = false
        return {
            if (!done) {
                done = true
                list.add(i.coerceAtMost(list.size), item)
            }
        }
    }

    /** The food editor asks before throwing away edits; an untouched editor just closes. */
    fun <T> editorChanged(start: T, now: T): Boolean = start != now
}
