package com.nutritionscribe.shared.resolve

import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.model.NutrientSanity

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
        /** How each value was read, for the debug log and the scan form. */
        val reads: List<Read> = emptyList(),
    ) {
        /** Values that cannot all be right: a misread is the usual cause. */
        val issues: List<NutrientSanity.Issue> get() = NutrientSanity.check(perServing, servingGrams)

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

    /**
     * Where one value came from: the cleaned line, the text that was taken as the
     * number, and any correction made to it.
     */
    data class Read(
        val metric: String,
        val line: String,
        val raw: String,
        val value: Double,
        /** Set when the value is not what the text literally says, and why. */
        val fix: String? = null,
        /** What the text literally says, when [fix] changed it. */
        val asRead: Double? = null,
    )

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
        // The micronutrients a US label has to print.
        Regex("\\bcholesterol\\b|\\bcholest\\.?\\b") to Metric.CHOLESTEROL,
        Regex("\\bcalcium\\b") to Metric.CALCIUM,
        Regex("\\biron\\b") to Metric.IRON,
        Regex("\\bpotassium\\b|\\bpotas\\.?\\b") to Metric.POTASSIUM,
        Regex("\\bvit(?:amin|\\.)?\\s*d\\b") to Metric.VITAMIN_D,
    )

    private val NUMBER = Regex("(\\d+(?:[.,]\\d+)?)\\s*(mg|g|mcg|µg|ug|iu)?(?![a-z])")
    private val PERCENT = Regex("(\\d+(?:[.,]\\d+)?)\\s*%")

    fun parse(ocr: String): Panel {
        val lines = ocr.lines().map { clean(it) }.filter { it.isNotBlank() }
        val found = linkedMapOf<String, Pair<Amount, String>>()
        var servingText: String? = null
        var servingGrams: Double? = null
        val reads = mutableListOf<Read>()

        for ((i, line) in lines.withIndex()) {
            if (servingText == null && Regex("\\bserving\\s*size\\b").containsMatchIn(line)) {
                val rest = line.substringAfter("size").trim().ifBlank { lines.getOrNull(i + 1).orEmpty() }
                servingText = rest.trim().takeIf { it.isNotBlank() }
                servingGrams = Regex("\\((\\d+(?:\\.\\d+)?)\\s*(g|ml)\\)").find(rest)?.groupValues?.get(1)?.toDoubleOrNull()
                    ?: Regex("(\\d+(?:\\.\\d+)?)\\s*(g|ml)\\b").find(rest)?.groupValues?.get(1)?.toDoubleOrNull()
                if (servingGrams == null) {
                    // "(32g)" read as "(329)": a bracketed weight always has a unit.
                    Regex("\\((\\d+)9\\)").find(rest)?.let { m ->
                        servingGrams = m.groupValues[1].toDoubleOrNull()
                        reads += Read("serving_grams", line, m.value, servingGrams!!, fix = "g_read_as_9",
                            asRead = (m.groupValues[1] + "9").toDoubleOrNull())
                    }
                }
            }
            for ((pattern, metric) in FIELDS) {
                if (metric.key in found) continue
                val m = pattern.find(line) ?: continue
                // "Added Sugars" and "Calories from Fat" are not the lines we want.
                if (metric == Metric.SUGAR && Regex("\\badded\\b|\\bincludes\\b").containsMatchIn(line)) continue
                if (metric == Metric.CALORIES && Regex("\\bfrom\\s*fat\\b").containsMatchIn(line)) continue
                if (metric == Metric.FAT && Regex("\\b(?:saturated|trans)\\b").containsMatchIn(line)) continue
                val after = line.substring(m.range.last + 1)
                val here = firstAmount(after, metric)
                // The value can sit on the next line, but not when that line is
                // another nutrient's: "Cholesterol 0mg" read as "Cholesterol Omg"
                // took sodium's 135 mg, and a "Calories" whose number was printed
                // big and apart took the fat under it.
                val next = if (here == null) lines.getOrNull(i + 1)?.takeIf { !namesField(it) }?.let { firstAmount(it, metric) } else null
                val amount = here ?: next ?: continue
                found[metric.key] = amount to (if (here != null) line else "$line / ${lines[i + 1]}")
            }
        }

        // Calories are printed large and apart from their word, so OCR often puts
        // the number on a line of its own, anywhere in the text.
        if (Metric.CALORIES.key !in found) {
            standaloneCalories(lines, found)?.let { (v, line) ->
                found[Metric.CALORIES.key] = Amount(v, line, fix = "calories_apart") to line
            }
        }

        // A gram amount with no unit, ending in 9, is usually "4g" read as "49".
        // Usually, not always: take the reading that makes the panel add up, and
        // the shorter one when both do.
        val doubtful = found.filterValues { it.first.asRead != null }.keys.toList()
        var choice = found.mapValues { it.value.first.value }
        if (doubtful.isNotEmpty() && doubtful.size <= 8) {
            var best = choice
            var bestIssues = Int.MAX_VALUE
            for (mask in 0 until (1 shl doubtful.size)) {
                val trial = found.mapValues { (k, v) ->
                    val bit = doubtful.indexOf(k)
                    if (bit >= 0 && (mask shr bit) and 1 == 1) v.first.asRead!! else v.first.value
                }
                val n = NutrientSanity.check(trial, servingGrams).size
                if (n < bestIssues) { best = trial; bestIssues = n }
            }
            choice = best
        }
        for ((k, v) in found) {
            val (a, line) = v
            val value = choice.getValue(k)
            reads += if (a.asRead != null && value == a.value) Read(k, line, a.raw, value, fix = "g_read_as_9", asRead = a.asRead)
            else Read(k, line, a.raw, value, fix = a.fix?.takeIf { a.asRead == null })
        }
        return Panel(servingText, servingGrams, choice.toMap(LinkedHashMap()), reads)
    }

    private fun namesField(line: String): Boolean = FIELDS.any { (pattern, _) -> pattern.containsMatchIn(line) } ||
        Regex("\\b(?:trans|added|includes|serving|daily\\s*value)\\b").containsMatchIn(line)

    /**
     * A line that is only a number, taken as the calories. With the macros read,
     * the one closest to what they add up to wins, and only if it is close;
     * without them, the first one after the word "Calories".
     */
    private fun standaloneCalories(lines: List<String>, found: Map<String, Pair<Amount, String>>): Pair<Double, String>? {
        val bare = lines.withIndex().filter { Regex("^\\d{2,4}$").matches(it.value) }
            .mapNotNull { (i, l) -> l.toDoubleOrNull()?.takeIf { it in 10.0..1500.0 }?.let { Triple(i, it, l) } }
        if (bare.isEmpty()) return null
        fun v(m: Metric) = found[m.key]?.first?.value
        val p = v(Metric.PROTEIN); val c = v(Metric.CARBS); val f = v(Metric.FAT)
        if (p != null && c != null && f != null) {
            val expected = 4 * p + 4 * c + 9 * f
            val best = bare.minBy { kotlin.math.abs(it.second - expected) }
            return if (kotlin.math.abs(best.second - expected) <= maxOf(25.0, 0.3 * expected)) best.second to best.third else null
        }
        val word = lines.indexOfFirst { Regex("\\bcalories\\b").containsMatchIn(it) }
        val after = bare.firstOrNull { it.first > word } ?: return null
        return after.second to after.third
    }

    private data class Amount(val value: Double, val raw: String, val fix: String? = null, val asRead: Double? = null)

    /** The first sensible amount on a line; a percent daily value is not an amount. */
    private fun firstAmount(text: String, metric: Metric): Amount? {
        for (m in NUMBER.findAll(text)) {
            val end = m.range.last + 1
            if (text.getOrNull(end) == '%' || text.substring(end).trimStart().startsWith("%")) continue
            val digits = m.groupValues[1]
            val raw = digits.replace(',', '.').toDoubleOrNull() ?: continue
            val unit = m.groupValues[2]
            val v = inUnitOf(metric, raw, unit) ?: continue
            if (!plausible(metric, v)) continue
            if (metric.unit == "g" && unit.isEmpty() && digits.length >= 2 && digits.all { it.isDigit() } && digits.endsWith('9')) {
                val shorter = digits.dropLast(1).toDouble()
                return Amount(shorter, m.value.trim(), asRead = v)
            }
            return Amount(v, m.value.trim())
        }
        // Only a percent: older labels print micronutrients as "%DV" alone.
        if (metric.isMicro) {
            PERCENT.find(text)?.let { pm ->
                pm.groupValues[1].replace(',', '.').toDoubleOrNull()?.let { pct ->
                    return Amount(pct / 100.0 * metric.defaultTarget, pm.value, fix = "from_percent_dv")
                }
            }
        }
        return null
    }

    /** [value] as read, in [metric]'s own unit. No unit read means it is already in it. */
    private fun inUnitOf(metric: Metric, value: Double, unit: String): Double? {
        val grams = when (unit) {
            "g" -> value
            "mg" -> value / 1000.0
            "mcg", "µg", "ug" -> value / 1_000_000.0
            // IU exists on labels only for vitamin D: 40 IU is one microgram.
            "iu" -> if (metric == Metric.VITAMIN_D) value / 40.0 / 1_000_000.0 else return null
            else -> return value
        }
        return when (metric.unit) {
            "g" -> grams
            "mg" -> grams * 1000.0
            "µg" -> grams * 1_000_000.0
            else -> value
        }
    }

    private fun plausible(metric: Metric, v: Double): Boolean = when (metric.unit) {
        "kcal" -> v <= 3000
        "g" -> v <= 300
        "mg" -> v <= 10_000
        else -> v <= 5_000
    }

    /** Undo the usual OCR slips. */
    private fun clean(line: String): String {
        var t = line.lowercase()
            .replace("rng", "mg")
            // "140mg" read as "140m9"
            .replace(Regex("(?<=\\d)\\s?m9\\b"), "mg")
            .replace(Regex("(?<=\\d)o\\b"), "0")
            .replace(Regex("\\bo(?=\\d)"), "0")
            // "0mg" read as "Omg": a letter O before a unit is a zero.
            .replace(Regex("\\bo\\s?(mg|g|mcg)\\b"), "0$1")
        // "Vitamin D3" names the vitamin; its 3 is not an amount.
        t = t.replace(Regex("\\bvit(amin|\\.)?\\s*d[23]\\b"), "vitamin d")
        // "protein5g" -> "protein 5g"
        t = Regex("([a-z])(\\d)").replace(t) { "${it.groupValues[1]} ${it.groupValues[2]}" }
        return t.trim()
    }
}
