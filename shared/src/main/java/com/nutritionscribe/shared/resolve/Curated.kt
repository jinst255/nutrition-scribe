package com.nutritionscribe.shared.resolve

import com.nutritionscribe.shared.model.Metric

/**
 * The seeded part of layer 2: what a common phrase means, decided once by a person
 * instead of re-decided by ranking on every lookup.
 *
 * Ranking finds the right food but often the wrong SENSE of it — "shredded
 * mozzarella cheese" ranked a caprese salad first, "4% cottage cheese" found
 * farmer's cheese, "fries" found fry sauce. Each entry names the USDA row AND the
 * description it expects; CuratedTest fails if a data refresh ever moves one.
 *
 * A few everyday things are not in USDA at all in the form people eat them — a
 * ready-to-drink protein shake is the big one — so those are typical label values,
 * marked as such in their name.
 */
object Curated {

    data class Entry(val phrase: String, val fdcId: Long?, val expect: String?, val custom: FoodCandidate? = null)

    private fun usda(phrase: String, fdcId: Long, expect: String) = Entry(phrase, fdcId, expect)

    private fun typical(
        phrase: String, name: String, grams: Double, servingText: String,
        kcal: Double, protein: Double, carbs: Double, fat: Double,
        sugar: Double? = null, fiber: Double? = null, satFat: Double? = null, sodium: Double? = null,
    ): Entry {
        val per = buildMap {
            put(Metric.CALORIES.key, kcal); put(Metric.PROTEIN.key, protein)
            put(Metric.CARBS.key, carbs); put(Metric.FAT.key, fat)
            sugar?.let { put(Metric.SUGAR.key, it) }; fiber?.let { put(Metric.FIBER.key, it) }
            satFat?.let { put(Metric.SATURATED_FAT.key, it) }; sodium?.let { put(Metric.SODIUM.key, it) }
        }.mapValues { it.value * 100.0 / grams }
        return Entry(
            phrase, null, null,
            FoodCandidate(
                id = "typical:${PersonalKeys.of(phrase)}", name = name, origin = Origin.CURATED,
                per100g = per, defaultGrams = grams, labelServingGrams = grams, labelServingText = servingText,
                portions = listOf(Portion("1 serving", grams)),
            ),
        )
    }

