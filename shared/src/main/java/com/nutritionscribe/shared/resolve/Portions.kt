package com.nutritionscribe.shared.resolve

import com.nutritionscribe.shared.food.FoodQuery

/**
 * How many grams an item is.
 *
 * Order of authority, highest first:
 *   1. A weight they said ("8 oz", "150 g").
 *   2. Their own remembered portion for this food (handled by the caller).
 *   3. The curated table below, for units and categories where the database's own
 *      portions are misleading.
 *   4. A portion the database lists under the unit they said.
 *   5. One of it / the database's default serving.
 *
 * The one thing it must never do is fall back to the database's smallest listed
 * unit on an ambiguous amount: USDA lists "1 chip" for potato chips, and "a bag of
 * potato chips" came out at eleven calories.
 */
object Portions {

    /**
     * A curated portion: when the food (by word) and the unit (or no unit) match,
     * this many grams is ONE of that unit.
     *
     * Values are the serving printed on typical US labels, or USDA's own "1 cup",
     * so a curated answer is as checkable as a database one.
     */
    data class Rule(
        val words: Set<String>,
        val unit: String?,
        val grams: Double,
        val note: String,
        /**
         * False for a rule that means "a serving" rather than "one of them": "potato
         * chips" is 28 g, but "10 chips" is ten chips, not ten servings.
         */
        val perEach: Boolean = true,
    )

    private fun r(words: String, unit: String?, grams: Double, note: String, perEach: Boolean = true) =
        Rule(words.split(" ").map { FoodQuery.stem(it) }.toSet(), unit, grams, note, perEach)

