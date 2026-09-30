package com.nutritionscribe.shared.resolve

import com.nutritionscribe.shared.food.FoodQuery

/**
 * Turns what someone said into a list of foods, each with ITS OWN quantity.
 *
 * The old path parsed one quantity per sentence and then split, so the number
 * landed on whichever food happened to be first: "chicken, rice, and two cups of
 * milk" could give the chicken the two. Here the sentence is split first and every
 * fragment reads its own amount, and fragments that are only an amount ("milk,
 * two cups, whole milk") or only a description ("corn cob, microwaved") are folded
 * into the food next to them rather than looked up as foods of their own.
 *
 * Every rule below came from a real dictated entry.
 */
/** Words as the label pack indexes them (see LabelPackSource.words). */
internal object LabelWords {
    fun of(text: String) = LabelPackSource.words(text)
}

object ItemParser {

    data class Item(
        /** The fragment as it was said, for showing back. */
        val said: String,
        /** Food words only: no amount, no filler. What gets searched. */
        val food: String,
        /** Null when no amount was said, which is NOT the same as one. */
        val amount: Double? = null,
        /** Canonical unit (see [UNITS]), or null for a bare count / nothing. */
        val unit: String? = null,
        /** small / medium / large, when said. */
        val size: String? = null,
        /** "20g protein shake": the protein they stated for this item. */
        val statedProteinG: Double? = null,
        /** "more than a serving" — a nudge on top of the amount. */
        val scale: Double = 1.0,
        /** "some ketchup", "a little syrup": a topping-sized amount, not a serving. */
        val topping: Boolean = false,
        /** Word said right before "brand", or a known brand word. */
        val brand: String? = null,
        /** Set when a parenthesised list broke a named thing into parts. */
        val group: String? = null,
        /** "jam on my pancakes", "peanut butter on toast": what it was on. */
        val onTopOf: String? = null,
    )

    /** Canonical unit for every way of saying it. */
    val UNITS: Map<String, String> = buildMap {
        fun add(canonical: String, vararg forms: String) = forms.forEach { put(it, canonical) }
        add("g", "g", "gram", "grams", "gr")
        add("oz", "oz", "ounce", "ounces")
        add("lb", "lb", "lbs", "pound", "pounds")
        add("ml", "ml", "milliliter", "milliliters", "millilitre", "millilitres")
        add("cup", "cup", "cups")
        add("tbsp", "tbsp", "tablespoon", "tablespoons", "tbs", "spoonful", "spoonfuls")
        add("tsp", "tsp", "teaspoon", "teaspoons")
        add("slice", "slice", "slices")
        add("piece", "piece", "pieces")
        add("serving", "serving", "servings", "portion", "portions")
        add("scoop", "scoop", "scoops")
        add("can", "can", "cans")
        add("bottle", "bottle", "bottles")
        add("bag", "bag", "bags")
        add("bowl", "bowl", "bowls")
        add("plate", "plate", "plates", "plateful")
        add("glass", "glass", "glasses")
        add("handful", "handful", "handfuls")
        add("stalk", "stalk", "stalks")
        add("stick", "stick", "sticks")
        add("container", "container", "containers", "cup-container", "tub", "carton")
        add("packet", "packet", "packets", "pack", "packs", "package", "pouch", "sleeve", "sleeves", "snack pack")
        add("cob", "cob", "cobs", "ear", "ears")
        add("pat", "pat", "pats")
        add("strip", "strip", "strips", "rasher", "rashers")
        add("link", "link", "links")
        add("square", "square", "squares")
        add("sheet", "sheet", "sheets")
        add("fillet", "fillet", "fillets", "filet", "filets")
        add("sprinkle", "sprinkle", "dash", "splash", "drizzle", "pinch")
    }

    private val NUMBER_WORDS = mapOf(
        "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0,
        "six" to 6.0, "seven" to 7.0, "eight" to 8.0, "nine" to 9.0, "ten" to 10.0,
        "eleven" to 11.0, "twelve" to 12.0, "a" to 1.0, "an" to 1.0, "half" to 0.5,
        "quarter" to 0.25, "third" to 1.0 / 3, "couple" to 2.0, "pair" to 2.0,
        "few" to 3.0, "several" to 3.0, "dozen" to 12.0, "both" to 2.0,
    )

    private val SIZES = listOf("extra large", "jumbo", "large", "big", "medium", "regular",
        "small", "smallish", "little", "mini", "miniature")

    /**
     * Things that are ONE food even though "and" is in the middle. Split, "mac and
     * cheese" matched Big Mac, and "PB and J" became peanut butter plus a letter.
     */
    private val COMPOUNDS = listOf(
        "peanut butter and jelly sandwich", "peanut butter and jelly", "peanut butter and jam",
        "pb and j", "pb & j", "pb&j", "pbj", "pb and js", "pb&js",
        "macaroni and cheese", "mac and cheese", "mac n cheese", "mac & cheese",
        "half and half", "half & half", "ham and cheese", "rice and beans", "beans and rice",
        "fish and chips", "chips and salsa", "chicken and waffles", "biscuits and gravy",
        "sweet and sour", "salt and pepper", "cookies and cream", "cookies n cream",
        "spaghetti and meatballs", "pork and beans", "franks and beans", "surf and turf",
        "bread and butter", "chicken and dumplings", "hot and sour", "lox and bagel",
        "bacon egg and cheese", "sausage egg and cheese", "bacon, egg and cheese",
        "sausage, egg and cheese", "egg and cheese", "peaches and cream", "strawberries and cream",
    )

