package com.nutritionscribe.shared.model

import com.nutritionscribe.shared.resolve.ShortName
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Ask's look at the whole log, worked out on the phone. The owner asked Ask for
 * "the biggest sodium sources across everything I've logged" and got "I can't see
 * past logs": it only ever saw today. The model asks through a tool for one metric,
 * a span of days and a grouping; only this small summary goes back to it, never
 * the entries themselves.
 */
object LogQuery {

    enum class GroupBy { FOOD, DAY, WEEK, MONTH, MEAL_TIME }

    data class Request(
        val metric: String,
        /** Inclusive; null = from the first entry. */
        val from: LocalDate? = null,
        /** Inclusive; null = today. */
        val to: LocalDate? = null,
        val groupBy: GroupBy = GroupBy.FOOD,
        val top: Int = 10,
        /** Smallest first ("which days was I lowest on fiber"). */
        val lowest: Boolean = false,
    )

    private val DAY = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)

    fun run(entries: List<Entry>, req: Request, zone: ZoneId, today: LocalDate): String {
        val metric = Metric.fromKey(req.metric) ?: return "Unknown metric \"${req.metric}\". Use one of: ${Metric.entries.joinToString(", ") { it.key }}."
        fun dayOf(e: Entry) = Instant.ofEpochMilli(e.timestampEpochMillis).atZone(zone).toLocalDate()
        val to = req.to ?: today
        val inSpan = entries.filter { e -> val d = dayOf(e); (req.from == null || !d.isBefore(req.from)) && !d.isAfter(to) }
        if (inSpan.isEmpty()) return "No entries between ${req.from?.format(DAY) ?: "the start"} and ${to.format(DAY)}."
        val first = inSpan.minOf { dayOf(it) }
        val unit = if (metric == Metric.CALORIES) "kcal" else metric.unit
        val total = inSpan.sumOf { it.nutrients[metric.key] ?: 0.0 }
        val days = inSpan.map { dayOf(it) }.toSet().size
        val head = "${metric.label}, ${first.format(DAY)} to ${to.format(DAY)}: ${inSpan.size} entries on $days logged days, " +
            "${fmt(total)} $unit in all, ${fmt(total / days.coerceAtLeast(1))} $unit a logged day."
        val n = req.top.coerceIn(1, 25)
        val rows: List<String> = when (req.groupBy) {
            GroupBy.FOOD -> {
                // Each food on its own: an entry's lines, or the entry when it has none.
                data class Acc(var amount: Double = 0.0, var times: Int = 0)
                val by = linkedMapOf<String, Acc>()
                for (e in inSpan) {
                    val parts = e.items.filter { it.nutrients.isNotEmpty() }
                    if (parts.isEmpty()) {
                        val v = e.nutrients[metric.key] ?: continue
                        by.getOrPut(ShortName.of(e.name)) { Acc() }.apply { amount += v; times++ }
                    } else for (i in parts) {
                        val v = i.nutrients[metric.key] ?: continue
                        by.getOrPut(ShortName.of(i.name)) { Acc() }.apply { amount += v; times++ }
                    }
                }
                val sorted = by.entries.filter { it.value.amount > 0 || req.lowest }
                    .sortedBy { if (req.lowest) it.value.amount else -it.value.amount }.take(n)
                sorted.mapIndexed { i, (name, a) ->
                    "${i + 1}. $name: ${fmt(a.amount)} $unit over ${a.times}× (${fmt(a.amount / a.times)} each, ${pct(a.amount, total)} of all)"
                }
            }
            GroupBy.DAY -> inSpan.groupBy { dayOf(it) }.map { (d, es) -> d to es.sumOf { it.nutrients[metric.key] ?: 0.0 } }
                .sortedBy { if (req.lowest) it.second else -it.second }.take(n)
                .mapIndexed { i, (d, v) -> "${i + 1}. ${d.format(DAY)}: ${fmt(v)} $unit" }
            GroupBy.WEEK -> inSpan.groupBy { dayOf(it).with(DayOfWeek.MONDAY) }.toSortedMap().entries.toList().takeLast(n)
                .map { (wk, es) ->
                    val d = es.map { dayOf(it) }.toSet().size
                    val v = es.sumOf { it.nutrients[metric.key] ?: 0.0 }
                    "Week of ${wk.format(DAY)}: ${fmt(v / d)} $unit a logged day ($d days)"
                }
            GroupBy.MONTH -> inSpan.groupBy { dayOf(it).withDayOfMonth(1) }.toSortedMap().entries.toList().takeLast(n)
                .map { (m, es) ->
                    val d = es.map { dayOf(it) }.toSet().size
                    "${m.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US))}: ${fmt(es.sumOf { it.nutrients[metric.key] ?: 0.0 } / d)} $unit a logged day ($d days)"
                }
            GroupBy.MEAL_TIME -> inSpan.filter { !it.timeUnspecified }.groupBy { mealOf(Instant.ofEpochMilli(it.timestampEpochMillis).atZone(zone).hour) }
                .map { (meal, es) -> meal to es.sumOf { it.nutrients[metric.key] ?: 0.0 } }
                .sortedByDescending { it.second }
                .map { (meal, v) -> "$meal: ${fmt(v)} $unit (${pct(v, total)})" }
        }
        return (listOf(head) + rows).joinToString("\n")
    }

    private fun mealOf(hour: Int) = when (hour) {
        in 4..10 -> "Breakfast (4–11 AM)"
        in 11..15 -> "Lunch (11 AM–4 PM)"
        in 16..20 -> "Dinner (4–9 PM)"
        else -> "Late night (9 PM–4 AM)"
    }

    private fun pct(v: Double, total: Double) = if (total <= 0) "0%" else "${(v / total * 100).roundToLong()}%"

    private fun fmt(v: Double): String = when {
        v >= 100 -> String.format(Locale.US, "%,d", v.roundToLong())
        v >= 10 -> String.format(Locale.US, "%.0f", v)
        else -> String.format(Locale.US, "%.1f", v).removeSuffix(".0")
    }
}

