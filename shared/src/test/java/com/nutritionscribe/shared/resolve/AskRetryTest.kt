package com.nutritionscribe.shared.resolve

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What broke (owner, 11-14 "why"): a question failed once with no connection. The
 * chat keeps that failure as an "error" line, and every later send put it in the
 * conversation as a turn with role "error". OpenAI refuses that role, so the same
 * question failed twice more with API error 400 in under 300 ms. Only user and
 * assistant turns may be sent; a question sent again isn't sent twice.
 */
class AskRetryTest {

    private val reply = """{"reply":"A cheese stick.","title":"Snack","ideas":[],"plan":null}"""
    private fun final() = """{"output":[{"type":"message","content":[{"type":"output_text","text":${kotlinx.serialization.json.JsonPrimitive(reply)}}]}]}"""

    private val afterAFailure = listOf(
        AskClient.Turn("user", "What should I eat for a snack?"),
        AskClient.Turn("error", "No connection. Your question is kept; send it again when you're online."),
        AskClient.Turn("user", "What should I eat for a snack?"),
    )

    @Test
    fun `a question sent again after a failure sends no error turn`() {
        var body = ""
        AskClient("k", AskClient.Provider.OPENAI, "gpt-6-luna", OpenAiItemLookup.Transport { _, _, b -> body = b; 200 to final() })
            .ask("", afterAFailure)
        val input = Json.parseToJsonElement(body).jsonObject["input"]!!.jsonArray
        val roles = input.map { it.jsonObject["role"]!!.jsonPrimitive.content }
        assertEquals(listOf("user"), roles)
        assertTrue(body, !body.contains("\"error\""))
    }

    @Test
    fun `anthropic gets alternating turns starting with the user`() {
        var body = ""
        val anthropic = """{"content":[{"type":"tool_use","name":"ask_reply","input":$reply}]}"""
        AskClient("k", AskClient.Provider.ANTHROPIC, "claude", OpenAiItemLookup.Transport { _, _, b -> body = b; 200 to anthropic })
            .ask("", listOf(AskClient.Turn("assistant", "Hi!")) + afterAFailure)
        val roles = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content }
        assertEquals(listOf("user"), roles)
    }

    @Test
    fun `the conversation keeps real turns in order`() {
        val c = AskClient.conversation(listOf(
            AskClient.Turn("user", "a"), AskClient.Turn("assistant", "b"), AskClient.Turn("error", "x"),
            AskClient.Turn("user", " "), AskClient.Turn("user", "c"), AskClient.Turn("user", "d"),
        ))
        assertEquals(listOf("user" to "a", "assistant" to "b", "user" to "c\n\nd"), c.map { it.role to it.text })
    }
}
