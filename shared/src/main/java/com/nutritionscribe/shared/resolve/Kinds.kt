package com.nutritionscribe.shared.resolve

import com.nutritionscribe.shared.model.Metric
import kotlin.math.abs

/**
 * Kinds of one product: the vanilla, strawberry and mixed berry cups of the same
 * yogurt, Life Original and Life Cinnamon. Same brand, same product, a different
 * flavour, and (usually) nearly the same numbers.
 *
 * Label pack names say it twice, after a comma: "VANILLA BLENDED NON-FAT GREEK
 * YOGURT, VANILLA". Without the comma (a scan the person named), the flavour words
 * are found from a list. What is left is the product itself, and two foods with
 * the same brand and the same product words are kinds of one another.
 */
object Kinds {

    /** The product without its flavour, and the flavour as a label ("Vanilla", "Original"). */
    data class Split(val base: Set<String>, val kind: String)

    fun split(name: String, brand: String? = null): Split {
        val comma = name.lastIndexOf(", ")
        val brandWords = LabelPackSource.words(brand.orEmpty()).toSet()
        if (comma > 0) {
            val main = name.substring(0, comma)
            val tail = name.substring(comma + 2).trim()
            val tailWords = LabelPackSource.words(tail)
            val mainWords = LabelPackSource.words(main)
            // "ORIGINAL MULTIGRAIN CEREAL, ORIGINAL": the tail repeats the flavour.
            if (tailWords.isNotEmpty() && tailWords.all { it in mainWords }) {
                val base = (mainWords - tailWords.toSet()).filter { it !in PLAIN }.toSet() + brandWords
                return Split(base, title(tail))
            }
        }
        val words = LabelPackSource.words(name)
        val flavour = words.filter { it in FLAVOUR || it in NAMES_OF_PLAIN }
        val base = words.filter { it !in FLAVOUR && it !in PLAIN }.toSet() + brandWords
        val kind = if (flavour.isEmpty()) "Original" else title(rawFlavourWords(name).joinToString(" ").ifBlank { flavour.joinToString(" ") })
        return Split(base, kind)
    }

    fun of(c: FoodCandidate): Split = split(c.name, c.brand)

    fun label(c: FoodCandidate): String = of(c).kind

    /** Two foods are kinds of one product: same product words, another flavour. */
    fun sameProduct(a: FoodCandidate, b: FoodCandidate): Boolean {
        val sa = of(a); val sb = of(b)
        return sa.base.isNotEmpty() && sa.base == sb.base && !sa.kind.equals(sb.kind, ignoreCase = true)
    }

    /**
     * The product without its kind, the way people say it: "Chobani Blended Nonfat
     * Greek Yogurt" for "Strawberry Blended Nonfat Greek Yogurt, Strawberry".
     */
    fun productName(c: FoodCandidate): String {
        val main = c.name.substringBeforeLast(", ")
        val kind = label(c)
        val kindWords = kind.lowercase().split(" ").toSet()
        val rest = main.split(" ").filter { w ->
            val bare = w.lowercase().trim(',', '&')
            bare.isNotBlank() && bare !in kindWords && bare != "&" && bare !in setOf("flavored", "flavor", "flavoured", "blended")
        }.joinToString(" ")
        val brand = c.brand?.takeIf { it.isNotBlank() && !rest.contains(it, ignoreCase = true) }
        return listOfNotNull(brand, rest.ifBlank { main }).joinToString(" ")
    }

    /** Is this word a flavour (as the label pack stems it)? */
    fun isFlavourWord(word: String): Boolean = word in FLAVOUR

    /**
     * Its numbers for [grams] of it, or for one serving's worth of [servings] when it
     * was never weighed. One kind swapped for another keeps the amount the person had.
     */
    fun nutrientsFor(c: FoodCandidate, grams: Double?, servings: Double = 1.0): Map<String, Double> = when {
        c.per100g != null && c.per100g.isNotEmpty() && grams != null -> c.nutrientsFor(grams)
        c.perServing != null -> c.perServing.mapValues { it.value * servings }
        else -> emptyMap()
    }

