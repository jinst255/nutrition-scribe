package com.nutritionscribe.shared.resolve

import com.nutritionscribe.shared.food.FoodQuery

/**
 * What a change said to an entry means: "actually 2 slices", "remove the cheese",
 * "make the milk 300 mL", "add a tablespoon of honey".
 *
 * Adding used to resolve whatever was said as new foods. Fixing a sandwich by
 * voice ("that butter I toasted into the bread… two slices of cheese… that
 * avocado… as much spinach as I could fit on the two sandwiches") logged the
 * butter, avocado, bread and cheese a second time, and a generic 600 g sandwich
 * on top. A food that is already a line is that line: with an amount it sets the
 * amount, without one it was only being pointed at.
 *
 * Saying what a line IS ("that bacon is low sodium bacon", "it was Great Value")
 * used to add the words as a food of their own: the owner said "That bacon is low
 * sodium bacon, previously scanned" and got a new line called "previously scanned";
 * "It was great value" about a pumpkin pie added a food called "value". Those are
 * now a [Change.Swap] of that line, and talk about scanning is never a food.
 */
object Changes {

    data class Line(val name: String, val food: String, val unit: String?)

    sealed class Change {
        data class SetAmount(val line: Int, val amount: Double, val unit: String?, val unitSaid: Boolean) : Change()
        data class Remove(val line: Int) : Change()
        /** New food, as words for the resolver. */
        data class Add(val text: String) : Change()
        /** The same line, but this food: [text] are the words to look it up by (amount kept). */
        data class Swap(val line: Int, val text: String) : Change()
        /** Words about a line that couldn't be pinned to one ("great value" with three lines). */
        data class Unclear(val text: String) : Change()
    }

    /** "previously scanned", "the one I scanned": where the food came from, never a food. */
    private val SCAN_TALK = Regex("^(?:it's |its |it was |that's |the one )?(?:i |i've |we )?(?:previously |already |just |had )?(?:scanned|scan(?:ned)? (?:it|in)|from (?:the|my) (?:label|scan))(?: (?:it|in|before|earlier|already|previously|last time|yesterday|the label))*$")

    /** Words that make a kind of the same food: "low sodium bacon", "multi grain bread". */
    private val KIND_WORDS = setOf(
        "low", "reduced", "less", "no", "sodium", "salt", "salted", "unsalted", "fat", "free", "nonfat", "lowfat",
        "lite", "light", "diet", "zero", "sugar", "added", "unsweetened", "sweetened", "whole", "wheat", "white",
        "multi", "multigrain", "grain", "organic", "lean", "extra", "skim", "thick", "thin", "cut", "center",
        "original", "plain", "regular", "turkey", "pork", "beef", "chicken", "smoked", "uncured", "honey", "maple",
        "sliced", "slices", "real", "classic", "natural", "keto", "protein", "high", "fiber",
    )

    /** The words of a line that say what food it is, for "it was great value" → "great value pumpkin pie". */
    private fun foodOf(l: Line) = l.food.ifBlank { l.name.substringAfter(" — ") }

    /** The words to look up a swapped line by, with its amount so the new food keeps it. */
    fun swapQuery(words: String, amount: Double?, unit: String?): String =
        listOfNotNull(amount?.takeIf { it > 0 }?.let { Units.format(it) }, unit?.takeIf { amount != null }, words)
            .joinToString(" ")

    private val REMOVE = Regex("^(?:please\\s+)?(?:remove|delete|take out|take off|drop|get rid of|scratch|minus|no|without|there was no|there wasn't any|i didn't have|i did not have|didn't have|not)\\s+(?:the\\s+|any\\s+|my\\s+)?(.+)$")
    private val ADD_LEAD = Regex("^(?:please\\s+)?(?:add|plus|also add|and also|also|and|include|put in|throw in|i also had|also had|there was also|there was|there were)\\s+")
    private val CHANGE_LEAD = Regex("^(?:actually|no wait|wait|oh|change|make|set|it was|that was|they were|it's|its|only|just|correction)\\s+(?:it\\s+)?")
    /** "the milk to 300 ml", "the butter was half a tbsp", "bread: 2 slices". */
    private val X_IS_AMOUNT = Regex("^(?:the\\s+|my\\s+)?(.+?)\\s*(?:\\bto\\b|\\bwas\\b|\\bwere\\b|\\bis\\b|\\bshould be\\b|=|:)\\s*(.+)$")

