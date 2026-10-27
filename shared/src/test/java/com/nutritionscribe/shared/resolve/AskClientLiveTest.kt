package com.nutritionscribe.shared.resolve

import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs only with OPENAI_API_KEY set: two real turns of Ask, a fraction of a cent. */
class AskClientLiveTest {
    private val key = System.getenv("OPENAI_API_KEY").orEmpty()

    @Test fun `ideas, then a plan when one is picked`() {
        assumeTrue(key.isNotBlank() && !System.getProperty("askLive").isNullOrBlank())
        val client = AskClient(key, AskClient.Provider.OPENAI, AskClient.OPENAI_DEFAULT)
        val context = """
            Now: Sunday 11:40 AM.
            Logged today:
            - 8:10 AM Overnight oats with blueberries (420 kcal)
            Today vs their targets (goal = they chose it):
            - Calories: 420 of at most 2400 kcal (goal)
            - Protein: 18 of 120 g (goal)
            - Fiber: 8 of 30 g
            - Vitamin C: 6 of 90 mg
            Foods they often log (use these when you can):
            Tuna, canned light; Greek yogurt, vanilla; Whole wheat tortilla; Red bell pepper; Eggs; Spinach; Turkey sandwich
        """.trimIndent()
        val first = client.ask(context, listOf(AskClient.Turn("user", "What should I make for lunch?")))
        println("REPLY 1: ${first.text}\nTITLE: ${first.title}\nIDEAS: ${first.ideas}\nPLAN: ${first.plan}")
        assertTrue(first.ideas.size >= 2)
        assertTrue(first.plan == null)
        val pick = first.ideas.first().name
        val second = client.ask(context, listOf(
            AskClient.Turn("user", "What should I make for lunch?"),
            AskClient.Turn("assistant", first.text + "\nIdeas: " + first.ideas.joinToString("; ") { it.name }),
            AskClient.Turn("user", "The $pick. I'll make it at noon."),
        ))
        println("REPLY 2: ${second.text}\nPLAN: ${second.plan}")
        assertTrue(second.plan != null)
    }
}
