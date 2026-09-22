package com.nutritionscribe.shared.food

/**
 * Text handling for the offline food lookup: turning what someone typed into a
 * quantity, a food phrase, and a set of searchable words.
 *
 * Deliberately free of Android and SQLite types so it can be tested on the JVM in
 * milliseconds. The reference implementation is `tools/foodsearch.py`, and
 * `tools/bench_food_db.py` measures any change to the ranking — if you change the
 * scoring here, change it there too and re-run the benchmark, or the numbers in
 * README stop meaning anything.
 */
object FoodQuery {

    /** Words that carry no food meaning, so they must not drive a match. */
    private val STOP = setOf(
        "i", "ate", "had", "a", "an", "the", "some", "of", "with", "and", "for",
        "my", "this", "that", "today", "then", "plus", "eating", "eat", "was",
        "were", "just", "about", "like", "bit", "little", "big", "large", "small",
    )

    private val NUMBER_WORDS = mapOf(
        "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0,
        "six" to 6.0, "seven" to 7.0, "eight" to 8.0, "nine" to 9.0, "ten" to 10.0,
        "half" to 0.5, "a" to 1.0, "an" to 1.0,
    )

    private const val UNITS =
        "(oz|ounce|ounces|g|gram|grams|lb|lbs|pound|pounds|cup|cups|tbsp|" +
            "tablespoon|tablespoons|tsp|teaspoon|teaspoons|slice|slices|piece|" +
            "pieces|serving|servings|scoop|scoops|can|cans|bottle|bottles)"

    private val LETTERS = Regex("[a-z]+")

    // Hoisted deliberately. These were built inside score(), which runs once per
    // candidate row — 400 rows meant ~800 Regex compilations per keystroke, and
    // on a device that alone cost roughly half a second per lookup.
    private val NFS = Regex("\\bnfs\\b")
    private val RAW = Regex("\\braw\\b")
    private val NUM_UNIT_FOOD = Regex("^(\\d+(?:\\.\\d+)?)\\s*$UNITS\\b\\s*(?:of\\s+)?(.*)$")
    private val NUM_FOOD = Regex("^(\\d+(?:\\.\\d+)?)\\s+(.*)$")
    private val UNIT_FOOD = Regex("^$UNITS\\b\\s*(?:of\\s+)?(.*)$")

    /** What someone typed, taken apart. */
    data class Parsed(val amount: Double?, val unit: String?, val food: String)

    /**
     * Cheap singularisation so "eggs" lines up with USDA's "Egg". Not a real
     * stemmer, and it does not need to be — but it MUST stay identical to stem()
     * in tools/foodsearch.py, which is what builds the word index. If the two
     * drift, words get indexed under a form the app never asks for, and the food
     * simply stops being findable with no error anywhere.
     */
    fun stem(word: String): String = when {
        word.length > 3 && word.endsWith("ies") -> word.dropLast(3) + "y"
        word.length > 3 && word.endsWith("es") && !word.endsWith("ses") -> word.dropLast(2)
        word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
        else -> word
    }

    /** "8oz chicken breast" -> (8.0, "oz", "chicken breast"). */
    fun parseQuantity(text: String): Parsed {
        val t = text.lowercase().trim()

        NUM_UNIT_FOOD.find(t)?.let {
            return Parsed(it.groupValues[1].toDouble(), it.groupValues[2], it.groupValues[3].trim())
        }
        NUM_FOOD.find(t)?.let {
            return Parsed(it.groupValues[1].toDouble(), null, it.groupValues[2].trim())
        }

        val parts = t.split(" ").filter { it.isNotBlank() }
        val first = parts.firstOrNull()
        if (first != null && parts.size > 1 && NUMBER_WORDS.containsKey(first)) {
            val rest = parts.drop(1).joinToString(" ")
            UNIT_FOOD.find(rest)?.let {
                return Parsed(NUMBER_WORDS[first], it.groupValues[1], it.groupValues[2].trim())
            }
            return Parsed(NUMBER_WORDS[first], null, rest)
        }
        return Parsed(null, null, t)
    }

    /**
     * Words that describe a meal rather than a food. "A solid breakfast" names no
     * ingredient, so any match for it is invented — and the offline lookup used to
     * answer it with "Breakfast bar, NFS", which is a number the user would then
     * have to notice was fiction. Better to say it needs detail.
     */
    private val MEAL_WORDS = setOf(
        "breakfast", "lunch", "dinner", "supper", "brunch", "meal", "snack",
        "leftover", "usual", "food", "something", "stuff", "everything", "dessert",
    )

    /** Splits a multi-item entry. "chicken, rice and broccoli" is three foods. */
    private val ITEM_SEPARATORS = Regex("\\s*(?:,|\\band\\b|\\bplus\\b|\\+|&)\\s*")

