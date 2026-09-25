package com.nutritionscribe.shared.resolve

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import kotlin.math.abs

/**
 * Replays a real logged week through the resolver, the way it would have been
 * lived: the personal cache starts empty, every first-time food shows its card,
 * and the simulated user either fixes a wrong card or accepts it.
 *
 * The answer key is tools/.cache/week_truth.json (personal data, gitignored), made
 * by tools/.cache/make_week_truth.py from labels and USDA using the portions the
 * person described. Scoring matches tools/.cache/week_baseline.py: wrong means
 * calories off by more than 20% (floor 20 kcal) or protein off by more than 20%
 * (floor 2 g).
 */
class WeekReplay(private val resolverFactory: () -> Resolver) {

    data class Truth(val kcal: Double?, val protein: Double?, val judge: String)

    data class PassStats(
        var entries: Int = 0,
        var judged: Int = 0,
        var cards: Int = 0,
        var silent: Int = 0,
        var silentJudged: Int = 0,
        var silentWrong: Int = 0,
        var cardJudged: Int = 0,
        var cardWrong: Int = 0,
        var loggedWrong: Int = 0,
        val lines: MutableList<String> = mutableListOf(),
    ) {
        fun pct(a: Int, b: Int) = if (b == 0) "-" else "${(100.0 * a / b).toInt()}%"
        fun summary() = buildString {
            append("entries $entries (judgeable $judged)\n")
            append("  confirm cards shown:        $cards\n")
            append("  logged silently:            $silent  (wrong: $silentWrong of $silentJudged judgeable)\n")
            append("  first-time card wrong:      $cardWrong of $cardJudged = ${pct(cardWrong, cardJudged)}\n")
            append("  logged value wrong:         $loggedWrong of $judged = ${pct(loggedWrong, judged)}\n")
        }
    }

    companion object {
        fun available(): Boolean =
            File(System.getProperty("exportJson") ?: "").exists() && File(System.getProperty("weekTruth") ?: "").exists()

        /** What was actually logged that day (the old app's numbers), by text. */
        fun logged(): Map<String, Truth> {
            val arr = Json.parseToJsonElement(File(System.getProperty("exportJson")).readText()).jsonArray
            return arr.map { it.jsonObject }.associate { o ->
                o["rawText"]!!.jsonPrimitive.content to Truth(
                    o["calories"]?.jsonPrimitive?.doubleOrNull, o["proteinGrams"]?.jsonPrimitive?.doubleOrNull, "logged",
                )
            }
        }

        fun entries(): List<String> {
            val arr = Json.parseToJsonElement(File(System.getProperty("exportJson")).readText()).jsonArray
            return arr.map { it.jsonObject }
                .sortedBy { it["timestampEpochMillis"]?.jsonPrimitive?.longOrNull ?: 0 }
                .map { it["rawText"]!!.jsonPrimitive.content }
        }

        fun truth(): Map<String, Truth> {
            val obj = Json.parseToJsonElement(File(System.getProperty("weekTruth")).readText()).jsonObject
            return obj.mapValues { (_, v) ->
                val o = v as JsonObject
                Truth(o["kcal"]?.jsonPrimitive?.doubleOrNull, o["protein"]?.jsonPrimitive?.doubleOrNull, o["judge"]!!.jsonPrimitive.content)
            }
        }

        fun wrong(kcal: Double?, protein: Double?, t: Truth): Boolean? {
            if (t.judge == "unknowable") return null
            fun off(v: Double?, target: Double, floor: Double) = v == null || abs(v - target) > maxOf(floor, 0.2 * abs(target))
            var bad = t.protein?.let { off(protein, it, 2.0) } ?: false
            t.kcal?.let { bad = bad || off(kcal, it, 20.0) }
            return bad
        }
    }

