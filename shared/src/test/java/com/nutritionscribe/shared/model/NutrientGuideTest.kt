package com.nutritionscribe.shared.model

import com.nutritionscribe.shared.resolve.TestDbs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NutrientGuideTest {
    private val per100 = { id: Long -> TestDbs.usda.byId(id)?.per100g }

    @Test fun `every nutrient has a page with three points and a source`() {
        for (m in Metric.entries) {
            val g = NutrientGuide.of(m)
            assertEquals(m.name, 3, g.points.size)
            assertTrue(m.name, g.source.isNotBlank() && g.headline.endsWith("."))
            // A limit lists swaps; a nutrient to reach lists foods instead.
            if (g.isLimit) assertTrue(m.name, g.swaps.isNotEmpty())
        }
        // A part gets its whole's page: added sugar is the sugar page.
        assertEquals(Metric.SUGAR, NutrientGuide.of(Metric.ADDED_SUGAR).metric)
    }

    @Test fun `the question names the nutrient the way people say it`() {
        assertEquals("What does fiber do for me?", NutrientGuide.question(Metric.FIBER))
        assertEquals("What does vitamin C do for me?", NutrientGuide.question(Metric.VITAMIN_C))
        assertEquals("What does omega-3 do for me?", NutrientGuide.question(Metric.OMEGA3))
        assertEquals("Why keep sodium low?", NutrientGuide.question(Metric.SODIUM))
        assertEquals("Why keep added sugar low?", NutrientGuide.question(Metric.SUGAR))
    }

    @Test fun `progress to reach says what is left, and when it is covered`() {
        assertEquals("Aim for about 38 g a day. Today you have 18 g, about 20 g to go.", NutrientGuide.progress(Metric.FIBER, 18.0, 38.0))
        assertEquals("Aim for about 38 g a day. Today you have 40 g, which covers it.", NutrientGuide.progress(Metric.FIBER, 40.0, 38.0))
        assertEquals("Aim for about 38 g a day. On Mon, Oct 12 you had 18 g, about 20 g to go.", NutrientGuide.progress(Metric.FIBER, 18.0, 38.0, "On Mon, Oct 12"))
    }

    @Test fun `progress under a limit is room, and over it is said kindly`() {
        assertEquals("The limit is about 2,300 mg a day. Today you're at 1,200 mg, with about 1,100 mg of room.",
            NutrientGuide.progress(Metric.SODIUM, 1200.0, 2300.0))
        val over = NutrientGuide.progress(Metric.SODIUM, 2800.0, 2300.0)
        assertTrue(over, over.contains("about 500 mg over") && over.contains("One day over is fine"))
        assertTrue(NutrientGuide.progress(Metric.SUGAR, 20.0, 50.0).startsWith("The added sugar limit"))
    }

    @Test fun `foods rich in fiber come from the USDA data, most first`() {
        val foods = NutrientGuide.richIn(Metric.FIBER, per100)
        assertEquals(4, foods.size)
        assertTrue(foods.zipWithNext().all { (a, b) -> a.second >= b.second })
        // Beans and lentils are the fiber foods; bananas and eggs are not.
        assertTrue(foods.map { it.first.name }.toString(), foods.any { it.first.name in setOf("Lentils", "Black beans", "Kidney beans") })
        assertFalse(foods.any { it.first.name == "Eggs" })
        assertTrue(foods.all { it.second in 3.0..20.0 })
    }

    @Test fun `a limit and calories list no foods`() {
        assertTrue(NutrientGuide.richIn(Metric.SODIUM, per100).isEmpty())
        assertTrue(NutrientGuide.richIn(Metric.CALORIES, per100).isEmpty())
    }

    @Test fun `vitamin C lists fruit and peppers`() {
        val names = NutrientGuide.richIn(Metric.VITAMIN_C, per100).map { it.first.name }
        assertTrue(names.toString(), names.any { it in setOf("Kiwi", "Orange", "Strawberries", "Red bell pepper", "Broccoli") })
    }
}