    /** How people shorten a food. Applied before anything else reads the words. */
    private val SYNONYMS = listOf(
        Regex("\\bpb ?(?:&|and|n) ?js?\\b") to "peanut butter and jelly sandwich",
        Regex("\\bpbjs?\\b") to "peanut butter and jelly sandwich",
        Regex("\\bpeanut butter and (?:jelly|jam)(?! sandwich)\\b") to "peanut butter and jelly sandwich",
        Regex("\\bpb\\b") to "peanut butter",
        Regex("\\bveggies\\b") to "vegetables",
        Regex("\\bveggie\\b") to "vegetable",
        Regex("\\boj\\b") to "orange juice",
        Regex("\\bhotdogs?\\b") to "hot dog",
        Regex("\\bmac n cheese\\b") to "mac and cheese",
        Regex("\\bcorn on the cob\\b") to "corn cob",
        Regex("\\bcorn cov\\b") to "corn cob",
        Regex("\\bplane\\b") to "plain",
        Regex("\\bwhile milk\\b") to "whole milk",
        Regex("\\bpeper\\b") to "pepper",
        Regex("\\bpukked\\b") to "pulled",
        Regex("\\bkula pork\\b") to "kalua pork",
        Regex("\\bgreek yoghurt\\b") to "greek yogurt",
        Regex("(?<!french )\\bfries\\b") to "french fries",
        Regex("\\bcorn on (?:a|the) cob\\b") to "corn cob",
        Regex("\\byoghurt\\b") to "yogurt",
    )

    /** Throat-clearing at the start of a fragment. Repeated until nothing changes. */
    private val LEADING_FILLER = Regex(
        "^(?:(?:and|then|also|so|but|plus|oh|um|uh|okay|ok|well|like|basically|actually)\\s+|" +
            "(?:i\\s+)?(?:also\\s+)?(?:just\\s+)?(?:had|ate|got|made|drank|finished|grabbed)\\s+|" +
            "(?:this|for)\\s+(?:morning|afternoon|evening|breakfast|lunch|dinner|supper|snack)\\s+(?:i\\s+)?(?:had\\s+|ate\\s+|was\\s+)?|" +
            "(?:breakfast|lunch|dinner|supper|snack)\\s+(?:was|is)\\s+|" +
            "(?:there\\s+was|there\\s+were|it\\s+was|it's|its|that\\s+was)\\s+(?:also\\s+)?|" +
            "(?:i\\s+(?:think|guess|believe|estimate)(?:\\s+that)?(?:\\s+it\\s+was)?)\\s+|" +
            "(?:kind\\s+of|sort\\s+of|about|approximately|roughly|around|maybe|probably|my|the|some|today)\\s+)",
    )

    /** Hedges in the middle of a fragment that carry nothing a search can use. */
    private val HEDGES = listOf(
        Regex("\\bi'?m not (?:\\d+% )?sure(?: yet)?\\b"),
        Regex("\\bof a size of\\b"), Regex("\\bi (?:think|guess|believe|estimate)\\b"),
        Regex("\\bor (?:yeah|so)\\b"), Regex("\\bthe one (?:like )?with the \\w+\\b"),
        Regex("\\bfrom grandma\\s+\\w+'?s?\\b"), Regex("\\bfrom \\w+'s\\b"),
        Regex("\\bon (?:it|them|top)\\b"), Regex("\\bon top\\b"),
        Regex("\\bi think they had(?: maybe)?\\b"), Regex("\\bthey had(?: maybe)?\\b"),
        Regex("\\b(?:that |which )?(?:mostly |mainly |basically )?(?:consisted|consists) (?:of|with)\\b"),
        Regex("\\bmostly\\b"), Regex("\\bit'?s\\b"),
    )

    /** What separates one food from the next in a sentence. */
    private val SEPARATOR = Regex(
        "\\s*(?:,|;|\\.(?=\\s|$)|\\n|\\s&\\s|\\+|\\band then\\b|\\bthen\\b|\\bas well as\\b|" +
            // "two eggs fried in a teaspoon of olive oil": the oil is its own food.
            "(?<=\\b(?:fried|cooked|scrambled|sauteed|sautéed|drizzled|tossed|topped|dipped|spread))\\s+(?:in|with)\\b|" +
            "\\balong with\\b|\\bplus\\b|\\band\\b|\\bwith\\b|\\balso\\b|\\bbut\\b)\\s*",
    )

    /** Words in a fragment that make it a description of the thing before it. */
    private val DESCRIPTOR_WORDS = setOf(
        "microwaved", "grilled", "baked", "steamed", "fried", "boiled", "roasted", "raw",
        "cooked", "plain", "homemade", "fresh", "frozen", "leftover", "cold", "warm", "hot",
        "sized", "size", "smallish", "medium", "large", "small", "big", "regular", "brand",
        "each", "it", "them", "those", "that", "this", "decent", "amount", "lot", "bit",
        "whole", "wheat", "white", "toasted", "scrambled", "poached", "over", "easy",
        "dry", "uncooked", "prepared", "mix", "cooked", "boxed", "frozen",
    )

    /**
     * A lone qualifier after a category word names WHICH one: "cheese, mozzarella".
     * Listed per category, because "rice, chicken" is two foods.
     */
    private val KINDS = mapOf(
        "cheese" to setOf("mozzarella", "cheddar", "swiss", "american", "parmesan", "provolone",
            "feta", "cottage", "cream", "string", "colby", "gouda", "ricotta", "goat", "jack", "brie"),
        "milk" to setOf("whole", "skim", "nonfat", "lowfat", "chocolate", "almond", "oat", "soy"),
        "bread" to setOf("white", "wheat", "sourdough", "rye", "multigrain", "whole"),
        "yogurt" to setOf("greek", "vanilla", "plain", "strawberry", "nonfat"),
        "rice" to setOf("white", "brown", "fried", "jasmine", "basmati", "wild"),
        "tortilla" to setOf("flour", "corn"),
        "jam" to setOf("raspberry", "strawberry", "grape", "apricot"),
        "jelly" to setOf("grape", "strawberry", "raspberry"),
    )

