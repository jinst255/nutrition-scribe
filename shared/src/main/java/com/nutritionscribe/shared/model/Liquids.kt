package com.nutritionscribe.shared.model

import java.util.Locale

/**
 * Drinks are measured in millilitres, not grams.
 *
 * Everything is still stored in grams, because that is what the food data is per.
 * This only decides how an amount is SHOWN and typed: "240 mL · 8 fl oz" for a
 * glass of milk rather than "247 g", which nobody pours by.
 */
object Liquids {

    private val DRINK_WORDS = setOf(
        "milk", "juice", "soda", "pop", "cola", "coke", "sprite", "water", "coffee", "tea", "latte",
        "cappuccino", "americano", "espresso", "macchiato", "frappuccino", "smoothie", "shake", "milkshake",
        "beer", "wine", "cider", "kombucha", "lemonade", "limeade", "drink", "beverage", "broth", "kefir",
        "creamer", "gatorade", "powerade", "soymilk", "eggnog", "cocoa", "chai", "punch", "seltzer",
        "sparkling", "nectar", "horchata",
    )

    /** Words that make a "milk" or "tea" word part of a solid food. */
    private val SOLID_WORDS = setOf(
        "powder", "bar", "bars", "bread", "pancake", "pancakes", "cereal", "cookie", "cookies", "cake",
        "pudding", "ice", "yogurt", "cheese", "butter", "candy", "chip", "chips", "biscuit", "cracker",
        "crackers", "mix", "gummies", "gummy", "bites", "muffin", "roll", "rolls", "bun", "dust", "leaves",
        "bag", "bags", "concentrate", "frozen", "popsicle", "pop-tart", "lollipop", "corn",
    )

    /** What can follow the drink word: "milk whole", "coke zero", "coffee black". */
    private val TRAILING = setOf("whole", "skim", "zero", "diet", "light", "lite", "original", "unsweetened",
        "sweetened", "black", "iced", "hot", "decaf", "plain")

    /** "milk chocolate" is chocolate; "chocolate milk" is milk. The last word decides. */
    fun isLiquid(name: String, servingText: String? = null): Boolean {
        val serving = servingText.orEmpty().lowercase()
        if (Regex("\\d\\s*(?:ml|mlt|fl\\.?\\s*oz|floz)\\b").containsMatchIn(serving)) return true
        val words = Regex("[a-z]+").findAll(name.lowercase()).map { it.value }.toList()
        if (words.isEmpty() || words.any { it in SOLID_WORDS }) return false
        val last = words.last()
        // "iced tea", "oat milk", "a Premier Protein shake"; "tea biscuit" is out above.
        return last in DRINK_WORDS || (words.size >= 2 && words[words.size - 2] in DRINK_WORDS && last in TRAILING)
    }

    /** Grams per millilitre. Milk and shakes are a little heavier than water. */
    fun density(name: String): Double {
        val n = name.lowercase()
        return when {
            Regex("\\b(milk|kefir|shake|smoothie|eggnog|creamer)\\b").containsMatchIn(n) -> 1.03
            Regex("\\bjuice|soda|cola|lemonade\\b").containsMatchIn(n) -> 1.04
            else -> 1.0
        }
    }

    fun ml(grams: Double, name: String): Double = grams / density(name)

    fun grams(ml: Double, name: String): Double = ml * density(name)

    /** "240 mL · 8 fl oz". */
    fun show(grams: Double, name: String): String {
        val ml = ml(grams, name)
        val floz = ml / 29.5735
        val oz = if (floz >= 10) Math.round(floz).toString() else String.format(Locale.ROOT, "%.1f", floz).removeSuffix(".0")
        return "${Math.round(ml)} mL · $oz fl oz"
    }

    /** How an amount reads anywhere in the app: grams for food, mL for a drink. */
    fun amount(grams: Double, name: String, servingText: String? = null): String =
        if (isLiquid(name, servingText)) show(grams, name) else "${Math.round(grams)} g"
}