    /** Dishes that name the whole entry, never a line of their own when said in a fix. */
    private val DISHES = setOf("sandwich", "toast", "salad", "bowl", "wrap", "plate", "meal", "breakfast", "lunch", "dinner", "snack")

    fun interpret(
        text: String,
        lines: List<Line>,
        isFoodWord: (String) -> Boolean = { true },
        isBrandWord: (String) -> Boolean = { false },
    ): List<Change> {
        val out = mutableListOf<Change>()
        val adds = mutableListOf<String>()
        /** The line the last clause was about: "that bacon, it was great value". */
        var lastLine: Int? = null
        fun qualifierOnly(words: List<String>) = words.isNotEmpty() &&
            (words.all { isBrandWord(it) } || words.all { it in KIND_WORDS || isBrandWord(it) })
        for (raw in clauses(text)) {
            var clause = raw.trim().trim('.', ',', '!', '?').trim()
            if (clause.isBlank()) continue
            if (SCAN_TALK.matches(ItemParser.normaliseText(clause).trim())) continue

            val removed = REMOVE.find(clause)?.let { m -> match(m.groupValues[1], lines) }
            if (removed != null) { out += Change.Remove(removed); continue }

            val explicitAdd = ADD_LEAD.containsMatchIn(clause)
            clause = CHANGE_LEAD.replace(clause, "").trim()
            // "the milk to 300 ml": a line, then its new amount.
            val xIs = X_IS_AMOUNT.find(clause)
            val xLine = xIs?.let { match(it.groupValues[1], lines) }
            val xAmount = xIs?.let { amountOnly(it.groupValues[2]) }
            if (xLine != null && xAmount != null) {
                out += Change.SetAmount(xLine, xAmount.first, xAmount.second, xAmount.second != null)
                lastLine = xLine
                continue
            }
            // "that bacon is low sodium bacon", "the bread was multi grain": the line is another kind.
            if (xLine != null && !explicitAdd) {
                val right = ItemParser.cleanFood(ItemParser.normaliseText(xIs.groupValues[2]))
                val rw = FoodQuery.contentWords(right)
                val head = FoodQuery.contentWords(foodOf(lines[xLine])).lastOrNull()
                when {
                    rw.isNotEmpty() && head != null && head in rw && match(right, lines) == xLine -> {
                        out += Change.Swap(xLine, right); lastLine = xLine; continue
                    }
                    qualifierOnly(right.split(" ").filter { it.isNotBlank() }) -> {
                        out += Change.Swap(xLine, "$right ${foodOf(lines[xLine])}"); lastLine = xLine; continue
                    }
                }
            }
            // "great value", "low sodium": a brand or kind with no food, about one line.
            val plain = ItemParser.cleanFood(ItemParser.normaliseText(clause))
            if (!explicitAdd && amountOnly(clause) == null && qualifierOnly(plain.split(" ").filter { it.isNotBlank() })) {
                val i = lastLine ?: lines.indices.singleOrNull()
                out += if (i != null) Change.Swap(i, "$plain ${foodOf(lines[i])}") else Change.Unclear(plain)
                continue
            }
            // "2 slices", "half": an amount with no food, for the only line it fits.
            val bare = amountOnly(clause)
            if (bare != null) {
                val (a, u) = bare
                val i = when {
                    lines.size == 1 -> 0
                    u != null -> lines.indices.singleOrNull { lines[it].unit == u }
                    else -> null
                }
                if (i != null) { out += Change.SetAmount(i, a, u, u != null); continue }
            }

            val words = ADD_LEAD.replace(clause, "").trim()
            for (item in ItemParser.parse(words, isFoodWord, isBrandWord)) {
                val foodWords = FoodQuery.contentWords(item.food)
                if (foodWords.isEmpty()) continue
                val i = match(item.food, lines)
                if (i != null) lastLine = i
                when {
                    // A line said again with an amount: that line's new amount.
                    i != null && item.amount != null -> out += Change.SetAmount(i, item.amount, item.unit, item.unit != null)
                    // A line pointed at ("that avocado", "the toasted bread"): nothing new.
                    i != null -> Unit
                    // "on the two sandwiches": the meal itself, being described.
                    lines.isNotEmpty() && foodWords.last() in DISHES -> Unit
                    // The food and its amount only: the rest of what was said ("on the two
                    // sandwiches") would be read again as more food.
                    else -> adds += listOfNotNull(
                        item.amount?.let { Units.format(it) } ?: if (item.topping) "some" else null,
                        item.unit, item.size, item.brand?.takeIf { b -> b !in item.food }, item.food,
                    ).joinToString(" ")
                }
            }
        }
        if (adds.isNotEmpty()) out += Change.Add(adds.joinToString(", "))
        return out
    }

