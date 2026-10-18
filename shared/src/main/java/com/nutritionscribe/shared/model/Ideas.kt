package com.nutritionscribe.shared.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Suggestions from what someone actually ate this week: what they were short on,
 * what went over a limit, meals they already make with one thing added, and a
 * short list for the store.
 *
 * Rules, not a model, so every line can say where its numbers came from:
 *  - Only days logged fully enough to judge count (half the day's calories or
 *    more). A day with just breakfast logged would read as a shortage of everything.
 *  - Three such days in the last seven before anything is said.
 *  - Short means under 70% of the person's need on average; over means past the
 *    limit on average. One big day is not a pattern.
 *  - A micronutrient is only judged when the foods logged list it.
 */
object Ideas {

    /** A food worth suggesting: the USDA row, and the everyday amount of it. */
    data class Food(val name: String, val fdcId: Long, val grams: Double, val serving: String, val kind: Kind) {
        enum class Kind { FRUIT, VEG, PROTEIN, DAIRY, SEED, GRAIN }
    }

    val FOODS: List<Food> = listOf(
        Food("Kiwi", 168153, 69.0, "1 kiwi", Food.Kind.FRUIT),
        Food("Orange", 169097, 131.0, "1 orange", Food.Kind.FRUIT),
        Food("Strawberries", 167762, 152.0, "1 cup", Food.Kind.FRUIT),
        Food("Raspberries", 167755, 62.0, "½ cup", Food.Kind.FRUIT),
        Food("Blueberries", 171711, 74.0, "½ cup", Food.Kind.FRUIT),
        Food("Pear", 169118, 178.0, "1 pear", Food.Kind.FRUIT),
        Food("Banana", 173944, 118.0, "1 banana", Food.Kind.FRUIT),
        Food("Spinach", 168462, 30.0, "1 cup raw", Food.Kind.VEG),
        Food("Kale", 168421, 21.0, "1 cup raw", Food.Kind.VEG),
        Food("Broccoli", 170379, 91.0, "1 cup", Food.Kind.VEG),
        Food("Red bell pepper", 170108, 75.0, "½ cup sliced", Food.Kind.VEG),
        Food("Sweet potato", 168483, 114.0, "1 medium, baked", Food.Kind.VEG),
        Food("Baked potato", 170093, 173.0, "1 medium", Food.Kind.VEG),
        Food("Avocado", 171705, 68.0, "½ avocado", Food.Kind.VEG),
        Food("Mushrooms", 169251, 70.0, "1 cup sliced", Food.Kind.VEG),
        Food("Black beans", 173735, 86.0, "½ cup cooked", Food.Kind.PROTEIN),
        Food("Lentils", 172421, 99.0, "½ cup cooked", Food.Kind.PROTEIN),
        Food("Kidney beans", 173740, 89.0, "½ cup cooked", Food.Kind.PROTEIN),
        Food("Edamame", 168411, 78.0, "½ cup", Food.Kind.PROTEIN),
        Food("Salmon", 175168, 85.0, "3 oz cooked", Food.Kind.PROTEIN),
        Food("Eggs", 173424, 100.0, "2 eggs", Food.Kind.PROTEIN),
        Food("Chicken breast", 171477, 85.0, "3 oz cooked", Food.Kind.PROTEIN),
        Food("Firm tofu", 172475, 85.0, "3 oz", Food.Kind.PROTEIN),
        Food("Greek yogurt", 170894, 170.0, "1 container, plain", Food.Kind.DAIRY),
        Food("Milk", 171265, 244.0, "1 cup", Food.Kind.DAIRY),
        Food("Almonds", 170567, 28.0, "1 oz, about 23", Food.Kind.SEED),
        Food("Pumpkin seeds", 170557, 28.0, "1 oz", Food.Kind.SEED),
        Food("Peanuts", 173806, 28.0, "1 oz", Food.Kind.SEED),
        Food("Chia seeds", 170554, 12.0, "1 tbsp", Food.Kind.SEED),
    )

