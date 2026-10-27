package com.nutritionscribe.shared.model

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * "What does fiber do for me?": the page behind a nutrient's sheet on Home.
 *
 * Written kindly and plainly: what staying in range does FOR you ("helps keep your
 * blood sugar steady"), never what happens if you don't. Every claim is one the
 * cited source makes; a page is general information, not medical advice.
 *
 * Nutrients to reach list foods rich in them, worked out from the bundled USDA
 * data like [Ideas] does. Nutrients to limit list easy swaps instead.
 */
object NutrientGuide {

    /** Which picture goes beside a point. The app maps each to an icon. */
    enum class Icon { FULL, STEADY, HEART, ENERGY, MUSCLE, BONE, BLOOD, SHIELD, HEAL, EYE, BRAIN, CELL, SLEEP, LEAF, SMILE, DROP, BABY }

    data class Point(val title: String, val body: String, val icon: Icon)

    data class Guide(
        val metric: Metric,
        /** One kind sentence: what it does for you. */
        val headline: String,
        /** "When you get enough" / "When you keep it low". */
        val pointsTitle: String,
        val points: List<Point>,
        /** For a limit: easy ways to have less. Empty for a nutrient to reach (it lists foods). */
        val swaps: List<String> = emptyList(),
        val source: String,
    ) {
        val isLimit: Boolean get() = metric.direction == GoalDirection.LIMIT
        /** Whether the page lists foods rich in it. Calories would just list the densest foods. */
        val listsFoods: Boolean get() = !isLimit && metric != Metric.CALORIES
    }

    private const val DGA = "the Dietary Guidelines for Americans, 2020–2025"
    private const val ODS = "the NIH Office of Dietary Supplements"
    private const val ENOUGH = "When you get enough"
    private const val LOW = "When you keep it low"

    /** The guide for a nutrient. A part (added sugar, trans fat) gets its whole's page. */
    fun of(metric: Metric): Guide = GUIDES[metric] ?: GUIDES.getValue(metric.partOf ?: Metric.CALORIES)

    /** The question on the sheet's link: "What does fiber do for me?" */
    fun question(metric: Metric): String {
        val m = of(metric).metric
        return when (m) {
            Metric.CALORIES -> "What do calories do for me?"
            Metric.SUGAR -> "Why keep added sugar low?"
            Metric.SATURATED_FAT, Metric.SODIUM, Metric.CHOLESTEROL -> "Why keep ${nameOf(m)} low?"
            Metric.CAFFEINE -> "How much caffeine is right?"
            else -> "What does ${nameOf(m)} do for me?"
        }
    }

    /** "fiber", "vitamin C", "omega-3". */
    private fun nameOf(m: Metric): String = when (m) {
        Metric.OMEGA3 -> "omega-3"
        Metric.VITAMIN_B12 -> "vitamin B12"
        else -> if (m.label.startsWith("Vitamin")) "vitamin" + m.label.removePrefix("Vitamin") else m.label.lowercase()
    }

    /**
     * Where today stands, in words: "Aim for about 38 g a day. Today you have 18 g,
     * about 20 g to go." [dayLabel] is null for today, else "On Mon, Oct 12".
     */
    fun progress(metric: Metric, have: Double, target: Double, dayLabel: String? = null): String {
        val m = of(metric).metric
        val day = dayLabel ?: "Today"
        val had = if (dayLabel == null) "you have" else "you had"
        val at = if (dayLabel == null) "you're at" else "you were at"
        val left = target - have
        val near = abs(left) < maxOf(target * 0.03, if (m.unit == "g") 1.0 else 5.0)
        return if (m.direction == GoalDirection.LIMIT) {
            val name = if (m == Metric.SUGAR) "The added sugar limit" else "The limit"
            "$name is about ${amount(target, m)} a day. " + when {
                near -> "$day $at about the limit."
                left > 0 -> "$day $at ${amount(have, m)}, with about ${amount(left, m)} of room."
                // One day over is not the point; the usual day is.
                else -> "$day $at ${amount(have, m)}, about ${amount(-left, m)} over. One day over is fine; it's the usual day that counts."
            }
        } else {
            "Aim for about ${amount(target, m)} a day. " + when {
                near || left <= 0 -> "$day $had ${amount(have, m)}, which covers it."
                else -> "$day $had ${amount(have, m)}, about ${amount(left, m)} to go."
            }
        }
    }

    /**
     * The everyday foods with the most of it per usual serving, most first:
     * (food, amount in that serving). [per100g] is USDA's numbers per 100 g by
     * FoodData Central id, null when the bundled data lacks the food.
     */
    fun richIn(metric: Metric, per100g: (Long) -> Map<String, Double>?, n: Int = 4): List<Pair<Ideas.Food, Double>> {
        val m = of(metric).metric
        if (!of(m).listsFoods) return emptyList()
        return Ideas.FOODS.mapNotNull { f ->
            val v = per100g(f.fdcId)?.get(m.key)?.times(f.grams / 100.0) ?: return@mapNotNull null
            if (v <= 0.0) null else f to v
        }
            .sortedByDescending { it.second }
            // A tenth of the top food's amount at least: a list of trace sources helps
            // nobody. But never fewer than three (note S17: omega-3 was "just salmon").
            .let { list ->
                val strong = list.filter { it.second >= (list.firstOrNull()?.second ?: 0.0) * 0.1 }
                if (strong.size >= 3) strong else list.take(3)
            }
            .take(maxOf(n, 3))
    }

    /** "8 g", "2,300 mg", "1.2 µg", rounded the way the rest of the app reads. */
    fun amount(v: Double, m: Metric): String {
        val n = when {
            v >= 1000 -> String.format(java.util.Locale.US, "%,d", (v / 10).roundToInt() * 10)
            v >= 100 -> ((v / 10).roundToInt() * 10).toString()
            v >= 10 -> v.roundToInt().toString()
            else -> ((v * 10).roundToInt() / 10.0).let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }
        }
        return "$n ${m.unit}"
    }

    private fun p(title: String, body: String, icon: Icon) = Point(title, body, icon)

    private val GUIDES: Map<Metric, Guide> = listOf(
        Guide(
            Metric.CALORIES, "Calories are the energy your body runs on.", "When you get about the right amount",
            listOf(
                p("Steady energy.", "Eating close to what you need keeps you going through the day without running on empty.", Icon.ENERGY),
                p("Your weight where you want it.", "Over weeks, matching what you eat to what you use is what holds your weight steady, or moves it the way you choose.", Icon.STEADY),
                p("Room for the rest.", "Enough food gives protein, fiber and vitamins the chance to add up.", Icon.LEAF),
            ),
            source = DGA,
        ),
        Guide(
            Metric.PROTEIN, "Protein builds and repairs your muscles, and keeps you full.", ENOUGH,
            listOf(
                p("Stronger muscles.", "Your body uses protein to repair and build muscle, most of all after exercise.", Icon.MUSCLE),
                p("Fuller for longer.", "Meals with protein hold you over, which makes eating the right amount easier.", Icon.FULL),
                p("Everyday upkeep.", "Skin, hair, enzymes and the antibodies that fight infection are all made from protein.", Icon.SHIELD),
            ),
            source = "the National Academies' Dietary Reference Intakes",
        ),
        Guide(
            Metric.CARBS, "Carbohydrates are your body's quickest fuel.", ENOUGH,
            listOf(
                p("Energy to move.", "Your muscles reach for carbohydrate first when you work hard.", Icon.ENERGY),
                p("Fuel for your brain.", "Your brain runs mostly on glucose, which comes from carbohydrate.", Icon.BRAIN),
                p("Best from whole foods.", "Whole grains, fruit, beans and vegetables bring fiber and vitamins along with the energy.", Icon.LEAF),
            ),
            source = DGA,
        ),
        Guide(
            Metric.FAT, "Fat gives food its flavor, and helps you take in vitamins A, D, E and K.", ENOUGH,
            listOf(
                p("Vitamins absorbed.", "Vitamins A, D, E and K dissolve in fat, so a little with a meal helps you take them in.", Icon.CELL),
                p("Lasting energy.", "Fat is the most concentrated fuel, and it helps a meal keep you satisfied.", Icon.FULL),
                p("Kind to your heart.", "Unsaturated fat from nuts, olive oil, avocado and fish is the kind that supports a healthy heart.", Icon.HEART),
            ),
            source = DGA,
        ),
        Guide(
            Metric.FIBER, "Fiber keeps you full and your energy steady.", ENOUGH,
            listOf(
                p("Fuller for longer.", "Meals with fiber hold you over, which makes eating the right amount easier.", Icon.FULL),
                p("Steadier blood sugar.", "Sugar from a meal reaches your blood more slowly, with fewer spikes and crashes.", Icon.STEADY),
                p("A healthier heart.", "Oats, beans and fruit help lower LDL cholesterol over time.", Icon.HEART),
            ),
            source = DGA,
        ),
        Guide(
            Metric.SUGAR, "Keeping added sugar low keeps your energy steady and your teeth happy.", LOW,
            listOf(
                p("Steadier energy.", "Less added sugar means fewer quick highs and the slump that follows.", Icon.STEADY),
                p("Room for food that fills you.", "Added sugar brings calories without much else; less of it leaves room for protein and fiber.", Icon.FULL),
                p("Healthier teeth.", "Less sugar between meals means fewer cavities.", Icon.SMILE),
            ),
            swaps = listOf(
                "Water or sparkling water in place of some soda",
                "Plain yogurt with fruit instead of flavored",
                "Half the syrup or sugar in your coffee",
                "Fruit for dessert some nights",
            ),
            source = DGA + ". Sugar in fruit and plain milk is natural and doesn't count toward the limit",
        ),
        Guide(
            Metric.SATURATED_FAT, "Keeping saturated fat low is kind to your heart.", LOW,
            listOf(
                p("Lower LDL cholesterol.", "Less saturated fat, with unsaturated fat in its place, helps keep LDL cholesterol down.", Icon.HEART),
                p("Clearer arteries.", "Lower LDL means less build-up in your blood vessels over the years.", Icon.BLOOD),
                p("Swaps, not cuts.", "Trading butter for olive oil, or some red meat for fish or beans, does most of the work.", Icon.LEAF),
            ),
            swaps = listOf(
                "Olive or canola oil instead of butter",
                "Chicken, fish or beans in place of some red meat",
                "Lower-fat milk and cheese",
                "Nuts instead of chips or pastries",
            ),
            source = DGA,
        ),
        Guide(
            Metric.SODIUM, "Keeping sodium in range helps keep your blood pressure healthy.", LOW,
            listOf(
                p("Healthier blood pressure.", "Less sodium helps keep blood pressure down, which is good for your heart and kidneys.", Icon.HEART),
                p("Less puffiness.", "Your body holds on to less water after a lower-salt day.", Icon.DROP),
                p("Most of it is hidden.", "Most sodium comes from packaged and restaurant food, not the salt shaker, so a few label checks go a long way.", Icon.LEAF),
            ),
            swaps = listOf(
                "Compare labels: the same soup or bread can differ by half",
                "Rinse canned beans and vegetables",
                "Season with herbs, lemon, garlic or pepper",
                "Ask for sauces and dressings on the side",
            ),
            source = DGA + " and the U.S. Food and Drug Administration",
        ),
        Guide(
            Metric.CAFFEINE, "In moderation, caffeine can sharpen your focus without costing you sleep.", "When you keep it in range",
            listOf(
                p("Better sleep.", "Caffeine stays in your body for hours; having it earlier in the day protects your sleep.", Icon.SLEEP),
                p("A calmer heart.", "Up to about 400 mg a day avoids the jitters, racing heart and upset stomach that too much can bring.", Icon.HEART),
                p("Focus when you want it.", "A moderate amount helps you feel alert.", Icon.BRAIN),
            ),
            swaps = listOf(
                "An 8 oz coffee has about 95 mg; energy drinks can have far more",
                "Decaf or herbal tea in the afternoon",
                "Check pre-workout drinks and some pain relievers, which add caffeine too",
            ),
            source = "the U.S. Food and Drug Administration. Less is advised when pregnant or breastfeeding",
        ),
        Guide(
            Metric.CHOLESTEROL, "Eating less cholesterol-rich food is one part of a heart-healthy day.", LOW,
            listOf(
                p("A healthier heart.", "Foods high in cholesterol are often high in saturated fat too; having less of both helps your heart.", Icon.HEART),
                p("Plants have none.", "Fruit, vegetables, grains, beans and nuts contain no cholesterol at all.", Icon.LEAF),
                p("Your whole day counts.", "It's the pattern over weeks that matters, not any one meal.", Icon.STEADY),
            ),
            swaps = listOf(
                "Fish, poultry or beans in place of some red and processed meat",
                "Lower-fat dairy",
                "Fewer fried foods and baked goods made with butter or lard",
            ),
            source = DGA,
        ),
        Guide(
            Metric.CALCIUM, "Calcium keeps your bones and teeth strong.", ENOUGH,
            listOf(
                p("Stronger bones.", "Your bones store calcium; getting enough helps keep them dense as you age.", Icon.BONE),
                p("Muscles that work.", "Every muscle contraction, your heartbeat included, uses calcium.", Icon.MUSCLE),
                p("Healthy teeth.", "Calcium is a building block of your teeth.", Icon.SMILE),
            ),
            source = ODS,
        ),
        Guide(
            Metric.IRON, "Iron carries oxygen in your blood, so you feel less tired.", ENOUGH,
            listOf(
                p("More energy.", "Iron is part of hemoglobin, which carries oxygen from your lungs to the rest of you.", Icon.ENERGY),
                p("Clearer thinking.", "Low iron can leave you tired and foggy; enough helps you focus.", Icon.BRAIN),
                p("Better with vitamin C.", "Vitamin C in the same meal helps you absorb iron from beans, spinach and grains.", Icon.BLOOD),
            ),
            source = ODS,
        ),
        Guide(
            Metric.POTASSIUM, "Potassium helps your heart keep a steady beat and your blood pressure healthy.", ENOUGH,
            listOf(
                p("Healthier blood pressure.", "Potassium helps balance out sodium, which is good for blood pressure.", Icon.HEART),
                p("Muscles and nerves.", "Your muscles and nerves, heart included, need it to work properly.", Icon.MUSCLE),
                p("Mostly from plants.", "Fruit, vegetables, beans and potatoes are the richest sources.", Icon.LEAF),
            ),
            source = ODS,
        ),
        Guide(
            Metric.MAGNESIUM, "Magnesium helps your muscles relax and your body make energy.", ENOUGH,
            listOf(
                p("Relaxed muscles.", "It helps muscles relax after they work, and keeps nerves working smoothly.", Icon.MUSCLE),
                p("Energy from food.", "Hundreds of your body's enzymes use it, including the ones that turn food into energy.", Icon.ENERGY),
                p("Bones and blood sugar.", "It helps build bone and helps your body handle blood sugar.", Icon.BONE),
            ),
            source = ODS,
        ),
        Guide(
            Metric.ZINC, "Zinc helps your immune system and helps cuts heal.", ENOUGH,
            listOf(
                p("A strong immune system.", "Your immune cells need zinc to fight off infection.", Icon.SHIELD),
                p("Faster healing.", "It helps your skin repair after a cut or scrape.", Icon.HEAL),
                p("Taste and smell.", "Zinc helps keep your senses of taste and smell sharp.", Icon.SMILE),
            ),
            source = ODS,
        ),
        Guide(
            Metric.SELENIUM, "Selenium helps protect your cells and keep your thyroid working.", ENOUGH,
            listOf(
                p("Protected cells.", "It is part of enzymes that protect your cells from damage.", Icon.CELL),
                p("A healthy thyroid.", "Your thyroid uses it to make the hormones that set your metabolism.", Icon.ENERGY),
                p("A strong immune system.", "It helps your immune cells do their job.", Icon.SHIELD),
            ),
            source = ODS,
        ),
        Guide(
            Metric.VITAMIN_A, "Vitamin A helps you see in dim light and keeps your skin healthy.", ENOUGH,
            listOf(
                p("Better night vision.", "Your eyes need it to adjust to dim light.", Icon.EYE),
                p("A strong immune system.", "It helps your body fight off infection.", Icon.SHIELD),
                p("Healthy skin.", "It helps keep your skin, and the lining of your lungs and gut, healthy.", Icon.HEAL),
            ),
            source = ODS,
        ),
        Guide(
            Metric.VITAMIN_C, "Vitamin C helps you heal, absorb iron and fight off colds.", ENOUGH,
            listOf(
                p("Faster healing.", "Your body uses it to make collagen, which repairs skin and blood vessels.", Icon.HEAL),
                p("More iron.", "It helps you absorb iron from plant foods.", Icon.BLOOD),
                p("A strong immune system.", "It helps your immune cells do their job.", Icon.SHIELD),
            ),
            source = ODS,
        ),
        Guide(
            Metric.VITAMIN_D, "Vitamin D helps your body use calcium to keep your bones strong.", ENOUGH,
            listOf(
                p("Stronger bones.", "Without enough vitamin D your body can't absorb calcium well.", Icon.BONE),
                p("Muscles that work.", "It helps your muscles move, which helps your balance.", Icon.MUSCLE),
                p("Sun and food.", "Sunlight helps your skin make it, but in winter and indoors, food and supplements matter more.", Icon.ENERGY),
            ),
            source = ODS,
        ),
        Guide(
            Metric.VITAMIN_E, "Vitamin E protects your cells from everyday wear.", ENOUGH,
            listOf(
                p("Protected cells.", "It's an antioxidant that shields your cells from damage.", Icon.CELL),
                p("A strong immune system.", "It helps your body fight off infection.", Icon.SHIELD),
                p("Healthy blood flow.", "It helps your blood vessels widen and keeps blood flowing smoothly.", Icon.BLOOD),
            ),
            source = ODS,
        ),
        Guide(
            Metric.VITAMIN_K, "Vitamin K helps your blood clot when you get a cut.", ENOUGH,
            listOf(
                p("Cuts that stop bleeding.", "It helps your blood clot, so small cuts heal over.", Icon.HEAL),
                p("Stronger bones.", "It helps your body build healthy bone.", Icon.BONE),
                p("Keep it steady on blood thinners.", "If you take warfarin, keep your vitamin K about the same day to day and check with your doctor.", Icon.STEADY),
            ),
            source = ODS,
        ),
        Guide(
            Metric.VITAMIN_B12, "Vitamin B12 keeps your nerves and blood cells healthy.", ENOUGH,
            listOf(
                p("More energy.", "It helps make red blood cells; too little can leave you tired.", Icon.ENERGY),
                p("Healthy nerves.", "Your nerves and brain need it to work well.", Icon.BRAIN),
                p("Mostly from animal foods.", "Meat, fish, eggs and dairy have it; if you eat few of these, fortified foods or a supplement help.", Icon.LEAF),
            ),
            source = ODS,
        ),
        Guide(
            Metric.FOLATE, "Folate helps your body make new cells every day.", ENOUGH,
            listOf(
                p("New cells.", "Your body needs it to make DNA and new cells.", Icon.CELL),
                p("Healthy blood.", "It helps make red blood cells.", Icon.BLOOD),
                p("Before and during pregnancy.", "It helps prevent birth defects of the brain and spine, so it matters most for anyone who could become pregnant.", Icon.BABY),
            ),
            source = ODS,
        ),
        Guide(
            Metric.OMEGA3, "Omega-3 fats from fish support your heart and brain.", ENOUGH,
            listOf(
                p("A healthier heart.", "Eating fish with EPA and DHA is linked with a healthier heart.", Icon.HEART),
                p("Brain and eyes.", "DHA is a building block of your brain and the back of your eyes.", Icon.EYE),
                p("Fish twice a week.", "About two servings of fatty fish a week, like salmon or sardines, gets most people there.", Icon.STEADY),
            ),
            source = ODS + " and " + DGA,
        ),
        // More to track.
        Guide(
            Metric.OMEGA3_ALA, "ALA is the omega-3 fat in plants.", ENOUGH,
            listOf(
                p("An essential fat.", "Your body can't make ALA, so it has to come from food.", Icon.LEAF),
                p("Your heart.", "It may help your heart, most of all when it takes the place of saturated fat.", Icon.HEART),
                p("Seeds and nuts.", "Flaxseed, chia seeds, walnuts and canola oil are rich in it.", Icon.STEADY),
            ),
            source = ODS,
        ),
        Guide(
            Metric.COPPER, "Copper helps your body make energy and use iron.", ENOUGH,
            listOf(
                p("Energy.", "It helps your body make energy from food.", Icon.ENERGY),
                p("Healthy blood.", "It helps your body use iron to make red blood cells.", Icon.BLOOD),
                p("Nerves and defences.", "It helps keep your nervous and immune systems working.", Icon.SHIELD),
            ),
            source = ODS,
        ),
        Guide(
            Metric.MANGANESE, "Manganese helps your body use the food you eat.", ENOUGH,
            listOf(
                p("Using your food.", "It helps your body break down carbohydrates, protein and fat.", Icon.ENERGY),
                p("Bones.", "It helps build bone and the tissue that holds you together.", Icon.BONE),
                p("A little goes far.", "Whole grains, nuts, beans, tea and leafy greens give most people plenty.", Icon.LEAF),
            ),
            source = ODS,
        ),
        Guide(
            Metric.PHOSPHORUS, "Phosphorus builds your bones and teeth, with calcium.", ENOUGH,
            listOf(
                p("Bones and teeth.", "Most of the phosphorus in your body is in your bones and teeth.", Icon.BONE),
                p("Energy.", "Your cells use it to store and move energy.", Icon.ENERGY),
                p("Easy to get.", "Dairy, meat, fish, beans and whole grains have plenty, and most people get enough.", Icon.LEAF),
            ),
            source = ODS,
        ),
        Guide(
            Metric.CHOLINE, "Choline helps your brain and liver do their work.", ENOUGH,
            listOf(
                p("Memory and mood.", "Your body uses it to make a messenger your brain needs for memory and mood.", Icon.BRAIN),
                p("A healthy liver.", "It helps move fat out of your liver.", Icon.CELL),
                p("Eggs lead.", "An egg has about a quarter of a day's need; meat, fish, soy and beans add more.", Icon.STEADY),
            ),
            source = ODS,
        ),
        Guide(
            Metric.THIAMIN, "Thiamin (vitamin B1) helps turn food into energy.", ENOUGH,
            listOf(
                p("Energy from food.", "It helps turn the carbohydrates you eat into energy.", Icon.ENERGY),
                p("Healthy cells.", "Your cells need it to grow and work well.", Icon.CELL),
                p("Grains and pork.", "Whole or enriched grains, pork, beans and seeds are good sources.", Icon.LEAF),
            ),
            source = ODS,
        ),
        Guide(
            Metric.RIBOFLAVIN, "Riboflavin (vitamin B2) helps turn food into energy.", ENOUGH,
            listOf(
                p("Energy from food.", "It helps turn the food you eat into energy.", Icon.ENERGY),
                p("Healthy cells.", "Your cells need it to grow and work well.", Icon.CELL),
                p("Dairy and eggs.", "Milk, yogurt, eggs, meat and fortified cereals are good sources.", Icon.LEAF),
            ),
            source = ODS,
        ),
        Guide(
            Metric.NIACIN, "Niacin (vitamin B3) helps turn food into energy.", ENOUGH,
            listOf(
                p("Energy from food.", "It helps turn the food you eat into energy.", Icon.ENERGY),
                p("Skin, nerves and digestion.", "It helps keep them healthy.", Icon.SMILE),
                p("Meat, fish and grains.", "Chicken, tuna, salmon, peanuts and enriched grains are good sources.", Icon.LEAF),
            ),
            source = ODS,
        ),
        Guide(
            Metric.VITAMIN_B6, "Vitamin B6 helps your body use protein.", ENOUGH,
            listOf(
                p("Using protein.", "It helps your body break down and use the protein you eat.", Icon.MUSCLE),
                p("Brain and defences.", "It helps your brain and immune system work well.", Icon.BRAIN),
                p("Easy to get.", "Fish, chicken, potatoes, chickpeas and bananas are good sources.", Icon.LEAF),
            ),
            source = ODS,
        ),
        Guide(
            Metric.ALCOHOL, "Less alcohol is better for your health.", LOW,
            listOf(
                p("Drinking less is better.", "For health, drinking less is better than drinking more.", Icon.HEART),
                p("A day's line.", "If you drink, up to 2 drinks a day for men and 1 for women. A drink is 14 g of alcohol.", Icon.STEADY),
                p("Sometimes none.", "Not drinking at all is best when pregnant, and for anyone who shouldn't drink.", Icon.BABY),
            ),
            swaps = listOf("Water or sparkling water between drinks", "A smaller glass, or a lighter beer", "Alcohol-free beer or wine"),
            source = DGA,
        ),
    ).associateBy { it.metric }
}
