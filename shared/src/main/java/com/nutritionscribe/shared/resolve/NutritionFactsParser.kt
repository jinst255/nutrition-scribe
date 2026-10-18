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
            portions = servingGrams?.let { g -> listOfNotNull(unitPortion(servingText, g), Portion("1 serving", g)) }.orEmpty(),
            brand = brand,
        )
    }

    /**
     * "1 slice (40g)" -> one slice is 40 g; "2/3 cup (88g)" -> a cup is 132 g. Scanned,
     * a bread knew only "1 serving", and "3 slices" of it took a generic 29 g slice.
     */
    fun unitPortion(servingText: String?, grams: Double): Portion? {
        val t = servingText?.lowercase() ?: return null
        val m = Regex("^\\s*:?\\s*(\\d+(?:\\.\\d+)?(?:\\s*/\\s*\\d+)?|\\d+\\s+\\d+\\s*/\\s*\\d+)\\s*([a-z]+)").find(t) ?: return null
        val amount = m.groupValues[1].trim().let { a ->
            Regex("^(\\d+)\\s+(\\d+)\\s*/\\s*(\\d+)$").find(a)?.let { it.groupValues[1].toDouble() + it.groupValues[2].toDouble() / it.groupValues[3].toDouble() }
                ?: Regex("^(\\d+(?:\\.\\d+)?)\\s*/\\s*(\\d+)$").find(a)?.let { it.groupValues[1].toDouble() / it.groupValues[2].toDouble() }
                ?: a.toDoubleOrNull()
        }?.takeIf { it > 0 } ?: return null
        val unit = ItemParser.UNITS[m.groupValues[2]] ?: m.groupValues[2].removeSuffix("s").takeIf { it in PIECE_UNITS } ?: return null
        if (unit == "g" || unit == "serving") return null
        return Portion("1 $unit", grams / amount)
    }

    private val PIECE_UNITS = setOf("slice", "piece", "bar", "cookie", "cracker", "chip", "burrito", "tortilla", "egg",
        "patty", "link", "strip", "nugget", "muffin", "bagel", "waffle", "pancake", "gummy", "scoop", "container", "pouch")

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
        Regex("\\btra[a-z]{1,2}s?\\.?\\s*fat\\b") to Metric.TRANS_FAT,
        // "Includes 10g Added Sugars": the number comes BEFORE the words.
        Regex("\\badded\\s*sugars?\\b") to Metric.ADDED_SUGAR,
        Regex("\\btotal\\s*fat\\b") to Metric.FAT,
        // With the slips OCR makes in these words on a curved pouch: "Sodlum",
        // "TalCarbohydrate", "Total Cai.", "Tota Sugas", "Fber", "Caloum".
        Regex("\\bsod[il1]um\\b") to Metric.SODIUM,
        Regex("\\btotal\\s*carb(?:ohydrate|\\.)?s?\\b|carbohydrates?\\b|\\btotal\\s*ca\\S{0,4}\\.") to Metric.CARBS,
        Regex("\\bdietary\\s*fiber\\b|\\bf[i1]?ber\\b|\\bfibre\\b") to Metric.FIBER,
        Regex("\\btotal\\s*sugars?\\b|\\btota?l?\\s*sug[a-z]{1,2}s\\b|(?<!added\\s)\\bsugars?\\b") to Metric.SUGAR,
        Regex("\\bprot[eo]in\\b") to Metric.PROTEIN,
        Regex("\\bcaffeine\\b") to Metric.CAFFEINE,
        // The micronutrients a US label has to print.
        Regex("\\bcholesterol\\b|\\bcholest\\.?\\b") to Metric.CHOLESTEROL,
        Regex("\\bcalcium\\b|\\bca[a-z]{1,3}um\\b") to Metric.CALCIUM,
        Regex("\\biron\\b") to Metric.IRON,
        Regex("\\bpotassium\\b|\\bpotas\\.?\\b") to Metric.POTASSIUM,
        Regex("\\bvit(?:amin|\\.)?\\s*d\\b") to Metric.VITAMIN_D,
        // What a Supplement Facts panel lists.
        Regex("\\bvit(?:amin|\\.)?\\s*c\\b") to Metric.VITAMIN_C,
        Regex("\\bvit(?:amin|\\.)?\\s*a\\b") to Metric.VITAMIN_A,
        Regex("\\bvit(?:amin|\\.)?\\s*e\\b") to Metric.VITAMIN_E,
        Regex("\\bvit(?:amin|\\.)?\\s*k\\b") to Metric.VITAMIN_K,
        Regex("\\bvit(?:amin|\\.)?\\s*b\\s?12\\b") to Metric.VITAMIN_B12,
        Regex("\\bfolate\\b|\\bfolic\\s*acid\\b") to Metric.FOLATE,
        Regex("\\bmagnesium\\b") to Metric.MAGNESIUM,
        Regex("\\bzinc\\b") to Metric.ZINC,
        Regex("\\bselenium\\b") to Metric.SELENIUM,
    )

    /** One line as the text recogniser returned it, with its box on the photo. */
    data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val height: Int get() = bottom - top
        val width: Int get() = right - left
        val centerY: Double get() = (top + bottom) / 2.0
    }

    /**
     * Reads a panel from recognised lines and where they sit. A Supplement Facts
     * panel prints its names in one column and its amounts in another, and the
     * recogniser returns all the names and then all the amounts: read in that
     * order, "Vitamin D" never met its "50 mcg". Lines sharing a row are put back
     * together, left to right, first. A label photographed sideways (lines taller
     * than wide) is read as the recogniser gave it.
     */
    fun parse(lines: List<OcrLine>): Panel {
        if (lines.isEmpty()) return parse("")
        val flat = lines.count { it.width >= it.height }
        if (flat * 10 < lines.size * 6) return parse(lines.joinToString("\n") { it.text })
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (l in lines.sortedBy { it.centerY }) {
            val row = rows.lastOrNull()
            if (row != null) {
                val cy = row.sumOf { it.centerY } / row.size
                val h = minOf(row.minOf { it.height }, l.height).coerceAtLeast(1)
                if (kotlin.math.abs(l.centerY - cy) <= h * 0.5) { row += l; continue }
            }
            rows += mutableListOf(l)
        }
        return parse(rows.joinToString("\n") { r -> r.sortedBy { it.left }.joinToString(" ") { it.text } })
    }

    private val NUMBER = Regex("(\\d+(?:[.,]\\d+)?)(?!\\d)\\s*(mg|g|mcg|µg|ug|iu)?(?![a-z])")
    private val PERCENT = Regex("(\\d+(?:[.,]\\d+)?)\\s*%")

    fun parse(ocr: String): Panel {
        val cleaned = ocr.lines().map { clean(it) }.filter { it.isNotBlank() }
        val found = linkedMapOf<String, Pair<Amount, String>>()
        var servingText: String? = null
        var servingGrams: Double? = null
        val reads = mutableListOf<Read>()

        // "Total Sugars 12g (Incl. 12g Added Sugars…": the added sugars' number comes
        // before its words, so it is read off the whole line before the line is cut up.
        for (line in cleaned) {
            // "Incl." as OCR reads it: "lncl.", "1ncl", "inci." (a jelly's added sugars took the 12 of "12%").
            Regex("\\b[il1]nc[il1](?:udes|\\.)?\\s*(\\d+(?:\\.\\d+)?)\\s*g\\s*(?:of\\s*)?add[a-z]*\\s*sug").find(line)?.let { m ->
                found.getOrPut(Metric.ADDED_SUGAR.key) { Amount(m.groupValues[1].toDouble(), m.groupValues[1] + "g") to line }
            }
        }
        // A small pack prints its panel as a paragraph: "Calories 70, Total Fat 0g (0%
        // DV), Sat. Fat 0g…". Read as one line, every nutrient took the first number
        // after its name — the next nutrient's. Each name gets its own piece.
        val lines = cleaned.flatMap { splitFields(it) }

        for ((i, line) in lines.withIndex()) {
            // The ingredients and directions beside the panel name nutrients too ("water,
            // sugar, yeast", "folic acid), let product stand 2"): never a value.
            if (looksLikeIngredients(line)) continue
            val servingAt = SERVING_SIZE.find(line)
            if (servingText == null && servingAt != null) {
                val rest = line.substring(servingAt.range.last + 1).trim().trimStart(':', ' ').trim()
                    .ifBlank { lines.getOrNull(i + 1).orEmpty() }
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
                if (metric == Metric.SUGAR && Regex("\\badded\\b|\\bincludes\\b").containsMatchIn(line) &&
                    !Regex("^\\s*tota?l?\\s*sug").containsMatchIn(line)) continue
                if (metric == Metric.CALORIES && Regex("\\bfrom\\s*fat\\b").containsMatchIn(line)) continue
                // The front of the box: "NET WT 32.5 OZ" is not 325 calories.
                if (metric == Metric.CALORIES && Regex("\\bnet\\s*w|\\bnet\\s*weight").containsMatchIn(line)) continue
                if (metric == Metric.FAT && Regex("\\b(?:saturated|trans)\\b").containsMatchIn(line)) continue
                if (metric == Metric.ADDED_SUGAR) {
                    // "Added Sugars 8g" in its own piece; the "Incl. 8g" form was read above.
                    firstAmount(line.substring(m.range.last + 1), metric)?.let { found[metric.key] = it to line }
                    continue
                }
                val after = line.substring(m.range.last + 1)
                val here = firstAmount(after, metric)
                // The value can sit on the next line, but not when that line is
                // another nutrient's: "Cholesterol 0mg" read as "Cholesterol Omg"
                // took sodium's 135 mg, and a "Calories" whose number was printed
                // big and apart took the fat under it.
                val next = if (here == null) lines.getOrNull(i + 1)?.takeIf { !namesField(it) && !PER_CONTAINER.containsMatchIn(it) && !looksLikeIngredients(it) }
                    ?.let { firstAmount(it, metric) } else null
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

        // "Calories 70" read as "Calories /0" or "(0": zero calories with real carbs.
        // Labels round calories to the nearest 10 (5 under 50), and the macros say
        // roughly where: that, marked as worked out, beats a 0 nobody checks.
        run {
            fun v(m: Metric) = found[m.key]?.first?.value
            val p = v(Metric.PROTEIN) ?: 0.0; val c = v(Metric.CARBS) ?: 0.0; val f = v(Metric.FAT) ?: 0.0
            val atwater = 4 * p + 4 * c + 9 * f
            val kcal = v(Metric.CALORIES)
            if (atwater >= 20 && (kcal == null || kcal < atwater * 0.25)) {
                val est = if (atwater < 50) Math.round(atwater / 5.0) * 5.0 else Math.round(atwater / 10.0) * 10.0
                found[Metric.CALORIES.key] = Amount(est, "", fix = "kcal_from_macros") to (found[Metric.CALORIES.key]?.second ?: "")
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

    /** "17 servings per container" is a count of servings, never a nutrient's value. */
    private val PER_CONTAINER = Regex("\\bservings?\\s*per\\b|\\bper\\s*container\\b|\\babout\\s*\\d+\\s*servings?\\b")

    private fun looksLikeIngredients(line: String): Boolean =
        Regex("\\bingredients?\\b|\\bcontains\\s*:|\\b(?:let|heat|cook|microwave|stand|remove|refrigerate|keep)\\b").containsMatchIn(line) ||
            line.count { it == ',' } >= 3

    private val SERVING_SIZE = Regex("\\bs[eo]r[a-z]{0,5}\\.?\\s*s[i1l]z[a-z]{0,2}\\b")

    /**
     * [line] cut at every nutrient name it holds, when it holds two or more: a
     * paragraph-style panel, or two columns OCR read as one row ("Iron 1mg 6%
     * Potassium 100mg 2%" gave iron the potassium's 100). Text before the first
     * name stays a piece of its own.
     */
    private fun splitFields(line: String): List<String> {
        val hits = FIELDS.flatMap { (pattern, _) -> pattern.findAll(line).map { it.range } }
            .sortedWith(compareBy<IntRange> { it.first }.thenByDescending { it.last })
        val starts = mutableListOf<Int>()
        var reach = -1
        for (r in hits) {
            if (r.first <= reach) continue
            starts += r.first; reach = r.last
        }
        if (starts.size < 2) return listOf(line)
        val pieces = mutableListOf<String>()
        if (starts.first() > 0) pieces += line.substring(0, starts.first())
        for ((k, s) in starts.withIndex()) pieces += line.substring(s, starts.getOrNull(k + 1) ?: line.length)
        return pieces.map { it.trim().trim(',', ';').trim() }.filter { it.isNotBlank() }
    }

    private fun namesField(line: String): Boolean = FIELDS.any { (pattern, _) -> pattern.containsMatchIn(line) } ||
        // "Trars Fat 0g" is still another nutrient's line, and "NET WT 32.5 OZ" no one's.
        Regex("\\b(?:trans|added|includes|serving|daily\\s*value)\\b|\\bfats?\\b|\\bnet\\s*w").containsMatchIn(line)

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
            // "(0'% DV)": a stray mark between the number and its percent sign.
            if (Regex("^\\s*['’`\"]?\\s*%").containsMatchIn(text.substring(end))) continue
            // "32.5 OZ", "2 LB": a weight on the box, never a calorie count.
            if (metric == Metric.CALORIES && Regex("^\\s*(?:0z|oz|lb|fl\\b|servings?)").containsMatchIn(text.substring(end))) continue
            val digits = m.groupValues[1]
            val raw = digits.replace(',', '.').toDoubleOrNull() ?: continue
            val unit = m.groupValues[2]
            // A micronutrient's number sits right after its name, with a unit or a %. One
            // words away with neither is text from beside the panel.
            if (metric.isMicro && unit.isEmpty() && Regex("[a-z]{4,}").containsMatchIn(text.substring(0, m.range.first))) continue
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
                pm.groupValues[1].replace(',', '.').toDoubleOrNull()?.let { pct0 ->
                    // "(20% DV)" read as "(209% DV)": past 100% on a food, a last 9 is the %.
                    val pct = if (pct0 > 100 && pm.groupValues[1].endsWith("9")) pm.groupValues[1].dropLast(1).toDouble() else pct0
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
            // "20mg" read as "20ng" or "20mng".
            .replace(Regex("(?<=\\d)\\s?m?ng\\b"), "mg")
            // "140mg" read as "140m9"
            .replace(Regex("(?<=\\d)\\s?m9\\b"), "mg")
            .replace(Regex("(?<=\\d)o\\b"), "0")
            .replace(Regex("\\bo(?=\\d)"), "0")
            // "0mg" read as "Omg": a letter O before a unit is a zero.
            .replace(Regex("\\bo\\s?(mg|g|mcg)\\b"), "0$1")
            // "0mg" as "Orrg" or "ong", "190mg" as "190rmg", "250mg" as "25OmE".
            .replace(Regex("\\bo(?:rr|rn|n)g\\b"), "0mg")
            .replace(Regex("(?<=\\d)\\s?r(?:m|r|n)g\\b"), "mg")
            .replace(Regex("(?<=\\d)o(?=m)"), "0")
            .replace(Regex("(?<=\\d)me\\b"), "mg")
            // "1mg" as "fmg" or "Lmg", "1g" as "tg"; "17g" as "t 7g", "11g" as "t1g".
            .replace(Regex("(?<=\\s)[fl|!]\\s?(mg|g)\\b"), "1$1")
            .replace(Regex("(?<![a-z])tg\\b"), "1g")
            .replace(Regex("(?<![a-z])t\\s?(?=\\d+(?:\\.\\d+)?\\s?g\\b)"), "1")
        // "Vitamin D3" names the vitamin; its 3 is not an amount.
        t = t.replace(Regex("\\bvit(amin|\\.)?\\s*d[23]\\b"), "vitamin d")
        // "protein5g" -> "protein 5g"
        t = Regex("([a-z])(\\d)").replace(t) { "${it.groupValues[1]} ${it.groupValues[2]}" }
        return t.trim()
    }
}