    /** "16g protein", "protein: 16 g", "16 grams of protein". */
    private val STATED_PROTEIN = listOf(
        Regex("(\\d+(?:\\.\\d+)?)\\s*(?:g|gram|grams)\\s*(?:of\\s+)?protein"),
        Regex("protein\\s*[:=]?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:g|gram|grams)?"),
    )

    /**
     * True when the text names a meal but no actual food.
     *
     * Deliberately conservative: "chicken dinner" contains a meal word but also a
     * food, so it is NOT vague. Only text whose every content word is a meal word,
     * a size word or a filler counts.
     */
    fun isVague(text: String): Boolean {
        val words = contentWords(text)
        if (words.isEmpty()) return true
        return words.all { stem(it) in MEAL_WORDS || it in VAGUE_MODIFIERS }
    }

    private val VAGUE_MODIFIERS = setOf(
        "solid", "good", "quick", "light", "heavy", "proper", "decent", "normal",
        "regular", "usual", "healthy", "nice", "full", "whole", "lot", "loads",
    )

    /**
     * Breaks "chicken, rice and broccoli" into its parts so each can be looked up
     * and logged separately. Splitting only on commas and "and"/"plus" is
     * deliberate — splitting on "with" would tear "spaghetti with meat sauce" and
     * "tuna sandwich on wheat" into nonsense.
     */
    fun splitItems(text: String): List<String> {
        val parts = ITEM_SEPARATORS.split(text.trim())
            .map { it.trim() }
            .filter { it.isNotBlank() && contentWords(it).isNotEmpty() }
        return if (parts.size <= 1) listOf(text.trim()) else parts
    }

    /**
     * A protein figure the person stated outright, e.g. "Built bar, 16g protein".
     * Their own number beats any database estimate for a food the database may not
     * even have.
     */
    fun statedProteinGrams(text: String): Double? {
        val lower = text.lowercase()
        for (pattern in STATED_PROTEIN) {
            val value = pattern.find(lower)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            if (value != null && value > 0 && value < 500) return value
        }
        return null
    }

    /**
     * How much of what the person said the match actually accounts for, 0..1.
     *
     * This is the honest confidence signal, and it separates cases that the score
     * alone cannot: "Big Mac" scores badly yet covers every word, while
     * "a solid breakfast" scores well against "Breakfast bar" but covers only half.
     */
    fun coverage(queryWords: List<String>, description: String): Double {
        if (queryWords.isEmpty()) return 0.0
        val descWords = LETTERS.findAll(description.lowercase()).map { stem(it.value) }.toSet()
        return queryWords.count { it in descWords }.toDouble() / queryWords.size
    }

    /** Searchable, stemmed words — stop words and single letters removed. */
    fun contentWords(text: String): List<String> =
        LETTERS.findAll(text.lowercase())
            .map { it.value }
            .filter { it !in STOP && it.length > 1 }
            .map { stem(it) }
            .toList()

    /**
     * USDA descriptions lead with the head noun — "Banana, raw"; "Pie, apple".
     * The head is what the row actually IS; everything after it qualifies.
     */
    fun headOf(description: String): String =
        description.substringBefore(",").trim().lowercase()

    /**
     * Stable key for the alias table, so "2 eggs", "eggs" and "Egg" all reach the
     * same curated or learned default. Order-independent because "cheese cheddar"
     * and "cheddar cheese" are the same request.
     */
    fun normalise(text: String): String = contentWords(text).sorted().joinToString(" ")

    /**
     * How well a USDA description answers a query. Higher is better.
     *
     * The decisive term is [headExtra]: every word in the head that the person did
     * NOT say is evidence this is a different food. It is what stops "apple" from
     * landing on "Pie, apple" and "avocado" from landing on "Oil, avocado", and it
     * is worth more than all the other signals put together.
     */
    fun score(queryWords: List<String>, description: String): Double {
        val lower = description.lowercase()
        val headWords = LETTERS.findAll(headOf(description)).map { stem(it.value) }.toList()
        val descWords = LETTERS.findAll(lower).map { stem(it.value) }.toSet()

        val matched = queryWords.count { it in descWords }
        val inHead = queryWords.count { it in headWords }
        val headExtra = headWords.count { it !in queryWords && it.length > 2 }

        // 16 rather than 14 for headExtra: at 14 an extra head word (cost 14) was
        // outweighed by the extra head match it came with (worth 16), so
        // "Cheddar cheese soup" beat "Cheese, Cheddar". Swept 14/16/18/20/24 against
        // the benchmark — 16 is where it flips, and nothing improves above it.
        var s = (matched * 12.0) + (inHead * 16.0) - (headExtra * 16.0) - (description.length / 60.0)
        // "NFS" (Not Further Specified) and "raw" are USDA's generic base forms —
        // which is exactly what a bare food name means.
        if (NFS.containsMatchIn(lower)) s += 6
        if (RAW.containsMatchIn(lower)) s += 3
        return s
    }
}
