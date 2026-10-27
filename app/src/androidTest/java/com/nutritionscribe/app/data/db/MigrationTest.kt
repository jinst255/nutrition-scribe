package com.nutritionscribe.app.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The database is the only copy of the user's food log, so every migration is
 * replayed here against realistic rows before it is allowed near a real device.
 *
 * `runMigrationsAndValidate` also checks the resulting schema against the exported
 * JSON, so a migration that produces subtly the wrong table fails here rather than
 * at the next app launch.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NutritionScribeDatabase::class.java,
    )

    private fun insertV2Entry(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: String,
        name: String,
        protein: Double,
        calories: String = "NULL",
        carbs: String = "NULL",
        fat: String = "NULL",
        timeUnspecified: Int = 0,
        timestamp: Long = 1_789_797_747_092L,
    ) {
        db.execSQL(
            "INSERT INTO entries (id, timestampEpochMillis, rawText, name, proteinGrams, calories," +
                " carbsGrams, fatGrams, isFixedFood, source, confidence, detailCompacted, timeUnspecified)" +
                " VALUES (?, $timestamp, ?, ?, $protein, $calories, $carbs, $fat, 0, 'manual', 'STATED', 0, $timeUnspecified)",
            arrayOf(id, name, name),
        )
    }

    @Test
    fun migrate2To3_preservesEveryEntryAndMovesNutrientsAcross() {
        helper.createDatabase(dbName, 2).use { db ->
            insertV2Entry(db, "full", "Chicken breast", 43.0, calories = "284.0", carbs = "0.0", fat = "12.0")
            // Only protein recorded — the others were never known, and must NOT
            // become zeros, because "unknown" and "zero" are different facts.
            insertV2Entry(db, "protein-only", "Mystery snack", 17.0)
            insertV2Entry(db, "no-time", "Eggs", 19.0, timeUnspecified = 1)
            insertV2Entry(db, "punctuation", "Ben & Jerry's \"Half Baked\", pint", 8.0)
        }

        val db = helper.runMigrationsAndValidate(
            dbName, 3, true, NutritionScribeDatabase.MIGRATION_2_3,
        )

        db.query("SELECT COUNT(*) FROM entries").use {
            it.moveToFirst()
            assertEquals("no entry may be lost by a migration", 4, it.getInt(0))
        }

        db.query("SELECT id, name, timeUnspecified, needsDetail, foodDbId FROM entries ORDER BY id").use { c ->
            val seen = mutableMapOf<String, Triple<String, Int, Int>>()
            while (c.moveToNext()) {
                seen[c.getString(0)] = Triple(c.getString(1), c.getInt(2), c.getInt(3))
                assertTrue("foodDbId should start null", c.isNull(4))
            }
            assertEquals(setOf("full", "no-time", "protein-only", "punctuation"), seen.keys)
            assertEquals("Chicken breast", seen["full"]!!.first)
            assertEquals("Ben & Jerry's \"Half Baked\", pint", seen["punctuation"]!!.first)
            assertEquals("the backdated flag must survive", 1, seen["no-time"]!!.second)
            assertEquals("needsDetail defaults to false", 0, seen["full"]!!.third)
        }

        fun amount(entryId: String, metric: String): Double? {
            db.query(
                "SELECT amount FROM entry_nutrient WHERE entryId = ? AND metric = ?",
                arrayOf(entryId, metric),
            ).use { return if (it.moveToFirst()) it.getDouble(0) else null }
        }

        assertEquals(43.0, amount("full", "protein")!!, 0.0001)
        assertEquals(284.0, amount("full", "calories")!!, 0.0001)
        assertEquals(12.0, amount("full", "fat")!!, 0.0001)
        // A carb value of 0.0 was genuinely recorded, so it must be carried over as 0.
        assertEquals(0.0, amount("full", "carbs")!!, 0.0001)

        assertEquals(17.0, amount("protein-only", "protein")!!, 0.0001)
        assertNull("an unrecorded nutrient must stay absent, not become 0", amount("protein-only", "calories"))
        assertNull(amount("protein-only", "carbs"))
        assertNull(amount("protein-only", "fat"))

        db.query("SELECT COUNT(*) FROM metric_goal").use {
            it.moveToFirst()
            assertEquals("goals start empty and are seeded by the app", 0, it.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrate2To3_dropsTheOldNutrientColumns() {
        helper.createDatabase(dbName, 2).use { db ->
            insertV2Entry(db, "a", "Thing", 10.0)
        }
        val db = helper.runMigrationsAndValidate(dbName, 3, true, NutritionScribeDatabase.MIGRATION_2_3)
        val columns = mutableListOf<String>()
        db.query("PRAGMA table_info(entries)").use { c ->
            while (c.moveToNext()) columns.add(c.getString(1))
        }
        // Leaving these behind would mean two competing sources of truth for the
        // same number, which is how a log starts disagreeing with itself.
        assertFalse(columns.contains("proteinGrams"))
        assertFalse(columns.contains("calories"))
        assertTrue(columns.contains("needsDetail"))
        assertTrue(columns.contains("foodDbId"))
        db.close()
    }

    @Test
    fun migrate1To3_runsTheWholeChain() {
        // Someone who never installed the v2 build jumps straight from 1 to 3.
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                "INSERT INTO entries (id, timestampEpochMillis, rawText, name, proteinGrams, calories," +
                    " carbsGrams, fatGrams, isFixedFood, source, confidence, detailCompacted)" +
                    " VALUES ('old', 1789797747092, 'yogurt', 'Greek yogurt', 17.0, NULL, NULL, NULL, 0," +
                    " 'manual', 'STATED', 0)"
            )
        }
        val db = helper.runMigrationsAndValidate(
            dbName, 3, true,
            NutritionScribeDatabase.MIGRATION_1_2,
            NutritionScribeDatabase.MIGRATION_2_3,
        )
        db.query("SELECT name FROM entries WHERE id = 'old'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Greek yogurt", it.getString(0))
        }
        db.query("SELECT amount FROM entry_nutrient WHERE entryId = 'old' AND metric = 'protein'").use {
            assertTrue("protein must survive a two-step migration", it.moveToFirst())
            assertEquals(17.0, it.getDouble(0), 0.0001)
        }
        db.close()
    }

    @Test
    fun migrate4To5_keepsEveryEntryAndAddsTheUsersFoods() {
        // The cache-first lookup: an install upgraded from v4 keeps its whole log,
        // untouched, and starts with no remembered foods of its own.
        helper.createDatabase(dbName, 4).use { db ->
            db.execSQL(
                "INSERT INTO entries (id, timestampEpochMillis, rawText, name, isFixedFood, source, confidence," +
                    " detailCompacted, timeUnspecified, needsDetail, foodDbId)" +
                    " VALUES ('e4', 1789797747092, 'PB&J', 'PB&J', 0, 'openai', 'ESTIMATED', 0, 0, 0, NULL)"
            )
            db.execSQL("INSERT INTO entry_nutrient (entryId, metric, amount) VALUES ('e4', 'calories', 400.0)")
        }
        val db = helper.runMigrationsAndValidate(dbName, 5, true, NutritionScribeDatabase.MIGRATION_4_5)
        db.query("SELECT name, itemsJson, needsConfirm FROM entries WHERE id = 'e4'").use {
            assertTrue(it.moveToFirst())
            assertEquals("PB&J", it.getString(0))
            assertTrue("no item breakdown for an old entry", it.isNull(1))
            assertEquals(0, it.getInt(2))
        }
        db.query("SELECT amount FROM entry_nutrient WHERE entryId = 'e4'").use {
            assertTrue(it.moveToFirst())
            assertEquals(400.0, it.getDouble(0), 0.0001)
        }
        db.query("SELECT COUNT(*) FROM personal_food").use {
            assertTrue(it.moveToFirst())
            assertEquals("old entries are NOT imported as remembered foods", 0, it.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrate5To6_keepsEverythingAndAddsAnEmptySpendingTable() {
        helper.createDatabase(dbName, 5).use { db ->
            db.execSQL(
                "INSERT INTO entries (id, timestampEpochMillis, rawText, name, isFixedFood, source, confidence," +
                    " detailCompacted, timeUnspecified, needsDetail, foodDbId, itemsJson, needsConfirm)" +
                    " VALUES ('e5', 1789797747092, 'two eggs', 'Egg', 0, 'usda', 'ESTIMATED', 0, 0, 0, NULL, '[]', 1)"
            )
            db.execSQL("INSERT INTO entry_nutrient (entryId, metric, amount) VALUES ('e5', 'protein', 12.5)")
            db.execSQL(
                "INSERT INTO personal_food (`key`, name, json, timesUsed, updatedAt, isMeal) " +
                    "VALUES ('egg', 'Egg', '{}', 3, 1789797747092, 0)"
            )
        }
        val db = helper.runMigrationsAndValidate(dbName, 6, true, NutritionScribeDatabase.MIGRATION_5_6)
        db.query("SELECT name, itemsJson, needsConfirm FROM entries WHERE id = 'e5'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Egg", it.getString(0))
            assertEquals("[]", it.getString(1))
            assertEquals(1, it.getInt(2))
        }
        db.query("SELECT amount FROM entry_nutrient WHERE entryId = 'e5'").use {
            assertTrue(it.moveToFirst())
            assertEquals(12.5, it.getDouble(0), 0.0001)
        }
        db.query("SELECT timesUsed FROM personal_food WHERE `key` = 'egg'").use {
            assertTrue(it.moveToFirst())
            assertEquals(3, it.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM ai_usage").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrate6To7_keepsEverythingAndAddsEmptyAskTables() {
        helper.createDatabase(dbName, 6).use { db ->
            db.execSQL(
                "INSERT INTO entries (id, timestampEpochMillis, rawText, name, isFixedFood, source, confidence," +
                    " detailCompacted, timeUnspecified, needsDetail, foodDbId, itemsJson, needsConfirm)" +
                    " VALUES ('e6', 1789797747092, 'an apple', 'Apple', 0, 'usda', 'ESTIMATED', 0, 0, 0, NULL, '[]', 0)"
            )
            db.execSQL("INSERT INTO entry_nutrient (entryId, metric, amount) VALUES ('e6', 'calories', 95.0)")
        }
        val db = helper.runMigrationsAndValidate(dbName, 7, true, NutritionScribeDatabase.MIGRATION_6_7)
        db.query("SELECT name FROM entries WHERE id = 'e6'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Apple", it.getString(0))
        }
        db.query("SELECT amount FROM entry_nutrient WHERE entryId = 'e6'").use {
            assertTrue(it.moveToFirst())
            assertEquals(95.0, it.getDouble(0), 0.0001)
        }
        listOf("chat", "chat_message", "meal_plan").forEach { t ->
            db.query("SELECT COUNT(*) FROM $t").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
        }
        db.close()
    }
}