    /** Nutrients worth suggesting food for, and where the advice comes from. */
    private val REACH_SOURCES: Map<Metric, String> = mapOf(
        Metric.FIBER to "Dietary Guidelines for Americans, 2020–2025",
        Metric.PROTEIN to "National Academies, Dietary Reference Intakes",
        Metric.VITAMIN_C to "NIH Office of Dietary Supplements",
        Metric.VITAMIN_D to "NIH Office of Dietary Supplements",
        Metric.CALCIUM to "NIH Office of Dietary Supplements",
        Metric.IRON to "NIH Office of Dietary Supplements",
        Metric.POTASSIUM to "NIH Office of Dietary Supplements",
        Metric.MAGNESIUM to "NIH Office of Dietary Supplements",
        Metric.FOLATE to "NIH Office of Dietary Supplements",
        Metric.VITAMIN_A to "NIH Office of Dietary Supplements",
        Metric.VITAMIN_K to "NIH Office of Dietary Supplements",
        Metric.ZINC to "NIH Office of Dietary Supplements",
        Metric.VITAMIN_E to "NIH Office of Dietary Supplements",
        Metric.OMEGA3 to "NIH Office of Dietary Supplements",
    )

    enum class Kind { SHORT, OVER, MEAL }

    data class Idea(
        /** Stable, so "Not for me" can hide it for good. */
        val id: String,
        val kind: Kind,
        val title: String,
        val body: String,
        val source: String,
        val metric: Metric?,
        /** The week's daily average and what it is measured against, for a meter. */
        val average: Double? = null,
        val target: Double? = null,
        /** What to try, one per line: "Kiwi · 1 kiwi · 64 mg". */
        val foods: List<String> = emptyList(),
    )

    data class Result(
        /** Days in the last week logged fully enough to judge. */
        val fullDays: Int,
        val needed: Int,
        val ideas: List<Idea>,
        /** "Kiwi (vitamin C, fiber)" — the next time at the store. */
        val store: List<String>,
    ) {
        val ready: Boolean get() = fullDays >= needed
        /** The one line Home shows on its Ideas button. */
        val headline: String? get() = ideas.firstOrNull { it.kind != Kind.MEAL }?.title
    }

    private const val NEEDED_DAYS = 3
    private const val SHORT_SHARE = 0.7

