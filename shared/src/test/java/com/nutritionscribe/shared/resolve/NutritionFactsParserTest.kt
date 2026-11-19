package com.nutritionscribe.shared.resolve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionFactsParserTest {

    @Test
    fun `reads a standard panel as OCR returns it`() {
        val ocr = """
            Nutrition Facts
            8 servings per container
            Serving size 2/3 cup (55g)
            Amount per serving
            Calories 230
            % Daily Value*
            Total Fat 8g 10%
            Saturated Fat 1g 5%
            Trans Fat 0g
            Cholesterol 0mg 0%
            Sodium 160mg 7%
            Total Carbohydrate 37g 13%
            Dietary Fiber 4g 14%
            Total Sugars 12g
            Includes 10g Added Sugars 20%
            Protein 3g
        """.trimIndent()
        val p = NutritionFactsParser.parse(ocr)
        assertEquals(55.0, p.servingGrams!!, 0.0)
        assertEquals(230.0, p.perServing["calories"]!!, 0.0)
        assertEquals(8.0, p.perServing["fat"]!!, 0.0)
        assertEquals(1.0, p.perServing["saturated_fat"]!!, 0.0)
        assertEquals(160.0, p.perServing["sodium"]!!, 0.0)
        assertEquals(37.0, p.perServing["carbs"]!!, 0.0)
        assertEquals(4.0, p.perServing["fiber"]!!, 0.0)
        assertEquals(12.0, p.perServing["sugar"]!!, 0.0)
        assertEquals(3.0, p.perServing["protein"]!!, 0.0)
        assertTrue(p.looksComplete)
    }

    @Test
    fun `copes with values on the next line and run-together text`() {
        val ocr = "Calories\n190\nProtein7g\nSodium 0.14g\nTotal Fat 16 g"
        val p = NutritionFactsParser.parse(ocr)
        assertEquals(190.0, p.perServing["calories"]!!, 0.0)
        assertEquals(7.0, p.perServing["protein"]!!, 0.0)
        assertEquals(140.0, p.perServing["sodium"]!!, 0.01)
        assertEquals(16.0, p.perServing["fat"]!!, 0.0)
    }

    @Test
    fun `a panel becomes a label candidate that scales by grams`() {
        val p = NutritionFactsParser.parse("Serving size 1 bar (60g)\nCalories 200\nProtein 20g")
        val c = p.asCandidate("Protein bar")
        assertEquals(Origin.LABEL, c.origin)
        assertEquals(400.0, c.nutrientsFor(120.0)["calories"]!!, 0.01)
    }

    @Test
    fun `reads the label micronutrients in their own units`() {
        val ocr = """
            Serving size 1 bottle (414mL)
            Calories 230
            Total Fat 3.5g 4%
            Cholesterol 15mg 5%
            Sodium 250mg 11%
            Protein 42g
            Vitamin D3 2mcg 10%
            Calcium 900mg 70%
            Iron 0.1mg 0%
            Potassium 750mg 15%
        """.trimIndent()
        val p = NutritionFactsParser.parse(ocr)
        assertEquals(15.0, p.perServing["cholesterol"]!!, 0.0)
        assertEquals(2.0, p.perServing["vitamin_d"]!!, 0.0)
        assertEquals(900.0, p.perServing["calcium"]!!, 0.0)
        assertEquals(0.1, p.perServing["iron"]!!, 1e-9)
        assertEquals(750.0, p.perServing["potassium"]!!, 0.0)
        assertEquals(3.5, p.perServing["fat"]!!, 0.0)
    }

    @Test
    fun `an old label's percent-only micronutrient becomes an amount`() {
        val p = NutritionFactsParser.parse("Calories 100\nProtein 5g\nCalcium 20%\nVitamin D 400 IU")
        assertEquals(260.0, p.perServing["calcium"]!!, 0.01)
        assertEquals(10.0, p.perServing["vitamin_d"]!!, 0.01)
    }

    @Test
    fun `a g read as 9 is taken back off, and the log says so`() {
        // A curved peanut butter jar: every "g" came out as "9".
        val ocr = """
            Serving size 2 tbsp (329)
            Calories 190
            Total Fat 169
            Saturated Fat 39
            Sodium 140m9
            Total Carbohydrate 79
            Dietary Fiber 29
            Total Sugars 49
            Protein 79
        """.trimIndent()
        val p = NutritionFactsParser.parse(ocr)
        assertEquals(32.0, p.servingGrams!!, 0.0)
        assertEquals(16.0, p.perServing["fat"]!!, 0.0)
        assertEquals(3.0, p.perServing["saturated_fat"]!!, 0.0)
        assertEquals(140.0, p.perServing["sodium"]!!, 0.0)
        assertEquals(7.0, p.perServing["carbs"]!!, 0.0)
        assertEquals(2.0, p.perServing["fiber"]!!, 0.0)
        assertEquals(4.0, p.perServing["sugar"]!!, 0.0)
        assertEquals(7.0, p.perServing["protein"]!!, 0.0)
        assertTrue(p.issues.isEmpty())
        val sugar = p.reads.first { it.metric == "sugar" }
        assertEquals("g_read_as_9", sugar.fix)
        assertEquals(49.0, sugar.asRead!!, 0.0)
    }

    @Test
    fun `a real 9 at the end stays when the panel only adds up with it`() {
        // The "g" was dropped, not misread: 19 g of carbs, 12 g of sugar.
        val ocr = "Calories 150\nTotal Fat 5g\nTotal Carbohydrate 19\nTotal Sugars 12g\nProtein 6g"
        val p = NutritionFactsParser.parse(ocr)
        assertEquals(19.0, p.perServing["carbs"]!!, 0.0)
        assertEquals(null, p.reads.first { it.metric == "carbs" }.fix)
    }

    @Test
    fun `a misread that does not add up is reported`() {
        val p = NutritionFactsParser.parse("Serving size 1 cup (240g)\nCalories 110\nTotal Fat 0g\nTotal Carbohydrate 7g\nTotal Sugars 45g\nProtein 20g")
        assertEquals(listOf("sugar_over_carbs"), p.issues.map { it.code })
    }

    // From the 2026-09 phone log: rolled oats logged at 3.5 calories (the fat under
    // a "Calories" printed apart from its number), and a bread whose cholesterol
    // came out as its sodium, 135 mg, because "0mg" was read as "Omg".
    @Test
    fun `calories printed apart from the word are found, not the fat below`() {
        val ocr = """
            Nutrition Facts
            Serving size 1/2 cup (40g)
            Amount per serving
            Calories
            Total Fat 3.5g 4%
            Saturated Fat 0.5g 3%
            Cholesterol Omg 0%
            Sodium 0mg 0%
            Total Carbohydrate 27g 10%
            Dietary Fiber 4g 14%
            Total Sugars 1g
            Protein 5g
            150
        """.trimIndent()
        val p = NutritionFactsParser.parse(ocr)
        assertEquals(150.0, p.perServing["calories"]!!, 0.0)
        assertEquals(3.5, p.perServing["fat"]!!, 0.0)
        assertEquals(0.0, p.perServing["cholesterol"]!!, 0.0)
        assertEquals(0.0, p.perServing["sodium"]!!, 0.0)
        assertTrue(p.issues.isEmpty())
    }

    @Test
    fun `a nutrient never takes the next nutrient's number`() {
        val p = NutritionFactsParser.parse("Calories 80\nTotal Fat 1.5g\nCholesterol\nSodium 135mg\nTotal Carbohydrate 15g\nProtein 4g")
        assertEquals(null, p.perServing["cholesterol"])
        assertEquals(135.0, p.perServing["sodium"]!!, 0.0)
        assertEquals(0.0, NutritionFactsParser.parse("Cholesterol Omg\nSodium 135mg").perServing["cholesterol"]!!, 0.0)
    }

    @Test
    fun `a stray number that does not fit the macros is not taken as calories`() {
        val p = NutritionFactsParser.parse("Calories\nTotal Fat 3g\nTotal Carbohydrate 9g\nProtein 20g\n12")
        // Not the 12. With calories unreadable, the macros' own figure stands in,
        // marked as worked out so the form asks for a check.
        assertEquals(140.0, p.perServing["calories"]!!, 0.0)
        assertEquals("kcal_from_macros", p.reads.first { it.metric == "calories" }.fix)
    }

    @Test
    fun `a panel printed as a paragraph gives each nutrient its own number`() {
        val p = NutritionFactsParser.parse(
            "Nutrition Facts Serving: 1, Serv. size: 1 pouch (25g),\n" +
                "Amount per serving: Calories 80, Total Fat 0g (0% DV), Sat. Fat 0g (0% DV),\n" +
                "Trans Fat 0g, Cholest. 0mg (0% DV), Sodium 20mg (1% DV), Total Carb. 19g (7%\n" +
                "DV), Fiber 0g (0% DV), Total Sugars 11g (Incl. 11g Added Sugars, 22% DV), Protein\n" +
                "1g, Vit. D (0% DV), Calcium (0% DV), Iron (0% DV), Potas. (0% DV).",
        )
        assertEquals(25.0, p.servingGrams!!, 0.0)
        assertEquals(80.0, p.perServing["calories"]!!, 0.0)
        assertEquals(20.0, p.perServing["sodium"]!!, 0.0)
        assertEquals(19.0, p.perServing["carbs"]!!, 0.0)
        assertEquals(11.0, p.perServing["sugar"]!!, 0.0)
        assertEquals(11.0, p.perServing["added_sugar"]!!, 0.0)
        assertEquals(1.0, p.perServing["protein"]!!, 0.0)
    }

    @Test
    fun `two columns read as one row keep their own numbers`() {
        val p = NutritionFactsParser.parse("Calories 90\nProtein 4g\nTotal Carbohydrate 16g\nTotal Fat 1g\nIron 1mg 6% Potassium 100mg 2%\nVitamin D 0mcg 0% Calcium 20rmg 2%")
        assertEquals(1.0, p.perServing["iron"]!!, 0.0)
        assertEquals(100.0, p.perServing["potassium"]!!, 0.0)
        assertEquals(20.0, p.perServing["calcium"]!!, 0.0)
    }

    /**
     * Owner, 2026-10-28, on a protein powder's detailed panel: "I think it missed
     * a lot of these values." B vitamins, phosphorus, copper, manganese and choline
     * were on the label and not read, so they were borrowed from the food list.
     */
    @Test
    fun `a detailed panel's B vitamins and minerals are read`() {
        val p = NutritionFactsParser.parse(
            "Calories 130\nProtein 25g\nTotal Carbohydrate 3g\nTotal Fat 2g\nThiamin 0.6mg 50%\nRiboflavin 0.7mg 50%\n" +
                "Niacin 8mg 50%\nVitamin B6 0.9mg 50%\nVitamin B12 1.2mcg 50%\nPhosphorus 250mg 20%\nCopper 0.3mg 35%\n" +
                "Manganese 1.1mg 50%\nCholine 55mg 10%",
        )
        assertEquals(0.6, p.perServing["thiamin"]!!, 1e-9)
        assertEquals(0.7, p.perServing["riboflavin"]!!, 1e-9)
        assertEquals(8.0, p.perServing["niacin"]!!, 1e-9)
        assertEquals(0.9, p.perServing["vitamin_b6"]!!, 1e-9)
        assertEquals(1.2, p.perServing["vitamin_b12"]!!, 1e-9)
        assertEquals(250.0, p.perServing["phosphorus"]!!, 1e-9)
        assertEquals(0.3, p.perServing["copper"]!!, 1e-9)
        assertEquals(1.1, p.perServing["manganese"]!!, 1e-9)
        assertEquals(55.0, p.perServing["choline"]!!, 1e-9)
    }

    /**
     * A tester's corn tortillas: the panel says Protein 1g, and the reader took the
     * footer's "Calories per gram: Fat 9 • Carbohydrates 4 • Protein 4", so six
     * tortillas logged 24 g of protein and the app flagged its own numbers. The
     * footnote (and its 2,000 / 2,500 calorie table) is never the food.
     */
    @Test
    fun `the footnote under the panel is not read as the food`() {
        val p = NutritionFactsParser.parse(
            "Nutrition Facts\nServing Size: 1 Tortilla (28.35 g)\nCalories 50 Calories From Fat 5\nTotal Fat 1g 0%\nSaturated Fat 0g\n" +
                "Trans Fat 0g\nCholesterol 0mg\nSodium 10mg 1%\nTotal Carbohydrates 13g 4%\nDietary Fiber 1g\nSugars 0g\nProtein/Proteina 1g\n" +
                "Calcium 4% Iron 2%\nPercent Daily Values are based on a 2,000 calorie diet.\nCalories 2,000 2,500\n" +
                "Total Fat Less than 65g 80g\nSodium Less than 2,400mg 2,400mg\nTotal Carbohydrate 300g 375g\nDietary Fiber 25g 30g\n" +
                "Calories per gram: Fat 9 Carbohydrates 4 Protein 4",
        )
        assertEquals(1.0, p.perServing["protein"]!!, 0.0)
        assertEquals(50.0, p.perServing["calories"]!!, 0.0)
        assertEquals(10.0, p.perServing["sodium"]!!, 0.0)
        assertEquals(13.0, p.perServing["carbs"]!!, 0.0)
        assertEquals(1.0, p.perServing["fiber"]!!, 0.0)
        assertEquals(1.0, p.perServing["fat"]!!, 0.0)
    }

    /** The same tortilla panel exactly as the phone's text recogniser read it ("Proteln/Proteina 19", "Percenl Daly Values"). */
    @Test
    fun `the tortilla panel as the phone read it gives 1 g of protein, not the footer's 4`() {
        val p = NutritionFactsParser.parse("23/202%\nNutrition Facts\nServ\nSeg Size: 1 Tortilla (28.35 g)\nVings Per Container: 100\nAmount Per Serving\nCalories 50 Calories From Fat 5\nTotal Fat 1g\nSaturated Fat Og\nTrans Fat Og\nCholesterol Omg\nSodlum 10mg\nTotal Carbohydrates 13g\nDietary Fiber 19\nSugars 0g\nProteln/Proteina 19\nVtamin A 0%\nCalcumlCaldo\n4%\nIngre\nPercenl Daly Values are based ona 2.000 calorie diet\nYour daly vaes may be higher or lower depending on your\nColoriles\nAmenos que\nmenos que\nnenos Que\nnenos QUe\nVitamin C 0%\nIron/Hierro 2%\n2, 000 2, 500\n650 80g\n200 259\n300mg 300mg\n2.400mg 2.400ng\n25\n% Daily Value\nProtein 4\n(Preservata\nPERISHABLE\nEEP REFRIGERATED\nER PIRCHAS\nLime\n0%\n0%\n0%\n1%\n4%\n5%")
        assertEquals(1.0, p.perServing["protein"]!!, 0.0)
        assertEquals(50.0, p.perServing["calories"]!!, 0.0)
        assertEquals(13.0, p.perServing["carbs"]!!, 0.0)
    }

    @Test
    fun `a weight on the front of the box is not calories`() {
        val p = NutritionFactsParser.parse("CALORIES\nNET WT 32.5 OZ (2 LB) 922 G")
        assertEquals(null, p.perServing["calories"])
    }

    @Test
    fun `calories misread as zero come from the macros`() {
        val p = NutritionFactsParser.parse("Calories /0\nTotal Fat 0g\nTotal Carbohydrate 17g\nProtein 1g")
        assertEquals(70.0, p.perServing["calories"]!!, 0.0)
    }
}
