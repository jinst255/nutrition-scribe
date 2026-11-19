package com.nutritionscribe.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nutritionscribe.app.data.db.NutritionScribeDatabase
import com.nutritionscribe.app.data.db.toEntry
import com.nutritionscribe.app.data.db.toNutrientRows
import com.nutritionscribe.app.data.db.toRow
import com.nutritionscribe.shared.model.Confidence
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.model.LoggedItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Review F3 (2026-11-04): History's bulk delete deleted at once, with no confirm
 * and no Undo. Undo puts back the same entries: ids, times, items and numbers.
 */
@RunWith(AndroidJUnit4::class)
class DeleteUndoTest {

    private lateinit var db: NutritionScribeDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, NutritionScribeDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun undoPutsDeletedEntriesBackWhole() = runBlocking {
        val dao = db.entryDao()
        val entries = (1..3).map { i ->
            Entry(id = "e$i", timestampEpochMillis = 1_000L * i, rawText = "toast $i", name = "Toast", nutrients = mapOf("calories" to 80.0 * i),
                source = "usda", confidence = Confidence.ESTIMATED,
                items = listOf(LoggedItem(name = "Toast", said = "toast", key = "toast", portion = "1 slice", grams = 30.0 * i,
                    origin = "USDA", candidateId = "usda:1", nutrients = mapOf("calories" to 80.0 * i))))
        }
        entries.forEach { dao.upsert(it.toRow(), it.toNutrientRows()) }
        dao.deleteByIds(entries.map { it.id })
        assertNull(dao.find("e2"))
        entries.forEach { dao.upsert(it.toRow(), it.toNutrientRows()) } // what EntryRepository.restore does
        val back = dao.find("e2")!!.toEntry()
        assertEquals(entries[1].copy(nutrients = back.nutrients), back)
        assertEquals(160.0, back.nutrients.getValue("calories"), 0.0)
        assertEquals(60.0, back.items.single().grams!!, 0.0)
    }
}
