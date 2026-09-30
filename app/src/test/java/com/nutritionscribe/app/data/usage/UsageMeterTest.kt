package com.nutritionscribe.app.data.usage

import com.nutritionscribe.app.ui.debuglog.DebugEvents
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * What a call costs is worked out from the provider's own usage block. These pin
 * the arithmetic and the parsing, because a mistake here is invisible on screen:
 * the chart would simply show the wrong money.
 */
class UsageMeterTest {

    private class FakeDao : AiUsageDao {
        val rows = mutableListOf<AiUsageRow>()
        override fun insertBlocking(row: AiUsageRow): Long { rows += row; return rows.size.toLong() }
        override fun observeAll(): Flow<List<AiUsageRow>> = flowOf(rows)
        override fun observeForEntry(entryId: String): Flow<List<AiUsageRow>> = flowOf(rows.filter { it.entryId == entryId })
        override suspend fun attach(tag: String, entryId: String) {}
        override suspend fun count(): Int = rows.size
        override fun observeCostSince(since: Long): Flow<Double> = flowOf(rows.sumOf { it.costUsd })
        override suspend fun deleteAll() { rows.clear() }
    }

    private val dir = Files.createTempDirectory("usage").toFile()
    private val events = DebugEvents(null, dir, dir).apply { enabled = true }
    private val dao = FakeDao()
    private val meter = UsageMeter(dao, events)

    @Test
    fun openAiTokensAreCostedAtTheListPriceWithCachedInputCheaper() {
        val response = """{"model":"gpt-5.6-luna-2026-07-01","usage":{"prompt_tokens":1000,"completion_tokens":400,
            "prompt_tokens_details":{"cached_tokens":600}}}"""
        meter.recordHttp("https://api.openai.com/v1/chat/completions", """{"model":"gpt-5.6-luna"}""", 200, response, 1500)
        val row = dao.rows.single()
        assertEquals("gpt-5.6-luna", row.model)
        assertEquals(1000, row.inputTokens)
        assertEquals(600, row.cachedInputTokens)
        assertEquals(400, row.outputTokens)
        // 400 uncached × $0.20 + 600 cached × $0.02 + 400 out × $0.75, per million.
        assertEquals((400 * 0.20 + 600 * 0.02 + 400 * 0.75) / 1e6, row.costUsd, 1e-12)
    }

    @Test
    fun anthropicCountsCacheReadsOnTopOfInput() {
        val response = """{"model":"claude-haiku-4-5","usage":{"input_tokens":200,"cache_read_input_tokens":800,"output_tokens":100}}"""
        meter.recordHttp("https://api.anthropic.com/v1/messages", "{}", 200, response, 900)
        val row = dao.rows.single()
        assertEquals("anthropic", row.provider)
        assertEquals(1000, row.inputTokens)
        assertEquals(800, row.cachedInputTokens)
        assertEquals((200 * 1.0 + 800 * 0.10 + 100 * 5.0) / 1e6, row.costUsd, 1e-12)
    }

    @Test
    fun aFailedCallWithNoUsageCostsNothingAndIsNotCharted() {
        meter.recordHttp("https://api.openai.com/v1/chat/completions", """{"model":"gpt-5.6-luna"}""", 500, """{"error":"x"}""", 100)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun speechIsBilledByTheMinute() {
        meter.recordAudio("gpt-4o-mini-transcribe", 30.0, 200, 1200, "two eggs", "t1")
        val row = dao.rows.single()
        assertEquals(UsageKind.SPEECH.name, row.kind)
        assertEquals(0.0015, row.costUsd, 1e-12)
        assertEquals("t1", row.tag)
    }

    @Test
    fun theTagFollowsTheCallsMadeInsideIt() {
        meter.withTag("entry-1") {
            meter.recordHttp("https://api.openai.com/v1/chat/completions", """{"model":"gpt-5.6-luna"}""", 200,
                """{"usage":{"prompt_tokens":10,"completion_tokens":5}}""", 10)
        }
        meter.recordHttp("https://api.openai.com/v1/chat/completions", """{"model":"gpt-5.6-luna"}""", 200,
            """{"usage":{"prompt_tokens":10,"completion_tokens":5}}""", 10)
        assertEquals("entry-1", dao.rows[0].tag)
        assertEquals(null, dao.rows[1].tag)
    }

    @Test
    fun anUnknownModelIsCostedHighNeverFree() {
        assertTrue(PriceBook.tokenCost("gpt-99-mystery", 1_000_000, 0, 0) >= 2.0)
        assertFalse(PriceBook.isKnown("gpt-99-mystery"))
    }

    @Test
    fun theDebugLogNeverHoldsAKey() {
        meter.recordHttp("https://api.openai.com/v1/chat/completions",
            """{"model":"gpt-5.6-luna","note":"sk-proj-abcdefghijklmnop1234"}""", 200,
            """{"usage":{"prompt_tokens":1,"completion_tokens":1}}""", 1)
        events.flush()
        val text = dir.resolve("debug_events.jsonl").readText()
        assertTrue(text.contains("ai_call"))
        assertFalse("key leaked into the debug log", text.contains("abcdefghijklmnop1234"))
    }
}