    val RULES: List<Rule> = listOf(
        // Bags. A "bag" of snack food is the single-serve bag, not a family bag.
        r("chip", "bag", 28.0, "single-serve bag, 1 oz"),
        r("crisp", "bag", 28.0, "single-serve bag, 1 oz"),
        r("pretzel", "bag", 28.0, "single-serve bag, 1 oz"),
        r("popcorn", "bag", 90.0, "microwave bag, popped"),
        r("candy", "bag", 48.0, "fun-size-to-standard bag"),
        r("vegetable", "bag", 170.0, "snack bag of cut vegetables"),
        r("carrot", "bag", 85.0, "snack bag of baby carrots"),
        r("nut", "bag", 42.0, "snack bag"),
        r("almond", "bag", 42.0, "snack bag"),
        r("trail mix", "bag", 42.0, "snack bag"),
        // Bowls and plates: a real bowl, not a tablespoon.
        r("cereal", "bowl", 40.0, "one bowl, dry cereal"),
        r("oatmeal", "bowl", 234.0, "one cup cooked"),
        r("oat", "bowl", 234.0, "one cup cooked"),
        r("rice", "bowl", 186.0, "one cup cooked"),
        r("soup", "bowl", 360.0, "one and a half cups"),
        r("chili", "bowl", 250.0, "one cup"),
        r("pasta", "bowl", 280.0, "two cups cooked"),
        r("salad", "bowl", 150.0, "large side salad"),
        r("ice cream", "bowl", 132.0, "one cup"),
        r("pasta", "plate", 280.0, "two cups cooked"),
        r("spaghetti", "plate", 280.0, "two cups cooked"),
        // Glasses, cans, bottles.
        r("milk", "glass", 244.0, "one cup"),
        r("juice", "glass", 248.0, "one cup"),
        r("water", "glass", 237.0, "one cup"),
        r("soda", "can", 368.0, "12 fl oz can"),
        r("cola", "can", 368.0, "12 fl oz can"),
        r("coke", "can", 368.0, "12 fl oz can"),
        r("beer", "can", 356.0, "12 fl oz can"),
        r("soda", "bottle", 620.0, "20 fl oz bottle"),
        r("cola", "bottle", 620.0, "20 fl oz bottle"),
        r("water", "bottle", 500.0, "500 ml bottle"),
        r("beer", "bottle", 356.0, "12 fl oz bottle"),
        r("juice", "bottle", 450.0, "15 fl oz bottle"),
        r("shake", "bottle", 325.0, "11 fl oz ready-to-drink"),
        // Handfuls.
        r("nut", "handful", 28.0, "about an ounce"),
        r("almond", "handful", 28.0, "about an ounce"),
        r("peanut", "handful", 28.0, "about an ounce"),
        r("cashew", "handful", 28.0, "about an ounce"),
        r("raisin", "handful", 28.0, "about a small box"),
        r("chip", "handful", 15.0, "about half a serving"),
        r("pretzel", "handful", 15.0, "about half a serving"),
        r("grape", "handful", 50.0, "about 10 grapes"),
        r("berry", "handful", 50.0, "about a third of a cup"),
        r("carrot", "handful", 60.0, "about 6 baby carrots"),
        // "A serving" of foods whose USDA default is not a serving anyone eats.
        r("rice", "serving", 158.0, "one cup cooked"),
        r("pasta", "serving", 140.0, "one cup cooked"),
        r("spaghetti", "serving", 140.0, "one cup cooked"),
        r("noodle", "serving", 160.0, "one cup cooked"),
        r("cheese", "serving", 28.0, "one ounce"),
        r("shredded cheese", "serving", 28.0, "a quarter cup"),
        r("mozzarella", "serving", 28.0, "one ounce"),
        r("cottage cheese", "serving", 113.0, "half a cup"),
        r("cream cheese", "serving", 29.0, "two tablespoons"),
        r("yogurt", "serving", 170.0, "one container"),
        r("greek yogurt", "serving", 170.0, "one container"),
        r("peanut butter", "serving", 32.0, "two tablespoons"),
        r("jam", "serving", 20.0, "one tablespoon"),
        r("jelly", "serving", 20.0, "one tablespoon"),
        r("preserve", "serving", 20.0, "one tablespoon"),
        r("raisin", "serving", 40.0, "a quarter cup"),
        r("dried", "serving", 40.0, "a quarter cup"),
        r("apricot", "serving", 40.0, "a quarter cup, dried"),
        r("mango", "serving", 40.0, "a quarter cup, dried"),
        r("nut", "serving", 28.0, "one ounce"),
        r("almond", "serving", 28.0, "one ounce"),
        r("chip", "serving", 28.0, "one ounce"),
        r("bean", "serving", 130.0, "half a cup"),
        r("refried bean", "serving", 121.0, "half a cup"),
        r("chicken", "serving", 85.0, "three ounces cooked"),
        r("beef", "serving", 85.0, "three ounces cooked"),
        r("pork", "serving", 85.0, "three ounces cooked"),
        r("fish", "serving", 85.0, "three ounces cooked"),
        r("salmon", "serving", 85.0, "three ounces cooked"),
        r("casserole", "serving", 240.0, "one cup"),
        r("vegetable", "serving", 85.0, "about a cup, raw"),
        r("ice cream", "serving", 88.0, "two-thirds of a cup"),
        r("cake", "serving", 80.0, "one slice"),
        r("pie", "serving", 125.0, "one slice"),
        r("cereal", "serving", 40.0, "one cup, dry"),
        r("oatmeal", "serving", 234.0, "one cup cooked"),
        r("pancake", "serving", 114.0, "three pancakes"),
        r("protein pancake", "serving", 53.0, "half a cup of mix"),
        r("powder", "serving", 32.0, "one scoop"),
        r("scoop", "serving", 32.0, "one scoop"),
        // Scoops.
        r("powder", "scoop", 32.0, "one scoop"),
        r("protein", "scoop", 32.0, "one scoop"),
        r("ice cream", "scoop", 66.0, "one scoop"),
        // Pieces and slices where USDA's first listed portion is a crumb.
        r("bread", "piece", 29.0, "one slice"),
        r("bread", "slice", 29.0, "one slice"),
        r("toast", "piece", 29.0, "one slice"),
        r("toast", "slice", 29.0, "one slice"),
        r("pizza", "slice", 107.0, "one slice, 14 inch"),
        r("pizza", "piece", 107.0, "one slice, 14 inch"),
        r("deli", "slice", 28.0, "one slice"),
        r("deli", "piece", 28.0, "one slice"),
        r("luncheon", "piece", 28.0, "one slice"),
        r("ham", "piece", 28.0, "one slice"),
        r("meat", "slice", 28.0, "one slice"),
        r("ham", "slice", 28.0, "one slice"),
        r("turkey", "slice", 28.0, "one slice"),
        r("cake", "slice", 80.0, "one slice"),
        r("cake", "piece", 80.0, "one slice"),
        r("pie", "slice", 125.0, "one slice"),
        r("bacon", "strip", 8.0, "one cooked strip"),
        r("bacon", "slice", 8.0, "one cooked strip"),
        r("celery", "stalk", 40.0, "one medium stalk"),
        r("butter", "pat", 5.0, "one pat"),
        r("corn", "cob", 90.0, "one medium ear, kernels"),
        r("chocolate", "square", 10.0, "one square"),
        // Things people count, where "one" is a whole one.
        r("chip", null, 28.0, perEach = false, note = "one serving (never one chip)"),
        r("crisp", null, 28.0, perEach = false, note = "one serving"),
        r("pretzel", null, 28.0, perEach = false, note = "one serving"),
        r("popcorn", null, 28.0, perEach = false, note = "about three cups popped"),
        r("baby carrot", null, 10.0, "one baby carrot"),
        r("corn cob", null, 90.0, "one medium ear, kernels"),
        r("raisin", null, 28.0, perEach = false, note = "one small box"),
        r("tortilla", null, 45.0, "one medium flour tortilla"),
        r("flour tortilla", null, 45.0, "one medium flour tortilla"),
        r("corn tortilla", null, 26.0, "one corn tortilla"),
        r("bagel", null, 105.0, "one medium bagel"),
        r("sandwich", null, 150.0, "one sandwich"),
        r("burrito", null, 250.0, "one burrito"),
        r("protein shake", null, 325.0, "one ready-to-drink bottle"),
        r("protein drink", null, 325.0, "one ready-to-drink bottle"),
        r("shake", null, 325.0, "one ready-to-drink bottle"),
    )