    /** Parts that come with the thing before them: a burger's bun is not extra food. */
    private val INHERENT = mapOf(
        "hamburger" to setOf("bun", "patty", "tomato", "lettuce", "pickle", "onion"),
        "cheeseburger" to setOf("bun", "patty", "tomato", "lettuce", "pickle", "onion", "cheese"),
        "burger" to setOf("bun", "patty", "tomato", "lettuce", "pickle", "onion"),
        "sandwich" to setOf("bread", "slice"),
        "dog" to setOf("bun"),
        "taco" to setOf("shell", "tortilla"),
        "burrito" to setOf("tortilla"),
    )

    /** Toppings: "some ketchup" means a spoonful, not a USDA serving of it. */
    private val TOPPINGS = setOf(
        "ketchup", "mustard", "mayo", "mayonnaise", "syrup", "honey", "jam", "jelly",
        "preserve", "butter", "margarine", "dressing", "sauce", "salsa", "relish", "gravy",
        "hummus", "cream", "sugar", "creamer", "sriracha", "hot sauce", "bbq", "barbecue",
        "vinegar", "oil", "guacamole", "sour cream", "cream cheese", "nutella", "spread",
        "milk", "halfnhalf", "whipped cream",
    )

    /**
     * Split and read every food in [raw].
     *
     * [isFoodWord] says whether a stemmed word names food anywhere the resolver can
     * look (USDA, the label pack, the personal cache). It is what tells "whole milk"
     * (a food) from "medium sized" (a description of the last one).
     */
    fun parse(
        raw: String,
        isFoodWord: (String) -> Boolean = { true },
        isBrandWord: (String) -> Boolean = { false },
    ): List<Item> {
        brandCheck = isBrandWord
        var text = normaliseText(raw)

        // "bag of veggies (⅓ bell pepper, several baby carrots, 1.5 stalks of celery)":
        // the brackets ARE the bag. Logging both would count the vegetables twice.
        val groups = mutableListOf<Pair<String, String>>()
        text = Regex("([^,;.()]*?)\\s*\\(([^)]*)\\)").replace(text) { m ->
            val name = m.groupValues[1].trim()
            val inner = m.groupValues[2]
            // "one pouch (2 bars)", "a bowl (about 2 cups)": the same amount said again.
            if (Regex("^\\s*(?:about|around|approx\\.?|like)?\\s*[\\d.]+\\s*[a-z]*\\s*$").matches(inner)) {
                name
            } else if (SEPARATOR.containsMatchIn(inner)) {
                groups += name to inner
                " \u0001${groups.size - 1}\u0001 "
            } else {
                "$name $inner"
            }
        }

        // "oatmeal made with 1/2 cup oats and 1 cup of milk": the ingredients ARE
        // the oatmeal. Logging the dish as well counted the oats twice.
        text = Regex("\\b(?:(?!and\\b)[a-z']+\\s+)?(?!and\\b)[a-z']+\\s+(?:made|cooked|prepared)\\s+(?:with|from|using)\\s+").replace(text, " ")
        // Hedges go before splitting: "the one with the bear" must not split on "with".
        for (h in HEDGES) text = h.replace(text, " ")
        // "it was kind of like a sandwich basically it was two pieces of bread...":
        // the rewording replaces what came before it in the same clause.
        text = Regex("[^,.;()]*?\\b(?:basically|i mean|actually|well)\\s+(?:it was|they were|it's|it is)\\s+").replace(text, " ")

        // "a kind dark chocolate nuts and sea salt bar" is one product: a phrase that
        // opens with a brand keeps its "and" when nothing after it has an amount.
        val protected = protectBrandedAnd(protectCompounds(text))
        val fragments = SEPARATOR.split(protected).map { restoreCompounds(it).trim() }
            .filter { it.isNotBlank() }
            .flatMap { splitOnSecondAmount(it) }

        val items = mutableListOf<Item>()
        for (fragment in fragments) {
            val marker = Regex("\u0001(\\d+)\u0001").find(fragment)
            if (marker != null) {
                val (groupName, inner) = groups[marker.groupValues[1].toInt()]
                val before = fragment.substring(0, marker.range.first)
                val label = cleanFood("$before $groupName").ifBlank { groupName }
                for (part in SEPARATOR.split(protectCompounds(inner)).map { restoreCompounds(it).trim() }) {
                    if (part.isBlank()) continue
                    readFragment(part, isFoodWord)?.let { items += it.copy(group = label) }
                }
                continue
            }
            val item = readFragment(fragment, isFoodWord) ?: continue
            fold(items, item.copy(onTopOf = null), isFoodWord)
            // "jam on my pancakes": the pancakes are already here. "peanut butter on
            // toast": the toast is not, so it is a food of its own.
            item.onTopOf?.let { base ->
                val baseWords = FoodQuery.contentWords(base)
                val known = baseWords.isNotEmpty() && items.any { i -> FoodQuery.contentWords(i.food).containsAll(baseWords) }
                if (!known && baseWords.isNotEmpty()) readFragment(base, isFoodWord)?.let { fold(items, it, isFoodWord) }
            }
        }
        return openUpGenericDishes(items.filter { FoodQuery.contentWords(it.food).isNotEmpty() })
    }

