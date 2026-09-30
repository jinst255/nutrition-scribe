package com.nutritionscribe.app.ui.exportimport

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nutritionscribe.app.data.db.NutritionScribeDatabase
import com.nutritionscribe.app.data.db.toEntry
import com.nutritionscribe.shared.model.Metric
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Importing a file exported by the PREVIOUS version of the app.
 *
 * This is not hypothetical housekeeping. The app's applicationId changed with the
 * rename, so an install of the new app cannot upgrade the old one in place — the
 * only way a user's existing food log reaches the new app is by exporting it from
 * the old one and importing it here. If this path is broken, their history is
 * stranded in an app they are about to uninstall.
 */
@RunWith(AndroidJUnit4::class)
class LegacyImportTest {

    private lateinit var db: NutritionScribeDatabase
    private lateinit var manager: ExportImportManager
    private val written = mutableListOf<File>()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, NutritionScribeDatabase::class.java).build()
        manager = ExportImportManager(context, db.entryDao())
    }

    @After
    fun tearDown() {
        written.forEach { it.delete() }
        db.close()
    }

    private fun fileUri(name: String, content: String): Uri {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val f = File(context.cacheDir, name)
        f.writeText(content)
        written.add(f)
        return Uri.fromFile(f)
    }

    /** Exactly the CSV the pre-rename app produced. */
    private val legacyCsv = """
        id,timestamp,raw_text,name,protein_grams,calories,carbs_grams,fat_grams,is_fixed_food,source,confidence
        v1-a,1789797747092,greek yogurt,Greek yogurt,17.0,130.0,9.0,4.0,false,manual,STATED
        v1-b,1789801347092,chicken breast,Chicken breast,43.0,284.0,0.0,12.0,false,openai,ESTIMATED
    """.trimIndent()

    /** Exactly the JSON the pre-rename app produced: flat nutrient fields, no map. */
    private val legacyJson = """
        [{"id":"v1-a","timestampEpochMillis":1789797747092,"rawText":"greek yogurt",
          "name":"Greek yogurt","proteinGrams":17.0,"calories":130.0,"carbsGrams":9.0,
          "fatGrams":4.0,"isFixedFood":false,"source":"manual","confidence":"STATED"}]
    """.trimIndent()

    @Test
    fun aCsvFromTheOldAppImportsWithItsNutrients() = runBlocking {
        manager.importFrom(fileUri("legacy.csv", legacyCsv))

        val entries = db.entryDao().observeAll().first().map { it.toEntry() }.sortedBy { it.id }
        assertEquals(2, entries.size)

        val yogurt = entries[0]
        assertEquals("Greek yogurt", yogurt.name)
        assertEquals(17.0, yogurt.proteinGrams, 0.0001)
        assertEquals(130.0, yogurt.calories!!, 0.0001)
        assertEquals(9.0, yogurt.carbsGrams!!, 0.0001)
        assertEquals(4.0, yogurt.fatGrams!!, 0.0001)
        assertEquals(1_789_797_747_092L, yogurt.timestampEpochMillis)

        // A 0.0 that was really recorded must survive as 0, not vanish.
        assertEquals(0.0, entries[1].carbsGrams!!, 0.0001)
        // Metrics the old app never had must be absent, not zero.
        assertNull(entries[1].amountOf(Metric.SODIUM))
    }

    @Test
    fun aJsonFromTheOldAppImportsWithItsNutrients() = runBlocking {
        // Entry's shape changed in v2 (flat nutrient fields became a map), so a v1
        // JSON file no longer matches the serializer. The old app offers JSON as an
        // export format, so anyone who picks it would lose everything.
        manager.importFrom(fileUri("legacy.json", legacyJson))

        val entries = db.entryDao().observeAll().first().map { it.toEntry() }
        assertEquals(1, entries.size)
        assertEquals("Greek yogurt", entries[0].name)
        assertEquals(17.0, entries[0].proteinGrams, 0.0001)
        assertEquals(130.0, entries[0].calories!!, 0.0001)
    }

    @Test
    fun importingTheSameFileTwiceDoesNotDuplicateEntries() = runBlocking {
        // Entries carry their original id, so a nervous second import should be a
        // no-op rather than doubling someone's calorie history.
        manager.importFrom(fileUri("legacy.csv", legacyCsv))
        manager.importFrom(fileUri("legacy2.csv", legacyCsv))
        assertEquals(2, db.entryDao().observeAll().first().size)
    }

    @Test
    fun aRoundTripThroughTheCurrentFormatKeepsEveryMetric() = runBlocking {
        manager.importFrom(fileUri("legacy.csv", legacyCsv))
        val out = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "out.csv",
        ).also { written.add(it) }
        manager.exportTo(Uri.fromFile(out), ExportFormat.CSV)

        assertTrue(out.readText().isNotBlank())
        db.clearAllTables()
        manager.importFrom(Uri.fromFile(out))

        val again = db.entryDao().observeAll().first().map { it.toEntry() }.sortedBy { it.id }
        assertEquals(2, again.size)
        assertEquals(17.0, again[0].proteinGrams, 0.0001)
        assertNotNull(again[0].calories)
    }

    @Test
    fun aTruncatedFileImportsWhatItCanRatherThanThrowing() = runBlocking {
        // Half-copied files happen. Losing the last row is acceptable; crashing
        // part-way through an import is not.
        val truncated = legacyCsv.substringBeforeLast("\n") + "\nv1-c,1789801347092,half"
        manager.importFrom(fileUri("bad.csv", truncated))
        assertTrue(db.entryDao().observeAll().first().isNotEmpty())
    }

    // ------------------------------------------------------------------
    // Saying what happened. All of these used to be indistinguishable from
    // success, because the screen discarded the result entirely.
    // ------------------------------------------------------------------

    @Test
    fun anImportReportsHowMuchItRead() = runBlocking {
        val outcome = manager.importFrom(fileUri("ok.csv", legacyCsv))
        assertTrue("expected success, got $outcome", outcome is TransferResult.Imported)
        assertEquals(2, (outcome as TransferResult.Imported).count)
    }

    @Test
    fun aFileThatIsNotAnExportSaysSoInsteadOfSayingNothing() = runBlocking {
        val outcome = manager.importFrom(fileUri("notes.txt", "shopping list\nmilk\neggs"))
        assertTrue("expected failure, got $outcome", outcome is TransferResult.Failed)
        assertTrue(db.entryDao().observeAll().first().isEmpty())
    }

    @Test
    fun anEmptyFileSaysSo() = runBlocking {
        val outcome = manager.importFrom(fileUri("empty.json", "   "))
        assertTrue(outcome is TransferResult.Failed)
    }

    @Test
    fun theFormatComesFromTheFileNotFromAChip() = runBlocking {
        // Picking the wrong format was an error the user had no way to see
        // coming. Both of these import the same entries with no format argument.
        val fromCsv = manager.importFrom(fileUri("a.csv", legacyCsv))
        assertEquals(ExportFormat.CSV, (fromCsv as TransferResult.Imported).format)

        val fromJson = manager.importFrom(fileUri("a.json", legacyJson))
        assertEquals(ExportFormat.JSON, (fromJson as TransferResult.Imported).format)
    }

    @Test
    fun anExportReportsHowMuchItWrote() = runBlocking {
        manager.importFrom(fileUri("seed.csv", legacyCsv))
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(ctx.cacheDir, "out.json").also { written.add(it) }
        val outcome = manager.exportTo(Uri.fromFile(out), ExportFormat.JSON)
        assertTrue("expected success, got $outcome", outcome is TransferResult.Exported)
        assertEquals(2, (outcome as TransferResult.Exported).count)
        assertTrue(out.length() > 0)
    }
}