    /**
     * [fixWrongCards]: true simulates a user who corrects any wrong card (the
     * intended use); false, one who taps Confirm on everything.
     */
    fun run(passes: Int = 2, fixWrongCards: Boolean = true): List<PassStats> {
        val resolver = resolverFactory()
        val truth = truth()
        val logged = logged()
        val texts = entries()
        return (1..passes).map { pass ->
            val s = PassStats()
            for (text in texts) {
                val t = truth[text] ?: error("no truth for: $text")
                s.entries++
                val r = resolver.resolve(text)
                val total = r.total
                val kcal = total["calories"]
                val prot = total["protein"]
                val w = wrong(kcal, prot, t)
                if (w != null) s.judged++
                val tag: String
                if (r.silent) {
                    s.silent++
                    if (w != null) { s.silentJudged++; if (w) { s.silentWrong++; s.loggedWrong++ } }
                    r.items.forEach { resolver.touch(it.key) }
                    tag = if (w == true) "SILENT-WRONG" else "silent"
                } else {
                    s.cards++
                    if (w != null) { s.cardJudged++; if (w) s.cardWrong++ }
                    val corrected = fixWrongCards && w == true
                    if (w == true && !fixWrongCards) s.loggedWrong++
                    // A card with nothing on it for a food nobody could know: the user
                    // types their own numbers once — here, what they logged that day.
                    val blank = r.items.all { it.nutrients.isEmpty() }
                    confirm(resolver, r, if (corrected) t else if (blank && fixWrongCards) logged[text] else null)
                    tag = when (w) { null -> "card(?)"; true -> if (corrected) "card-wrong->fixed" else "card-wrong"; false -> "card-ok" }
                }
                s.lines += "%-18s kcal %5s/%5s  P %5s/%5s  %s".format(
                    tag, kcal?.let { "%.0f".format(it) } ?: "-", t.kcal?.let { "%.0f".format(it) } ?: "?",
                    prot?.let { "%.1f".format(it) } ?: "-", t.protein?.let { "%.1f".format(it) } ?: "?",
                    text.replace("\n", " ").take(80),
                )
            }
            s
        }
    }

    /**
     * What tapping Confirm does: every item goes into the personal cache. A
     * correction scales the card to the truth — the user typing the right numbers —
     * and that is what gets remembered.
     */
    private fun confirm(resolver: Resolver, r: Resolver.Resolution, fixTo: Truth?) {
        val total = r.total
        val fk = fixTo?.kcal?.let { target -> total["calories"]?.takeIf { it > 0 }?.let { target / it } }
        val fp = fixTo?.protein?.let { target -> total["protein"]?.takeIf { it > 0 }?.let { target / it } }
        val unknownOnly = r.items.all { it.nutrients.isEmpty() }
        if (fixTo != null && (unknownOnly || (fixTo.kcal != null && fk == null))) {
            // Nothing to scale: the user typed the whole entry's numbers in, and
            // saved it as one of their own.
            val n = buildMap {
                fixTo.kcal?.let { put("calories", it) }
                fixTo.protein?.let { put("protein", it) }
            }
            resolver.saveMeal(r.text, r.text, listOf(MealPart(r.text, r.text, null, n)))
            return
        }
        // A part nothing could identify gets its numbers typed in: the rest of what
        // the card is off by, shared among such parts.
        val blanks = r.items.count { it.nutrients.isEmpty() }
        val known = sumNutrients(r.items.map { it.nutrients })
        val fill = if (fixTo != null && blanks > 0) buildMap {
            fixTo.kcal?.let { put("calories", ((it - (known["calories"] ?: 0.0)).coerceAtLeast(0.0)) / blanks) }
            fixTo.protein?.let { put("protein", ((it - (known["protein"] ?: 0.0)).coerceAtLeast(0.0)) / blanks) }
        } else emptyMap()
        resolver.confirm(r.text, r.items.map { i ->
            val n = if (i.nutrients.isEmpty()) fill
            else i.nutrients.mapValues { (k, v) -> if (k == "protein") v * (if (blanks > 0) 1.0 else fp ?: 1.0) else v * (if (blanks > 0) 1.0 else fk ?: 1.0) }
            Resolver.Confirmed(
                item = i.item, key = i.key, name = i.displayName, candidate = i.choice, grams = i.grams,
                nutrients = n, unchangedMemory = i.status == Resolver.Status.REMEMBERED && fixTo == null,
            )
        })
    }
}
