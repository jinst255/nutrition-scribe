package com.nutritionscribe.app.ui.myfoods

import com.nutritionscribe.shared.model.Confidence
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.model.LoggedItem
import com.nutritionscribe.shared.resolve.FoodCandidate
import com.nutritionscribe.shared.resolve.MealPart
import com.nutritionscribe.shared.resolve.Origin
import com.nutritionscribe.shared.resolve.PersonalFood
import org.junit.Assert.assertEquals
import org.junit.Test

/** My foods round 2: the filter chips' counts, and "See when you ate it". */
class FoodKindsTest {
    private fun food(key: String, origin: Origin? = null, parts: Int = 0) = PersonalFood(
        key = key, phrase = key, name = key, nutrients = mapOf("calories" to 100.0),
        candidate = origin?.let { FoodCandidate(id = "$key-c", name = key, origin = it) },
        parts = List(parts) { MealPart("p$it", "p$it", 10.0, emptyMap()) },
    )

    @Test
    fun `labels, meals and plain foods each have their chip`() {
        val foods = listOf(food("oats", Origin.USDA), food("yogurt", Origin.LABEL), food("bowl", parts = 2), food("toast"))
        assertEquals(mapOf(FoodKinds.Kind.FOOD to 2, FoodKinds.Kind.LABEL to 1, FoodKinds.Kind.MEAL to 1), FoodKinds.counts(foods))
    }

    @Test
    fun `when you ate it finds the food inside an entry, newest first`() {
        fun e(id: String, t: Long, keys: List<String>, name: String = "x") = Entry(
            id = id, timestampEpochMillis = t, rawText = name, name = name, source = "t", confidence = Confidence.STATED,
            items = keys.map { LoggedItem(name = it, said = it, key = it, origin = "USDA") },
        )
        val log = listOf(e("a", 1, listOf("oats", "banana")), e("b", 3, listOf("oats")), e("c", 2, listOf("toast")),
            e("d", 4, emptyList(), name = "Oats"))
        assertEquals(listOf("d", "b", "a"), FoodKinds.eaten(food("oats").copy(name = "Oats"), log).map { it.id })
    }
}
