package com.nutritionscribe.shared.resolve

import com.nutritionscribe.shared.food.FoodQuery
import kotlin.math.ln

/**
 * What a product is from the words on the front of its box: brand and name, in
 * the biggest print. The answer is offered as "Is this Quaker Life Original?" and
 * only when one product clearly wins; otherwise the person is told to turn the box
 * to the Nutrition Facts, which always works.
 *
 * Text recognition reads a logo badly ("QUARER" for QUAKER, the stylised "life" not
 * at all), so a word the label pack has never seen is corrected to one it has, one
 * slip away, before matching. The old front match took the five tallest lines as a
 * search and offered Smarties, toast rounds and an energy drink for a Life box.
 */
class FrontMatcher(private val sql: SqlReader, private val labels: LabelPackSource) {

    data class Word(val text: String, val height: Int)

    data class Match(
        val candidate: FoodCandidate,
        /** Sure enough to ask "Is this …?" with one answer. */
        val sure: Boolean,
        /** The other likely products, for "It's a different one". */
        val others: List<FoodCandidate>,
        /** The words it went on, after correction, for the debug log. */
        val words: List<String>,
        /**
         * A flavour printed big on the box that [candidate] is not, and no label has:
         * "Pumpkin Spice" on a Life box. A kind nobody has saved yet.
         */
        val newKind: String? = null,
    )

    /** [lines]: each recognised line's text and its height in the picture. */
    fun match(lines: List<Pair<String, Int>>): Match? {
        val tallest = lines.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: return null
        // Every word, weighted by how big it was printed: the brand and the name are
        // the largest type on a box, the boilerplate the smallest.
        val raw = lines.flatMap { (text, h) ->
            Regex("[A-Za-z][A-Za-z']{2,}").findAll(text).map { Word(it.value.lowercase().replace("'", ""), h) }.toList()
        }.filter { it.text !in NOISE }
        if (raw.isEmpty()) return null
        val weighted = linkedMapOf<String, Double>()
        for (w in raw) {
            val known = correct(w.text) ?: continue
            val weight = 0.4 + 0.6 * w.height.toDouble() / tallest
            weighted[known] = maxOf(weighted[known] ?: 0.0, weight)
        }
        if (weighted.isEmpty()) return null
        val brandWords = weighted.keys.filter { labels.isBrandWord(it) }

        // With a brand read, only that brand's products; without one, the products
        // sharing the rarest words.
        val pool: List<Long> = if (brandWords.isNotEmpty()) {
            query("SELECT DISTINCT id FROM pword WHERE in_brand = 1 AND word IN (${brandWords.joinToString(",") { "?" }}) LIMIT 4000", brandWords) { it.long(0) }
        } else {
            val rare = weighted.keys.sortedBy { frequency(it) }.take(3)
            query("SELECT id FROM pword WHERE word IN (${rare.joinToString(",") { "?" }}) GROUP BY id ORDER BY COUNT(*) DESC LIMIT 400", rare) { it.long(0) }
        }
        if (pool.isEmpty()) return null
        val words = weighted.keys.toList()
        val hits = mutableMapOf<Long, MutableSet<String>>()
        for (chunk in pool.chunked(900)) {
            query("SELECT id, word FROM pword WHERE id IN (${chunk.joinToString(",")}) AND word IN (${words.joinToString(",") { "?" }})", words) {
                it.long(0) to it.string(1).orEmpty()
            }.forEach { (id, w) -> hits.getOrPut(id) { mutableSetOf() } += w }
        }
        // Scored on the words it shares, the product words above all.
        val scoredIds = hits.entries.map { (id, ws) ->
            val product = ws.filter { it !in brandWords }
            id to (ws.sumOf { weighted.getValue(it) } + product.sumOf { weighted.getValue(it) } * 0.5) to product.size
        }.sortedByDescending { it.first.second }.take(60)
        val rows = scoredIds.mapNotNull { (scored, productWords) ->
            val (id, score) = scored
            labels.byId(id)?.let { c ->
                // Words of its own name the box did not show: a variant (honey graham,
                // cinnamon) is likelier the plain one when those words were not read.
                val desc = LabelPackSource.words(c.name).toSet()
                val unseen = (desc - words.toSet() - LabelPackSource.words(c.brand.orEmpty()).toSet()).filter { w -> w.none { it.isDigit() } }
                Triple(c, score - unseen.size * 0.35 + minOf(1.0, ln(1.0 + popOf(id)) * 0.15), productWords)
            }
        }.sortedByDescending { it.second }
            // One product under several label versions ("Original Multigrain Cereal",
            // "Multigrain Original Cereal"): the same words are the same product.
            .distinctBy { LabelPackSource.words(it.first.name).toSortedSet().joinToString(" ") }
        val best = rows.firstOrNull() ?: return null
        val second = rows.getOrNull(1)?.second ?: 0.0
        // Sure: its brand was read, at least one of its product words, and it is
        // clearly ahead of the next product.
        val sure = brandWords.isNotEmpty() && best.third >= 1 && best.second - second >= 0.6
        // Big flavour words the best product does not have: a new kind of it, unless
        // another product read better has them (then that product is the answer).
        val own = (LabelPackSource.words(best.first.name) + LabelPackSource.words(best.first.brand.orEmpty())).toSet()
        val extra = weighted.filter { (w, weight) -> weight >= 0.66 && Kinds.isFlavourWord(w) && w !in own }.keys
        val newKind = extra.takeIf { brandWords.isNotEmpty() && best.third >= 1 && it.isNotEmpty() }
            ?.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        return Match(best.first, sure && newKind == null, rows.drop(1).take(3).map { it.first }, words, newKind)
    }