    val ENTRIES: List<Entry> = listOf(
        // Dairy
        usda("mozzarella cheese", 2341125, "Cheese, Mozzarella, NFS"),
        usda("shredded mozzarella", 170900, "Cheese, mozzarella, low moisture, part-skim, shredded"),
        usda("shredded mozzarella cheese", 170900, "Cheese, mozzarella, low moisture, part-skim, shredded"),
        usda("string cheese", 2341126, "Cheese, Mozzarella, part skim"),
        usda("creamed cottage cheese", 2341151, "Cheese, cottage, creamed"),
        usda("lowfat 2% cottage cheese", 172182, "Cheese, cottage, lowfat, 2% milkfat"),
        usda("lowfat 1% cottage cheese", 173417, "Cheese, cottage, lowfat, 1% milkfat"),
        usda("nonfat cottage cheese", 172181, "Cheese, cottage, nonfat"),
        usda("cream cheese", 2341163, "Cream cheese, regular, plain"),
        usda("halfnhalf", 2340996, "Cream, half and half"),
        usda("cheddar", 2341112, "Cheese, Cheddar"),
        usda("feta", 2341117, "Cheese, Feta"),
        usda("swiss cheese", 2341138, "Cheese, Swiss"),
        usda("swiss", 2341138, "Cheese, Swiss"),
        usda("sour cream", 171257, "Cream, sour, cultured"),
        usda("almond milk", 2340791, "Almond milk, unsweetened"),
        usda("unsweetened almond milk", 2340791, "Almond milk, unsweetened"),
        usda("greek yogurt", 2340804, "Yogurt, Greek, low fat milk, plain"),
        usda("nonfat greek yogurt", 2340805, "Yogurt, Greek, nonfat milk, plain"),
        usda("whole greek yogurt", 2340803, "Yogurt, Greek, whole milk, plain"),
        usda("high protein greek yogurt", 2340805, "Yogurt, Greek, nonfat milk, plain"),
        usda("protein yogurt", 2340805, "Yogurt, Greek, nonfat milk, plain"),
        usda("milk", 171265, "Milk, whole, 3.25% milkfat"),
        // Cereal sold by a one-word name the labels never print: the label pack
        // lists Life as "Original Multigrain Cereal", and "life" found Food For Life.
        usda("life cereal", 173891, "Cereals ready-to-eat, QUAKER, QUAKER OAT LIFE, plain"),
        usda("quaker life cereal", 173891, "Cereals ready-to-eat, QUAKER, QUAKER OAT LIFE, plain"),
        usda("cinnamon life cereal", 174620, "Cereals ready-to-eat, QUAKER, QUAKER OAT CINNAMON LIFE"),
        usda("cinnamon life", 174620, "Cereals ready-to-eat, QUAKER, QUAKER OAT CINNAMON LIFE"),
        usda("whole milk", 171265, "Milk, whole, 3.25% milkfat"),
        usda("reduced fat milk", 171267, "Milk, reduced fat, fluid, 2% milkfat"),
        usda("lowfat milk", 170872, "Milk, lowfat, fluid, 1% milkfat"),
        usda("nonfat milk", 171269, "Milk, nonfat, fluid"),
        usda("skim milk", 171269, "Milk, nonfat, fluid"),
        // Eggs, meat
        // Plain cooked egg with no fat added: "fried in butter" is said when it was.
        usda("egg", 2342629, "Egg, whole, boiled or poached"),
        usda("eggs", 2342629, "Egg, whole, boiled or poached"),
        usda("large eggs", 2342629, "Egg, whole, boiled or poached"),
        usda("scrambled eggs", 172187, "Egg, whole, cooked, scrambled"),
        usda("fried egg", 2342631, "Egg, whole, fried no added fat"),
        usda("hard boiled egg", 173424, "Egg, whole, cooked, hard-boiled"),
        usda("hot dog", 2342501, "Hot dog sandwich, NFS, on white bun"),
        usda("hot dog bun", 2342501, "Hot dog sandwich, NFS, on white bun"),
        usda("frankfurter", 2341575, "Hot dog, NFS"),
        usda("deli meat", 2341620, "Luncheon meat, NFS"),
        usda("lunch meat", 2341620, "Luncheon meat, NFS"),
        usda("deli ham", 2341621, "Ham, prepackaged or deli, luncheon meat"),
        usda("deli turkey", 2341630, "Turkey, prepackaged or deli, luncheon meat"),
        usda("turkey deli meat", 2341630, "Turkey, prepackaged or deli, luncheon meat"),
        usda("sliced turkey", 2341630, "Turkey, prepackaged or deli, luncheon meat"),
        usda("burger patty", 171797, "Beef, ground, 80% lean meat / 20% fat, patty, cooked, broiled"),
        usda("hamburger patty", 171797, "Beef, ground, 80% lean meat / 20% fat, patty, cooked, broiled"),
        usda("lean burger patty", 174031, "Beef, ground, 90% lean meat / 10% fat, patty, cooked, broiled"),
        usda("lean ground beef patty", 174031, "Beef, ground, 90% lean meat / 10% fat, patty, cooked, broiled"),
        usda("ground beef patty", 171797, "Beef, ground, 80% lean meat / 20% fat, patty, cooked, broiled"),
        usda("pulled pork", 173344, "Pulled pork in barbecue sauce"),
        usda("chicken breast", 2341362, "Chicken breast, baked, broiled, or roasted, skin not eaten"),
        usda("chicken breasts", 2341362, "Chicken breast, baked, broiled, or roasted, skin not eaten"),
        usda("chicken thigh", 2341436, "Chicken thigh, baked, broiled, or roasted, skin not eaten"),
        usda("chicken thighs", 2341436, "Chicken thigh, baked, broiled, or roasted, skin not eaten"),
        usda("ground turkey", 171506, "Turkey, Ground, cooked"),
        usda("salmon", 175168, "Fish, salmon, Atlantic, farmed, cooked"),
        usda("salmon fillet", 175168, "Fish, salmon, Atlantic, farmed, cooked"),
        usda("cod", 171956, "Fish, cod, Atlantic, cooked"),
        usda("shrimp", 175180, "Crustaceans, shrimp, cooked"),
        usda("tuna water", 173709, "Fish, tuna, light, canned in water, drained"),
        usda("canned tuna", 173709, "Fish, tuna, light, canned in water, drained"),
        usda("can tuna", 173709, "Fish, tuna, light, canned in water, drained"),
        usda("pork tenderloin", 168250, "Pork, fresh, loin, tenderloin, separable lean only, cooked, roasted"),
        usda("chicken", 2341340, "Chicken, NS as to part, baked, broiled, or roasted, skin not eaten"),
        // Bread, grains
        usda("white bread", 2343073, "Bread, white"),
        usda("wheat bread", 2343189, "Bread, whole wheat"),
        usda("whole wheat bread", 2343189, "Bread, whole wheat"),
        usda("toast", 2343074, "Bread, white, toasted"),
        usda("whole wheat toast", 2343190, "Bread, whole wheat, toasted"),
        usda("wheat toast", 2343190, "Bread, whole wheat, toasted"),
        usda("flour tortilla", 2343304, "Tortilla, flour"),
        usda("tortilla", 2343304, "Tortilla, flour"),
        usda("pancake", 2343787, "Pancakes, plain"),
        usda("pancakes", 2343787, "Pancakes, plain"),
        usda("casserole rice", 2345163, "Broccoli casserole with rice"),
        // Produce
        usda("bell pepper", 170427, "Peppers, sweet, green, raw"),
        usda("green pepper", 170427, "Peppers, sweet, green, raw"),
        usda("red pepper", 170108, "Peppers, sweet, red, raw"),
        usda("baby carrots", 168568, "Carrots, baby, raw"),
        usda("carrots", 170393, "Carrots, raw"),
        usda("celery", 169988, "Celery, raw"),
        usda("corn cob", 169999, "Corn, sweet, yellow, cooked, boiled, drained, without salt"),
        usda("corn", 169999, "Corn, sweet, yellow, cooked, boiled, drained, without salt"),
        usda("raisins", 2344707, "Raisins"),
        usda("dried raisins", 2344707, "Raisins"),
        usda("dried mango", 2344700, "Mango, dried"),
        usda("dried apricots", 2344692, "Apricot, dried"),
        usda("tomatoes", 2345232, "Tomatoes, raw"),
        usda("tomato", 2345232, "Tomatoes, raw"),
        usda("avocado", 2344719, "Avocado, raw"),
        usda("peach", 169928, "Peaches, yellow, raw"),
        usda("apple", 171688, "Apples, raw, with skin"),
        usda("pear", 169118, "Pears, raw"),
        usda("pineapple", 169124, "Pineapple, raw, all varieties"),
        usda("pineapple chunks", 169124, "Pineapple, raw, all varieties"),
        // Spreads and toppings
        usda("peanut butter", 2343014, "Peanut butter"),
        usda("jelly", 2345860, "Jelly"),
        usda("jam", 2345861, "Jam"),
        usda("raspberry jam", 2345861, "Jam"),
        usda("preserves", 2345861, "Jam"),
        usda("black currant preserve", 2345861, "Jam"),
        usda("maple syrup", 169661, "Syrups, maple"),
        usda("syrup", 169661, "Syrups, maple"),
        usda("ketchup", 2345246, "Ketchup"),
        // Dishes
        usda("peanut butter and jelly sandwich", 2343031, "Peanut butter and jelly sandwich, with regular peanut butter, regular jelly, on white bread"),
        usda("sandwich", 2342325, "Sandwich, NFS"),
        usda("carrot cake", 2343339, "Cake or cupcake, carrot"),
        usda("refried beans", 2342871, "Refried beans"),
        usda("black beans", 2342838, "Black beans, from canned, no added fat"),
        usda("chickpeas", 2342893, "Chickpeas, from canned, no added fat"),
        usda("lentils", 2342900, "Lentils, from dried, no added fat"),
        usda("baked potato", 170093, "Potatoes, baked, flesh and skin, without salt"),
        usda("potato", 170093, "Potatoes, baked, flesh and skin, without salt"),
        usda("baked sweet potato", 168483, "Sweet potato, cooked, baked in skin"),
        usda("sweet potato", 168483, "Sweet potato, cooked, baked in skin"),
        usda("french fries", 2344956, "Potato, french fries, restaurant"),
        usda("fries", 2344956, "Potato, french fries, restaurant"),
        usda("frosty", 170326, "WENDY'S, Frosty Dairy Dessert"),
        usda("vegetables", 170393, "Carrots, raw"),
        // Not in USDA as people buy them: typical label values.
        typical(
            "protein shake", "Protein shake, ready-to-drink (typical label)", 325.0, "1 bottle (11 fl oz)",
            kcal = 160.0, protein = 30.0, carbs = 5.0, fat = 3.0, sugar = 1.0, fiber = 1.0, satFat = 1.0, sodium = 230.0,
        ),
        typical(
            "protein drink", "Protein shake, ready-to-drink (typical label)", 325.0, "1 bottle (11 fl oz)",
            kcal = 160.0, protein = 30.0, carbs = 5.0, fat = 3.0, sugar = 1.0, fiber = 1.0, satFat = 1.0, sodium = 230.0,
        ),
        typical(
            "protein bar", "Protein bar (typical label)", 60.0, "1 bar (60 g)",
            kcal = 210.0, protein = 20.0, carbs = 22.0, fat = 8.0, sugar = 3.0, fiber = 5.0, satFat = 3.5, sodium = 200.0,
        ),
    )

