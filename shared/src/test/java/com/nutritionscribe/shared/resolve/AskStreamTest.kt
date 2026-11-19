package com.nutritionscribe.shared.resolve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner, 11-16: Ask should stream its answer and say what it's doing (looking
 * through the log, searching the web, thinking) instead of one fixed "Thinking…"
 * line; and it should be able to search the web for a restaurant item.
 */
class AskStreamTest {

    private val reply = """{"reply":"A cheese stick and an apple.","title":"Snack","ideas":[],"plan":null}"""
    private fun q(s: String) = kotlinx.serialization.json.JsonPrimitive(s).toString()
    private val finished = """{"output":[{"type":"message","content":[{"type":"output_text","text":${q(reply)}}]}],"usage":{"input_tokens":10,"output_tokens":5}}"""

    @Test
    fun `the reply is read out of json written part-way`() {
        assertEquals("Cottage ch", AskStream.partialString("""{"reply":"Cottage ch""", "reply"))
        assertEquals("Line one\nsaid \"hi\"", AskStream.partialString("""{"reply":"Line one\nsaid \"hi\"","title":"x"}""", "reply"))
        assertEquals("caf", AskStream.partialString("""{"reply":"caf\u00""", "reply"))
        assertEquals("café", AskStream.partialString("""{"reply":"café""", "reply"))
        assertEquals("ends on a backslash ", AskStream.partialString("""{"reply":"ends on a backslash \""", "reply"))
        assertNull(AskStream.partialString("""{"title":"x""", "reply"))
    }

    @Test
    fun `what it is doing is said as it happens`() {
        val buf = StringBuilder()
        fun p(json: String) = AskStream.progressOf(json, buf)
        assertEquals(AskStream.Progress.Status("Looking through your log…"),
            p("""{"type":"response.output_item.added","item":{"type":"function_call","name":"query_log"}}"""))
        assertEquals(AskStream.Progress.Status("Searching the web…"), p("""{"type":"response.web_search_call.searching"}"""))
        assertEquals(AskStream.Progress.Status("Thinking…"), p("""{"type":"response.output_item.added","item":{"type":"reasoning"}}"""))
        assertNull(p("""{"type":"response.output_text.delta","delta":"{\"reply\":\""}"""))
        assertEquals(AskStream.Progress.Text("A cheese"), p("""{"type":"response.output_text.delta","delta":"A cheese"}"""))
        assertEquals(AskStream.Progress.Text("A cheese stick"), p("""{"type":"response.output_text.delta","delta":" stick"}"""))
        assertNull(p("not json"))
        val done = AskStream.completed("""{"type":"response.completed","response":$finished}""")!!
        assertEquals(kotlinx.serialization.json.Json.parseToJsonElement(finished), kotlinx.serialization.json.Json.parseToJsonElement(done))
        assertNull(AskStream.completed("""{"type":"response.created"}"""))
    }

    @Test
    fun `ask streams over the responses api and reports progress`() {
        var sentBody = ""
        val shown = mutableListOf<AskStream.Progress>()
        val stream = AskStream.StreamTransport { _, _, body, onData ->
            sentBody = body
            onData("""{"type":"response.output_item.added","item":{"type":"message"}}""")
            onData("""{"type":"response.output_text.delta","delta":${q("{\"reply\":\"A cheese stick")}}""")
            200 to finished
        }
        val r = AskClient("k", AskClient.Provider.OPENAI, "gpt-6-luna", OpenAiItemLookup.Transport { _, _, _ -> error("not streamed") }, stream = stream)
            .ask("", listOf(AskClient.Turn("user", "snack?")), onProgress = { shown += it })
        assertEquals("A cheese stick and an apple.", r.text)
        assertTrue(sentBody, sentBody.endsWith(",\"stream\":true}"))
        assertEquals(listOf(AskStream.Progress.Status("Writing…"), AskStream.Progress.Text("A cheese stick")), shown)
    }

    @Test
    fun `web search is offered to openai only when it is on`() {
        var on = ""; var off = ""
        AskClient("k", AskClient.Provider.OPENAI, "m", OpenAiItemLookup.Transport { _, _, b -> on = b; 200 to finished }, webSearch = true)
            .ask("", listOf(AskClient.Turn("user", "look up the hilltop double burger")))
        AskClient("k", AskClient.Provider.OPENAI, "m", OpenAiItemLookup.Transport { _, _, b -> off = b; 200 to finished })
            .ask("", listOf(AskClient.Turn("user", "look up the hilltop double burger")))
        assertTrue(on, on.contains("\"type\":\"web_search\""))
        assertTrue(on, on.contains("You can search the web"))
        assertFalse(off, off.contains("web_search"))
    }
}