    fun build(
        entries: List<Entry>,
        needs: Map<Metric, Double>,
        today: LocalDate,
        zone: ZoneId,
        /** USDA numbers per 100 g, by FoodData Central id; null when not in the bundled data. */
        per100g: (Long) -> Map<String, Double>?,
        dismissed: Set<String> = emptySet(),
        /** Refresh: 0 is the best fit, 1, 2… the next ones, so a pull shows other foods. */
        variant: Int = 0,
    ): Result {
        val byDay = entries.groupBy { Instant.ofEpochMilli(it.timestampEpochMillis).atZone(zone).toLocalDate() }
        val kcalNeed = needs[Metric.CALORIES] ?: Metric.CALORIES.defaultTarget
        // The last seven days before today: today is not over yet.
        val window = (1..7).map { today.minusDays(it.toLong()) }
        val full = window.mapNotNull { d -> byDay[d]?.takeIf { day -> day.sumOf { it.calories ?: 0.0 } >= kcalNeed * 0.5 } }
        if (full.size < NEEDED_DAYS) return Result(full.size, NEEDED_DAYS, emptyList(), emptyList())
        val week = full.flatten()

        val ideas = mutableListOf<Idea>()
        val storeFoods = linkedMapOf<String, MutableList<String>>()

        // Short on: the emptiest first.
        val shorts = REACH_SOURCES.keys.mapNotNull { m ->
            val need = needs[m] ?: return@mapNotNull null
            // A micronutrient only when the foods logged list it, day after day.
            if (m.isMicro && full.any { day -> NutrientDay.coverage(day, m) < NutrientTotal.TRUSTED_COVERAGE }) return@mapNotNull null
            val avg = full.map { day -> day.sumOf { it.nutrients[m.key] ?: 0.0 } }.average()
            if (avg >= need * SHORT_SHARE) null else Triple(m, avg, need)
        }.sortedBy { it.second / it.third }

        val foodAmounts = FOODS.mapNotNull { f -> per100g(f.fdcId)?.let { p -> f to p.mapValues { it.value * f.grams / 100.0 } } }

        for ((m, avg, need) in shorts.take(3)) {
            val ranked = foodAmounts.filter { (_, n) -> (n[m.key] ?: 0.0) > 0 }
                .sortedByDescending { (_, n) -> n[m.key] ?: 0.0 }
            val best = rotate(ranked, variant, 2)
            val already = NutrientDay.sources(week, m).take(1).firstOrNull()?.let { (name, v) ->
                "From what you ate: ${shortName(name)} gave ${amount(v / full.size, m)} a day. "
            }.orEmpty()
            val add = best.joinToString(" or ") { (f, n) -> "${f.name.lowercase()} (${f.serving}, ${amount(n[m.key] ?: 0.0, m)})" }
            ideas += Idea(
                id = "short:${m.key}",
                kind = Kind.SHORT,
                title = "${m.label} averaged ${amount(avg, m, unit = false)} of ${amount(need, m)}",
                // The suggestions are [foods]; the words say what already helped.
                body = already.trim(),
                source = REACH_SOURCES.getValue(m),
                metric = m,
                average = avg, target = need,
                foods = best.map { (f, n) -> "${f.name} · ${f.serving} · ${amount(n[m.key] ?: 0.0, m)}" },
            )
            best.forEach { (f, _) -> storeFoods.getOrPut(f.name) { mutableListOf() } += m.shortLabel.lowercase() }
        }

        // Over a limit.
        val sugar = full.map { day -> com.nutritionscribe.shared.resolve.Sugars.split(day).let { it.added + it.unknown } }.average()
        val addedLimit = needs[Metric.ADDED_SUGAR] ?: Metric.ADDED_SUGAR.defaultTarget
        if (sugar > addedLimit) ideas += overIdea(
            Metric.ADDED_SUGAR, sugar, addedLimit, week, full.size, Metric.SUGAR,
            "Under 10% of calories from added sugar, about ${amount(addedLimit, Metric.ADDED_SUGAR)} a day for you.",
            "Dietary Guidelines for Americans, 2020–2025",
        )
        for ((m, why, src) in listOf(
            Triple(Metric.SODIUM, "Under 2,300 mg a day for adults.", "Dietary Guidelines for Americans, 2020–2025"),
            Triple(Metric.SATURATED_FAT, "Under 10% of calories from saturated fat.", "Dietary Guidelines for Americans, 2020–2025"),
            Triple(Metric.CAFFEINE, "Up to 400 mg a day is not generally linked to harm in healthy adults; less when pregnant.", "U.S. Food and Drug Administration"),
        )) {
            val limit = needs[m] ?: m.defaultTarget
            val avg = full.map { day -> day.sumOf { it.nutrients[m.key] ?: 0.0 } }.average()
            if (avg > limit) ideas += overIdea(m, avg, limit, week, full.size, m, why, src)
        }

        // Meals they already make, with the most useful food added.
        val gap = shorts.firstOrNull()?.first
        if (gap != null) {
            val usual = entries.filter { it.items.isNotEmpty() && !it.needsConfirm }
                .groupBy { it.name.lowercase() }.values.sortedByDescending { it.size }
                .filter { it.size >= 2 }.take(4).map { it.last() }
            for (meal in usual.take(2)) {
                val fit = fitFor(meal)
                val pick = rotate(
                    foodAmounts.filter { (f, n) -> (n[gap.key] ?: 0.0) > 0 && (fit == null || f.kind in fit) && !(f.kind == Food.Kind.PROTEIN && hasMeat(meal)) }
                        .sortedByDescending { (_, n) -> n[gap.key] ?: 0.0 },
                    variant, 1,
                ).firstOrNull() ?: continue
                val (f, n) = pick
                val extra = listOf(gap, Metric.FIBER, Metric.VITAMIN_C, Metric.PROTEIN).distinct()
                    .mapNotNull { m -> n[m.key]?.takeIf { it >= if (m == Metric.VITAMIN_C) 5.0 else 1.0 }?.let { "+${amount(it, m)} ${m.shortLabel.lowercase()}" } }
                    .take(2).joinToString(", ")
                ideas += Idea(
                    id = "meal:${meal.name.lowercase().hashCode()}:${f.name}",
                    kind = Kind.MEAL,
                    title = "${shortName(meal.name)} + ${f.name.lowercase()}",
                    body = "Your usual, with ${f.name.lowercase()} (${f.serving}): $extra.",
                    source = "USDA FoodData Central",
                    metric = gap,
                    foods = listOf("${f.name} · ${f.serving}" + if (extra.isNotBlank()) " · $extra" else ""),
                )
            }
        }

        val shown = ideas.filter { it.id !in dismissed }
        val store = storeFoods.entries.take(5).map { (food, why) -> "$food (${why.distinct().joinToString(", ")})" }
        return Result(full.size, NEEDED_DAYS, shown, store)
    }

