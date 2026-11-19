package com.nutritionscribe.shared.resolve

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenFoodFactsParseTest {

    /**
     * Owner, 2026-10-28, on a protein powder found by barcode: "I think it missed a
     * lot of these values. The nutrition label is quite detailed." Open Food Facts
     * had its B vitamins, phosphorus, copper and manganese; they were never read,
     * so they were borrowed from the food list (dashed) instead. Every nutrient the
     * app tracks is taken from the product page when it is there.
     */
    @Test
    fun `a detailed product page brings every nutrient the app tracks`() {
        val body = """{"status":1,"product":{"product_name":"Super Protein","brands":"Metabolic","serving_quantity":32,
            "nutriments":{"energy-kcal_100g":390,"proteins_100g":75,"carbohydrates_100g":10,"fat_100g":5,
            "trans-fat_100g":0.1,"added-sugars_100g":3,"phosphorus_100g":0.5,"copper_100g":0.0006,"manganese_100g":0.0015,
            "vitamin-b1_100g":0.0012,"vitamin-b2_100g":0.0013,"vitamin-pp_100g":0.016,"vitamin-b6_100g":0.0017}}}"""
        val n = OpenFoodFactsSource.parse("019962361012", body)!!.per100g!!
        assertEquals(0.1, n.getValue("trans_fat"), 1e-9)
        assertEquals(3.0, n.getValue("added_sugar"), 1e-9)
        assertEquals(500.0, n.getValue("phosphorus"), 1e-6)
        assertEquals(0.6, n.getValue("copper"), 1e-6)
        assertEquals(1.5, n.getValue("manganese"), 1e-6)
        assertEquals(1.2, n.getValue("thiamin"), 1e-6)
        assertEquals(1.3, n.getValue("riboflavin"), 1e-6)
        assertEquals(16.0, n.getValue("niacin"), 1e-6)
        assertEquals(1.7, n.getValue("vitamin_b6"), 1e-6)
    }
}
