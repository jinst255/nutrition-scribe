package com.nutritionscribe.shared.resolve

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Required test 7: the real logged week, first pass vs. once the cache is warm.
 *
 * Skipped on a machine without the export and its answer key (both personal data,
 * both gitignored). The full per-entry report goes to
 * build/reports/resolver/week_replay.txt.
 */
class WeekReplayTest {

    @Test
    fun `real week - first pass vs warm cache, local only`() {
        assumeTrue("no export/answer key on this machine", WeekReplay.available())
        val fixing = WeekReplay { Resolver(InMemoryPersonalStore(), TestDbs.usda, TestDbs.labels) }.run(passes = 2)
        val accepting = WeekReplay { Resolver(InMemoryPersonalStore(), TestDbs.usda, TestDbs.labels) }.run(passes = 2, fixWrongCards = false)

        val report = buildString {
            append("LOCAL ONLY (USDA + curated").append(if (TestDbs.labels != null) " + label pack" else "").append(")\n\n")
            append("== User fixes wrong cards: pass 1 (cache empty)\n").append(fixing[0].summary())
            append("== pass 2 (same week again, cache warm)\n").append(fixing[1].summary())
            append("\n== User accepts every card as shown: pass 1\n").append(accepting[0].summary())
            append("== pass 2\n").append(accepting[1].summary())
            append("\n-- pass 1 detail (fixing) --\n").append(fixing[0].lines.joinToString("\n"))
            append("\n\n-- pass 2 detail (fixing) --\n").append(fixing[1].lines.joinToString("\n"))
        }
        TestDbs.report("week_replay.txt", report)

        // The structural guarantee: nothing wrong is ever logged without being shown.
        assertEquals("silent wrong answers, pass 1", 0, fixing[0].silentWrong)
        assertEquals("silent wrong answers, pass 2", 0, fixing[1].silentWrong)
        // Once warm, the same week asks nothing it already asked.
        assertEquals("cards on the second pass", 0, fixing[1].cards)
    }
}
