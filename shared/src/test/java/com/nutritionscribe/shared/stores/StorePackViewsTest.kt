package com.nutritionscribe.shared.stores

import com.nutritionscribe.shared.resolve.JdbcSql
import com.nutritionscribe.shared.resolve.LabelPackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Store packs are ATTACHed to the label pack and read through TEMP views named like
 * its tables. These tests build a tiny built-in pack and a tiny Kirkland pack on
 * disk and run the app's own LabelPackSource over the views: a store barcode must
 * be found, a logged store food must still open by id, and the built-in labels
 * must keep working, with or without stores attached.
 */
class StorePackViewsTest {

    private val schema = """
        CREATE TABLE product (id INTEGER PRIMARY KEY, brand TEXT, description TEXT,
            serving_g REAL, serving_text TEXT, count REAL, count_unit TEXT, measure REAL, measure_unit TEXT,
            kcal INTEGER, protein_g INTEGER, carbs_g INTEGER, fat_g INTEGER, fiber_g INTEGER, sugar_g INTEGER,
            sat_fat_g INTEGER, sodium_mg INTEGER, caffeine_mg INTEGER, pop INTEGER, owner TEXT,
            calcium_mg INTEGER, iron_mg INTEGER, potassium_mg INTEGER, vitamin_d_ug INTEGER, cholesterol_mg INTEGER);
        CREATE TABLE gtin (gtin TEXT PRIMARY KEY, id INTEGER NOT NULL);
        CREATE TABLE pword (word TEXT NOT NULL, id INTEGER NOT NULL, in_brand INTEGER, PRIMARY KEY (word, id));
        CREATE TABLE brand_word (word TEXT PRIMARY KEY);
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
    """.trimIndent()

    private fun db(file: File, sql: String) {
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { c ->
            c.createStatement().use { st -> (schema + "\n" + sql).split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach(st::execute) }
        }
    }

    /** The pack's word index, made by the app's own word rules (build_label_pack.words_of mirrors them). */
    private fun pwords(id: Long, brand: String, desc: String): String {
        val b = LabelPackSource.words(brand).toSet()
        val rows = (b + LabelPackSource.words(desc)).map { w -> "('$w', $id, ${if (w in b) 1 else 0})" }
        return "INSERT INTO pword VALUES ${rows.joinToString(", ")};"
    }

    private fun packs(): Pair<File, File> {
        val dir = createTempDir("storepacks")
        val main = File(dir, "labels.db")
        db(main, """
            INSERT INTO product VALUES (123, 'Skippy', 'Creamy Peanut Butter', 32, '2 tbsp (32 g)', NULL, NULL, 2, 'tbsp',
              1900, 70, 60, 160, 20, 30, 35, 1500, NULL, 50, NULL, NULL, NULL, NULL, NULL, NULL);
            INSERT INTO gtin VALUES ('037600165662', 123);
            ${pwords(123, "Skippy", "Creamy Peanut Butter")}
            INSERT INTO brand_word VALUES ('skippy');
            INSERT INTO meta VALUES ('format', '4');
        """)
        val store = File(dir, "kirkland.db")
        val id = 2 * StorePackViews.SLOT_SIZE + 9177646
        db(store, """
            INSERT INTO product VALUES ($id, 'Kirkland Signature', 'Cottage Cheese', 113, '1/2 cup (113 g)', NULL, NULL, 0.5, 'cup',
              900, 130, 50, 25, 0, 40, 15, 3600, NULL, 12, NULL, 800, 0, 1500, 0, 100);
            INSERT INTO gtin VALUES ('0096619177646', $id);
            ${pwords(id, "Kirkland Signature", "Cottage Cheese")}
            INSERT INTO brand_word VALUES ('kirkland'), ('skippy');
            INSERT INTO meta VALUES ('format', '4'), ('kind', 'store'), ('store', 'kirkland'), ('slot', '2'), ('products', '1');
        """)
        return main to store
    }

    private fun Connection.run(sql: List<String>) = createStatement().use { st -> sql.forEach(st::execute) }

    @Test
    fun `a store pack's barcode, id and words are found through the views`() {
        val (main, store) = packs()
        DriverManager.getConnection("jdbc:sqlite:${main.path}").use { c ->
            c.run(listOf("ATTACH DATABASE '${store.path}' AS ${StorePackViews.schemaName(2)}"))
            c.run(StorePackViews.views(mainHasMicros = true, slots = listOf(2)))
            val src = LabelPackSource(JdbcSql(c), hasMicros = true)

            val scanned = src.byBarcode("096619177646")
            assertNotNull("store barcode not found", scanned)
            assertEquals("Cottage Cheese", scanned!!.name)
            val id = scanned.id.removePrefix("label:").toLong()
            assertEquals(2, StorePackViews.slotOf(id))
            assertEquals("a logged store food must still open", "Cottage Cheese", src.byId(id)?.name)
            // Calcium comes from the store pack's micronutrient column.
            assertTrue(scanned.per100g?.containsKey("calcium") == true)

            // The built-in pack still answers, through the same views.
            assertEquals("Creamy Peanut Butter", src.byBarcode("037600165662")?.name)
            assertNull(StorePackViews.slotOf(123))
            assertTrue(src.search("kirkland cottage cheese").any { it.name == "Cottage Cheese" })
            assertTrue(src.isBrandWord("kirkland"))
        }
    }

    @Test
    fun `dropping the views brings back the built-in pack alone`() {
        val (main, store) = packs()
        DriverManager.getConnection("jdbc:sqlite:${main.path}").use { c ->
            c.run(listOf("ATTACH DATABASE '${store.path}' AS store2"))
            c.run(StorePackViews.views(true, listOf(2)))
            c.run(StorePackViews.dropViews())
            val src = LabelPackSource(JdbcSql(c), hasMicros = true)
            assertNull(src.byBarcode("0096619177646"))
            assertNotNull(src.byBarcode("037600165662"))
        }
    }

    @Test
    fun `no stores means no views at all`() {
        assertTrue(StorePackViews.views(true, emptyList()).all { it.startsWith("DROP VIEW") })
    }

    @Test
    fun `a format 3 built-in pack leaves the micronutrient columns out`() {
        val v = StorePackViews.views(mainHasMicros = false, slots = listOf(1)).first { it.contains("VIEW product") }
        assertTrue(v, !v.contains("calcium_mg"))
        val w = StorePackViews.views(mainHasMicros = true, slots = listOf(1)).first { it.contains("VIEW product") }
        assertTrue(w, w.contains("calcium_mg"))
    }

    @Test
    fun `a pack is attached only when its meta matches the catalogue`() {
        val good = mapOf("kind" to "store", "store" to "kirkland", "slot" to "2", "format" to "4", "products" to "995")
        assertNull(StorePackViews.metaProblem(good, "kirkland", 2))
        assertNotNull(StorePackViews.metaProblem(good - "kind", "kirkland", 2))
        assertNotNull(StorePackViews.metaProblem(good, "great-value", 2))
        assertNotNull("wrong slot would put its ids in another store's range", StorePackViews.metaProblem(good, "kirkland", 1))
        assertNotNull(StorePackViews.metaProblem(good + ("format" to "3"), "kirkland", 2))
        assertNotNull(StorePackViews.metaProblem(good + ("products" to "0"), "kirkland", 2))
    }

    @Test
    fun `schema names refuse anything but a plain slot number`() {
        assertEquals("store12", StorePackViews.schemaName(12))
        for (bad in listOf(0, -1, 1000)) {
            assertTrue(runCatching { StorePackViews.schemaName(bad) }.isFailure)
        }
    }
}