    private val byKey: Map<String, Entry> by lazy { ENTRIES.associateBy { PersonalKeys.of(it.phrase) } }

    /** Words that describe how it was served, not what it is. */
    private val SERVING_WORDS = setOf("plain", "microwaved", "cooked", "boiled", "steamed", "fresh",
        "warm", "cold", "hot", "leftover", "homemade", "raw", "toasted", "baked", "grilled", "roasted",
        "fillet", "filet", "drained", "chunks", "sliced", "diced", "chopped", "whole", "medium", "large", "small")

    fun entryFor(food: String): Entry? {
        byKey[PersonalKeys.of(food)]?.let { return it }
        // "corn on the cob, plain" is still corn on the cob.
        val trimmed = food.split(" ").filter { it !in SERVING_WORDS }.joinToString(" ")
        return if (trimmed != food) byKey[PersonalKeys.of(trimmed)] else null
    }

    /** The curated answer for [food], resolved against [usda]; null if none applies. */
    /**
     * A soda-shop flavour shot ("raspberry flavoring"): not in USDA as people use
     * it. Typical of the coffee-and-soda syrups (2 tbsp, 80 kcal, 20 g sugar), as
     * one ½ fl oz shot.
     */
    private val FLAVOR_SYRUP = typical(
        "flavored syrup", "Flavor syrup, one shot (typical label)", 15.0, "1 shot (½ fl oz, 15 mL)",
        kcal = 40.0, protein = 0.0, carbs = 10.0, fat = 0.0, sugar = 10.0, sodium = 3.0,
    ).custom!!