    /** Clauses: the separators a list of changes uses. */
    private fun clauses(text: String): List<String> {
        val t = ItemParser.normaliseText(text)
        return t.split(Regex("\\s*(?:,|;|\\.(?=\\s|$)|\\band also\\b|\\balso\\b|\\bthen\\b|\\band\\s+(?=remove|delete|take|drop|add|make|change|no\\b|the\\b))\\s*"))
            .filter { it.isNotBlank() }
    }

    /** "300 ml", "half a tbsp", "2 slices", "half" — an amount and nothing else. */
    fun amountOnly(text: String): Pair<Double, String?>? {
        val t = ItemParser.normaliseText(text)
        val m = Regex("^(?:about |around |like |maybe |probably )?(\\d+(?:\\.\\d+)?|a|an|one|two|three|four|five|six|seven|eight|nine|ten|half|a half|quarter|a quarter|third)(?:\\s+(?:of\\s+)?(?:a|an)\\s*)?(?:\\s*([a-z ]+?))?\\s*(?:of it|of them|each)?$").find(t)
            ?: return null
        val n = m.groupValues[1].let { s ->
            s.toDoubleOrNull() ?: when (s.removePrefix("a ")) {
                "a", "an", "one" -> 1.0; "two" -> 2.0; "three" -> 3.0; "four" -> 4.0; "five" -> 5.0
                "six" -> 6.0; "seven" -> 7.0; "eight" -> 8.0; "nine" -> 9.0; "ten" -> 10.0
                "half" -> 0.5; "quarter" -> 0.25; "third" -> 1.0 / 3; else -> return null
            }
        }
        val unitWord = m.groupValues[2].trim()
        if (unitWord.isEmpty()) return n to null
        val u = ItemParser.UNITS[unitWord] ?: ItemParser.UNITS[unitWord.removeSuffix("s")]
            ?: if (unitWord in setOf("whole", "each", "piece", "pieces")) return n to null else return null
        // "half a serving" / "half a cup" read as the number with the unit.
        return n to u
    }

    /** The line [said] names, or null. The head noun has to match; more shared words win. */
    fun match(said: String, lines: List<Line>): Int? {
        val words = FoodQuery.contentWords(ItemParser.cleanFood(ItemParser.normaliseText(said)))
        if (words.isEmpty()) return null
        var best: Int? = null
        var bestScore = 0
        for ((i, l) in lines.withIndex()) {
            val lw = (FoodQuery.contentWords(l.food) + FoodQuery.contentWords(l.name)).toSet()
            val shared = words.count { it in lw }
            val headHit = words.last() in lw
            if (!headHit && shared < 2) continue
            val score = shared * 2 + (if (headHit) 3 else 0)
            if (score >= bestScore) { best = i; bestScore = score }
        }
        return best
    }
}