    /** Toppings: "some ketchup", "a little syrup" — a spoonful, not a serving. */
    private val TOPPING_GRAMS = listOf(
        r("syrup", null, 20.0, "one tablespoon"),
        r("honey", null, 21.0, "one tablespoon"),
        r("ketchup", null, 17.0, "one tablespoon"),
        r("mustard", null, 5.0, "one teaspoon"),
        r("mayonnaise", null, 14.0, "one tablespoon"),
        r("mayo", null, 14.0, "one tablespoon"),
        r("jam", null, 20.0, "one tablespoon"),
        r("jelly", null, 20.0, "one tablespoon"),
        r("preserve", null, 20.0, "one tablespoon"),
        r("butter", null, 7.0, "half a tablespoon"),
        r("peanut butter", null, 16.0, "one tablespoon"),
        r("cream cheese", null, 29.0, "two tablespoons"),
        r("sour cream", null, 24.0, "two tablespoons"),
        r("dressing", null, 30.0, "two tablespoons"),
        r("sauce", null, 30.0, "two tablespoons"),
        r("salsa", null, 32.0, "two tablespoons"),
        r("gravy", null, 60.0, "a quarter cup"),
        r("milk", null, 60.0, "a quarter cup, a splash"),
        r("cream", null, 15.0, "one tablespoon"),
        r("creamer", null, 15.0, "one tablespoon"),
        r("sugar", null, 4.0, "one teaspoon"),
        r("oil", null, 5.0, "one teaspoon"),
        r("cheese", null, 14.0, "half an ounce"),
    )

    /** Volume units by millilitres; grams come via a cup portion when one exists. */
    private val ML = mapOf("cup" to 236.6, "tbsp" to 14.79, "tsp" to 4.93, "ml" to 1.0, "floz" to 29.57, "l" to 1000.0)

    data class Answer(val grams: Double, val how: String)

