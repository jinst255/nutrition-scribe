package com.nutritionscribe.shared.resolve

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner, 11-18: "When I click something is wrong with this entry, AI should be able
 * to go in and fix whatever I say." The report only went in the debug log. Now
 * the AI is shown the entry line by line with what they said, and answers with
 * changes the app can apply: these tests pin the prompt, both providers' requests,
 * and reading the answer safely (lines out of range, empty actions, noise).
 */
class EntryFixTest {

    private val lines = listOf(
        EntryFix.Line("Brand — Black Beans", "half a serving of black beans", 0.5, "serving", 65.0, mapOf("calories" to 55.0, "protein" to 3.5)),
        EntryFix.Line("Brand — Real Bacon Pieces", "five slices of low sodium bacon", 2.5, "serving", 17.5, mapOf("calories" to 75.0, "protein" to 7.5)),
        EntryFix.Line("banana", "banana", null, null, 118.0, mapOf("calories" to 105.0)),
    )

    @Test
    fun `the prompt numbers the lines and drops speech noise`() {
        val p = EntryFix.prompt("bacon, beans and a banana", lines, "[BLANK_AUDIO] A serving of bacon is two slices. This is five. [BLANK_AUDIO]")
        assertTrue(p, p.contains("0. Brand — Black Beans — 0.5 serving (65 g): calories 55, protein 3.5"))
        assertTrue(p, p.contains("1. Brand — Real Bacon Pieces"))
        assertTrue(p, p.contains("2. banana — 1 (118 g)"))
        assertFalse(p, p.contains("BLANK_AUDIO"))
        assertTrue(p, p.contains("\"A serving of bacon is two slices. This is five.\""))
    }

    @Test
    fun `an answer becomes actions, and bad ones are dropped`() {
        val json = """{"summary":"Swapped the bacon bits for your sliced bacon.","changes":[
            {"line":1,"action":"food","food":"great value low sodium sliced bacon","amount":5,"unit":"slices","nutrients":null},
            {"line":0,"action":"numbers","food":null,"amount":null,"unit":null,"nutrients":{"calories":60,"protein":4,"carbs":null,"fat":null,"fiber":null,"sugar":null,"added_sugar":null,"saturated_fat":null,"sodium":null}},
            {"line":7,"action":"remove","food":null,"amount":null,"unit":null,"nutrients":null},
            {"line":2,"action":"amount","food":null,"amount":null,"unit":null,"nutrients":null},
            {"line":-1,"action":"add","food":"vitamin d gummy","amount":1,"unit":null,"nutrients":null}]}"""
        val fix = EntryFix.parse(json, lines.size)!!
        assertEquals("Swapped the bacon bits for your sliced bacon.", fix.summary)
        assertEquals(listOf(
            EntryFix.Action.Food(1, "great value low sodium sliced bacon", 5.0, "slice"),
            EntryFix.Action.Numbers(0, mapOf("calories" to 60.0, "protein" to 4.0)),
            EntryFix.Action.Add("vitamin d gummy", 1.0, null),
        ), fix.actions)
        assertEquals("5 slice great value low sodium sliced bacon", EntryFix.query("great value low sodium sliced bacon", 5.0, "slice"))
        assertNull(EntryFix.parse("not json", 3))
    }

    @Test
    fun `openai gets a strict schema on the responses api, storing nothing`() {
        var url = ""; var body = ""
        val answer = """{"summary":"Nothing to change.","changes":[]}"""
        val reply = """{"output":[{"type":"message","content":[{"type":"output_text","text":${kotlinx.serialization.json.JsonPrimitive(answer)}}]}]}"""
        val fix = AskClient("k", AskClient.Provider.OPENAI, "gpt-6-luna", OpenAiItemLookup.Transport { u, _, b -> url = u; body = b; 200 to reply })
            .fixEntry("bacon", lines, "the bacon is wrong")
        assertEquals("https://api.openai.com/v1/responses", url)
        val o = Json.parseToJsonElement(body).jsonObject
        assertEquals("false", o["store"]!!.jsonPrimitive.content)
        assertTrue(body, body.contains("\"name\":\"entry_fix\"") && body.contains("\"strict\":true"))
        assertTrue(fix.actions.isEmpty())
    }

    @Test
    fun `anthropic is made to answer with the entry_fix tool`() {
        var body = ""
        val reply = """{"content":[{"type":"tool_use","name":"entry_fix","input":{"summary":"Removed the banana.","changes":[{"line":2,"action":"remove","food":null,"amount":null,"unit":null,"nutrients":null}]}}]}"""
        val fix = AskClient("k", AskClient.Provider.ANTHROPIC, "claude", OpenAiItemLookup.Transport { _, _, b -> body = b; 200 to reply })
            .fixEntry("bacon", lines, "I didn't have the banana")
        assertTrue(body, body.contains("\"tool_choice\":{\"type\":\"tool\",\"name\":\"entry_fix\"}"))
        assertEquals(listOf(EntryFix.Action.Remove(2)), fix.actions)
    }
}
