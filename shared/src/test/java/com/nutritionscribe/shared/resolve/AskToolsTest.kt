package com.nutritionscribe.shared.resolve

import com.nutritionscribe.shared.model.LogQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner, 2026-11-02: "what are my biggest sodium sources across everything I've
 * logged" got "I can't see past logs". Ask now has a query_log tool the phone
 * answers from the whole log; these drive it with a fake server for each provider.
 */
class AskToolsTest {

    private val reply = """{"reply":"Cottage cheese is your biggest source.","title":"Sodium sources","ideas":[],"plan":null}"""

    private fun tools(log: (LogQuery.Request) -> String, foods: (String) -> String = { "none" }) = object : AskClient.Tools {
        override fun queryLog(request: LogQuery.Request) = log(request)
        override fun findMyFoods(query: String) = foods(query)
    }

    /** A /v1/responses reply that calls a tool, with the reasoning before it. */
    private fun call(id: String, name: String, args: String) =
        """{"output":[{"type":"reasoning","id":"rs_1","encrypted_content":"enc-1","summary":[]},
            {"type":"function_call","id":"fc_$id","call_id":"$id","name":"$name","arguments":${quoted(args)}}]}"""

    private fun final() = """{"output":[{"type":"message","content":[{"type":"output_text","text":${quoted(reply)}}]}]}"""

    /**
     * Every Ask message failed with a 400 once it had tools: GPT-6 Luna takes
     * function tools with reasoning only on /v1/responses, not chat completions.
     */
    @Test
    fun `openai tools go to the responses api, with reasoning, storing nothing`() {
        var url = ""; var body = ""
        AskClient("k", AskClient.Provider.OPENAI, "gpt-6-luna", OpenAiItemLookup.Transport { u, _, b -> url = u; body = b; 200 to final() })
            .ask("", listOf(AskClient.Turn("user", "?")), tools({ "x" }))
        assertEquals("https://api.openai.com/v1/responses", url)
        assertTrue(body, body.contains("\"reasoning\":{\"effort\":\"low\"}") && body.contains("\"tools\"") && body.contains("\"store\":false"))
        assertTrue(body, !body.contains("reasoning_effort"))
    }

    private fun quoted(s: String) = kotlinx.serialization.json.JsonPrimitive(s).toString()

    @Test
    fun `openai asks the log, gets the phone's answer, then replies`() {
        val bodies = mutableListOf<String>()
        val transport = OpenAiItemLookup.Transport { _, _, body ->
            bodies += body
            if (bodies.size == 1) 200 to call("call_1", "query_log", """{"metric":"sodium","from":null,"to":null,"group_by":"food","top":5,"lowest":false}""")
            else 200 to final()
        }
        var asked: LogQuery.Request? = null
        val r = AskClient("k", AskClient.Provider.OPENAI, "gpt-6-luna", transport)
            .ask("Now: today.", listOf(AskClient.Turn("user", "biggest sodium sources ever?")), tools({ req -> asked = req; "1. Cottage cheese: 1,350 mg" }))
        assertEquals(LogQuery.Request("sodium", null, null, LogQuery.GroupBy.FOOD, 5, false), asked)
        assertEquals("Cottage cheese is your biggest source.", r.text)
        assertTrue(bodies[0].contains("query_log"))
        assertTrue("the answer went back as the tool's result", bodies[1].contains("\"call_id\":\"call_1\"") && bodies[1].contains("1,350 mg"))
        assertTrue("the reasoning went back with the call", bodies[1].contains("enc-1"))
    }

    @Test
    fun `anthropic asks the log through tool use, then replies with ask_reply`() {
        val bodies = mutableListOf<String>()
        val transport = OpenAiItemLookup.Transport { _, _, body ->
            bodies += body
            if (bodies.size == 1) 200 to """{"content":[{"type":"tool_use","id":"tu_1","name":"query_log",
                "input":{"metric":"fiber","from":"2026-11-07","to":null,"group_by":"day","top":3,"lowest":true}}]}"""
            else 200 to """{"content":[{"type":"tool_use","id":"tu_2","name":"ask_reply","input":$reply}]}"""
        }
        var asked: LogQuery.Request? = null
        val r = AskClient("k", AskClient.Provider.ANTHROPIC, "claude-haiku-5-5", transport)
            .ask("Now: today.", listOf(AskClient.Turn("user", "my low fiber days this week?")), tools({ req -> asked = req; "1. Tue Nov 11: 8 g" }))
        assertEquals(LogQuery.GroupBy.DAY, asked!!.groupBy)
        assertEquals(java.time.LocalDate.of(2026, 11, 7), asked!!.from)
        assertTrue(asked!!.lowest)
        assertEquals("Sodium sources", r.title)
        assertTrue(bodies[1].contains("\"tool_use_id\":\"tu_1\"") && bodies[1].contains("Tue Nov 11"))
    }