    /**
     * Grams for [item] of [food]. [item.amount] null means "one of it / one
     * serving"; the amount is applied here, and [item.scale] on top.
     */
    fun gramsFor(food: FoodCandidate, item: ItemParser.Item): Answer? {
        val count = (item.amount ?: 1.0)
        val per = gramsForOne(food, item) ?: return null
        return Answer(per.grams * count * item.scale, per.how)
    }

    /** Grams for ONE of whatever unit the item names. */
    fun gramsForOne(food: FoodCandidate, item: ItemParser.Item): Answer? {
        val unit = item.unit
        when (unit) {
            "g" -> return Answer(1.0, "grams")
            "oz" -> return Answer(28.3495, "ounces")
            "lb" -> return Answer(453.592, "pounds")
        }

        val itemWords = FoodQuery.contentWords(item.food).toSet()

        // "3 oreos" against a label whose serving is "3 cookies (34 g)": a bare count
        // is that many PIECES, so it goes through the label's own piece weight.
        if (food.origin == Origin.LABEL && unit == null && item.amount != null) {
            food.portions.firstOrNull { it.label != "1 serving" }?.let {
                return Answer(it.grams, "one ${it.label.removePrefix("1 ")}, from the label")
            }
        }
        // A packaged food is counted by its label: "one bar" is one label serving.
        if ((food.origin == Origin.LABEL || food.origin == Origin.CURATED) && food.labelServingGrams != null &&
            (unit == null || unit == "serving" || unit == "packet" || unit == "container" ||
                unit == "bottle" || unit == "can" || unit == "bag" || unit == "piece")
        ) {
            return Answer(food.labelServingGrams, food.labelServingText ?: "one label serving")
        }

        // A label's own measures beat any table: "2 slices" of a bread whose label
        // says "1 slice (45 g)" is 90 g, not two generic 29 g slices.
        if (food.origin == Origin.LABEL && unit != null) {
            food.portions.firstOrNull { it.label != "1 serving" && labelSays(it.label, unit) }
                ?.let { return Answer(it.grams, "one ${it.label.removePrefix("1 ")}, from the label") }
        }

        if (item.topping && item.amount == null) {
            best(TOPPING_GRAMS, itemWords, null)?.let { return Answer(it.grams, it.note) }
        }

        // A curated rule for this unit; the one naming the most words wins, so
        // "cottage cheese" beats "cheese". Only the words SAID count: matching on
        // the database's own description let "Hot dog sandwich" pick up the
        // generic 180 g sandwich rule, and two hot dogs came to 1066 calories.
        val counted = (item.amount ?: 1.0) > 1.0
        if (unit != null) best(RULES, itemWords, unit, counted)?.let { return Answer(it.grams, it.note) }

        if (unit != null && unit in ML) {
            // Prefer the database's own cup, which knows the food's density.
            // A real cup, not "Guideline amount per cup of hot cereal".
            val cup = food.portions.firstOrNull {
                Regex("^\\s*[\\d./]+\\s*cups?\\b").containsMatchIn(it.label.lowercase()) && labelAmount(it.label) > 0
            }
            if (cup != null) {
                val gramsPerMl = cup.grams / labelAmount(cup.label) / ML.getValue("cup")
                return Answer(gramsPerMl * ML.getValue(unit), "from the cup measure")
            }
            val named = food.portions.filter { labelSays(it.label, unit) }
            if (named.isNotEmpty()) return Answer(plainest(named), "listed portion")
            return Answer(ML.getValue(unit), "assumed water density")
        }

        if (unit != null) {
            val named = food.portions.filter { labelSays(it.label, unit) }
            if (named.isNotEmpty()) return Answer(plainest(named), "listed portion")
        }

        // A size said out loud picks the sized portion: "a small cookie".
        item.size?.let { size ->
            // "1 medium" — not "1 strip, medium", which is a piece of one.
            val sized = food.portions.filter {
                it.label.contains(size, ignoreCase = true) && PART_WORDS.none { p -> Regex("\\b$p").containsMatchIn(it.label.lowercase()) }
            }
            if (sized.isNotEmpty()) return Answer(plainest(sized), "$size portion")
        }

        val default = food.defaultGrams ?: 100.0
        // A bare count means that many OF IT: "six pancakes" is six pancakes.
        // With a floor, so "1 chip" (2 g) can never stand in for "a bag of chips".
        // The database's own "1 sandwich" beats the generic table below, which is
        // only for foods whose listed portions are no help.
        if (unit == null || unit == "piece") {
            // The floor only guards an uncounted "one": "10 chips" really is ten chips.
            itemPortion(food)?.let { if (it >= default * 0.25 || counted) return Answer(it, "one of it") }
        }
        if (unit == null) best(RULES, itemWords, null, counted)?.let { return Answer(it.grams, it.note) }
        if (food.origin == Origin.LABEL && food.labelServingGrams != null) {
            return Answer(food.labelServingGrams, food.labelServingText ?: "one label serving")
        }
        return Answer(default, "typical serving")
    }

