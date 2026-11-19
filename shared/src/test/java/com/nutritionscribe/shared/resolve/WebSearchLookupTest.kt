package com.nutritionscribe.shared.resolve

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner, 2026-11-14: "I need to just be able to say hey, I had the [a campus
 * diner's double burger] and then it actually gets all of the information it needs
 * from wherever you pull that from." The lookup marks what it isn't sure of, and
 * only those go to a web search; the search never costs the entry its answer.
 */
class WebSearchLookupTest {

    private fun chat(vararg items: String) =
        """{"choices":[{"message":{"content":${Json.encodeToString(kotlinx.serialization.serializer<String>(), """{"items":[${items.joinToString(",")}]}""")}}}]}"""

    private fun item(n: Int, food: String, kcal: Double, online: Boolean) =
        """{"item":$n,"needs_detail":false,"look_up_online":$online,"food":"$food","brand":null,"product_search":null,"count":1,"unit":null,"grams":200,"kcal":$kcal,"protein_g":20,"carbs_g":null,"fat_g":null,"fiber_g":null,"sugar_g":null,"sat_fat_g":null,"sodium_mg":null,"caffeine_mg":null}"""

    private fun responses(text: String) =
        """{"model":"gpt-6-luna","output":[{"type":"web_search_call","id":"ws1","status":"completed"},
            {"type":"message","role":"assistant","content":[{"type":"output_text","text":${Json.encodeToString(kotlinx.serialization.serializer<String>(), text)},"annotations":[]}]}],
            "usage":{"input_tokens":2000,"output_tokens":150}}"""

    private class Recorder(val replies: (String, String) -> Pair<Int, String>) : OpenAiItemLookup.Transport {
        val calls = mutableListOf<Pair<String, String>>()
        override fun post(url: String, headers: Map<String, String>, body: String): Pair<Int, String> {
            calls += url to body
            return replies(url, body)
        }
    }

    @Test
    fun `only the items it isn't sure of are searched, and the search's numbers win`() {
        val t = Recorder { url, _ ->
            if (url.endsWith("/chat/completions")) 200 to chat(item(1, "Double burger", 600.0, true), item(2, "Apple", 95.0, false))
            else 200 to responses("""{"items":[${item(1, "Double burger", 780.0, false)}]}""")
        }
        val out = OpenAiItemLookup("sk-test", transport = t, webSearch = true).lookupSentence("a double burger from the campus diner and an apple", emptyList())
        assertEquals(2, t.calls.size)
        val web = Json.parseToJsonElement(t.calls[1].second).jsonObject
        assertTrue(web["tools"].toString().contains("web_search"))
        assertTrue("only the unsure item is sent", t.calls[1].second.contains("Double burger") && !t.calls[1].second.contains("Apple"))
        assertEquals(780.0, out[0].nutrients.getValue("calories"), 0.0)
        assertTrue(out[0].fromWeb)
        assertEquals(95.0, out[1].nutrients.getValue("calories"), 0.0)
        assertFalse(out[1].fromWeb)
    }

    /**
     * A tester's scanned tortillas came out at 300 kcal against macros worth about
     * 460, and nothing looked twice. An answer whose numbers can't all be true gets
     * one more look, with thinking on and the problem named; the better one stays.
     */
    @Test
    fun `numbers that don't add up get a second look`() {
        fun bad(n: Int) = """{"item":$n,"needs_detail":false,"look_up_online":false,"food":"Corn tortillas","brand":null,"product_search":null,"count":6,"unit":null,"grams":156,"kcal":300,"protein_g":24,"carbs_g":78,"fat_g":6,"fiber_g":null,"sugar_g":null,"sat_fat_g":null,"sodium_mg":null,"caffeine_mg":null}"""
        fun good(n: Int) = """{"item":$n,"needs_detail":false,"look_up_online":false,"food":"Corn tortillas","brand":null,"product_search":null,"count":6,"unit":null,"grams":156,"kcal":340,"protein_g":9,"carbs_g":70,"fat_g":4,"fiber_g":null,"sugar_g":null,"sat_fat_g":null,"sodium_mg":null,"caffeine_mg":null}"""
        val t = Recorder { _, body ->
            if (body.contains("\"reasoning_effort\":\"medium\"")) 200 to chat(good(1)) else 200 to chat(bad(1), item(2, "Apple", 95.0, false))
        }
        val out = OpenAiItemLookup("sk-test", transport = t).lookupSentence("6 corn tortillas and an apple", emptyList())
        assertEquals(2, t.calls.size)
        assertTrue("the problem is named", t.calls[1].second.contains("don't match"))
        assertFalse("only the bad one goes back", t.calls[1].second.contains("Apple"))
        assertEquals(340.0, out[0].nutrients.getValue("calories"), 0.0)
        assertEquals(95.0, out[1].nutrients.getValue("calories"), 0.0)
    }

    @Test
    fun `with web search off, nothing is searched`() {
        val t = Recorder { _, _ -> 200 to chat(item(1, "Double burger", 600.0, true)) }
        val out = OpenAiItemLookup("sk-test", transport = t, webSearch = false).lookupSentence("a double burger", emptyList())
        assertEquals(1, t.calls.size)
        assertEquals(600.0, out.single().nutrients.getValue("calories"), 0.0)
    }

    @Test
    fun `a refused schema retries as plain JSON, and a failed search keeps the first answer`() {
        var n = 0
        val t = Recorder { url, _ ->
            when {
                url.endsWith("/chat/completions") -> 200 to chat(item(1, "Double burger", 600.0, true))
                n++ == 0 -> 400 to """{"error":{"message":"text.format not supported with web_search"}}"""
                else -> 200 to responses("Here you go:\n" + """{"items":[${item(1, "Double burger", 760.0, false)}]}""")
            }
        }
        val out = OpenAiItemLookup("sk-test", transport = t, webSearch = true).lookupSentence("a double burger", emptyList())
        assertEquals(3, t.calls.size)
        assertFalse("the retry asks for plain JSON", t.calls[2].second.contains("json_schema"))
        assertEquals(760.0, out.single().nutrients.getValue("calories"), 0.0)

        val down = Recorder { url, _ -> if (url.endsWith("/chat/completions")) 200 to chat(item(1, "Double burger", 600.0, true)) else throw java.io.IOException("offline") }
        val kept = OpenAiItemLookup("sk-test", transport = down, webSearch = true).lookupSentence("a double burger", emptyList())
        assertEquals(600.0, kept.single().nutrients.getValue("calories"), 0.0)
        assertFalse(kept.single().fromWeb)
    }
}