/**
 * Ask's look at the person's saved foods ("all the Great Value foods I eat", "my
 * typical bread with my typical avocado"): name, brand, usual amount and its
 * numbers, worked out on the phone.
 */
object MyFoodsQuery {
    fun run(foods: List<com.nutritionscribe.shared.resolve.PersonalFood>, query: String, top: Int = 20): String {
        val words = com.nutritionscribe.shared.food.FoodQuery.contentWords(query.lowercase()).toSet()
        val singles = foods.filter { it.nutrients.isNotEmpty() }
        val hits = if (words.isEmpty()) singles else singles.filter { f ->
            val have = com.nutritionscribe.shared.food.FoodQuery.contentWords(
                "${f.name} ${f.phrase} ${f.candidate?.brand.orEmpty()} ${f.candidate?.name.orEmpty()}".lowercase()).toSet()
            words.all { it in have }
        }
        if (hits.isEmpty()) return "None of their saved foods match \"$query\". They have ${singles.size} saved foods."
        val shown = hits.sortedByDescending { it.timesUsed }.take(top.coerceIn(1, 50))
        val lines = shown.map { f ->
            val name = ShortName.withBrand(f.candidate?.name ?: f.name, f.candidate?.brand)
            val kind = when {
                f.isRecipe -> "recipe, per serving"
                f.isMeal -> "saved meal"
                f.candidate?.origin == com.nutritionscribe.shared.resolve.Origin.LABEL -> "their scanned label"
                else -> "their usual"
            }
            val amount = listOfNotNull(f.amount?.let { a -> if (a == a.toLong().toDouble()) a.toLong().toString() else "%.2g".format(Locale.US, a) }, f.unit)
                .joinToString(" ").ifBlank { null }
            val grams = f.grams?.let { "${it.roundToLong()} g" }
            val n = listOf("calories" to "kcal", "protein" to "g protein", "carbs" to "g carbs", "fat" to "g fat", "fiber" to "g fiber", "sodium" to "mg sodium")
                .mapNotNull { (k, u) -> f.nutrients[k]?.let { "${it.roundToLong()} $u" } }.joinToString(", ")
            "- $name ($kind): ${listOfNotNull(amount, grams).joinToString(", ").ifBlank { "one serving" }}: $n · logged ${f.timesUsed}×"
        }
        val more = if (hits.size > shown.size) "\n…and ${hits.size - shown.size} more." else ""
        return "${hits.size} saved foods match:\n" + lines.joinToString("\n") + more
    }
}