    private fun best(rules: List<Rule>, words: Set<String>, unit: String?, counted: Boolean = false): Rule? =
        rules.filter { it.unit == unit && it.words.all { w -> w in words } && (it.perEach || !counted) }
            .maxByOrNull { it.words.size }

    private fun labelSays(label: String, unit: String): Boolean {
        val low = label.lowercase().replace(Regex("\\bfl\\.?\\s*oz\\b"), "floz")
        val forms = ItemParser.UNITS.filterValues { it == unit }.keys + unit
        return forms.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(low) }
    }

    /** "2 tbsp" -> 2, "1 cup, diced" -> 1, "0.5 cup" -> 0.5. */
    fun labelAmount(label: String): Double {
        val m = Regex("^\\s*(\\d+(?:\\.\\d+)?)(?:\\s*/\\s*(\\d+))?").find(label) ?: return 1.0
        val n = m.groupValues[1].toDouble()
        val d = m.groupValues[2].toDoubleOrNull()
        return if (d != null && d > 0) n / d else n
    }

    /** Grams per ONE of the unit in the plainest of several labelled portions. */
    private fun plainest(portions: List<Portion>): Double {
        for (want in listOf("medium", "regular")) {
            portions.firstOrNull { it.label.contains(want, ignoreCase = true) }
                ?.let { return it.grams / labelAmount(it.label).coerceAtLeast(0.01) }
        }
        val p = portions.minBy { it.label.length }
        return p.grams / labelAmount(p.label).coerceAtLeast(0.01)
    }

    /** Portions that are a piece OF the food, never the food itself. */
    private val PART_WORDS = listOf("strip", "slice", "piece", "stick", "cup", "chip", "chunk", "tbsp", "tsp",
        "tablespoon", "teaspoon", "oz", "cubic", "wedge", "leaf", "segment", "floweret", "spear", "pat", "sprig", "serving")

    /** A portion that is one of the food itself ("1 medium pancake", "1 fruit"). */
    private fun itemPortion(food: FoodCandidate): Double? {
        // USDA's generic words for one whole one: "1 medium", "1 fruit", "1 egg".
        val whole = food.portions.filter { p ->
            val l = p.label.lowercase()
            PART_WORDS.none { Regex("\\b$it").containsMatchIn(l) }
        }
        whole.firstOrNull { it.label.lowercase().startsWith("1 medium") }?.let { return it.grams }
        whole.firstOrNull { Regex("^1 (fruit|whole|each|egg)\\b").containsMatchIn(it.label.lowercase()) }?.let { return it.grams }
        val nouns = Regex("[a-z]+").findAll(FoodQuery.headOf(food.name))
            .map { FoodQuery.stem(it.value) }.filter { it.length > 2 }.toSet()
        if (nouns.isEmpty()) return null
        val named = food.portions.filter { portion ->
            val low = portion.label.lowercase()
            val words = Regex("[a-z]+").findAll(low).map { it.value }.toList()
            if (words.none { FoodQuery.stem(it) in nouns }) return@filter false
            val sized = FoodQuery.SIZE_WORDS.any { low.contains(it) }
            sized || words.none { FoodQuery.stem(it) !in nouns }
        }
        return if (named.isEmpty()) null else plainest(named)
    }
}
