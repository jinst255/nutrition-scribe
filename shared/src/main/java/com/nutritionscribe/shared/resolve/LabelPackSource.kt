package com.nutritionscribe.shared.resolve

import com.nutritionscribe.shared.food.FoodQuery
import com.nutritionscribe.shared.model.Metric
import kotlin.math.ln

/**
 * Real package labels: the optional label pack (built by tools/build_label_pack.py
 * from USDA Branded Foods — what manufacturers themselves submitted).
 *
 * Tested against 54 real labels, reading the label instead of estimating took
 * packaged-food accuracy from 83% to 91% and halved the sodium misses. It is a
 * download, not part of the APK, because it is ~40 MB on the phone.
 *
 * Schema (see the build script, which is authoritative):
 *   product(id, brand, description, serving_g, serving_text, count, count_unit,
 *           kcal, protein_g, ... per ONE serving x10, pop)
 *   gtin(gtin, id), pword(word, id, in_brand), brand_word(word)
 */
class LabelPackSource(
    private val sql: SqlReader,
    /** A format 4 pack: the five label micronutrients follow the other columns. */
    private val hasMicros: Boolean = false,
) {

    /**
     * [counted]: the person gave a bare count ("3 oreos"), so a product sold in
     * pieces is likelier than one measured by the cup.
     */
    fun search(
        text: String,
        brand: String? = null,
        limit: Int = 6,
        counted: Boolean = false,
        /** The unit they said ("container"): a label measured in it fits better. */
        unit: String? = null,
    ): List<FoodCandidate> {
        // Labels say it their way: Kodiak sells "flapjack mix", not pancakes.
        val said = words(text)
        val words = (said + said.flatMap { LABEL_SYNONYMS[it].orEmpty() }).distinct()
        if (words.isEmpty()) return emptyList()
        val needBrand = brand?.let { words(it) }.orEmpty()
        // The brand's words go into the search too. Without them, "protein pancakes"
        // shortlisted three hundred other protein products and Kodiak never made it.
        val sqlWords = (words + needBrand).distinct()
        val hits = runCatching {
            sql.query(
                "SELECT id, COUNT(DISTINCT word), SUM(in_brand) FROM pword WHERE word IN (${sqlWords.joinToString(",") { "?" }})" +
                    " GROUP BY id ORDER BY COUNT(DISTINCT word) DESC LIMIT 200",
                sqlWords,
            ) { Triple(it.long(0), it.long(1).toInt(), it.long(2).toInt()) }
        }.getOrDefault(emptyList())
        if (hits.isEmpty()) return emptyList()
        val hitCount = hits.associate { it.first to it.second }

        val rows = read(hits.map { it.first })
        val scored = rows.mapNotNull { (c, pop) ->
            val brandWords = words("${c.brand.orEmpty()} ${c.owner.orEmpty()}").toSet()
            if (needBrand.isNotEmpty() && !needBrand.all { it in brandWords }) return@mapNotNull null
            val descWords = words(c.name).toSet()
            val extra = (descWords - words.toSet() - brandWords).size
            var score = (hitCount[c.id.removePrefix("label:").toLong()] ?: 0) * 10.0 - extra * 1.5
            if (brandWords.any { it in words }) score += 5
            // Only the brand said ("3 oreos", "a built bar"): the product everyone
            // means is the one sold longest, under the most labels.
            if (said.isNotEmpty() && said.all { it in brandWords }) score += minOf(10.0, ln(1.0 + pop) * 2.0)
            // How many label versions this product has had: the long-selling plain
            // product has dozens, a limited edition has one. Without it "3 oreos"
            // tied "Oreo fudge covered bites" with the cookie everyone means.
            score += minOf(6.0, ln(1.0 + pop) * 1.5)
            // The ordinary version unless they said otherwise: "a can of coke" is not
            // Diet Coke, "3 oreos" not the fudge-covered minis. (Measured: without
            // this the pick was the diet or variety product one time in five.)
            val allWords = descWords + words(c.brand.orEmpty())
            for (w in SPECIAL) if (w in allWords && w !in words) score -= 8.0
            if (unit != null && c.portions.any { p -> p.label.endsWith(" $unit") || p.label.contains(" $unit ") }) score += 4.0
            if (unit != null && c.labelServingText?.contains(unit) == true) score += 2.0
            // A bare count ("3 oreos") fits a label counted in pieces, not in packs.
            if (counted && c.portions.any { p -> p.label != "1 serving" && PACKAGES.none { p.label.endsWith(" $it") } &&
                    MEASURE_LABELS.none { p.label.endsWith(" $it") } }) score += 4.0
            // Flavours they did not say: "fairlife 2% milk" is not the strawberry one.
            for (f in FLAVOURS) if (f in descWords && f !in words) score -= 5.0
            c to score
        }
        return scored.sortedByDescending { it.second }
            .distinctBy { "${it.first.brand?.lowercase()}|${it.first.name.lowercase()}|${it.first.labelServingGrams}" }
            .take(limit).map { it.first }
    }

    fun byBarcode(code: String): FoodCandidate? {
        val digits = code.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        // UPC-A (12), EAN-13 and GTIN-14 name the same product with leading zeros.
        val forms = listOf(digits, digits.trimStart('0'), digits.padStart(12, '0'),
            digits.padStart(13, '0'), digits.padStart(14, '0')).distinct()
        val ids = runCatching {
            sql.query(
                "SELECT id FROM gtin WHERE gtin IN (${forms.joinToString(",") { "?" }}) LIMIT 1",
                forms,
            ) { it.long(0) }
        }.getOrDefault(emptyList())
        return read(ids).firstOrNull()?.first?.copy(barcode = digits)
    }

    fun byId(id: Long): FoodCandidate? = read(listOf(id)).firstOrNull()?.first

    /** Does any label mention this word? A brand the USDA list has never heard of. */
    private val known = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val brands = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun knowsWord(word: String): Boolean = known.getOrPut(word) {
        runCatching { sql.query("SELECT 1 FROM pword WHERE word = ? LIMIT 1", listOf(word)) { true }.isNotEmpty() }
            .getOrDefault(false)
    }

    fun isBrandWord(word: String): Boolean = brands.getOrPut(word) {
        runCatching { sql.query("SELECT 1 FROM brand_word WHERE word = ? LIMIT 1", listOf(word)) { true }.isNotEmpty() }
            .getOrDefault(false)
    }

    private fun read(ids: List<Long>): List<Pair<FoodCandidate, Int>> {
        if (ids.isEmpty()) return emptyList()
        return runCatching {
            sql.query(
                "SELECT id, NULL, brand, description, serving_g, " +
                    "serving_text, count, count_unit, $NUTRIENTS, pop, measure, measure_unit, owner" +
                    (if (hasMicros) ", $MICROS" else "") + " FROM product p " +
                    "WHERE id IN (${ids.joinToString(",") { "?" }})",
                ids.map { it.toString() },
            ) { r ->
                val servingG = r.doubleOrNull(4)
                // Stored per serving x10; the rest of the app thinks per 100 g.
                val per100 = buildMap {
                    if (servingG != null && servingG > 0) {
                        MACRO_COLUMNS.forEachIndexed { i, (_, metric) ->
                            r.doubleOrNull(8 + i)?.let { put(metric.key, it / 10.0 / servingG * 100.0) }
                        }
                        if (hasMicros) MICRO_COLUMNS.forEachIndexed { i, (_, metric) ->
                            r.doubleOrNull(12 + MACRO_COLUMNS.size + i)?.let { put(metric.key, it / 10.0 / servingG * 100.0) }
                        }
                    }
                }
                val count = r.doubleOrNull(6)
                val countUnit = r.string(7)
                val portions = buildList {
                    if (servingG != null && count != null && count > 0 && !countUnit.isNullOrBlank()) {
                        add(Portion("1 $countUnit", servingG / count))
                    }
                    if (servingG != null) add(Portion("1 serving", servingG))
                    // "1/4 cup (28 g)" -> a cup of it weighs 112 g.
                    val measure = r.doubleOrNull(9 + MACRO_COLUMNS.size)
                    val measureUnit = r.string(10 + MACRO_COLUMNS.size)
                    if (servingG != null && measure != null && measure > 0 && !measureUnit.isNullOrBlank()) {
                        add(Portion("1 $measureUnit", servingG / measure))
                    }
                }
                FoodCandidate(
                    id = "label:${r.long(0)}",
                    name = r.string(3).orEmpty().titleCase(),
                    origin = Origin.LABEL,
                    per100g = per100,
                    portions = portions,
                    defaultGrams = servingG,
                    labelServingGrams = servingG,
                    labelServingText = r.string(5),
                    brand = r.string(2)?.titleCase(),
                    owner = r.string(11 + MACRO_COLUMNS.size),
                    barcode = r.string(1),
                ) to (r.doubleOrNull(8 + MACRO_COLUMNS.size)?.toInt() ?: 0)
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        /** The pack's own columns: USDA's first nine, then (format 4) five label micros. */
        private val MACRO_COLUMNS = UsdaFoodSource.NUTRIENT_COLUMNS.take(9)
        private val MICRO_COLUMNS = listOf(
            "calcium_mg" to Metric.CALCIUM, "iron_mg" to Metric.IRON, "potassium_mg" to Metric.POTASSIUM,
            "vitamin_d_ug" to Metric.VITAMIN_D, "cholesterol_mg" to Metric.CHOLESTEROL,
        )
        private val NUTRIENTS = MACRO_COLUMNS.joinToString(", ") { it.first }
        private val MICROS = MICRO_COLUMNS.joinToString(", ") { it.first }
        private val NOISE = setOf("oz", "fl", "ct", "count", "pack", "pk", "box", "bag", "bottle",
            "can", "jar", "size", "value", "family", "net", "wt", "per", "brand")

        fun synonymsOf(word: String): List<String> = LABEL_SYNONYMS[word].orEmpty()

        private val wordCache = object : java.util.LinkedHashMap<String, List<String>>(512, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > 4000
        }

        /** Must stay identical to words_of() in tools/build_label_pack.py. */
        fun words(text: String): List<String> = synchronized(wordCache) { wordCache[text] } ?: computeWords(text).also {
            synchronized(wordCache) { wordCache[text] = it }
        }

        private fun computeWords(text: String): List<String> =
            Regex("[a-z0-9]+").findAll(text.lowercase().replace("'", ""))
                .map { it.value }
                .filter { it.length >= 2 && it !in NOISE && (it.any { c -> c.isDigit() } || FoodQuery.contentWords(it).isNotEmpty() || it in KEEP) }
                .map { FoodQuery.stem(it) }
                .toList()

        private val SPECIAL = listOf("diet", "zero", "light", "lite", "free", "reduced", "low", "lo",
            "concentrate", "powder", "pod", "drop", "syrup", "dry", "mix", "kit",
            "mini", "minis", "family", "variety", "pack", "multipack", "fudge", "dipped", "covered", "thin",
            "thins", "decaf", "keto", "carb", "sugarfree", "caffeine", "fridge").map { FoodQuery.stem(it) }

        private val PACKAGES = listOf("pack", "package", "container", "pouch", "bag", "bottle", "can", "box", "packet", "carton", "jar", "tub")
        private val MEASURE_LABELS = listOf("cup", "tbsp", "tsp")
        private val FLAVOURS = listOf("vanilla", "strawberry", "chocolate", "blueberry", "cherry", "peach", "raspberry",
            "banana", "coconut", "cinnamon", "caramel", "mint", "lemon", "lime", "orange", "mango", "apple", "grape",
            "honey", "maple", "berry", "cookie", "peanut", "almond", "pumpkin", "coffee", "mocha", "salted",
            "birthday", "cake", "smore", "fruit", "tropical", "watermelon", "pineapple", "cheddar", "ranch", "bbq",
            "jalapeno", "spicy", "sour", "cream").map { FoodQuery.stem(it) }

        private val LABEL_SYNONYMS = mapOf(
            "pancake" to listOf("flapjack"), "flapjack" to listOf("pancake"),
            "soda" to listOf("cola", "soft"), "cola" to listOf("soda"),
            "chip" to listOf("crisp"), "yogurt" to listOf("yoghurt"),
            "shake" to listOf("drink"), "drink" to listOf("shake"),
            "coke" to listOf("cola", "coca"), "pop" to listOf("soda"),
        )

        /** Short words FoodQuery treats as filler but that name products. */
        private val KEEP = setOf("big", "mini", "light", "lite", "zero", "diet", "original", "kind", "simply", "just", "pure", "good")

        private fun String.titleCase(): String =
            lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }
}
