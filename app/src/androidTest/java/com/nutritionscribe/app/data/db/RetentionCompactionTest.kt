package com.nutritionscribe.app.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Review F2 (2026-11-04): compacting an old entry renamed it "Compacted entry", so
 * History filled with entries nobody could tell apart. It drops what was said and
 * the food matches, and keeps the name and the numbers.
 */
@RunWith(AndroidJUnit4::class)
class RetentionCompactionTest {

    private lateinit var db: NutritionScribeDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, NutritionScribeDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun compactingKeepsTheNameAndNumbers() = runBlocking {
        val dao = db.entryDao()
        dao.upsert(
            EntryRow(id = "old", timestampEpochMillis = 1_000, rawText = "two eggs and toast", name = "Eggs, Toast",
                isFixedFood = false, source = "usda", confidence = "ESTIMATED", timeUnspecified = false, needsDetail = false,
                foodDbId = null, itemsJson = "[]"),
            listOf(EntryNutrientRow("old", "calories", 300.0)),
        )
        dao.compactDetailBefore(2_000)
        val e = dao.find("old")!!.toEntry()
        assertEquals("Eggs, Toast", e.name)
        assertEquals("", e.rawText)
        assertEquals(300.0, e.nutrients.getValue("calories"), 0.0)
        assertTrue(e.items.isEmpty())
        assertEquals(1, dao.countBefore(2_000))
    }
}
