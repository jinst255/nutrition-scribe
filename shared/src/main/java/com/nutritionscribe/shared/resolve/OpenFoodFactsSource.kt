package com.nutritionscribe.shared.resolve

import com.nutritionscribe.shared.model.Metric
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * A barcode the label pack does not have, looked up in Open Food Facts.
 *
 * The pack is USDA's Branded Foods, which is what manufacturers submit, and most
 * store brands barely do: Kirkland Signature has 56 products in it at all, and
 * of Food Club's 909 (what Broulim's sells) only 60 are recent enough to ship.
 * Open Food Facts is labels that shoppers photographed, store brands included.
 * Only the barcode is sent, and nothing is sent unless the pack missed.
 */
class OpenFoodFactsSource(private val get: (String) -> Pair<Int, String> = ::httpGet) {

    /** The product, or null when it is not there, has no usable label, or the lookup failed. */
    fun byBarcode(code: String): FoodCandidate? {
        val digits = code.filter { it.isDigit() }.takeIf { it.length in 8..14 } ?: return null
        val (status, body) = runCatching { get("$BASE/$digits.json?fields=$FIELDS") }.getOrNull() ?: return null
        if (status != 200) return null
        return parse(digits, body)
    }

    companion object {
        private const val BASE = "https://world.openfoodfacts.org/api/v2/product"
        private const val FIELDS = "code,product_name,product_name_en,brands,serving_size,serving_quantity,nutriments"

        /** Open Food Facts keeps every amount per 100 g in grams; these are the app's units. */
        private val NUTRIENTS: List<Triple<String, Metric, Double>> = listOf(
            Triple("proteins_100g", Metric.PROTEIN, 1.0),
            Triple("carbohydrates_100g", Metric.CARBS, 1.0),
            Triple("fat_100g", Metric.FAT, 1.0),
            Triple("fiber_100g", Metric.FIBER, 1.0),
            Triple("sugars_100g", Metric.SUGAR, 1.0),
            Triple("saturated-fat_100g", Metric.SATURATED_FAT, 1.0),
            Triple("sodium_100g", Metric.SODIUM, 1000.0),
            Triple("caffeine_100g", Metric.CAFFEINE, 1000.0),
            Triple("cholesterol_100g", Metric.CHOLESTEROL, 1000.0),
            Triple("calcium_100g", Metric.CALCIUM, 1000.0),
            Triple("iron_100g", Metric.IRON, 1000.0),
            Triple("potassium_100g", Metric.POTASSIUM, 1000.0),
            Triple("magnesium_100g", Metric.MAGNESIUM, 1000.0),
            Triple("zinc_100g", Metric.ZINC, 1000.0),
            Triple("selenium_100g", Metric.SELENIUM, 1_000_000.0),
            Triple("vitamin-a_100g", Metric.VITAMIN_A, 1_000_000.0),
            Triple("vitamin-c_100g", Metric.VITAMIN_C, 1000.0),
            Triple("vitamin-d_100g", Metric.VITAMIN_D, 1_000_000.0),
            Triple("vitamin-e_100g", Metric.VITAMIN_E, 1000.0),
            Triple("vitamin-k_100g", Metric.VITAMIN_K, 1_000_000.0),
            Triple("vitamin-b12_100g", Metric.VITAMIN_B12, 1_000_000.0),
            Triple("vitamin-b9_100g", Metric.FOLATE, 1_000_000.0),
        )

        fun parse(code: String, body: String): FoodCandidate? {
            val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
            if (root["status"]?.jsonPrimitive?.intOrNull != 1) return null
            val p = root["product"] as? JsonObject ?: return null
            fun text(k: String) = p[k]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val name = text("product_name_en") ?: text("product_name") ?: return null
            val n = p["nutriments"] as? JsonObject ?: return null
            fun num(k: String) = n[k]?.jsonPrimitive?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

            val per100 = LinkedHashMap<String, Double>()
            val kcal = num("energy-kcal_100g") ?: num("energy_100g")?.let { it / 4.184 }
            kcal?.let { per100[Metric.CALORIES.key] = it }
            for ((k, metric, factor) in NUTRIENTS) num(k)?.let { per100[metric.key] = it * factor }
            if (Metric.SODIUM.key !in per100) num("salt_100g")?.let { per100[Metric.SODIUM.key] = it / 2.5 * 1000.0 }
            // A product page with no label on it is no help: it would log as zeros.
            if (Metric.CALORIES.key !in per100 || Metric.PROTEIN.key !in per100) return null

            val servingText = text("serving_size")
            val servingGrams = p["serving_quantity"]?.jsonPrimitive?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
                ?.takeIf { it > 0 && it < 2000 }
            val brand = text("brands")?.split(',')?.firstOrNull()?.trim()
            return FoodCandidate(
                id = "off:$code",
                name = name,
                origin = Origin.LABEL,
                per100g = per100,
                portions = servingGrams?.let { listOf(Portion("1 serving", it)) }.orEmpty(),
                defaultGrams = servingGrams,
                labelServingGrams = servingGrams,
                labelServingText = servingText,
                brand = brand,
                barcode = code,
            )
        }

        private fun httpGet(url: String): Pair<Int, String> {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 6000
            // Open Food Facts asks every app to name itself.
            c.setRequestProperty("User-Agent", "NutritionScribe/1.0 (Android)")
            return try {
                val code = c.responseCode
                code to (if (code == 200) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            } finally {
                c.disconnect()
            }
        }
    }
}
