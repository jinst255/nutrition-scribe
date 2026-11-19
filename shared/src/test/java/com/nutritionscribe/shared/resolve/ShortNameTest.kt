package com.nutritionscribe.shared.resolve

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortNameTest {

    /**
     * Review L2 and the owner (2026-11-07: "this is just words. Isn't actually like
     * the name of the thing. It's like marketing"): database names leaked onto
     * cards, lists and My foods. A name a person would say goes there; the source's
     * own name stays on the food's detail.
     */
    @Test
    fun `database names become names a person would say`() {
        assertEquals("Apples", ShortName.of("Apples, raw, with skin (Includes foods for USDA's Food Distribution Program)"))
        assertEquals("Cottage cheese", ShortName.of("Cheese, cottage, NFS"))
        assertEquals("White rice", ShortName.of("Rice, white, cooked, NS as to fat"))
        assertEquals("Pineapple", ShortName.of("Pineapple, raw, all varieties"))
        assertEquals("Taco", ShortName.of("Taco, NFS"))
        assertEquals("Maple syrup", ShortName.of("Syrups, maple"))
        assertEquals("Fruit & Yogurt Crispy Rice and Wheat Cereal", ShortName.of(
            "Fruit & Yogurt Crispy Rice And Wheat Cereal With Oat And Naturally Fruit Flavored With Other Natural Flavors Clusters And Yogurt Flavored Coated Clusters, Fruit & Yogurt"))
        assertEquals("Dark Chocolate Sea Salt Caramel Squares", ShortName.of("Dark Chocolate Sea Salt Caramel Squares, Dark Chocolate Sea Salt Caramel"))
        // Already short, or a person's own words: left alone.
        assertEquals("Peanut butter", ShortName.of("Peanut butter"))
        assertEquals("Mom's chili", ShortName.of("Mom's chili"))
        // Names already stored as "Brand — Product, Kind" keep the brand whole.
        assertEquals("Great Value — 100% Whole Wheat Round Top Bread", ShortName.of("Great Value — 100% Whole Wheat Round Top Bread, 100% Whole Wheat"))
        // With its brand, once.
        assertEquals("Great Value — Real Bacon Pieces", ShortName.withBrand("Real Bacon Pieces", "Great Value"))
        assertEquals("Ghirardelli — Dark Chocolate Mint Squares", ShortName.withBrand("Dark Chocolate Mint Squares, Dark Chocolate Mint", "Ghirardelli"))
    }

    /** Owner, 2026-10-29: a Goldfish barcode came back as "Pepperidge Farm Crackers". "This was gold fish." */
    @Test
    fun `a name that only says what kind of thing it is is generic`() {
        assertEquals(true, ShortName.isGeneric("Crackers", "Pepperidge Farm"))
        assertEquals(true, ShortName.isGeneric("Pepperidge Farm Crackers", "Pepperidge Farm"))
        assertEquals(false, ShortName.isGeneric("Goldfish Cheddar Crackers", "Pepperidge Farm"))
        assertEquals(false, ShortName.isGeneric("Real Bacon Pieces", "Great Value"))
    }
}
