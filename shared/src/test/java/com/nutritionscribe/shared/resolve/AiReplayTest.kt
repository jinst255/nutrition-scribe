package com.nutritionscribe.shared.resolve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class AiReplayTest {

    /**
     * Real OpenAI calls, cached on disk by request hash so a rerun costs nothing.
     * The key comes only from the environment and is never written anywhere.
     */
    class CachingTransport(private val dir: File) : OpenAiItemLookup.Transport {
        var calls = 0
        var hits = 0
        override fun post(url: String, headers: Map<String, String>, body: String): Pair<Int, String> {
            val hash = MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
            val f = File(dir, "ai_$hash.json")
            if (f.exists()) { hits++; return 200 to f.readText() }
            calls++
            val (status, text) = OpenAiItemLookup.HttpTransport.post(url, headers, body)
            if (status == 200) { dir.mkdirs(); f.writeText(text) }
            return status to text
        }
    }

    @Test
    fun `parses a canned response and lines answers up with items`() {
        val content = """{"items":[{"item":2,"needs_detail":false,"food":"Whole milk","brand":null,"product_search":null,"count":2,"unit":"cup","grams":488,"kcal":298,"protein_g":15.4,"carbs_g":23,"fat_g":16,"fiber_g":0,"sugar_g":24,"sat_fat_g":9,"sodium_mg":210,"caffeine_mg":null},{"item":1,"needs_detail":true,"food":"breakfast","brand":null,"product_search":null,"count":null,"unit":null,"grams":0,"kcal":null,"protein_g":null,"carbs_g":null,"fat_g":null,"fiber_g":null,"sugar_g":null,"sat_fat_g":null,"sodium_mg":null,"caffeine_mg":null}]}"""
        val response = """{"choices":[{"message":{"content":${kotlinx.serialization.json.JsonPrimitive(content)}}}]}"""
        val answers = OpenAiItemLookup("k").parse(response, 2)!!
        assertTrue(answers[0]!!.needsDetail)
        assertEquals(298.0, answers[1]!!.nutrients["calories"]!!, 0.0)
        assertEquals("2 cup (488 g)", answers[1]!!.portion)
    }

    @Test
    fun `an unreachable AI never fails the entry`() {
        val offline = OpenAiItemLookup("k", transport = { _, _, _ -> throw java.io.IOException("offline") })
        val r = Resolver(InMemoryPersonalStore(), TestDbs.usda, ai = offline, aiMode = Resolver.AiMode.ALL_NEW).resolve("a banana")
        assertNotNull(r.aiProblem)
        assertTrue("local answer kept", r.items.single().choice != null)
    }

    @Test
    fun `real week with the AI filling first-time cards`() {
        val key = System.getenv("OPENAI_API_KEY").orEmpty()
        assumeTrue("no OPENAI_API_KEY", key.isNotBlank())
        assumeTrue("no export/answer key on this machine", WeekReplay.available())
        val transport = CachingTransport(File(System.getProperty("aiCacheDir"), "ai_replay"))
        val ai = OpenAiItemLookup(key, transport = transport)
        val report = StringBuilder()
        val failures = mutableListOf<String>()
        for (mode in Resolver.AiMode.values()) {
        val fixing = WeekReplay { Resolver(InMemoryPersonalStore(), TestDbs.usda, TestDbs.labels, ai, aiMode = mode) }.run(passes = 2)
        val accepting = WeekReplay { Resolver(InMemoryPersonalStore(), TestDbs.usda, TestDbs.labels, ai, aiMode = mode) }.run(passes = 2, fixWrongCards = false)
        report.append("########## AI mode $mode\n").append(buildString {
            append("WITH AI (${OpenAiItemLookup.DEFAULT_MODEL})").append(if (TestDbs.labels != null) " + label pack" else "").append("\n")
            append("network calls ${transport.calls}, cache hits ${transport.hits}\n\n")
            append("== User fixes wrong cards: pass 1\n").append(fixing[0].summary())
            append("== pass 2\n").append(fixing[1].summary())
            append("\n== User accepts every card: pass 1\n").append(accepting[0].summary())
            append("== pass 2\n").append(accepting[1].summary())
            append("\n-- pass 1 detail --\n").append(fixing[0].lines.joinToString("\n")).append("\n")
            append("\n-- pass 2 cards (must be none) --\n").append(fixing[1].lines.filter { !it.startsWith("silent") }.joinToString("\n")).append("\n\n")
        })
        failures += if (fixing[1].cards != 0) listOf("$mode: ${fixing[1].cards} cards on pass 2") else emptyList()
        }
        TestDbs.report("week_replay_ai.txt", report.toString())
        assertEquals(emptyList<String>(), failures)
    }
}
