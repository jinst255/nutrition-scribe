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
        // How people count when they are not really counting.
        "couple" to 2.0, "pair" to 2.0, "few" to 3.0, "several" to 3.0, "dozen" to 12.0,
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

    // ------------------------------------------------------------------
    // Normalising what people actually type.
    //
    // Every pattern here was put in by a case from a real exported log, not by
    // imagination. Dictated entries are messy in specific, repeatable ways:
    // unicode fractions run into the next word ("⅓bell peper"), quantities
    // arrive in the middle or at the end ("milk 2 cups whole milk"), and half of
    // them open with a few words of throat-clearing ("I just had...").
    // ------------------------------------------------------------------

    private val UNICODE_FRACTIONS = mapOf(
        '½' to 0.5, '⅓' to 1.0 / 3, '⅔' to 2.0 / 3, '¼' to 0.25, '¾' to 0.75,
        '⅛' to 0.125, '⅜' to 0.375, '⅝' to 0.625, '⅞' to 0.875,
        '⅕' to 0.2, '⅖' to 0.4, '⅗' to 0.6, '⅘' to 0.8, '⅙' to 1.0 / 6, '⅚' to 5.0 / 6,
    )

    private val LEADING_FILLER = Regex(
        "^(?:i\\s+(?:just\\s+)?(?:had|ate|got|made)|" +
            "(?:this\\s+)?(?:morning|afternoon|evening)\\s+i\\s+had|" +
            "(?:for\\s+)?(?:breakfast|lunch|dinner|supper)\\s+(?:i\\s+had|was)|" +
            "today\\s+i\\s+had|" +
            "about|approximately|roughly|around|like|maybe|" +
            "my|the|a\\s+bit\\s+of|a\\s+little\\s+bit\\s+of|a\\s+little)\\s+",
        RegexOption.IGNORE_CASE,
    )

    private val TRAILING_QUANTITY =
        Regex("^(.*?)[,\\s]+(\\d+(?:\\.\\d+)?)\\s*${UNITS}s?\\b\\.?$", RegexOption.IGNORE_CASE)
    private val INLINE_QUANTITY =
        Regex("\\b(\\d+(?:\\.\\d+)?)\\s*$UNITS\\b", RegexOption.IGNORE_CASE)
    private val LEADING_NUMBER =
        Regex("^(?:\\d|(?:${NUMBER_WORDS.keys.joinToString("|")})\\b)", RegexOption.IGNORE_CASE)
    private val SLASH_FRACTION = Regex("\\b(\\d+)\\s*/\\s*(\\d+)\\b")
    private val UNIT_AND_A_HALF =
        Regex("\\b(?:a\\s+)?${UNITS}\\s+and\\s+(?:a\\s+)?half\\b", RegexOption.IGNORE_CASE)
    private val UNIT_AND_FRACTION =
        Regex("\\b(?:a\\s+)?${UNITS}\\s+and\\s+(\\d+(?:\\.\\d+)?)\\b", RegexOption.IGNORE_CASE)
    private val AND_A_HALF =
        Regex("\\b(\\d+(?:\\.\\d+)?)\\s+and\\s+(?:a\\s+)?half\\b", RegexOption.IGNORE_CASE)
    private val NUM_A_UNIT = Regex(
        "\\b((?:${NUMBER_WORDS.keys.joinToString("|")})|\\d+(?:\\.\\d+)?)\\s+an?\\s+$UNITS\\b",
        RegexOption.IGNORE_CASE,
    )
    private val ARTICLE_THEN_COUNT =
        Regex("\\ban?\\s+(couple|pair|few|several|dozen)\\b", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("\\s+")

    // Locale.ROOT is not optional: String.format on a device set to a locale that
    // uses a decimal comma would write "0,3333", which the quantity parser then
    // reads as a number followed by a separator, and the amount silently becomes
    // zero. Nothing about this would reproduce in English.
    private fun trim(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString()
        else String.format(java.util.Locale.ROOT, "%.4g", value).trimEnd('0').trimEnd('.')

    /** Text in, cleaner text out. Safe to call more than once. */
    fun preprocess(text: String): String {
        var t = text.trim()

        // "⅓bell peper" -> "0.3333 bell peper". Spaces on both sides because the
        // glyph is regularly typed flush against the next word.
        for ((glyph, value) in UNICODE_FRACTIONS) {
            if (t.indexOf(glyph) >= 0) t = t.replace(glyph.toString(), " ${trim(value)} ")
        }

        // Order matters: "1/4" has to become 0.25 before UNIT_AND_FRACTION looks
        // for the number after "and", or "a cup and 1/4" collapses to "2 cup/4".
        t = SLASH_FRACTION.replace(t) { m ->
            val d = m.groupValues[2].toDouble()
            if (d == 0.0) m.value else trim(m.groupValues[1].toDouble() / d)
        }
        t = UNIT_AND_A_HALF.replace(t) { m -> "1.5 ${m.groupValues[1]}" }
        t = UNIT_AND_FRACTION.replace(t) { m ->
            "${trim(1 + (m.groupValues[2].toDoubleOrNull() ?: 0.0))} ${m.groupValues[1]}"
        }
        t = AND_A_HALF.replace(t) { m -> trim(m.groupValues[1].toDouble() + 0.5) }
        // "half a serving" -> "half serving", so the quantity parser sees the unit.
        t = NUM_A_UNIT.replace(t) { m -> "${m.groupValues[1]} ${m.groupValues[2]}" }
        // "a couple of eggs" -> "couple of eggs". The article was being read as a
        // count of one, and the word carrying the actual number was skipped.
        t = ARTICLE_THEN_COUNT.replace(t) { m -> m.groupValues[1] }

        t = WHITESPACE.replace(t, " ").trim()
        while (true) {
            val stripped = LEADING_FILLER.replaceFirst(t, "").trim()
            if (stripped == t || stripped.isEmpty()) break
            t = stripped
        }

        // Move a count to the front so the one quantity parser sees it. People
        // put it wherever: "carrot cake 2 servings", "milk 2 cups whole milk".
        TRAILING_QUANTITY.matchEntire(t)?.let { m ->
            if (contentWords(m.groupValues[1]).isNotEmpty()) {
                return "${m.groupValues[2]} ${m.groupValues[3]} " +
                    m.groupValues[1].trimEnd('.', ',')
            }
        }
        // LEADING_NUMBER is anchored with ^, so this is "does it start with one".
        if (!LEADING_NUMBER.containsMatchIn(t)) {
            INLINE_QUANTITY.find(t)?.let { m ->
                val rest = (t.substring(0, m.range.first) + " " +
                    t.substring(m.range.last + 1)).trim()
                if (contentWords(rest).isNotEmpty()) {
                    return "${m.groupValues[1]} ${m.groupValues[2]} ${WHITESPACE.replace(rest, " ")}"
                }
            }
        }
        return t
    }

    /**
     * Cheap singularisation so "eggs" lines up with USDA's "Egg". Not a real
     * stemmer, and it does not need to be — but it MUST stay identical to stem()
     * in tools/foodsearch.py, which is what builds the word index. If the two
     * drift, words get indexed under a form the app never asks for, and the food
     * simply stops being findable with no error anywhere.
     */
    fun stem(word: String): String = when {
        word.length > 3 && word.endsWith("ies") -> word.dropLast(3) + "y"
        word.length > 3 && PLURAL_ES.containsMatchIn(word) -> word.dropLast(2)
        word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
        else -> word
    }

    /**
     * "-es" is only a plural marker after a sibilant or an "o": box/boxes,
     * dish/dishes, glass/glasses, potato/potatoes. Everywhere else the "e" belongs
     * to the word and only the "s" is the plural: cake/cakes, apple/apples.
     *
     * Stripping "es" everywhere turned "pancakes" into "pancak" while "pancake"
     * stayed whole, so a search for "pancake" could not reach a row described as
     * "Pancakes, NFS" — it found "Pancake syrup" instead. 89 words in the USDA
     * descriptions were affected, among them apples, cakes, grapes, noodles,
     * juices, oranges, sausages and waffles.
     */
    private val PLURAL_ES = Regex("(x|z|ch|sh|ss|o)es$")

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

    private val WITH_SEPARATOR =
        Regex("\\s+(?:with|on top of|topped with|and\\s+some)\\s+", RegexOption.IGNORE_CASE)

    /**
     * A second, more aggressive reading of the same text.
     *
     * "with" cannot be split unconditionally — it would tear "spaghetti with meat
     * sauce" and "tuna sandwich on wheat" into nonsense. But refusing to split it
     * was losing an entire food: "two pancakes with a little bit of maple syrup"
     * matched the SYRUP and logged 431 calories of nothing but syrup, and "one
     * piece of bread with a serving of cream cheese" lost the bread.
     *
     * So produce both readings and let coverage decide which one the person meant.
     */
    fun splitOnWith(items: List<String>): List<String> {
        val out = items.flatMap { item ->
            WITH_SEPARATOR.split(item).map { it.trim() }
                .filter { it.isNotEmpty() && contentWords(it).isNotEmpty() }
        }
        return out.ifEmpty { items }
    }

    /**
     * Foods that are sold and described BY their protein content. For these, a
     * leading "17g" is a protein claim, not a serving weight — nobody eats 17
     * grams of protein bar. This list is short on purpose: "15g butter" really is
     * a weight, so only nouns routinely marketed this way belong here.
     */
    private val PROTEIN_CLAIM_NOUNS =
        setOf("bar", "shake", "drink", "smoothie", "yogurt", "powder")

    private val GRAMS_PREFIX =
        Regex("^(\\d+(?:\\.\\d+)?)\\s*(?:g|grams?)\\b\\s*(?:of\\s+)?", RegexOption.IGNORE_CASE)
    private val GRAMS_OF_PROTEIN = Regex(
        "\\b(\\d+(?:\\.\\d+)?)\\s*(?:g|grams?)\\s*(?:of\\s+)?protein\\b",
        RegexOption.IGNORE_CASE,
    )

    /** A stated protein figure, and the text with that number taken out of it. */
    data class ProteinClaim(val grams: Double?, val text: String)

    /**
     * "20g protein shake" used to parse as twenty grams OF protein powder: the
     * protein came out right at 20 g and the calories came out at 70 instead of
     * 160, because the app weighed the shake as if it were a rounding error. The
     * number has to be taken OUT of the text, not merely read from it.
     */
    fun stripProteinClaim(text: String): ProteinClaim {
        GRAMS_OF_PROTEIN.find(text)?.let { m ->
            val rest = (text.substring(0, m.range.first) + "protein " +
                text.substring(m.range.last + 1)).trim()
            return ProteinClaim(m.groupValues[1].toDouble(), rest)
        }
        GRAMS_PREFIX.find(text)?.takeIf { it.range.first == 0 }?.let { m ->
            val rest = text.substring(m.range.last + 1)
            if (contentWords(rest).any { stem(it) in PROTEIN_CLAIM_NOUNS }) {
                return ProteinClaim(m.groupValues[1].toDouble(), rest.trim())
            }
        }
        // Other orderings ("protein: 16 g") are read but left in place; they do
        // not sit where the quantity parser would trip over them.
        return ProteinClaim(statedProteinGrams(text), text)
    }

    /**
     * USDA lists portions BY SIZE — "1 small pancake" 20 g, "1 medium pancake"
     * 50 g, "1 large pancake" 90 g. The size words are stop words for SEARCHING
     * (nobody wants "small" to steer which food is found), but throwing them away
     * entirely meant "a small cookie" was logged as a 60 g cookie at 295 calories.
     */
    val SIZE_WORDS = listOf(
        "extra large", "jumbo", "large", "medium", "regular", "small", "miniature", "mini",
    )

    fun sizeOf(text: String): String? {
        val low = text.lowercase()
        return SIZE_WORDS.firstOrNull { Regex("\\b$it\\b").containsMatchIn(low) }
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
    @JvmOverloads
    fun score(queryWords: List<String>, description: String, source: String? = null): Double {
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
        // English puts the head noun LAST and USDA puts it FIRST: "raspberry jam"
        // is a jam, "apple pie" is a pie, "bell pepper" is a pepper. So the final
        // word of the query is the best single clue to what the food actually IS,
        // and a row whose head noun agrees with it is usually the right sense.
        // Without this, "raspberry jam" matched "Raspberries, raw".
        if (queryWords.isNotEmpty() && queryWords.last() in headWords) s += 10
        // Forms that are an ingredient or a stage, not a meal. "carrot cake" has
        // no cooked row in USDA, so it landed on "Cake, pudding-type, carrot, DRY
        // MIX" and two servings came to 4233 calories. Same family of error as
        // raw rice: the row is real, it is just not what a person means.
        if (UNEATEN.containsMatchIn(lower)) s -= 20
        // FNDDS ("survey") rows describe food AS EATEN and carry realistic serving
        // sizes; SR Legacy rows describe ingredients and often default to a cup.
        // 81 descriptions exist in both and the tie was broken arbitrarily, so
        // "greek yogurt with honey" drew a 339 g cup of honey: 1104 calories.
        if (source == "survey_fndds_food") s += 2
        return s
    }

    private val UNEATEN = Regex(
        "\\b(dry mix|unprepared|dehydrated|condensed, undiluted|" +
            "reconstituted|not reconstituted|mix, dry)\\b",
    )

    /**
     * Does this row even claim to be the kind of thing that was named?
     *
     * Splitting a sentence produces fragments that are not food at all — "medium
     * sized.", "1/4 of it", "medium frosty" — and every one of them still found a
     * nearest row, so the app added calories for food nobody ate: raw rice for
     * "medium sized", Reese's cups for "1/4 of it". Requiring the match to share a
     * word with the query in its HEAD noun throws all of those out while keeping
     * every real match, because USDA leads with the head noun.
     *
     * A curated alias is exempt: it is authoritative by construction and
     * deliberately points at rows whose head is nothing like the query
     * ("protein shake" -> "Nutritional powder mix, protein, NFS").
     */
    fun isCredible(queryText: String, description: String): Boolean {
        val words = contentWords(queryText)
        if (words.isEmpty()) return false
        val headWords = LETTERS.findAll(headOf(description)).map { stem(it.value) }.toSet()
        return words.any { it in headWords }
    }

    /** How much of the original sentence a set of matches accounts for, 0..1. */
    fun explained(descriptions: List<String>, allWords: List<String>): Double {
        if (descriptions.isEmpty() || allWords.isEmpty()) return 0.0
        val covered = mutableSetOf<String>()
        for (d in descriptions) {
            val descWords = LETTERS.findAll(d.lowercase()).map { stem(it.value) }.toSet()
            covered += allWords.filter { it in descWords }
        }
        return covered.size.toDouble() / allWords.size
    }
}