    @Test
    fun `a model that keeps asking is cut off, and with no tool it answers straight away`() {
        var calls = 0
        val looping = OpenAiItemLookup.Transport { _, _, body ->
            calls++
            if (body.contains("\"tools\"")) 200 to call("c$calls", "query_log", """{"metric":"sodium","from":null,"to":null,"group_by":"food","top":5,"lowest":false}""")
            else 200 to final()
        }
        val r = AskClient("k", AskClient.Provider.OPENAI, "gpt-6-luna", looping).ask("", listOf(AskClient.Turn("user", "?")), tools({ "x" }))
        assertEquals(AskClient.MAX_TOOL_ROUNDS + 1, calls)
        assertEquals("Cottage cheese is your biggest source.", r.text)
        calls = 0
        AskClient("k", AskClient.Provider.OPENAI, "gpt-6-luna", looping).ask("", listOf(AskClient.Turn("user", "?")))
        assertEquals(1, calls)
    }

    @Test
    fun `bad tool arguments get a note back, not a crash`() {
        assertEquals(null, AskClient.queryOf(kotlinx.serialization.json.buildJsonObject { put("metric", kotlinx.serialization.json.JsonPrimitive("sodium")); put("group_by", kotlinx.serialization.json.JsonPrimitive("planet")) }))
    }

    @Test
    fun `my typical bread is looked up among their saved foods`() {
        val bodies = mutableListOf<String>()
        val transport = OpenAiItemLookup.Transport { _, _, body ->
            bodies += body
            if (bodies.size == 1) 200 to call("call_9", "find_my_foods", """{"query":"bread"}""")
            else 200 to final()
        }
        var searched: String? = null
        AskClient("k", AskClient.Provider.OPENAI, "gpt-6-luna", transport)
            .ask("", listOf(AskClient.Turn("user", "my typical bread and avocado?")), tools({ "x" }, { q -> searched = q; "- Whole wheat bread: 2 slices" }))
        assertEquals("bread", searched)
        assertTrue(bodies[1].contains("2 slices"))
        assertTrue(bodies[0].contains("find_my_foods"))
    }

    @Test
    fun `their saved foods by brand, with numbers, most used first`() {
        val foods = listOf(
            PersonalFood(key = "bacon", phrase = "bacon bits", name = "Real Bacon Pieces", timesUsed = 3,
                candidate = FoodCandidate(id = "label:1", name = "Real Bacon Pieces", brand = "Great Value", origin = Origin.LABEL),
                amount = 1.0, unit = "tbsp", grams = 7.0, nutrients = mapOf("calories" to 30.0, "sodium" to 190.0)),
            PersonalFood(key = "milk", phrase = "milk", name = "Vitamin D Whole Milk", timesUsed = 9,
                candidate = FoodCandidate(id = "label:2", name = "Vitamin D Whole Milk", brand = "Great Value", origin = Origin.LABEL),
                amount = 1.0, unit = "cup", grams = 244.0, nutrients = mapOf("calories" to 150.0)),
            PersonalFood(key = "apple", phrase = "apple", name = "Apples, raw", nutrients = mapOf("calories" to 95.0)),
        )
        val out = com.nutritionscribe.shared.model.MyFoodsQuery.run(foods, "great value")
        assertTrue(out, out.startsWith("2 saved foods match"))
        assertTrue(out, out.lines()[1].contains("Great Value — Vitamin D Whole Milk (their scanned label): 1 cup, 244 g: 150 kcal"))
        assertTrue(out, out.contains("190 mg sodium"))
        assertTrue(com.nutritionscribe.shared.model.MyFoodsQuery.run(foods, "costco").startsWith("None of their saved foods"))
    }
}
