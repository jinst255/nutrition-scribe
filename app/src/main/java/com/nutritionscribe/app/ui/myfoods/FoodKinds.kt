package com.nutritionscribe.app.ui.myfoods

import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.resolve.Origin
import com.nutritionscribe.shared.resolve.PersonalFood

/**
 * My foods, round 2 (approved 11-20): its filter chips (All, Foods, Labels, Meals)
 * and "See when you ate it". Kept apart from the screen so it's tested on the JVM.
 */
object FoodKinds {
    enum class Kind(val label: String) { FOOD("Foods"), LABEL("Labels"), MEAL("Meals") }

    /** A recipe is in Meals with the meals: both are several foods logged as one. */
    fun of(f: PersonalFood): Kind = when {
        f.isMeal || f.isRecipe -> Kind.MEAL
        f.candidate?.origin == Origin.LABEL || f.candidate?.id?.startsWith("kindguess:") == true -> Kind.LABEL
        else -> Kind.FOOD
    }

    fun counts(foods: List<PersonalFood>): Map<Kind, Int> = Kind.entries.associateWith { k -> foods.count { of(it) == k } }

    /** The entries this food was logged in, newest first: by its key, or as the whole entry's name. */
    fun eaten(f: PersonalFood, entries: List<Entry>): List<Entry> =
        entries.filter { e -> e.items.any { it.key == f.key } || (e.items.isEmpty() && e.name.equals(f.name, ignoreCase = true)) }
            .sortedByDescending { it.timestampEpochMillis }
}