    /**
     * A curated phrase said with its brand ("life cereal", where "life" reads as a
     * brand): only an exact entry, never the alias fallback.
     */
    fun exact(phrase: String, usda: UsdaFoodSource?): FoodCandidate? {
        val e = entryFor(phrase) ?: return null
        return e.custom ?: e.fdcId?.let { usda?.byId(it) }
    }

    fun lookup(food: String, usda: UsdaFoodSource?): FoodCandidate? {
        if (Regex("\\bflavou?red syrups?\\b").containsMatchIn(food)) {
            val flavour = food.replace(Regex("\\bflavou?red syrups?\\b"), " ").trim().replace(Regex("\\s+"), " ")
            return if (flavour.isBlank()) FLAVOR_SYRUP
            else FLAVOR_SYRUP.copy(name = "${flavour.replaceFirstChar { it.uppercase() }} flavor syrup, one shot (typical label)")
        }
        val e = entryFor(food)
        if (e != null) {
            e.custom?.let { return it }
            return e.fdcId?.let { usda?.byId(it) }
        }
        // The bundled database's own curated phrases, reached the same way:
        // "grilled chicken breast" is still the chicken breast it has an alias for.
        val trimmed = food.split(" ").filter { it !in SERVING_WORDS }.joinToString(" ")
        if (usda != null && trimmed.isNotBlank() && usda.hasAlias(trimmed)) {
            return usda.search(trimmed, limit = 1).firstOrNull()
        }
        return null
    }
}
