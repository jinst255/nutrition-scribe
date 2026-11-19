package com.nutritionscribe.app.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner, 11-15: "there is no no option given" on "Did you have the greek yogurt
 * with banana?". The card offered Yes, Part of it and Something else only.
 */
class PlanAnswersTest {
    @Test
    fun `did you have it offers a plain no`() {
        assertTrue(PlanAnswers.ALL.any { it.label == "No" })
        assertEquals(listOf("Yes, log it", "Part of it", "No", "Something else"), PlanAnswers.ALL.map { it.label })
    }

    @Test
    fun `the card draws every answer`() {
        // The card loops over ALL; a hand-written button list would miss a new one again.
        val src = java.io.File("src/main/java/com/nutritionscribe/app/ui/home/HomeScreen.kt").readText()
        assertTrue(src.contains("for (a in PlanAnswers.ALL)"))
        assertTrue(src.contains("PlanAnswers.Answer.NO -> "))
    }
}
