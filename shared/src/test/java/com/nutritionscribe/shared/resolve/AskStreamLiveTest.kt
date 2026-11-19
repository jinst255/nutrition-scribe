package com.nutritionscribe.shared.resolve

import org.junit.Test
import java.io.File

/**
 * Live checks of the request shapes added for the 11-19 notes: Ask streamed with
 * web search on, and "Something's wrong with this entry" fixed by the AI. Runs only
 * with -DliveStream=out.txt and OPENAI_API_KEY (a few cents); writes what came back.
 *
 *   OPENAI_API_KEY=… ./gradlew :shared:testDebugUnitTest --rerun --tests '*AskStreamLiveTest*' -DliveStream=/path/out.txt
 */
class AskStreamLiveTest {
    @Test
    fun live() {
        val out = System.getProperty("liveStream")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        val key = System.getenv("OPENAI_API_KEY").orEmpty().ifBlank { return }
        val report = StringBuilder()
        val seen = mutableListOf<String>()
        val client = AskClient(key, AskClient.Provider.OPENAI, AskClient.OPENAI_DEFAULT, stream = AskStream.HttpSse, webSearch = true)
        val r = runCatching {
            client.ask("Today: 1,200 kcal, 60 g protein.", listOf(AskClient.Turn("user",
                "How many calories and grams of protein are in a Chick-fil-A grilled chicken sandwich? Look it up.")),
                onProgress = { p -> seen += p.toString().take(120) })
        }
        report.append("ASK: ").append(r.map { it.text }.getOrElse { "FAILED $it" }).append("\n")
        report.append("progress (${seen.size}): ").append(seen.distinct().take(12).joinToString(" | ")).append("\n")
        report.append("streamed text seen: ").append(seen.any { it.startsWith("Text") }).append("\n")
        report.append("web search seen: ").append(seen.any { "Searching the web" in it }).append("\n\n")

        val fixer = AskClient(key, AskClient.Provider.OPENAI, AskClient.OPENAI_DEFAULT)
        val lines = listOf(
            EntryFix.Line("Brand — Black Beans", "half a serving of black beans", 0.5, "serving", 65.0, mapOf("calories" to 55.0, "protein" to 3.5)),
            EntryFix.Line("Brand — Real Bacon Pieces", "five slices of low sodium brand bacon", 2.5, "serving", 17.5, mapOf("calories" to 75.0, "protein" to 7.5, "sodium" to 475.0)),
            EntryFix.Line("banana", "banana", null, null, 118.0, mapOf("calories" to 105.0)),
        )
        val f = runCatching {
            fixer.fixEntry("five slices of low sodium Brand bacon, half a serving of black beans and a banana", lines,
                "[BLANK_AUDIO] A serving of bacon is two slices. This is five. It's sliced bacon, not bacon bits. There's no way there's 75 calories in five slices.")
        }
        report.append("FIX: ").append(f.map { "${it.summary} :: ${it.actions}" }.getOrElse { "FAILED $it" }).append("\n")
        out.writeText(report.toString())
    }
}