    /**
     * "two sandwiches, one piece of deli meat each, white bread": a generic dish
     * followed by what was in it. Logging "Sandwich, NFS" AND the parts counted the
     * meat twice, and 1330 calories came out of a 390-calorie lunch. When the parts
     * are named, the parts are the meal — one set per sandwich, with the bread a
     * sandwich implies if they did not mention it.
     */
    private fun openUpGenericDishes(items: List<Item>): List<Item> {
        val i = items.indexOfFirst { item ->
            val w = FoodQuery.contentWords(item.food)
            w.isNotEmpty() && w.last() in DISHES && items.indexOf(item) < items.lastIndex &&
                // A generic dish, or a named one whose own ingredient is listed after
                // it with an amount ("a peanut butter sandwich with 2 tbsp peanut butter").
                ((w.size == 1 && w.last() != "toast") || items.drop(items.indexOf(item) + 1).any { part ->
                    val pw = FoodQuery.contentWords(part.food)
                    part.amount != null && pw.isNotEmpty() && pw.any { it in w } && pw.all { it in w || brandCheck(it) }
                })
        }
        if (i < 0) return items
        val dish = items[i]
        val dishWords = FoodQuery.contentWords(dish.food)
        val kind = dishWords.last()
        val count = dish.amount ?: 1.0
        val parts = items.subList(i + 1, items.size)
        val opened = parts.map { p ->
            val isBread = FoodQuery.contentWords(p.food).lastOrNull() in BREADS
            when {
                isBread && p.amount == null -> p.copy(amount = 2.0 * count, unit = "slice")
                p.amount != null && p.unit !in WEIGHT_UNITS && count > 1 -> p.copy(amount = p.amount * count)
                p.amount == null && !p.topping && count > 1 -> p.copy(amount = count)
                else -> p
            }
        }.toMutableList()
        // What the dish's name says it had that the parts do not mention: the jelly
        // in "peanut butter and jelly sandwich with 2 tbsp peanut butter".
        val mentioned = opened.flatMap { FoodQuery.contentWords(it.food) }.toSet()
        val missing = dishWords.dropLast(1).filter { it !in mentioned && it !in setOf("and") }
        if (missing.isNotEmpty() && dishWords.size > 1) {
            opened += Item(said = dish.said, food = missing.joinToString(" "), topping = true)
        }
        if (kind in setOf("sandwich", "wrap", "burrito") && opened.none { FoodQuery.contentWords(it.food).lastOrNull() in BREADS }) {
            opened.add(0, when (kind) {
                "sandwich" -> Item(said = dish.said, food = "bread", amount = 2.0 * count, unit = "slice")
                else -> Item(said = dish.said, food = "flour tortilla", amount = count)
            })
        }
        return items.subList(0, i) + opened.map { it.copy(group = it.group ?: dish.said) }
    }

    private val DISHES = setOf("sandwich", "salad", "wrap", "bowl", "burrito", "toast")
    private val BREADS = setOf("bread", "toast", "bun", "roll", "tortilla", "bagel", "pita")

    /** Lowercase, unicode and slash fractions, "a cup and a half", typo fixes. */
    fun normaliseText(raw: String): String {
        var t = " " + raw.lowercase().replace('’', '\'').replace('\n', ',') + " "
        // "90/10 ground beef", "80/20": a lean ratio, which the fraction reader
        // would turn into 9.
        t = Regex("\\b([5-9]\\d)\\s*/\\s*([1-4]?\\d)\\b").replace(t, " lean ")
        // "half and half" is a cream, not two halves.
        t = Regex("\\bhalf\\s*(?:and|&|n)\\s*half\\b").replace(t, " halfnhalf ")
        t = FoodQuery.normaliseNumbers(t)
        // "one and a half cups" must not split on its "and".
        t = Regex("\\b(one|two|three|four|five|\\d+)\\s+and\\s+(?:a|one)\\s+half\\b").replace(t) { m ->
            val n = m.groupValues[1].toDoubleOrNull() ?: NUMBER_WORDS[m.groupValues[1]] ?: 1.0
            " ${n + 0.5} "
        }
        for ((pattern, replacement) in SYNONYMS) t = pattern.replace(t, replacement)
        t = fatLevels(t)
        return Regex("\\s+").replace(t, " ").trim()
    }

    /**
     * "4% cottage cheese", "0% fat greek yogurt", "2% milk" name a USDA row by its
     * fat level. Left as a percentage, "4" was read as a count and "%" vanished.
     */
    private fun fatLevels(text: String): String =
        Regex("(\\d+(?:\\.\\d+)?)\\s*%\\s*(?:fat\\b|milkfat\\b)?").replace(text) { m ->
            val pct = m.groupValues[1].toDoubleOrNull() ?: return@replace m.value
            val after = text.substring(m.range.last + 1).take(30)
            val before = text.substring(0, m.range.first).takeLast(30)
            val context = "$before $after"
            when {
                "cottage" in context -> if (pct >= 4) " creamed " else if (pct >= 2) " lowfat 2% " else " lowfat 1% "
                "milk" in context || "yogurt" in context || "cheese" in context -> when {
                    pct < 0.5 -> " nonfat "
                    pct < 1.5 -> " lowfat "
                    pct < 2.5 -> " reduced fat "
                    else -> " whole "
                }
                // "90% lean", "100% whole wheat": not a fat level, and not an amount.
                else -> " "
            }
        }

    private fun protectCompounds(text: String): String {
        var t = text
        for (c in COMPOUNDS.sortedByDescending { it.length }) {
            t = Regex("\\b${Regex.escape(c)}\\b").replace(t) {
                it.value.replace(",", "\u0003").replace(" and ", "\u0002").replace(" & ", "\u0004")
                    .replace("&", "\u0005").replace(" with ", "\u0006")
            }
        }
        return t
    }

    private fun protectBrandedAnd(text: String): String =
        Regex("(?:^|(?<=[,;.] ))((?:an? )?([a-z']+)\\b[^,;.]*?) and ([^,;.]+)").replace(text) { m ->
            val lead = m.groupValues[2]
            val tail = m.groupValues[3]
            val branded = LabelWords.of(lead).let { w -> w.isNotEmpty() && w.all { brandCheck(it) } }
            if (branded && !Regex("\\d|\\b(?:a|an|one|two|some)\\b").containsMatchIn(tail)) {
                "${m.groupValues[1]}\u0002$tail"
            } else m.value
        }

    private fun restoreCompounds(text: String): String = text.replace("\u0002", " and ")
        .replace("\u0003", ",").replace("\u0004", " & ").replace("\u0005", "&").replace("\u0006", " with ")

