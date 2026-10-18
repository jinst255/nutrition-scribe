package com.nutritionscribe.shared.resolve

import com.nutritionscribe.shared.model.Liquids
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The units a line can be counted in, how far one tap of − / + moves it, and how
 * to switch units without the amount jumping.
 *
 * A unit of null means "one of it": a slice, a bar, a whole avocado, whatever the
 * food's own piece is (see [Portions.gramsForOne]).
 */
object Units {

    data class Option(val unit: String?, val label: String)

    private val COUNT_UNITS = setOf(null, "slice", "piece", "stalk", "scoop", "strip", "link", "can", "bottle",
        "packet", "container", "bag", "cob", "pat", "square", "sheet", "fillet", "stick", "bowl", "plate", "glass", "handful")

    /** How a unit reads on a stepper: "fl oz", "mL", "tbsp". */
    fun label(unit: String?, piece: String? = null): String = when (unit) {
        null -> piece ?: "whole"
        "ml" -> "mL"
        "floz" -> "fl oz"
        "l" -> "L"
        else -> unit
    }

    /** The food's own word for one of it, from its portions: "slice", "bar", "medium". */
    fun pieceOf(c: FoodCandidate?): String? = c?.portions?.firstOrNull { p ->
        p.label != "1 serving" && Regex("^1 [a-z]").containsMatchIn(p.label) &&
            Regex("^1 (?:cup|tbsp|tsp|oz|fl|ml|g)\\b").find(p.label) == null
    }?.label?.removePrefix("1 ")?.substringBefore(",")?.substringBefore(" (")?.trim()?.takeIf { it.length in 2..20 }

    /** The units worth offering for this line, the current one first. */
    fun optionsFor(c: FoodCandidate?, item: ItemParser.Item): List<Option> {
        val name = c?.name ?: item.food
        val liquid = Liquids.isLiquid(name, c?.labelServingText) || Liquids.isLiquid(item.food)
        val piece = pieceOf(c)
        val out = mutableListOf<Option>()
        fun add(u: String?) { if (out.none { it.unit == u }) out += Option(u, label(u, piece)) }
        add(item.unit)
        if (c?.labelServingGrams != null || c?.portions?.any { it.label == "1 serving" } == true) add("serving")
        if (piece != null || item.unit == null) add(null)
        if (liquid) { add("ml"); add("floz"); add("cup") } else {
            val spoonable = c?.portions?.any { Regex("\\b(?:cup|tbsp|tablespoon|tsp)\\b").containsMatchIn(it.label.lowercase()) } == true
            add("g"); add("oz")
            if (spoonable) { add("cup"); add("tbsp") }
        }
        return out
    }

    /** One tap of − or + from [value] in [unit]. */
    fun step(unit: String?, value: Double): Double = when (unit) {
        "g" -> when { value < 20 -> 1.0; value < 100 -> 5.0; value < 500 -> 10.0; else -> 25.0 }
        "ml" -> when { value < 100 -> 10.0; value < 500 -> 25.0; else -> 50.0 }
        "oz", "floz" -> if (value < 2) 0.5 else 1.0
        "lb" -> 0.25
        "l" -> 0.25
        "cup" -> 0.25
        "tbsp", "tsp" -> 0.5
        "serving" -> if (value < 1) 0.25 else 0.5
        else -> if (value < 2) 0.5 else 1.0
    }

    /** [value] moved one tap in [dir] (−1 or +1), on the unit's grid, never below the smallest step. */
    fun bump(unit: String?, value: Double, dir: Int): Double {
        // Going down uses the step just below: 2 slices goes to 1.5, then 1.
        val s = if (dir > 0) step(unit, value) else step(unit, (value - 1e-6).coerceAtLeast(0.0))
        return snap(value + dir * s, s).coerceAtLeast(step(unit, 0.0))
    }

    /** [value] on the grid of [step]: 0.33 cup becomes 0.25 or 0.5, not 0.58. */
    fun snap(value: Double, step: Double): Double = (value / step).roundToInt() * step

    /**
     * The amount in [newUnit] that weighs what the line weighs now, rounded to that
     * unit's grid. Null when the food's weight in the new unit is not known.
     */
    fun convert(c: FoodCandidate, item: ItemParser.Item, grams: Double, newUnit: String?): Double? {
        val one = Portions.gramsForOne(c, item.copy(unit = newUnit, amount = 1.0, scale = 1.0))?.grams ?: return null
        if (one <= 0) return null
        val raw = grams / one
        // Finer than a stepper tap, so a cup of milk is 235 mL, not 225.
        val s = when (newUnit) { "ml" -> 5.0; "g" -> 1.0; "oz", "floz" -> 0.5; else -> step(newUnit, raw) }
        return snap(raw, s).takeIf { it > 0 } ?: s
    }

    /** "1.5", "0.25", "120": an amount the way a stepper shows it. */
    fun format(v: Double): String = when {
        v == floor(v) -> v.toLong().toString()
        v >= 10 -> v.roundToInt().toString()
        else -> (Math.round(v * 100) / 100.0).toString().trimEnd('0').trimEnd('.')
    }

    fun isCount(unit: String?) = unit in COUNT_UNITS
}