    /** [n] from [list], starting [variant] picks further down and wrapping round. */
    private fun <T> rotate(list: List<T>, variant: Int, n: Int): List<T> {
        if (list.size <= n || variant == 0) return list.take(n)
        val start = (variant * n) % list.size
        return (list.drop(start) + list.take(start)).take(n)
    }

    /** "Pasta with meat sauce + chicken breast": a dish with meat needs no more of it. */
    private fun hasMeat(meal: Entry): Boolean =
        Regex("\\b(?:chicken|beef|pork|ham|turkey|sausage|meat|steak|bacon|fish|tuna|salmon|shrimp|egg|eggs)\\b")
            .containsMatchIn((meal.name + " " + meal.items.joinToString(" ") { it.name }).lowercase())

    private fun overIdea(m: Metric, avg: Double, limit: Double, week: List<Entry>, days: Int, sourcesOf: Metric, why: String, src: String): Idea {
        // Added sugar's sources are the added part only: raisins are not why it went over.
        val top = (if (m == Metric.ADDED_SUGAR) com.nutritionscribe.shared.resolve.Sugars.addedSources(week) else NutrientDay.sources(week, sourcesOf)).take(2)
        val from = if (top.isEmpty()) "" else " Most came from ${top.joinToString(" and ") { (n, v) -> "${shortName(n)} (${amount(v / days, sourcesOf)} a day)" }}."
        return Idea(
            id = "over:${m.key}",
            kind = Kind.OVER,
            title = "${m.label} averaged ${amount(avg, m, unit = false)}, over ${amount(limit, m)}",
            body = why,
            source = src,
            metric = m,
            average = avg, target = limit,
            foods = top.map { (n, v) -> "${shortName(n)} · ${amount(v / days, sourcesOf)} a day" },
        )
    }

    /** What kind of food goes with a meal: berries in oats, greens on a sandwich. */
    private fun fitFor(meal: Entry): Set<Food.Kind>? {
        val words = (meal.name + " " + meal.items.joinToString(" ") { it.name }).lowercase()
        return when {
            Regex("\\b(?:oat|oats|oatmeal|yogurt|cereal|granola)\\b").containsMatchIn(words) -> setOf(Food.Kind.FRUIT, Food.Kind.SEED)
            Regex("\\b(?:sandwich|bread|toast|wrap|burger|burrito|taco)\\b").containsMatchIn(words) -> setOf(Food.Kind.VEG)
            Regex("\\b(?:salad)\\b").containsMatchIn(words) -> setOf(Food.Kind.VEG, Food.Kind.SEED, Food.Kind.PROTEIN)
            else -> null
        }
    }

    private fun shortName(name: String): String =
        name.substringAfter(" — ").substringBefore(",").substringBefore(" (").trim().let { if (it.length > 32) it.take(30).trimEnd() + "…" else it }

    private fun amount(v: Double, m: Metric, unit: Boolean = true): String {
        val n = when {
            v >= 1000 -> String.format(java.util.Locale.US, "%,d", (v / 10).roundToInt() * 10)
            v >= 100 -> ((v / 10).roundToInt() * 10).toString()
            v >= 10 -> v.roundToInt().toString()
            else -> ((v * 10).roundToInt() / 10.0).let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }
        }
        return if (unit) "$n ${m.unit}" else n
    }
}