    /**
     * "one pork chop a serving of rice": two foods with no "and" between them. A new
     * amount straight after a food word starts a new food. An amount with nothing
     * after it ("carrot cake 2 servings") stays, and [fold] hands it back.
     */
    private fun splitOnSecondAmount(fragment: String): List<String> {
        val tokens = fragment.split(" ").filter { it.isNotBlank() }
        val cuts = mutableListOf<Int>()
        for (i in 1 until tokens.size) {
            // "dave's killer bread 21 whole grains", "chick-fil-a 8 count nuggets":
            // after a brand, a number is part of the product's name.
            if (tokens.subList(0, i).any { t -> FoodQuery.contentWords(t).any { brandCheck(it) } || t.contains("'s") && i < 4 }) break
            val tok = tokens[i]
            val prev = tokens[i - 1]
            val next = tokens.getOrNull(i + 1)
            val isNumber = tok.toDoubleOrNull() != null || (tok in NUMBER_WORDS && tok !in setOf("a", "an", "half"))
            val articleUnit = tok in setOf("a", "an") && next != null && UNITS.containsKey(next)
            val prevIsFood = prev !in NUMBER_WORDS && !UNITS.containsKey(prev) && prev !in setOf("of", "about", "maybe", "approximately", "around", "roughly", "had", "ate", "was", "and", "with", "than", "like", "i")
                && prev.toDoubleOrNull() == null && FoodQuery.contentWords(prev).isNotEmpty()
            if ((isNumber || articleUnit) && prevIsFood && next != null) cuts += i
        }
        if (cuts.isEmpty()) return listOf(fragment)
        val out = mutableListOf<String>()
        var start = 0
        for (c in cuts) { out += tokens.subList(start, c).joinToString(" "); start = c }
        out += tokens.subList(start, tokens.size).joinToString(" ")
        return out
    }

    // Set per parse; the parser is single-threaded by construction (one call, one text).
    @Volatile private var brandCheck: (String) -> Boolean = { false }

    /** One fragment -> one item, or null when nothing food-like is left. */
    private fun readFragment(fragment: String, isFoodWord: (String) -> Boolean = { true }): Item? {
        var t = " ${fragment.trim()} "
        for (h in HEDGES) t = h.replace(t, " ")
        t = Regex("\\s+").replace(t, " ").trim().trim('.', ',', '!', '?', '"', '\'').trim()

        // "peanut butter and jelly sandwich which contained 2 tablespoons of peanut
        // butter": detail about the dish, not a second food. Logged as a second food
        // it counted the peanut butter twice.
        Regex("^(.*?)\\s+(?:which|that)\\s+(?:contained|contains|had|has|was made with|included)\\s+(.*)$").find(t)?.let { m ->
            val head = FoodQuery.contentWords(m.groupValues[1]).toSet()
            val tail = FoodQuery.contentWords(m.groupValues[2].replace(Regex("\\d+(?:\\.\\d+)?\\s*(?:g|grams?)\\s+(?:of\\s+)?protein"), ""))
            if (tail.isEmpty() || tail.all { it in head } || tail.all { it == "protein" }) {
                t = if (Regex("protein").containsMatchIn(m.groupValues[2])) "${m.groupValues[1]} ${m.groupValues[2]}" else m.groupValues[1]
            }
        }

        // Their own protein number, taken OUT so it is not read as a weight.
        val claim = FoodQuery.stripProteinClaim(t)
        var stated = claim.grams
        t = claim.text
        // "30 g of protein in those pancakes": a claim about the item, not a food.
        Regex("\\b(\\d+(?:\\.\\d+)?)\\s*(?:g|grams?)\\s+(?:of\\s+)?protein\\b").find(t)?.let { m ->
            stated = stated ?: m.groupValues[1].toDoubleOrNull()
            t = t.removeRange(m.range).trim()
        }
        // "protein shake 38g" / "shake, 38g protein" — trailing grams on a protein noun.
        Regex("^(.*\\b(?:bar|shake|drink|smoothie|yogurt|powder))\\s+(\\d+(?:\\.\\d+)?)\\s*(?:g|grams?)$").find(t)?.let { m ->
            stated = stated ?: m.groupValues[2].toDoubleOrNull()
            t = m.groupValues[1]
        }

        // "a greek yogurt that contains 20 g of protein" leaves "protein" behind; it
        // names the claim, not the food, unless it is the food ("protein shake").
        run {
            val words = FoodQuery.contentWords(t)
            if (stated != null && words.size > 1 && words.last() == "protein") {
                t = Regex("\\bprotein\\b(?!.*\\bprotein\\b)").replace(t, " ").trim()
            }
        }

        var scale = 1.0
        if (Regex("\\b(?:a )?(?:decent|good|generous|large|big) (?:amount|bit|helping|portion|serving) of\\b").containsMatchIn(t)) {
            scale = 1.5
            t = Regex("\\b(?:a )?(?:decent|good|generous|large|big) (?:amount|bit|helping|portion) of\\b").replace(t, " ")
        }
        if (Regex("\\b(?:more than|a little more than|a bit more than|or (?:a little )?(?:maybe )?more)\\b").containsMatchIn(t)) {
            scale = 1.25
        }
        t = Regex("\\b(?:more than|a little more than|a bit more than|or a little maybe more|or a little more|or maybe more|or more)\\b")
            .replace(t, " ")
        var onTopOf: String? = null
        Regex("\\bon (?:top of )?(?:my |the |his |her |their |some |a |an )?([a-z' ]+)$").find(t)?.let { m ->
            onTopOf = m.groupValues[1].trim()
            t = t.substring(0, m.range.first).trim()
            // "on a bun" said on its own: the bun is the food.
            if (FoodQuery.contentWords(t).isEmpty() && Regex("\\d").find(t) == null) { t = onTopOf!!; onTopOf = null }
        }

        // "some ketchup", "a little bit of maple syrup": a topping-sized amount. The
        // phrase comes out entirely, or "a" reads as one serving and "little" as small.
        val toppingPhrase = Regex("(?:^|\\s)(?:some|a (?:little|tiny)(?: bit)?(?: of)?|a bit of|a touch of|a dab of|a drizzle of|a splash of|a dash of|a smear of)\\s+")
        val topping = toppingPhrase.containsMatchIn(" $t")
        t = toppingPhrase.replace(" $t", " ").trim()

        t = stripFiller(t)
        // "a yogurt cup", "a ramen cup", "a fruit cup": the cup is the container.
        t = Regex("\\b(yogurt|yoghurt|ramen|noodles?|fruit|pudding|applesauce|oatmeal|cheerios|soup)\\s+cups?\\b").replace(t) { "${it.groupValues[1]} container" }
        val (amount0, unit0, size, rest0) = readQuantity(t)
        var amount = amount0
        var unit = unit0
        var rest = rest0
        if (unit == null && Regex("\\bcontainer\\b").containsMatchIn(rest)) {
            unit = "container"; amount = amount ?: 1.0; rest = rest.replace(Regex("\\bcontainer\\b"), " ")
        }
        // "mission brand flour tortillas", "protein pancakes from kodiak": a brand is
        // for the label search, and noise for everything else.
        var brand = Regex("\\b([a-z']+)\\s+brand\\b").find(rest)?.groupValues?.get(1)
        var restNoBrand = rest
        brand?.let { b -> restNoBrand = Regex("\\b${Regex.escape(b)}\\s+brand\\b").replace(rest, " ") }
        // A chain: "wendy's baconator", "chick fil a nuggets".
        if (brand == null) {
            RESTAURANTS.firstOrNull { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(rest) }?.let { chain ->
                brand = chain
                restNoBrand = rest.replace(Regex("\\b${Regex.escape(chain)}\\b"), " ").replace(Regex("^\\s*(?:from|at)\\s+|\\s+(?:from|at)\\s*$"), " ")
            }
        }
        // A brand word said up front: "chobani plain greek yogurt", "a kind bar".
        if (brand == null) {
            val toks = rest.split(" ").filter { it.isNotBlank() }
            val lead = toks.takeWhile { t -> LabelWords.of(t).let { w -> w.isNotEmpty() && w.all { brandCheck(it) } } }
            if (lead.isNotEmpty() && lead.size < toks.size) {
                brand = lead.joinToString(" ")
                restNoBrand = toks.drop(lead.size).joinToString(" ")
            }
        }
        Regex("\\bfrom\\s+([a-z']+)").find(restNoBrand)?.let { m ->
            val w = m.groupValues[1]
            val stem = FoodQuery.contentWords(w).firstOrNull()
            if (brand == null && stem != null && (brandCheck(stem) || !isFoodWord(stem))) {
                brand = w
                restNoBrand = restNoBrand.removeRange(m.range)
            }
        }
        val food = dedupeWords(cleanFood(restNoBrand)).split(" ")
            .filter { w -> w.any { it.isDigit() } || FoodQuery.contentWords(w).isNotEmpty() || w in KEEP_WORDS }
            .joinToString(" ")
        // "mission brand", "medium sized": nothing to search, but something to know.
        if (food.isBlank() && amount == null && stated == null && brand == null && size == null) return null
        return Item(
            said = fragment.trim(),
            food = food,
            amount = amount,
            unit = unit,
            size = size,
            statedProteinG = stated,
            scale = scale,
            topping = topping && amount == null && isTopping(food),
            brand = brand,
            onTopOf = onTopOf,
        )
    }

