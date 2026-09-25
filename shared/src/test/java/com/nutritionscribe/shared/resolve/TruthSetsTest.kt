package com.nutritionscribe.shared.resolve

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * First-time card accuracy on the three answer-key sets from the accuracy research
 * (tools/truth_set.py, truth_holdout.py, truth_labels.py; exported to JSON by
 * tools/export_truth_sets.py). The holdout set was written before any tuning and
 * must not be tuned on; it is the honest number.
 *
 * Scored exactly as tools/bench_truth.py: calories and protein both within the
 * case's tolerance (or its floor), and a vague case passes only by asking.
 *
 * Reports to build/reports/resolver/truth_sets.txt; asserts nothing, because the
 * numbers are the point.
 */
class TruthSetsTest {

    private data class Case(val text: String, val cat: String, val tol: Double, val truth: Map<String, Double>?)

    private fun load(): Map<String, List<Case>>? {
        val f = File(System.getProperty("aiCacheDir"), "truth_sets.json")
        if (!f.exists()) return null
        val sets = Json.parseToJsonElement(f.readText()).jsonObject["sets"]!!.jsonObject
        return sets.mapValues { (_, arr) ->
            arr.jsonArray.map { el ->
                val o = el.jsonObject
                Case(
                    text = o["text"]!!.jsonPrimitive.content,
                    cat = o["cat"]!!.jsonPrimitive.content,
                    tol = o["tol"]!!.jsonPrimitive.doubleOrNull ?: 0.15,
                    truth = (o["truth"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.doubleOrNull ?: 0.0 },
                )
            }
        }
    }

    private fun within(pred: Double?, truth: Double, tol: Double, floor: Double) =
        pred != null && abs(pred - truth) <= maxOf(tol * abs(truth), floor)

    private fun score(name: String, cases: List<Case>, resolver: () -> Resolver): String {
        var pass = 0
        var sodiumOff = 0
        var sodiumN = 0
        val misses = StringBuilder()
        for (c in cases) {
            val r = resolver().resolve(c.text)
            val ok = if (c.truth == null) {
                r.items.any { it.status == Resolver.Status.UNKNOWN }
            } else {
                val t = r.total
                within(t["calories"], c.truth["kcal"] ?: 0.0, c.tol, 20.0) &&
                    within(t["protein"], c.truth["protein_g"] ?: 0.0, c.tol, 2.0)
            }
            c.truth?.get("sodium_mg")?.let { na ->
                sodiumN++
                if (!within(r.total["sodium"], na, 0.2, 40.0)) sodiumOff++
            }
            if (ok) pass++ else misses.append(
                "  MISS %-48s kcal %5.0f/%5.0f  P %5.1f/%5.1f  %s\n".format(
                    c.text.take(48), r.total["calories"] ?: -1.0, c.truth?.get("kcal") ?: -1.0,
                    r.total["protein"] ?: -1.0, c.truth?.get("protein_g") ?: -1.0,
                    r.items.joinToString(" + ") { it.displayName.take(28) },
                ),
            )
        }
        return "%-10s %3d/%3d = %3d%%   sodium off >20%%: %d/%d\n%s".format(
            name, pass, cases.size, 100 * pass / cases.size, sodiumOff, sodiumN, misses,
        )
    }

    @Test
    fun `answer-key sets`() {
        val sets = load()
        assumeTrue("run tools/export_truth_sets.py first", sets != null)
        val out = StringBuilder()
        val configs = mutableListOf<Pair<String, () -> Resolver>>(
            "LOCAL (USDA + curated${if (TestDbs.labels != null) " + label pack" else ""})" to
                { Resolver(InMemoryPersonalStore(), TestDbs.usda, TestDbs.labels) },
        )
        val key = System.getenv("OPENAI_API_KEY").orEmpty()
        if (key.isNotBlank()) {
            val ai = OpenAiItemLookup(key, transport = AiReplayTest.CachingTransport(File(System.getProperty("aiCacheDir"), "ai_replay")))
            for (mode in Resolver.AiMode.values()) {
                configs += "WITH AI, $mode" to { Resolver(InMemoryPersonalStore(), TestDbs.usda, TestDbs.labels, ai, aiMode = mode) }
            }
        }
        for ((label, factory) in configs) {
            out.append("########## ").append(label).append('\n')
            for ((name, cases) in sets!!) out.append(score(name, cases, factory)).append('\n')
        }
        TestDbs.report("truth_sets.txt", out.toString())
    }
}