    /**
     * Do these kinds differ enough to be worth asking about? "Within 10 kcal" logs
     * the last kind without a word; plain and honey, 60 kcal apart, are offered.
     */
    fun differ(numbers: List<Map<String, Double>>): Boolean {
        if (numbers.size < 2) return false
        val kcal = numbers.mapNotNull { it[Metric.CALORIES.key] }
        val protein = numbers.mapNotNull { it[Metric.PROTEIN.key] }
        val kcalSpread = if (kcal.size >= 2) kcal.max() - kcal.min() else 0.0
        val proteinSpread = if (protein.size >= 2) protein.max() - protein.min() else 0.0
        val top = kcal.maxOrNull() ?: 0.0
        return kcalSpread > maxOf(20.0, top * 0.12) || proteinSpread > 3.0
    }

    /** The largest calorie gap between kinds, for "kinds differ by up to 60 kcal". */
    fun spread(numbers: List<Map<String, Double>>): Double {
        val kcal = numbers.mapNotNull { it[Metric.CALORIES.key] }
        return if (kcal.size >= 2) kcal.max() - kcal.min() else 0.0
    }

    /**
     * The same product as [base] in a kind no one has saved: the name a scan of it
     * should get. "Original Multigrain Cereal, Original" with "Pumpkin Spice" is
     * "Pumpkin Spice Multigrain Cereal", which then sits with the other Life kinds.
     */
    fun nameFor(base: FoodCandidate, kind: String): String {
        val name = base.name
        val comma = name.lastIndexOf(", ")
        val main = if (comma > 0) name.substring(0, comma) else name
        val old = of(base).kind
        val swapped = Regex("\\b${Regex.escape(old)}\\b", RegexOption.IGNORE_CASE).replace(main, kind.trim()).trim()
        return if (swapped.equals(main, ignoreCase = true) || !swapped.contains(kind.trim(), ignoreCase = true)) "${kind.trim()} $main" else swapped
    }

    /** The flavour as printed, words in their order ("Madagascar Vanilla"), not stemmed. */
    private fun rawFlavourWords(name: String): List<String> =
        Regex("[A-Za-z]+").findAll(name).map { it.value }
            .filter { w -> LabelPackSource.words(w).let { it.size == 1 && (it[0] in FLAVOUR || it[0] in NAMES_OF_PLAIN) } }
            .toList()

    private val NAMES_OF_PLAIN = setOf("original", "plain", "classic", "regular", "unflavored")

    private fun title(s: String): String =
        s.lowercase().split(" ").filter { it.isNotBlank() }.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    /**
     * Not a flavour, but the name of the kind that has none, and the words that only
     * say a flavour was added: "Vanilla Flavored Multigrain Cereal" is still Life.
     */
    private val PLAIN = setOf("original", "plain", "classic", "regular", "unflavored", "unflavoured",
        "flavored", "flavor", "flavour", "flavoured", "blended", "naturally", "artificially")
        .map { com.nutritionscribe.shared.food.FoodQuery.stem(it) }.toSet()

    private val FLAVOUR = setOf(
        "vanilla", "strawberry", "chocolate", "blueberry", "cherry", "peach", "raspberry", "blackberry",
        "banana", "coconut", "cinnamon", "caramel", "mint", "lemon", "lime", "orange", "mango", "apple", "grape",
        "honey", "maple", "berry", "mixed", "wild", "peanut", "almond", "pumpkin", "spice", "coffee", "mocha", "salted",
        "birthday", "smore", "tropical", "watermelon", "pineapple", "cheddar", "ranch", "bbq", "barbecue",
        "jalapeno", "spicy", "sour", "key", "pear", "pomegranate", "passion",
        "madagascar", "monterey", "fudge", "pecan", "walnut",
        "hazelnut", "pistachio", "toffee", "butterscotch", "gingerbread", "ginger", "matcha", "chai",
        "lavender", "guava", "papaya", "kiwi", "cranberry", "acai", "dragonfruit", "citrus",
        "graham", "dill", "buffalo", "teriyaki", "sriracha", "nacho",
    ).map { com.nutritionscribe.shared.food.FoodQuery.stem(it) }.toSet()
}