    /** Chains, said as people say them. Their food is never in a package to scan. */
    val RESTAURANTS = listOf(
        "mcdonald's", "mcdonalds", "wendy's", "wendys", "burger king", "taco bell", "chick-fil-a",
        "chick fil a", "chickfila", "chipotle", "starbucks", "subway", "kfc", "popeyes", "domino's",
        "dominos", "pizza hut", "dunkin'", "dunkin", "panera", "five guys", "in-n-out", "in n out",
        "arby's", "arbys", "sonic", "jack in the box", "dairy queen", "panda express", "olive garden",
        "applebee's", "whataburger", "culver's", "raising cane's", "shake shack", "jimmy john's",
        "jersey mike's", "little caesars", "papa john's", "qdoba", "wingstop", "zaxby's", "bojangles",
    )

    /** Short words that are filler to a search but part of a food's name. */
    private val KEEP_WORDS = setOf("and", "&", "hot")

    private fun stripFiller(text: String): String {
        var t = text.trim()
        while (true) {
            val next = LEADING_FILLER.replaceFirst(t, "").trim()
            if (next == t || next.isEmpty()) return t
            t = next
        }
    }

    private data class Quantity(val amount: Double?, val unit: String?, val size: String?, val rest: String)

    /**
     * The amount in a fragment, wherever it is: "2 cups milk", "milk 2 cups",
     * "a cup of milk", "half a serving of cheese", "1/3 of a pork chop".
     */
    private fun readQuantity(text: String): Quantity {
        val tokens = text.split(" ").filter { it.isNotBlank() }.toMutableList()
        if (tokens.isEmpty()) return Quantity(null, null, null, text)

        fun numberAt(i: Int): Double? {
            val tok = tokens.getOrNull(i) ?: return null
            tok.toDoubleOrNull()?.let { return it }
            Regex("^(\\d+(?:\\.\\d+)?)([a-z]+)$").find(tok)?.let { m ->
                if (UNITS.containsKey(m.groupValues[2])) return m.groupValues[1].toDouble()
            }
            return NUMBER_WORDS[tok]
        }

        // "8oz" -> "8 oz"
        for (i in tokens.indices.reversed()) {
            Regex("^(\\d+(?:\\.\\d+)?)([a-z]+)$").find(tokens[i])?.let { m ->
                if (UNITS.containsKey(m.groupValues[2])) {
                    tokens[i] = m.groupValues[1]
                    tokens.add(i + 1, m.groupValues[2])
                }
            }
        }

        var size: String? = null
        var best: Triple<Int, Int, Pair<Double, String?>>? = null // start, end(excl), amount/unit
        for (i in tokens.indices) {
            var n = numberAt(i) ?: continue
            if ((tokens[i] == "a" || tokens[i] == "an") && i != 0 && best != null) continue
            var j = i + 1
            // "half a dozen", "a half", "one and a half", "a dozen"
            if (tokens.getOrNull(j) == "dozen") { n *= 12; j++ }
            if (tokens.getOrNull(j) == "and" && tokens.getOrNull(j + 1) in setOf("a", "one") && tokens.getOrNull(j + 2) == "half") {
                n += 0.5; j += 3
            }
            if (tokens.getOrNull(j) == "half" && (tokens[i] == "a" || tokens[i] == "an")) { n = 0.5; j++ }
            if (tokens.getOrNull(j) in setOf("of", "a", "an") && tokens.getOrNull(j + 1)?.let { UNITS.containsKey(it) || numberAt(j + 1) != null } == true &&
                tokens[i] != "a" && tokens[i] != "an") j++
            if (tokens.getOrNull(j) in setOf("a", "an") && tokens.getOrNull(j + 1)?.let { UNITS.containsKey(it) } == true) j++
            var sizeHere: String? = null
            SIZES.firstOrNull { tokens.getOrNull(j) == it }?.let { sizeHere = it; j++ }
            if (tokens.getOrNull(j) == "sized" || tokens.getOrNull(j) == "size") j++
            val unit = tokens.getOrNull(j)?.let { UNITS[it] }
            if (unit != null) j++
            if (tokens.getOrNull(j) == "of") j++
            if (tokens.getOrNull(j) in setOf("a", "an") && unit == null) j++
            // "a" alone only counts at the very start: "a banana" is one banana, but
            // the "a" in "rice with a bit" is not a quantity worth reading.
            if ((tokens[i] == "a" || tokens[i] == "an") && i != 0 && unit == null) continue
            val candidate = Triple(i, j, n to unit)
            // A number WITH a unit beats a bare number; the earlier wins a tie.
            if (best == null || (best.third.second == null && unit != null)) {
                best = candidate
                size = sizeHere ?: size
            }
            if (unit != null) break
        }

        if (best == null) {
            size = SIZES.firstOrNull { s -> Regex("\\b$s\\b").containsMatchIn(text) }
            // "my typical bag of vegetables", "bowl of cereal": a unit with no number
            // is still one of that unit, and the unit is what sizes it.
            val u = tokens.indexOfFirst { UNITS.containsKey(it) && it !in setOf("can", "pack") }
            if (u >= 0 && tokens.getOrNull(u + 1) == "of") {
                val rest = (tokens.subList(0, u) + tokens.subList(u + 2, tokens.size)).joinToString(" ")
                return Quantity(1.0, UNITS[tokens[u]], normaliseSize(size), rest)
            }
            return Quantity(null, null, normaliseSize(size), text)
        }
        val (start, end, amountUnit) = best
        val rest = (tokens.subList(0, start) + tokens.subList(end, tokens.size)).joinToString(" ")
        size = size ?: SIZES.firstOrNull { s -> Regex("\\b$s\\b").containsMatchIn(rest) }
        var amount = amountUnit.first
        // "1/3 of a pork chop" parses as 0.333 then "of a" — already consumed above.
        if (amount <= 0) amount = 1.0
        return Quantity(amount, amountUnit.second, normaliseSize(size), rest)
    }

