package com.nutritionscribe.shared.resolve

import com.nutritionscribe.shared.food.FoodQuery
import kotlinx.serialization.Serializable

/**
 * A food the user has confirmed once: what it is, how much they usually have, and
 * the numbers for that amount. This is layer 1 — checked before anything else and
 * trusted without asking, because the user already looked at it.
 *
 * [nutrients] are for [grams] (when the weight is known) or for exactly
 * [amount] × [unit] as they said it. The numbers are authoritative even when the
 * user typed them over a database answer, so scaling always goes through them,
 * never back through [candidate]'s per-100 g figures.
 */
@Serializable
data class PersonalFood(
    val key: String,
    /** The food words as they said them the first time, for display and search. */
    val phrase: String,
    val name: String,
    val candidate: FoodCandidate? = null,
    val amount: Double? = null,
    val unit: String? = null,
    /** "smallish" the first time is not their usual; see [Resolver.scale]. */
    val size: String? = null,
    val grams: Double? = null,
    val nutrients: Map<String, Double>,
    /** Grams in ONE of each unit they have confirmed: "cup" -> 244. */
    val unitGrams: Map<String, Double> = emptyMap(),
    /** A saved meal: its parts, so it can be opened back up. Empty for one food. */
    val parts: List<MealPart> = emptyList(),
    val timesUsed: Int = 1,
    val updatedAt: Long = 0,
    /**
     * The other kinds of it they have had (the strawberry when [candidate] is the
     * vanilla), so switching back is one tap. See [Kinds].
     */
    val kinds: List<FoodCandidate> = emptyList(),
    /** Times each kind was logged, by its label ("Vanilla"). */
    val kindUses: Map<String, Int> = emptyMap(),
    /** When logging it asks which kind: [ASK_WHEN_DIFFERENT], [ASK_ALWAYS] or [ASK_NEVER]. */
    val askKind: String = ASK_WHEN_DIFFERENT,
    /** The photo of its label they scanned, a file name in the app's labels folder. */
    val photo: String? = null,
) {
    val isMeal: Boolean get() = parts.isNotEmpty()

    /** This kind and the others they have had, the one they have now first. */
    val allKinds: List<FoodCandidate> get() = listOfNotNull(candidate) + kinds

    companion object {
        const val ASK_WHEN_DIFFERENT = "differ"
        const val ASK_ALWAYS = "always"
        const val ASK_NEVER = "never"
    }
}

@Serializable
data class MealPart(val name: String, val said: String, val grams: Double?, val nutrients: Map<String, Double>)

/** Where personal foods live. Room in the app, a map in the tests. */
interface PersonalStore {
    fun get(key: String): PersonalFood?
    fun all(): List<PersonalFood>
    fun put(food: PersonalFood)
    fun remove(key: String)
}

class InMemoryPersonalStore : PersonalStore {
    private val foods = linkedMapOf<String, PersonalFood>()
    override fun get(key: String) = foods[key]
    override fun all() = foods.values.toList()
    override fun put(food: PersonalFood) { foods[food.key] = food }
    override fun remove(key: String) { foods.remove(key) }
}

object PersonalKeys {

    /**
     * The identity of an item, independent of how much of it there was.
     *
     * "2 cups whole milk" and "whole milk" are the same food; "20g protein shake"
     * and "38g protein shake" are not, so a stated protein figure is part of the key.
     */
    /**
     * The brand is part of what a food IS: without it "a built bar" was remembered
     * as "bar", and "a kind bar" would have logged silently as a Built bar.
     */
    fun of(item: ItemParser.Item): String =
        of(listOfNotNull(item.brand, item.food).joinToString(" "), item.statedProteinG)

    fun of(food: String, statedProteinG: Double? = null): String {
        val words = FoodQuery.contentWords(food).distinct().sorted()
        val base = words.joinToString(" ")
        return if (statedProteinG != null) "$base#p${fmt(statedProteinG)}" else base
    }

    /** A whole sentence, for meals saved as one thing ("my usual bag of veggies"). */
    fun ofText(text: String): String =
        FoodQuery.contentWords(ItemParser.normaliseText(text)).distinct().sorted().joinToString(" ")

    private fun fmt(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    /**
     * Same food despite a typo? Every word must match, allowing one slip in a word
     * of five letters or more ("yogrut", "peper"). Deliberately strict: this is
     * the test for logging SILENTLY, and a near miss must never pass as a hit —
     * "raspberry jam" is not "raspberries".
     */
    fun sameFood(a: String, b: String): Boolean {
        if (a == b) return true
        val (aw, ap) = split(a)
        val (bw, bp) = split(b)
        if (ap != bp || aw.size != bw.size || aw.isEmpty()) return false
        val unmatched = bw.toMutableList()
        for (w in aw) {
            val hit = unmatched.firstOrNull { it == w } ?: unmatched.firstOrNull {
                minOf(it.length, w.length) >= 5 && editDistance(it, w) <= 1
            } ?: return false
            unmatched.remove(hit)
        }
        return true
    }

    /** Looser: worth offering as a suggestion on a confirm card, never silently. */
    fun similarity(a: String, b: String): Double {
        val (aw, _) = split(a)
        val (bw, _) = split(b)
        if (aw.isEmpty() || bw.isEmpty()) return 0.0
        val inter = aw.count { w -> bw.any { it == w || (minOf(it.length, w.length) >= 5 && editDistance(it, w) <= 1) } }
        return inter.toDouble() / (aw.toSet() + bw.toSet()).size
    }

    private fun split(key: String): Pair<List<String>, String?> {
        val hash = key.indexOf('#')
        val words = (if (hash >= 0) key.substring(0, hash) else key).split(" ").filter { it.isNotBlank() }
        return words to (if (hash >= 0) key.substring(hash) else null)
    }

    /**
     * Edit distance counting a swap of two neighbouring letters as one edit
     * ("yogrut" is one slip from "yogurt", not two). Returns 2 for anything
     * further, which is all the callers need to know.
     */
    fun editDistance(a: String, b: String): Int {
        if (kotlin.math.abs(a.length - b.length) > 1) return 2
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
            }
        }
        return d[a.length][b.length]
    }
}
