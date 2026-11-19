package com.nutritionscribe.shared.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Owner, 11-04, on History's chart: "this line isn't actually correct on days I
 * don't need plasma, or if I were to change my goals." Every past day was judged
 * by today's target. Targets are now looked up per day.
 */
class GoalTimelineTest {
    private val p = "protein"

    @Test
    fun `with no changes every day has the current target`() {
        assertEquals(160.0, GoalTimeline.targetOn(p, 100, 160.0, emptyList())!!, 0.0)
    }

    @Test
    fun `days before a change keep the old target, days after get the new one`() {
        val changes = listOf(GoalTimeline.Change(p, 200, 140.0, 160.0))
        assertEquals(140.0, GoalTimeline.targetOn(p, 150, 160.0, changes)!!, 0.0)
        assertEquals(140.0, GoalTimeline.targetOn(p, 199, 160.0, changes)!!, 0.0)
        // The day it changed counts with the new target.
        assertEquals(160.0, GoalTimeline.targetOn(p, 200, 160.0, changes)!!, 0.0)
        assertEquals(160.0, GoalTimeline.targetOn(p, 250, 160.0, changes)!!, 0.0)
    }

    @Test
    fun `several changes each cover their own stretch`() {
        val changes = listOf(
            GoalTimeline.Change(p, 100, 120.0, 140.0),
            GoalTimeline.Change(p, 200, 140.0, 160.0),
        )
        assertEquals(120.0, GoalTimeline.targetOn(p, 50, 160.0, changes)!!, 0.0)
        assertEquals(140.0, GoalTimeline.targetOn(p, 150, 160.0, changes)!!, 0.0)
        assertEquals(160.0, GoalTimeline.targetOn(p, 300, 160.0, changes)!!, 0.0)
    }

    @Test
    fun `two changes on one day, that day ends on the last one`() {
        val changes = listOf(
            GoalTimeline.Change(p, 200, 140.0, 150.0),
            GoalTimeline.Change(p, 200, 150.0, 160.0),
        )
        assertEquals(140.0, GoalTimeline.targetOn(p, 199, 160.0, changes)!!, 0.0)
        assertEquals(160.0, GoalTimeline.targetOn(p, 200, 160.0, changes)!!, 0.0)
    }

    @Test
    fun `other metrics' changes don't move this one`() {
        val changes = listOf(GoalTimeline.Change("calories", 200, 2000.0, 2200.0))
        assertEquals(160.0, GoalTimeline.targetOn(p, 150, 160.0, changes)!!, 0.0)
    }

    @Test
    fun `a target that didn't exist before stays empty before`() {
        val changes = listOf(GoalTimeline.Change(p, 200, null, 160.0))
        assertNull(GoalTimeline.targetOn(p, 150, 160.0, changes))
    }

    @Test
    fun `saving the same number records nothing, and the list is capped`() {
        assertEquals(emptyList<GoalTimeline.Change>(), GoalTimeline.record(emptyList(), p, 1, 160.0, 160.0))
        var list = emptyList<GoalTimeline.Change>()
        repeat(GoalTimeline.MAX_KEPT + 50) { list = GoalTimeline.record(list, p, it.toLong(), it.toDouble(), it + 1.0) }
        // (one change a day, so none merge)
        assertEquals(GoalTimeline.MAX_KEPT, list.size)
        assertEquals((GoalTimeline.MAX_KEPT + 49).toLong(), list.last().day)
    }

    @Test
    fun `changes survive saving and loading, blanks included`() {
        val changes = listOf(GoalTimeline.Change(p, 200, null, 160.0), GoalTimeline.Change("sodium", 210, 2300.0, null))
        assertEquals(changes, GoalTimeline.decode(GoalTimeline.encode(changes)))
        assertEquals(emptyList<GoalTimeline.Change>(), GoalTimeline.decode(null))
        assertEquals(emptyList<GoalTimeline.Change>(), GoalTimeline.decode("garbage\nalso|bad"))
    }

    /**
     * On the emulator, typing 140 over 160 in Goals stored six changes (160 → 16 →
     * 1 → blank → 1 → 14 → 140): the field saves on each keystroke. One day's
     * changes are one change now.
     */
    @Test
    fun `typing a new target records one change for the day`() {
        var list = emptyList<GoalTimeline.Change>()
        for ((b, a) in listOf(160.0 to 16.0, 16.0 to 1.0, 1.0 to null, null to 1.0, 1.0 to 14.0, 14.0 to 140.0)) {
            list = GoalTimeline.record(list, p, 300, b, a)
        }
        assertEquals(listOf(GoalTimeline.Change(p, 300, 160.0, 140.0)), list)
    }

    @Test
    fun `typing back to where the day started leaves no change`() {
        var list = GoalTimeline.record(emptyList(), p, 300, 160.0, 16.0)
        list = GoalTimeline.record(list, p, 300, 16.0, 160.0)
        assertEquals(emptyList<GoalTimeline.Change>(), list)
    }

    @Test
    fun `a change on another day or metric is kept apart`() {
        var list = GoalTimeline.record(emptyList(), p, 300, 160.0, 140.0)
        list = GoalTimeline.record(list, "calories", 300, 2200.0, 2000.0)
        list = GoalTimeline.record(list, p, 301, 140.0, 150.0)
        assertEquals(3, list.size)
    }
}
