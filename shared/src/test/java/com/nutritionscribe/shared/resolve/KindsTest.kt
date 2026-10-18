package com.nutritionscribe.shared.resolve

import com.nutritionscribe.shared.model.NutrientSanity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class KindsTest {
    private fun label(name: String, brand: String, kcal: Double = 140.0, grams: Double = 150.0) = FoodCandidate(
        id = "label:${name.hashCode()}", name = name, brand = brand, origin = Origin.LABEL,
        per100g = mapOf("calories" to kcal * 100 / grams), labelServingGrams = grams,
    )

    @Test fun `a label pack name says its kind after the comma`() {
        val v = Kinds.of(label("Vanilla Blended Non-Fat Greek Yogurt, Vanilla", "Chobani"))
        val s = Kinds.of(label("Strawberry Blended Non-Fat Greek Yogurt, Strawberry", "Chobani"))
        assertEquals("Vanilla", v.kind)
        assertEquals("Strawberry", s.kind)
        assertEquals(v.base, s.base)
        assertEquals("Madagascar Vanilla & Cinnamon", Kinds.label(label("Madagascar Vanilla & Cinnamon Less Sugar Low-Fat Greek Yogurt, Madagascar Vanilla & Cinnamon", "Chobani")))
        assertEquals("Original", Kinds.label(label("Original Multigrain Cereal, Original", "Quaker")))
    }

    @Test fun `kinds are the same product in another flavour, not another product`() {
        val original = label("Original Multigrain Cereal, Original", "Quaker")
        assertTrue(Kinds.sameProduct(original, label("Cinnamon Multigrain Cereal, Cinnamon", "Quaker")))
        // On the bottom is another product line; another brand is another product.
        assertFalse(Kinds.sameProduct(label("Vanilla Blended Non-Fat Greek Yogurt, Vanilla", "Chobani"),
            label("Strawberry On The Bottom Non-Fat Greek Yogurt, Strawberry", "Chobani")))
        assertFalse(Kinds.sameProduct(original, label("Cinnamon Multigrain Cereal, Cinnamon", "Best Choice")))
        // The same kind twice is not two kinds.
        assertFalse(Kinds.sameProduct(original, label("Original Multigrain Cereal, Original", "Quaker", grams = 32.0)))
    }

    @Test fun `a name typed for a scan finds its flavour from the words`() {
        val a = Kinds.split("Store brand Greek yogurt vanilla")
        val b = Kinds.split("Store brand Greek yogurt mixed berry")
        assertEquals("Vanilla", a.kind)
        assertEquals("Mixed Berry", b.kind)
        assertEquals(a.base, b.base)
        assertEquals("Original", Kinds.split("Store brand Greek yogurt").kind)
    }

    @Test fun `the product is named without its kind`() {
        assertEquals("Chobani Non-Fat Greek Yogurt", Kinds.productName(label("Strawberry Blended Non-Fat Greek Yogurt, Strawberry", "Chobani")))
        assertEquals("Quaker Multigrain Cereal", Kinds.productName(label("Maple & Brown Sugar Flavored Multigrain Cereal, Maple & Brown Sugar", "Quaker")))
    }

    @Test fun `a new kind is named the way the pack names its siblings`() {
        val original = label("Original Multigrain Cereal, Original", "Quaker")
        val name = Kinds.nameFor(original, "Pumpkin Spice")
        assertEquals("Pumpkin Spice Multigrain Cereal", name)
        assertTrue(Kinds.sameProduct(original, label(name, "Quaker")))
    }

    @Test fun `kinds within a few calories are not worth asking about`() {
        val vanilla = mapOf("calories" to 140.0, "protein" to 20.0)
        val berry = mapOf("calories" to 150.0, "protein" to 20.0)
        val honey = mapOf("calories" to 150.0, "protein" to 15.0)
        val plain = mapOf("calories" to 90.0, "protein" to 15.0)
        assertFalse(Kinds.differ(listOf(vanilla, berry)))
        assertTrue(Kinds.differ(listOf(plain, honey)))
        assertEquals(60.0, Kinds.spread(listOf(plain, honey)), 0.01)
    }

    @Test fun `switching kinds keeps the old one beside it`() {
        val store = InMemoryPersonalStore()
        val r = Resolver(store, null, null, null)
        val vanilla = label("Vanilla Blended Non-Fat Greek Yogurt, Vanilla", "Chobani")
        val berry = label("Strawberry Blended Non-Fat Greek Yogurt, Strawberry", "Chobani", kcal = 150.0)
        val item = ItemParser.Item(said = "chobani yogurt", food = "chobani yogurt")
        r.learn(item, vanilla.name, vanilla, 150.0, mapOf("calories" to 140.0))
        val after = r.learn(item, berry.name, berry, 150.0, mapOf("calories" to 150.0))
        assertEquals(berry.id, after.candidate?.id)
        assertEquals(listOf(vanilla.id), after.kinds.map { it.id })
        assertEquals(mapOf("Vanilla" to 1, "Strawberry" to 1), after.kindUses)
        // Another food under the same words is not kept as a kind.
        val other = label("Plain Whole Milk Greek Yogurt, Plain", "Fage")
        assertTrue(r.learn(item, other.name, other, 150.0, mapOf("calories" to 130.0)).kinds.isEmpty())
    }

    @Test fun `a misread box gets the value one slip away`() {
        // 80 g of sugar in 33 g of carbs: the 0 was a stray mark.
        val n = mapOf("calories" to 160.0, "protein" to 4.0, "carbs" to 33.0, "fat" to 5.0, "sugar" to 80.0)
        assertEquals(mapOf("sugar" to 8.0), NutrientSanity.suggest(n, 35.0))
        // Nothing wrong, nothing offered.
        assertTrue(NutrientSanity.suggest(n + ("sugar" to 8.0), 35.0).isEmpty())
    }

    @Test fun `numbers that add up but differ from the same product's label are pointed out`() {
        val read = mapOf("calories" to 160.0, "protein" to 3.0, "sugar" to 13.0, "sodium" to 160.0)
        val label = mapOf("calories" to 160.0, "protein" to 4.0, "sugar" to 8.0, "sodium" to 160.0)
        assertEquals(mapOf("sugar" to 8.0), NutrientSanity.against(read, label))
    }

    @Test fun `the label pack lists a product's other kinds`() {
        val labels = TestDbs.labels
        assumeTrue("needs -DlabelPack", labels != null)
        val life = labels!!.search("quaker original multigrain cereal", brand = "quaker", limit = 1).single()
        val kinds = labels.siblings(life).map { Kinds.label(it) }
        assertTrue(kinds.toString(), "Cinnamon" in kinds && "Pumpkin Spice" in kinds && "Vanilla" in kinds)
        assertFalse(kinds.toString(), "Original" in kinds)
        val vanilla = labels.search("chobani vanilla blended non fat greek yogurt", brand = "chobani", limit = 1).single()
        val yogurts = labels.siblings(vanilla).map { Kinds.label(it) }
        assertTrue(yogurts.toString(), "Strawberry" in yogurts && "Blueberry" in yogurts)
    }
}
