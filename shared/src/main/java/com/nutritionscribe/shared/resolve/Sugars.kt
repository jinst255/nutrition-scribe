package com.nutritionscribe.shared.resolve

import com.nutritionscribe.shared.food.FoodQuery
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.model.Metric

/**
 * Natural and added sugar.
 *
 * The 50 g daily limit is for ADDED sugar only; the sugar in fruit and plain milk
 * has none. A label states its added sugars ("Includes 10 g Added Sugars"), so a
 * scanned label is taken as read. USDA and the label pack do not, so for those the
 * kind of food decides: honey and soda are all added, a raspberry or plain milk
 * none. A food that could be either (a cookie, a flavoured yogurt) stays unknown
 * rather than getting an invented split.
 */
object Sugars {

    private val ADDED = setOf(
        "honey", "syrup", "sugar", "molasses", "agave", "jam", "jelly", "preserve", "marmalade", "candy",
        "soda", "cola", "pop", "lemonade", "frosting", "icing", "caramel", "gummy", "gummies", "marshmallow",
        "sprite", "gatorade", "powerade", "sweetener", "nectar", "slushie", "slurpee", "koolaid", "punch",
    ).map { FoodQuery.stem(it) }.toSet()

    /** Soft drinks known by name, whose name has no "soda" in it. */
    private val DRINK_NAMES = listOf("root beer", "dr pepper", "mountain dew", "ginger ale", "cream soda", "energy drink")

    /** Anything that makes a natural food maybe-sweetened. */
    private val MIXED = setOf(
        "chocolate", "sweetened", "flavored", "flavoured", "vanilla", "cookie", "cake", "bar", "cereal", "granola",
        "muffin", "pie", "ice", "pastry", "sauce", "dressing", "ketchup", "bbq", "barbecue", "bread", "cracker",
        "yogurt", "drink", "smoothie", "shake", "coffee", "latte", "tea", "glazed", "candied", "sweet", "dessert",
        "pudding", "cobbler", "crisp", "donut", "doughnut", "waffle", "pancake", "oatmeal", "instant", "trail",
        "mix", "cocktail", "canned", "syrupy", "light",
    ).map { FoodQuery.stem(it) }.toSet()

    /** Foods whose sugar is their own. */
    private val NATURAL = setOf(
        "milk", "apple", "banana", "orange", "berry", "strawberry", "blueberry", "raspberry", "blackberry",
        "grape", "kiwi", "kiwifruit", "mango", "pear", "peach", "plum", "cherry", "melon", "watermelon",
        "cantaloupe", "pineapple", "apricot", "date", "fig", "raisin", "prune", "papaya", "nectarine",
        "grapefruit", "tangerine", "clementine", "lemon", "lime", "pomegranate", "avocado", "tomato",
        "carrot", "celery", "pepper", "spinach", "lettuce", "broccoli", "cucumber", "onion", "potato",
        "squash", "zucchini", "corn", "pea", "bean", "lentil", "oat", "rice", "egg", "cheese", "butter",
        "beef", "chicken", "pork", "turkey", "ham", "fish", "salmon", "tuna", "nut", "almond", "peanut",
        "walnut", "cashew", "seed", "vegetable", "fruit", "juice",
    ).map { FoodQuery.stem(it) }.toSet()

    /**
     * How much of [name]'s sugar is added: 1 (all), 0 (none), or null when the
     * kind of food does not say.
     */
    fun addedFraction(name: String, origin: Origin?): Double? {
        val low = name.lowercase()
        val words = Regex("[a-z]+").findAll(low).map { FoodQuery.stem(it.value) }.toSet()
        if (words.any { it in ADDED } || DRINK_NAMES.any { it in low }) return 1.0
        // "peanut butter" is butter by its last word but is often sweetened.
        if (words.any { it in MIXED } || "peanut butter" in low) return null
        if (words.any { it in NATURAL }) return 0.0
        return null
    }

    /** [n] with an added-sugar figure, when its own label did not give one and the food says. */
    fun withAddedSugar(n: Map<String, Double>, name: String, origin: Origin?): Map<String, Double> {
        if (Metric.ADDED_SUGAR.key in n) return n
        val sugar = n[Metric.SUGAR.key] ?: return n
        val f = addedFraction(name, origin) ?: return n
        return n + (Metric.ADDED_SUGAR.key to sugar * f)
    }

    /** A day's sugar: what is known to be added, known to be natural, and not known. */
    data class Split(val added: Double, val natural: Double, val unknown: Double) {
        val total: Double get() = added + natural + unknown
    }

    fun split(entries: List<Entry>): Split {
        var added = 0.0; var natural = 0.0; var unknown = 0.0
        for (e in entries) {
            val s = splitEntry(e)
            added += s.added; natural += s.natural; unknown += s.unknown
        }
        return Split(added, natural, unknown)
    }

    /** Which foods gave the most ADDED sugar (unsplit sugar counted as added), largest first. */
    fun addedSources(entries: List<Entry>): List<Pair<String, Double>> = entries.flatMap { e ->
        if (e.items.isEmpty()) listOf(e.name to splitEntry(e).let { it.added + it.unknown })
        else e.items.map { it.name to (it.nutrients[Metric.SUGAR.key]?.let { s ->
            it.nutrients[Metric.ADDED_SUGAR.key]
                ?: addedFraction(it.name, Origin.entries.firstOrNull { o -> o.name == it.origin })?.let { f -> s * f } ?: s
        } ?: 0.0) }
    }.filter { it.second > 0.05 }.groupBy({ it.first }, { it.second }).map { (n, v) -> n to v.sum() }.sortedByDescending { it.second }

    fun splitEntry(e: Entry): Split {
        val sugar = e.nutrients[Metric.SUGAR.key] ?: return Split(0.0, 0.0, 0.0)
        e.nutrients[Metric.ADDED_SUGAR.key]?.let { a ->
            val added = a.coerceIn(0.0, sugar)
            return Split(added, sugar - added, 0.0)
        }
        if (e.items.isEmpty()) {
            val f = addedFraction(e.name, null) ?: return Split(0.0, 0.0, sugar)
            return Split(sugar * f, sugar * (1 - f), 0.0)
        }
        var added = 0.0; var natural = 0.0; var unknown = 0.0
        for (it in e.items) {
            val s = it.nutrients[Metric.SUGAR.key] ?: continue
            val a = it.nutrients[Metric.ADDED_SUGAR.key]
                ?: addedFraction(it.name, Origin.entries.firstOrNull { o -> o.name == it.origin })?.let { f -> s * f }
            if (a == null) unknown += s else { added += a.coerceIn(0.0, s); natural += s - a.coerceIn(0.0, s) }
        }
        // Items can miss a sugar figure the entry total has (an edited total).
        val rest = sugar - (added + natural + unknown)
        if (rest > 0.5) unknown += rest
        return Split(added, natural, unknown)
    }
}

/**
 * Fat by kind. Saturated and trans fat are the ones to keep low; the rest
 * (mono- and polyunsaturated) is the kind the guidelines want instead.
 */
object Fats {
    data class Split(val saturatedAndTrans: Double, val unsaturated: Double, val total: Double)

    fun split(n: Map<String, Double>): Split {
        val total = n[Metric.FAT.key] ?: 0.0
        val bad = (n[Metric.SATURATED_FAT.key] ?: 0.0) + (n[Metric.TRANS_FAT.key] ?: 0.0)
        val hard = bad.coerceAtMost(total)
        return Split(hard, (total - hard).coerceAtLeast(0.0), total)
    }
}
