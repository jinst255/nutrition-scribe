package com.nutritionscribe.shared.stores

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The "you eat a lot of Great Value" suggestion (owner, 11-19). It must speak up
 * for a store that is a real share of what someone eats, stay quiet otherwise,
 * and never nag about a pack already installed or waved off.
 */
class StoreSuggestTest {
    private val day = 86_400_000L
    private val now = 1_795_000_000_000L

    private fun gv(i: Int, id: String? = "label:$i", daysAgo: Int = i % 25) =
        StoreSuggest.Food("Great Value — Product $i", id, "LABEL", now - daysAgo * day)

    private fun other(i: Int) = StoreSuggest.Food("Skippy — Thing $i", "label:9$i", "LABEL", now - (i % 25) * day)
    private fun plain(i: Int) = StoreSuggest.Food("Banana", "usda:1", "USDA", now - (i % 25) * day)

    @Test
    fun `a store that is a fifth of packaged foods is suggested`() {
        val foods = (1..6).map { gv(it) } + (1..10).map { other(it) } + (1..30).map { plain(it) }
        val s = StoreSuggest.suggest(foods, now, emptySet(), emptyMap())
        assertNotNull(s)
        assertEquals("great-value", s!!.store.id)
        assertEquals(6, s.storeFoods)
        assertEquals("unbranded foods are not packaged foods", 16, s.packagedFoods)
    }

    @Test
    fun `too few or too small a share says nothing`() {
        assertNull(StoreSuggest.suggest((1..4).map { gv(it) }, now, emptySet(), emptyMap()))
        val diluted = (1..5).map { gv(it) } + (1..40).map { other(it) }
        assertNull(StoreSuggest.suggest(diluted, now, emptySet(), emptyMap()))
    }

    @Test
    fun `installed or snoozed stores are not suggested again`() {
        val foods = (1..8).map { gv(it) }
        assertNull(StoreSuggest.suggest(foods, now, setOf("great-value"), emptyMap()))
        assertNull(StoreSuggest.suggest(foods, now, emptySet(), mapOf("great-value" to now + day)))
        assertNotNull("a snooze that ran out", StoreSuggest.suggest(foods, now, emptySet(), mapOf("great-value" to now - 1)))
    }

    @Test
    fun `foods older than a month don't count`() {
        val old = (1..8).map { gv(it, daysAgo = 40 + it) }
        assertNull(StoreSuggest.suggest(old, now, emptySet(), emptyMap()))
    }

    @Test
    fun `the claim counts each guessed product once`() {
        // Over a full month: one product guessed by AI twice, one web search, one label read, two labels.
        val foods = listOf(
            gv(1, "ai:1", 30), gv(1, "ai:1", 20), gv(2, "web:2", 10),
            gv(3, "scan:3", 5), gv(4), gv(5),
        )
        val s = StoreSuggest.suggest(foods, now, emptySet(), emptyMap())!!
        assertEquals(2, s.guessesPerMonth)
        assertEquals(2, s.centsPerMonth)
        assertEquals("~2", s.guessesText)
        assertEquals("~2¢", s.centsText)
    }

    /**
     * A pack comes from Open Food Facts, which the app already asks online after a
     * barcode miss; a product whose label had to be read isn't in it. The first
     * version promised "~3 min of label reading a month" for those (11-19); on the
     * owner's log the packs held none of the 26 misses.
     */
    @Test
    fun `label reads are never claimed as savings`() {
        val foods = (1..6).map { gv(it, "scan:$it", it) }
        val s = StoreSuggest.suggest(foods, now, emptySet(), emptyMap())!!
        assertEquals(0, s.guessesPerMonth)
        assertNull(s.guessesText)
        assertNull(s.centsText)
    }

    @Test
    fun `nothing guessed means no saving is claimed`() {
        val s = StoreSuggest.suggest((1..6).map { gv(it) }, now, emptySet(), emptyMap())!!
        assertNull(s.guessesText)
        assertNull(s.centsText)
    }

    @Test
    fun `brands are matched as whole names, apostrophes and all`() {
        assertEquals("members-mark", StoreCatalog.storeOf(null, "Member’s Mark — Almonds")?.id)
        assertEquals("trader-joes", StoreCatalog.storeOf("TRADER JOE'S", null)?.id)
        assertEquals("kirkland", StoreCatalog.storeOf(null, "Kirkland Signature — Cottage Cheese")?.id)
        assertEquals("365", StoreCatalog.storeOf(null, "365 Whole Foods Market — Oats")?.id)
        assertNull("a calorie count is not the 365 brand", StoreCatalog.storeOf(null, "Oatmeal 365 kcal"))
        assertNull(StoreCatalog.storeOf(null, "Great Valley Farms — Eggs"))
    }

    @Test
    fun `snoozes survive a round trip through settings`() {
        val m = mapOf("great-value" to 5L, "kirkland" to 1_795_000_000_000L)
        assertEquals(m, StoreSuggest.parseSnoozes(StoreSuggest.formatSnoozes(m)))
        assertEquals(emptyMap<String, Long>(), StoreSuggest.parseSnoozes(null))
        assertEquals(mapOf("a" to 1L), StoreSuggest.parseSnoozes("a:1,broken,:3,b:x"))
    }

    /** tools/store_packs.json builds the packs; the app's list must say the same. */
    @Test
    fun `the app's store list matches the build tool's`() {
        val file = listOf(File("../tools/store_packs.json"), File("tools/store_packs.json")).first { it.exists() }
        val stores = Json.parseToJsonElement(file.readText()).jsonObject["stores"]!!.jsonArray.map { it.jsonObject }
        assertEquals(stores.size, StoreCatalog.BUILT_IN.size)
        for (s in stores) {
            val id = s["id"]!!.jsonPrimitive.content
            val app = StoreCatalog.byId(id) ?: error("app lacks $id")
            assertEquals(id, s["title"]!!.jsonPrimitive.content, app.title)
            assertEquals(id, s["slot"]!!.jsonPrimitive.int, app.slot)
            assertEquals(id, s["aliases"]!!.jsonArray.map { it.jsonPrimitive.content }, app.aliases)
        }
        assertEquals("slots must be unique", StoreCatalog.BUILT_IN.size, StoreCatalog.BUILT_IN.map { it.slot }.toSet().size)
    }

    @Test
    fun `a food's store pack is known from its id`() {
        assertEquals("kirkland", StoreCatalog.forCandidate("label:2000123456")?.id)
        assertNull("a built-in label is no store's", StoreCatalog.forCandidate("label:1234567"))
        assertNull(StoreCatalog.forCandidate("usda:2000123456"))
        assertNull(StoreCatalog.forCandidate(null))
        assertNull("a slot nobody has", StoreCatalog.forCandidate("label:999000000001"))
    }
}