    private fun normaliseSize(size: String?): String? = when (size) {
        null -> null
        "big", "jumbo", "extra large" -> "large"
        "little", "smallish", "mini", "miniature" -> "small"
        "regular" -> "medium"
        else -> size
    }

    /** The words a search should see: no filler, no size, no stray punctuation. */
    fun cleanFood(text: String): String {
        var t = " $text "
        t = Regex("\\b(?:brand|sized|size|of it|of them|itself|each|those|these|was|were|is|in|on|my|the|some|a|an|of|that|which|contained|contains|containing|had|have)\\b").replace(t, " ")
        t = Regex("[^a-z0-9&' -]").replace(t, " ")
        t = Regex("\\b(?:${SIZES.joinToString("|")})\\b").replace(t, " ")
        return Regex("\\s+").replace(t, " ").trim().trim('\'', '-').trim()
    }

    /** "tortillas flour tortillas" -> "flour tortillas": a food said twice in one breath. */
    private fun dedupeWords(food: String): String {
        val words = food.split(" ").filter { it.isNotBlank() }
        val out = mutableListOf<String>()
        for ((i, w) in words.withIndex()) {
            val stem = FoodQuery.stem(w)
            if (words.drop(i + 1).any { FoodQuery.stem(it) == stem }) continue
            out += w
        }
        return out.joinToString(" ")
    }

    private fun isTopping(food: String): Boolean {
        val words = FoodQuery.contentWords(food)
        return TOPPINGS.any { top -> top.split(" ").all { w -> FoodQuery.stem(w) in words } }
    }

