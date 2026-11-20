package com.nutritionscribe.shared.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The watch-face shortcuts (owner, 11-20: "more useful shortcuts … other than just
 * letting them know that they are missing some protein"; "give the choice … to the
 * user"). Each is picked on the watch from the last state and the hour now, so a
 * stale state still gives the right meal at the right time of day.
 */
class WatchShortcutsTest {
    private val lunch = WatchState.Meal(listOf("a", "b"), "Your usual lunch", listOf("Veggie bag", "Burrito"), 460, hour = 12.2)
    private val breakfast = WatchState.Meal(listOf("c"), "Your usual breakfast", listOf("Oats"), 300, hour = 7.5)
    private val coffee = WatchState.Again("k", "Coffee", "1 cup · 5 kcal", 5, hour = 8.0)
    private val shake = WatchState.Again("s", "Protein shake", "1 bottle · 160 kcal", 160, hour = 16.0)
    private val need = WatchState.Need("Fiber", 9.0, 30.0, "g", foods = listOf(WatchState.Fill("o", "Oatmeal", 4.0, 150)))
    private val s = WatchState(need = need, again = listOf(shake, coffee), meals = listOf(lunch, breakfast), lastAt = 1_000, toCheck = 2)

    @Test
    fun `need most plus say shows what is left, and just Say once every goal is met`() {
        assertEquals("21 g" to "Fiber", WatchShortcuts.needSay(s))
        assertNull(WatchShortcuts.needSay(s.copy(need = need.copy(value = 30.0))))
        assertNull(WatchShortcuts.needSay(WatchState()))
    }

    @Test
    fun `the usual meal for this time of day, and none between meals`() {
        assertEquals(lunch, WatchShortcuts.mealNow(s, 13.0))
        assertEquals(breakfast, WatchShortcuts.mealNow(s, 6.0))
        assertNull(WatchShortcuts.mealNow(s, 17.0))
        // An older phone sends meals with no hour: never guessed as "now".
        assertNull(WatchShortcuts.mealNow(s.copy(meals = listOf(lunch.copy(hour = null))), 12.0))
    }

    @Test
    fun `again is what is usually logged around now, else the top of Log again`() {
        assertEquals(coffee, WatchShortcuts.againNow(s, 8.5))
        assertEquals(shake, WatchShortcuts.againNow(s, 16.5))
        assertEquals(shake, WatchShortcuts.againNow(s, 12.0))
        assertNull(WatchShortcuts.againNow(WatchState(), 12.0))
    }

    @Test
    fun `hours wrap round midnight`() {
        assertEquals(2.0, WatchShortcuts.hoursApart(23.0, 1.0), 1e-9)
        val late = WatchState(meals = listOf(lunch.copy(label = "Your usual late snack", hour = 23.5)))
        assertEquals("Your usual late snack", WatchShortcuts.mealNow(late, 0.5)?.label)
    }

    @Test
    fun `close the gap offers their food until the goal is met`() {
        assertEquals("Oatmeal", WatchShortcuts.gap(s)?.name)
        assertEquals("+4 g", WatchShortcuts.gives(WatchShortcuts.gap(s)!!, "g"))
        assertNull(WatchShortcuts.gap(s.copy(need = need.copy(value = 31.0))))
        assertNull(WatchShortcuts.gap(s.copy(need = need.copy(foods = emptyList()))))
    }

    @Test
    fun `since you logged, and the phone queue only when something waits`() {
        val h = 3_600_000L
        assertEquals("25 m", WatchShortcuts.since(0, 25 * 60_000L))
        assertEquals("4 h", WatchShortcuts.since(0, 4 * h + 59 * 60_000L))
        assertEquals("3 d", WatchShortcuts.since(0, 72 * h))
        assertEquals("0 m", WatchShortcuts.since(10_000, 0))   // a clock set back
        assertNull(WatchShortcuts.since(null, 5))
        assertEquals(2, WatchShortcuts.toCheck(s))
        assertNull(WatchShortcuts.toCheck(s.copy(toCheck = 0)))
    }
}
