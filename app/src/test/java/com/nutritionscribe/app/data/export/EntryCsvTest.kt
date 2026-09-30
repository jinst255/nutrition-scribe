package com.nutritionscribe.app.data.export

import com.nutritionscribe.shared.model.Confidence
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.model.nutrientsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The export is the only way a log ever leaves this app. These tests exist so that a
 * future schema change cannot quietly corrupt it — a corrupted export is not noticed
 * until someone tries to use the data, by which point the original may be gone.
 */
class EntryCsvTest {

    private val zone = ZoneId.of("America/Denver")

    private fun entry(
        id: String = "id-1",
        ts: Long = 1_789_797_747_092L,
        raw: String = "greek yogurt",
        name: String = "Greek yogurt",
        protein: Double = 17.0,
        calories: Double? = 130.0,
        carbs: Double? = 9.0,
        fat: Double? = 4.0,
        fixed: Boolean = false,
        source: String = "manual",
        confidence: Confidence = Confidence.STATED,
        timeUnspecified: Boolean = false,
    ) = Entry(
        id = id, timestampEpochMillis = ts, rawText = raw, name = name,
        nutrients = nutrientsOf(protein = protein, calories = calories, carbs = carbs, fat = fat),
        isFixedFood = fixed, source = source,
        confidence = confidence, timeUnspecified = timeUnspecified,
    )

    @Test
    fun `round trip preserves every field`() {
        val original = listOf(
            entry(),
            entry(id = "id-2", name = "Chicken breast", protein = 43.0, confidence = Confidence.ESTIMATED),
        )
        val decoded = EntryCsv.decode(EntryCsv.encode(original, zone))
        assertEquals(original, decoded)
    }

    @Test
    fun `null macros survive as null rather than becoming zero`() {
        // A missing calorie count and a calorie count of 0 mean different things.
        // Collapsing them is the kind of silent corruption this suite exists to catch.
        val original = listOf(entry(calories = null, carbs = null, fat = null))
        val decoded = EntryCsv.decode(EntryCsv.encode(original, zone))
        assertNull(decoded[0].calories)
        assertNull(decoded[0].carbsGrams)
        assertNull(decoded[0].fatGrams)
    }

    @Test
    fun `an unspecified time round trips and exports a blank local datetime`() {
        val original = listOf(entry(timeUnspecified = true))
        val csv = EntryCsv.encode(original, zone)
        val cols = EntryCsv.splitLine(csv.lines()[1])
        val header = EntryCsv.splitLine(EntryCsv.HEADER)

        assertEquals("", cols[header.indexOf("local_datetime")])
        assertEquals("true", cols[header.indexOf("time_unspecified")])
        assertTrue(EntryCsv.decode(csv)[0].timeUnspecified)
    }

    @Test
    fun `a real time exports a readable local datetime`() {
        val csv = EntryCsv.encode(listOf(entry()), zone)
        val header = EntryCsv.splitLine(EntryCsv.HEADER)
        val value = EntryCsv.splitLine(csv.lines()[1])[header.indexOf("local_datetime")]
        assertTrue("expected an ISO local datetime, got '$value'", value.startsWith("2026-"))
        assertTrue(value.contains("T"))
    }

    @Test
    fun `commas quotes and newlines in food names survive intact`() {
        val nasty = "Ben & Jerry's \"Half Baked\", 1/2 pint\nsecond line"
        val original = listOf(entry(name = nasty, raw = nasty))
        val decoded = EntryCsv.decode(EntryCsv.encode(original, zone))
        assertEquals(1, decoded.size)
        assertEquals(nasty, decoded[0].name)
        assertEquals(nasty, decoded[0].rawText)
    }

    @Test
    fun `a newline inside a quoted field does not split the row`() {
        // The bug this guards: a line-oriented reader turns one entry into two
        // malformed ones, so a log silently gains a junk row on every import.
        val entries = listOf(
            entry(id = "a", name = "line one\nline two"),
            entry(id = "b", name = "plain"),
        )
        val decoded = EntryCsv.decode(EntryCsv.encode(entries, zone))
        assertEquals(2, decoded.size)
        assertEquals(listOf("a", "b"), decoded.map { it.id })
    }

    @Test
    fun `windows line endings import correctly`() {
        val crlf = "id,timestamp,name,protein_grams,confidence\r\n" +
            "w-1,1789797747092,Greek yogurt,17.0,STATED\r\n"
        val decoded = EntryCsv.decode(crlf)
        assertEquals(1, decoded.size)
        assertEquals("Greek yogurt", decoded[0].name)
        assertEquals(17.0, decoded[0].proteinGrams, 0.0001)
    }