    /** The word as the label pack spells it, or null when nothing close is known. */
    fun correct(word: String): String? {
        val w = FoodQuery.stem(word)
        if (w.length < 3) return null
        if (labels.knowsWord(w)) return w
        if (w.length < 5) return null
        val variants = edits(w)
        val known = variants.chunked(400).flatMap { chunk ->
            query("SELECT word, MAX(in_brand) FROM pword WHERE word IN (${chunk.joinToString(",") { "?" }}) GROUP BY word", chunk) {
                it.string(0).orEmpty() to (it.long(1) > 0)
            }
        }
        // A brand beats any other word one slip away: "quarer" is Quaker.
        return known.firstOrNull { it.second }?.first ?: known.maxByOrNull { frequency(it.first) }?.first
    }

    private fun edits(w: String): List<String> = buildSet {
        val letters = 'a'..'z'
        for (i in w.indices) {
            add(w.removeRange(i, i + 1))
            for (c in letters) if (c != w[i]) add(w.substring(0, i) + c + w.substring(i + 1))
            if (i + 1 < w.length) add(w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2))
        }
        for (i in 0..w.length) for (c in letters) add(w.substring(0, i) + c + w.substring(i))
    }.toList()

    private val freq = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private fun frequency(w: String): Int = freq.getOrPut(w) {
        query("SELECT COUNT(*) FROM pword WHERE word = ?", listOf(w)) { it.long(0).toInt() }.firstOrNull() ?: 0
    }

    private fun popOf(id: Long): Int =
        query("SELECT pop FROM product WHERE id = ?", listOf(id.toString())) { it.long(0).toInt() }.firstOrNull() ?: 0

    private fun <T> query(q: String, args: List<String>, map: (SqlRow) -> T): List<T> =
        runCatching { sql.query(q, args, map) }.getOrDefault(emptyList())

    companion object {
        /** What every box says and no product is called. */
        private val NOISE = setOf(
            "mega", "size", "family", "net", "new", "look", "improved", "serving", "suggestion",
            "enlarged", "image", "shown", "heart", "healthy", "keep", "source", "excellent", "made",
            "with", "and", "the", "per", "contains", "calories", "cal", "sodium", "sat", "fat", "sugars", "added",
            "daily", "nutrition", "facts", "see", "panel", "side", "for", "information", "grams",
            "ounces", "pack", "count", "ct", "oz", "lb", "box", "bag", "resealable", "artificial", "flavors",
            "colors", "preservatives", "gluten", "free", "non", "gmo", "project", "verified", "certified",
            "kosher", "usda", "organic", "real", "taste", "now", "more", "less", "than", "only",
            "www", "com", "since", "est", "brand", "official", "share", "sharing", "limited", "edition",
        )
    }
}
