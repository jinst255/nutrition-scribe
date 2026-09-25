package com.nutritionscribe.shared.resolve

import com.nutritionscribe.shared.model.Metric

/**
 * Reads a US Nutrition Facts panel out of OCR text.
 *
 * On-device text recognition returns the panel as lines in roughly reading
 * order, with the usual OCR slips: "0" for "O", a missing space ("Protein5g"),
 * the value on the next line, "mg" read as "rng". The panel's own vocabulary is
 * small and fixed, so a label per line with the nearest number is reliable enough
 * — and whatever it reads is shown to the user in editable boxes before use.
 */
object NutritionFactsParser {

    data class Panel(
        val servingText: String?,
        val servingGrams: Double?,
        /** Per ONE serving, keyed by Metric.key. */
        val perServing: Map<String, Double>,
    ) {
        val looksComplete: Boolean get() = Metric.CALORIES.key in perServing && Metric.PROTEIN.key in perServing

        fun asCandidate(name: String, brand: String? = null): FoodCandidate = FoodCandidate(
            id = "scan:${name.lowercase().hashCode()}:${perServing.hashCode()}",
            name = name,
            origin = Origin.LABEL,
            perServing = perServing,
            servingLabel = servingText,
            per100g = servingGrams?.takeIf { it > 0 }?.let { g -> perServing.mapValues { it.value * 100.0 / g } },
            defaultGrams = servingGrams,
            labelServingGrams = servingGrams,
            labelServingText = servingText,
            portions = servingGrams?.let { listOf(Portion("1 serving", it)) }.orEmpty(),
            brand = brand,
        )
    }

    private val FIELDS: List<Pair<Regex, Metric>> = listOf(
        Regex("\\bcalories\\b") to Metric.CALORIES,
        Regex("\\bsaturated\\s*fat\\b|\\bsat\\.?\\s*fat\\b") to Metric.SATURATED_FAT,
        Regex("\\btotal\\s*fat\\b") to Metric.FAT,
        Regex("\\bsodium\\b") to Metric.SODIUM,
        Regex("\\btotal\\s*carb(?:ohydrate|\\.)?s?\\b|\\bcarbohydrates?\\b") to Metric.CARBS,
        Regex("\\bdietary\\s*fiber\\b|\\bfiber\\b|\\bfibre\\b") to Metric.FIBER,
        Regex("\\btotal\\s*sugars?\\b|(?<!added\\s)\\bsugars?\\b") to Metric.SUGAR,
        Regex("\\bprotein\\b") to Metric.PROTEIN,
        Regex("\\bcaffeine\\b") to Metric.CAFFEINE,
    )

    private val NUMBER = Regex("(\\d+(?:[.,]\\d+)?)\\s*(mg|g|mcg)?\\b")

    fun parse(ocr: String): Panel {
        val lines = ocr.lines().map { clean(it) }.filter { it.isNotBlank() }
        val out = linkedMapOf<String, Double>()
        var servingText: String? = null
        var servingGrams: Double? = null

        for ((i, line) in lines.withIndex()) {
            if (servingText == null && Regex("\\bserving\\s*size\\b").containsMatchIn(line)) {
                val rest = line.substringAfter("size").trim().ifBlank { lines.getOrNull(i + 1).orEmpty() }
                servingText = rest.trim().takeIf { it.isNotBlank() }
                servingGrams = Regex("\\((\\d+(?:\\.\\d+)?)\\s*(g|ml)\\)").find(rest)?.groupValues?.get(1)?.toDoubleOrNull()
                    ?: Regex("(\\d+(?:\\.\\d+)?)\\s*(g|ml)\\b").find(rest)?.groupValues?.get(1)?.toDoubleOrNull()
            }
            for ((pattern, metric) in FIELDS) {
                if (metric.key in out) continue
                val m = pattern.find(line) ?: continue
                // "Added Sugars" and "Calories from Fat" are not the lines we want.
                if (metric == Metric.SUGAR && Regex("\\badded\\b|\\bincludes\\b").containsMatchIn(line)) continue
                if (metric == Metric.CALORIES && Regex("\\bfrom\\s*fat\\b").containsMatchIn(line)) continue
                if (metric == Metric.FAT && Regex("\\b(?:saturated|trans)\\b").containsMatchIn(line)) continue
                val after = line.substring(m.range.last + 1)
                val value = firstAmount(after, metric) ?: lines.getOrNull(i + 1)?.let { firstAmount(it, metric) }
                if (value != null) out[metric.key] = value
            }
        }
        return Panel(servingText, servingGrams, out)
    }

    /** The first sensible amount on a line; a percent daily value is not an amount. */
    private fun firstAmount(text: String, metric: Metric): Double? {
        for (m in NUMBER.findAll(text)) {
            val end = m.range.last + 1
            if (text.getOrNull(end) == '%' || text.substring(end).trimStart().startsWith("%")) continue
            var v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue
            val unit = m.groupValues[2]
            if (metric == Metric.SODIUM || metric == Metric.CAFFEINE) {
                if (unit == "g") v *= 1000.0
                if (unit == "mcg") v /= 1000.0
            } else if (unit == "mg") {
                v /= 1000.0
            }
            if (metric == Metric.CALORIES && v > 3000) continue
            if (metric != Metric.CALORIES && metric != Metric.SODIUM && metric != Metric.CAFFEINE && v > 300) continue
            return v
        }
        return null
    }

    /** Undo the usual OCR slips. */
    private fun clean(line: String): String {
        var t = line.lowercase()
            .replace("rng", "mg")
            .replace(Regex("(?<=\\d)o\\b"), "0")
            .replace(Regex("\\bo(?=\\d)"), "0")
        // "protein5g" -> "protein 5g"
        t = Regex("([a-z])(\\d)").replace(t) { "${it.groupValues[1]} ${it.groupValues[2]}" }
        return t.trim()
    }
}