    /**
     * Add [next] to [items], or fold it into the item before it when it is only a
     * quantity, only a description, a repeat of the same food, or a part the
     * previous food already includes.
     */
    private fun fold(items: MutableList<Item>, next: Item, isFoodWord: (String) -> Boolean) {
        val prev = items.lastOrNull()
        val words = FoodQuery.contentWords(next.food)

        if (prev != null) {
            val prevWords = FoodQuery.contentWords(prev.food)

            // "milk, two cups" — an amount with no food of its own. A weight wins over
            // a rougher amount: "half a block of tofu, about 200 g" is 200 g.
            if (words.isEmpty() && next.amount != null) {
                if (prev.amount == null || next.unit in WEIGHT_UNITS) {
                    items[items.lastIndex] = prev.copy(amount = next.amount, unit = next.unit, size = next.size ?: prev.size)
                    return
                }
            }
            // "a bowl of spaghetti, about 2 cups pasta": the same food, measured again.
            if (next.amount != null && Regex("^(?:about|around|roughly|approximately|like|maybe|so)\\b").containsMatchIn(next.said.trim()) &&
                words.isNotEmpty() && prevWords.isNotEmpty() && sameKind(words.last(), prevWords.last())
            ) {
                items[items.lastIndex] = prev.copy(amount = next.amount, unit = next.unit)
                return
            }
            // "pop tarts frosted strawberry, both pastries": how many, in the thing's own word.
            if (next.amount != null && next.unit == null && words.isNotEmpty() && words.all { it in COUNT_NOUNS } &&
                prev.amount == null
            ) {
                items[items.lastIndex] = prev.copy(amount = next.amount)
                return
            }
            // "cottage cheese, the 4% kind": which one, not another food.
            if (next.amount == null && Regex("\\b(?:kind|type|version|variety|style)$").containsMatchIn(next.said.trim().trimEnd('.'))) {
                val extra = next.food.split(" ").filter { w -> w !in setOf("kind", "type", "version", "variety", "style") }.joinToString(" ")
                items[items.lastIndex] = prev.copy(food = "${prev.food} $extra".trim())
                return
            }
            // "... 30 g of protein in those pancakes": a claim with nothing new in it.
            if (next.statedProteinG != null && (words.isEmpty() || words.all { it in prevWords || it == "protein" })) {
                items[items.lastIndex] = prev.copy(statedProteinG = prev.statedProteinG ?: next.statedProteinG)
                return
            }
            // "protein drink with milk, the protein drink itself is 30 g of protein":
            // the claim is about an earlier item, and whatever else is left is its own food.
            if (next.statedProteinG != null) {
                val owner = items.indices.reversed().firstOrNull { i ->
                    val w = FoodQuery.contentWords(items[i].food)
                    w.isNotEmpty() && w.all { it in words } && items[i].statedProteinG == null
                }
                if (owner != null) {
                    val ownerWords = FoodQuery.contentWords(items[owner].food).toSet()
                    items[owner] = items[owner].copy(statedProteinG = next.statedProteinG)
                    val leftover = next.food.split(" ").filter { w ->
                        FoodQuery.contentWords(w).let { it.isNotEmpty() && it.none { s -> s in ownerWords } }
                    }.joinToString(" ")
                    if (leftover.isBlank() || items.any { FoodQuery.contentWords(it.food) == FoodQuery.contentWords(leftover) }) return
                    fold(items, next.copy(food = leftover, statedProteinG = null), isFoodWord)
                    return
                }
            }
            // "kraft mac and cheese, one serving of the dry mix": how much, and how it
            // was, of the food just said.
            if (next.amount != null && words.isNotEmpty() && words.all { it in DESCRIPTOR_WORDS } && prev.amount == null) {
                items[items.lastIndex] = prev.copy(food = "${prev.food} ${next.food}".trim(), amount = next.amount, unit = next.unit)
                return
            }
            // "corn cob, microwaved", "tortillas. mission brand, medium sized."
            val descriptive = words.isNotEmpty() && words.all { it in DESCRIPTOR_WORDS || it == next.brand }
            val unknown = words.isNotEmpty() && words.none { isFoodWord(it) }
            if ((descriptive || unknown || words.isEmpty()) && next.amount == null) {
                items[items.lastIndex] = prev.copy(
                    food = if (descriptive && words.any { it !in setOf("sized", "size", "brand") && it !in SIZE_STEMS }) {
                        "${prev.food} ${next.food}".trim()
                    } else prev.food,
                    size = next.size ?: prev.size,
                    brand = prev.brand ?: next.brand ?: if (unknown) next.food.takeIf { words.size <= 2 } else null,
                )
                return
            }
            // "milk ... whole milk", "tortillas ... flour tortillas": the same food said twice.
            if (words.isNotEmpty() && prevWords.isNotEmpty() && words.last() == prevWords.last() &&
                (prevWords.all { it in words } || words.all { it in prevWords })
            ) {
                val longer = if (words.size >= prevWords.size) next.food else prev.food
                items[items.lastIndex] = prev.copy(
                    food = longer,
                    amount = prev.amount ?: next.amount,
                    unit = if (prev.amount != null) prev.unit else next.unit,
                    size = prev.size ?: next.size,
                    statedProteinG = prev.statedProteinG ?: next.statedProteinG,
                    brand = prev.brand ?: next.brand,
                )
                return
            }
            // "a serving of cheese, mozzarella" — which cheese.
            if (next.amount == null && words.size == 1 && prevWords.size == 1 &&
                KINDS[prevWords[0]]?.contains(words[0]) == true
            ) {
                items[items.lastIndex] = prev.copy(food = "${next.food} ${prev.food}")
                return
            }
            // "hamburger with bun, tomato, ketchup": the bun comes with the burger.
            val prevHead = prevWords.lastOrNull()
            val inherent = prevHead?.let { INHERENT[it] }
                ?: items.asReversed().firstNotNullOfOrNull { i -> FoodQuery.contentWords(i.food).lastOrNull()?.let { INHERENT[it] } }
            // Head noun is the part ("white bread" in a sandwich); the rest describes it.
            if (inherent != null && next.amount == null && words.isNotEmpty() && words.last() in inherent &&
                prevHead != "sandwich" && items.none { FoodQuery.contentWords(it.food) == listOf("sandwich") }
            ) {
                return
            }
        }
        items += next
    }

    private val SIZE_STEMS = setOf("small", "medium", "large", "big", "regular", "smallish")
    private val WEIGHT_UNITS = setOf("g", "oz", "lb")
    private val COUNT_NOUNS = setOf("pastry", "piece", "bar", "cookie", "cooky", "cracker", "slice", "patty",
        "link", "strip", "nugget", "wing", "taco", "one", "unit", "item")

    /** Words that name the same food: "spaghetti" is pasta, "coke" is soda. */
    private val KIND_OF = mapOf(
        "spaghetti" to "pasta", "noodle" to "pasta", "penne" to "pasta", "macaroni" to "pasta", "linguine" to "pasta",
        "coke" to "soda", "cola" to "soda", "pop" to "soda", "sprite" to "soda",
        "oatmeal" to "oat", "fry" to "potato",
    )

    private fun sameKind(a: String, b: String): Boolean = a == b || (KIND_OF[a] ?: a) == (KIND_OF[b] ?: b)
}