    @Test
    fun `older exports without the new columns still import`() {
        // Exactly the file v1 produced, before local_datetime/time_unspecified existed.
        val old = """
            id,timestamp,raw_text,name,protein_grams,calories,carbs_grams,fat_grams,is_fixed_food,source,confidence
            old-1,1789797747092,greek yogurt,Greek yogurt,17.0,130.0,9.0,4.0,false,manual,STATED
        """.trimIndent()
        val decoded = EntryCsv.decode(old)
        assertEquals(1, decoded.size)
        assertEquals("Greek yogurt", decoded[0].name)
        assertEquals(17.0, decoded[0].proteinGrams, 0.0001)
        assertEquals(1_789_797_747_092L, decoded[0].timestampEpochMillis)
        // Absent column must read as false, not crash and not true.
        assertEquals(false, decoded[0].timeUnspecified)
    }

    @Test
    fun `columns are read by name so reordering them is harmless`() {
        val reordered = """
            name,protein_grams,id,timestamp,confidence
            Greek yogurt,17.0,x-1,1789797747092,STATED
        """.trimIndent()
        val decoded = EntryCsv.decode(reordered)
        assertEquals("x-1", decoded[0].id)
        assertEquals("Greek yogurt", decoded[0].name)
        assertEquals(17.0, decoded[0].proteinGrams, 0.0001)
    }

    @Test
    fun `rows with no id or no timestamp are skipped not defaulted`() {
        val broken = """
            id,timestamp,name,protein_grams,confidence
            ,1789797747092,No id,17.0,STATED
            has-id,,No timestamp,17.0,STATED
            good,1789797747092,Fine,17.0,STATED
        """.trimIndent()
        val decoded = EntryCsv.decode(broken)
        assertEquals(1, decoded.size)
        assertEquals("good", decoded[0].id)
    }

    @Test
    fun `the v2 metrics round trip including the non-gram units`() {
        // Sodium is mg and caffeine is mg, not grams. These are the units most likely
        // to be silently mangled by code that assumes everything is grams.
        val original = entry().copy(
            nutrients = nutrientsOf(
                protein = 43.0, calories = 284.0, carbs = 0.0, fat = 12.0,
                fiber = 3.5, sugar = 1.2, saturatedFat = 4.0,
                sodium = 1180.0, caffeine = 95.0,
            ),
        )
        val decoded = EntryCsv.decode(EntryCsv.encode(listOf(original), zone))[0]
        assertEquals(1180.0, decoded.amountOf(Metric.SODIUM)!!, 0.0001)
        assertEquals(95.0, decoded.amountOf(Metric.CAFFEINE)!!, 0.0001)
        assertEquals(4.0, decoded.amountOf(Metric.SATURATED_FAT)!!, 0.0001)
        assertEquals(3.5, decoded.amountOf(Metric.FIBER)!!, 0.0001)
        assertEquals(1.2, decoded.amountOf(Metric.SUGAR)!!, 0.0001)
        assertEquals(original.nutrients, decoded.nutrients)
    }

    @Test
    fun `a metric the user never tracked stays absent rather than reading zero`() {
        val decoded = EntryCsv.decode(EntryCsv.encode(listOf(entry()), zone))[0]
        assertNull(decoded.amountOf(Metric.SODIUM))
        assertNull(decoded.amountOf(Metric.CAFFEINE))
    }

    @Test
    fun `needs detail entries round trip and carry no invented numbers`() {
        // "I ate a solid breakfast" — the words are kept, the numbers are not guessed.
        val vague = Entry(
            id = "vague-1",
            timestampEpochMillis = 1_789_797_747_092L,
            rawText = "I ate a solid breakfast",
            name = "I ate a solid breakfast",
            nutrients = emptyMap(),
            source = "manual",
            confidence = Confidence.ESTIMATED,
            needsDetail = true,
        )
        val decoded = EntryCsv.decode(EntryCsv.encode(listOf(vague), zone))[0]
        assertTrue(decoded.needsDetail)
        assertEquals("I ate a solid breakfast", decoded.rawText)
        assertEquals(0.0, decoded.proteinGrams, 0.0001)
        assertNull(decoded.calories)
    }

    @Test
    fun `header only file decodes to nothing`() {
        assertEquals(emptyList<Entry>(), EntryCsv.decode(EntryCsv.HEADER))
        assertEquals(emptyList<Entry>(), EntryCsv.decode(""))
    }

    @Test
    fun `an unknown confidence value falls back instead of throwing`() {
        val weird = """
            id,timestamp,name,protein_grams,confidence
            a,1789797747092,Thing,17.0,BANANAS
        """.trimIndent()
        val decoded = EntryCsv.decode(weird)
        assertNotNull(decoded[0])
        assertEquals(Confidence.ESTIMATED, decoded[0].confidence)
    }
}
