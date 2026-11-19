package com.nutritionscribe.shared.resolve

/**
 * The name a person would say for a food, for cards, lists and My foods (review
 * L2): "Apples" for "Apples, raw, with skin (Includes foods for USDA's Food
 * Distribution Program)", "Cottage cheese" for "Cheese, cottage, NFS", and a label's
 * product without its marketing ("… Cereal With Oat And Naturally Fruit Flavored …").
 * The source's own name stays on the food's detail, where it says where the
 * numbers came from.
 */
object ShortName {

    /** USDA's qualifiers that say nothing a person would. */
    private val NOISE = Regex(
        "^(?:raw|nfs|ns as to .*|all varieties|with skin|without skin|includes .*|plain|regular|fresh|fresh or raw|" +
            "cooked|boiled|drained|ready-to-eat|ready to eat|commercial|prepared|unprepared|not fortified|fortified)$",
    )

    /** Heads USDA files a kind under ("Cheese, cottage"): said the other way round. */
    private val FLIPPED_HEADS = setOf("cheese", "rice", "milk", "syrup", "syrups", "beans", "oil", "bread", "juice", "yogurt", "soup",
        "pasta", "beef", "pork", "chicken", "turkey", "fish", "sauce", "nuts", "squash", "lettuce", "peppers", "pepper", "cereal", "tea", "coffee")

    private const val MAX_WORDS = 7

    fun of(name: String): String {
        // Stored as "Brand — Product, Kind": the brand stays whole, the product is shortened.
        if (" — " in name) return withBrand(name.substringAfter(" — "), name.substringBefore(" — "))
        val trimmed = name.replace(Regex("\\s*\\([^)]*\\)"), "").trim()
        if (!trimmed.contains(',') && trimmed.split(' ').size <= MAX_WORDS) return trimmed
        val parts = trimmed.split(",").map { it.trim() }.filter { it.isNotEmpty() && !NOISE.matches(it.lowercase()) }
        if (parts.isEmpty()) return trimmed.substringBefore(",")
        val first = parts[0]
        val looksUsda = first.split(' ').size <= 2
        // "Cheese, cottage" -> "Cottage cheese"; "Syrups, maple" -> "Maple syrup".
        if (looksUsda && parts.size > 1 && first.lowercase() in FLIPPED_HEADS && parts[1].split(' ').size <= 2) {
            val head = first.lowercase().let { if (it == "syrups") "syrup" else it }
            return parts[1].replaceFirstChar { it.uppercase() } + " " + head
        }
        return marketingCut(first)
    }

    /** A label's product without the marketing after "With", "Made With", "Naturally"…, at most [MAX_WORDS] words. */
    private fun marketingCut(s: String): String {
        val cut = Regex("\\s+(?:with|made with|naturally|artificially|in a|topped with|featuring)\\s+", RegexOption.IGNORE_CASE).split(s).first()
        val words = cut.split(Regex("\\s+"))
        // "&" and "and" join words; they don't make a name longer.
        var counted = 0
        val kept = words.takeWhile { w -> if (w == "&" || w.equals("and", true)) true else ++counted <= MAX_WORDS }.joinToString(" ")
        // Title-case labels capitalise "And"; a name reads better with it small.
        return kept.replace(Regex("(?<=\\s)And(?=\\s)"), "and")
    }

    /** Words that name a kind of product, never which one. */
    private val CATEGORY = setOf("cracker", "crackers", "chip", "chips", "cookie", "cookies", "cereal", "candy", "snack", "snacks",
        "bar", "bars", "juice", "soda", "drink", "bread", "chocolate", "pretzels", "popcorn", "yogurt", "cheese", "milk", "water", "food", "product")

    /**
     * A label pack name that says only what kind of thing it is: Goldfish's barcode
     * came back as "Pepperidge Farm Crackers", and the owner (10-29) "this was
     * gold fish." The scan then asks Open Food Facts for the product's own name.
     */
    fun isGeneric(name: String, brand: String?): Boolean {
        val brandWords = brand.orEmpty().lowercase().split(Regex("[^a-z']+")).toSet()
        val words = name.lowercase().split(Regex("[^a-z']+")).filter { it.length > 1 && it !in brandWords }
        return words.isEmpty() || words.all { it in CATEGORY }
    }

    /** "Great Value — Real Bacon Pieces": the brand once, when it isn't already in the name. */
    fun withBrand(name: String, brand: String?): String {
        val n = of(name)
        val b = brand?.trim()?.takeIf { it.isNotBlank() && !n.contains(it, ignoreCase = true) } ?: return n
        return "$b — $n"
    }
}
