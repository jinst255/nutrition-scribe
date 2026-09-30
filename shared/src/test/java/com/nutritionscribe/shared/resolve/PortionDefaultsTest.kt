package com.nutritionscribe.shared.resolve

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * For the portion-photo benchmark (tools/bench_portion_photo.py): what the app
 * would log for each Nutrition5k ingredient with NO photo — one ordinary serving.
 * That is the bar a photo has to beat to be worth the user's trouble.
 */
class PortionDefaultsTest {
    @Test
    fun writeDefaults() {
        val sample = File(System.getProperty("user.home"), ".cache/ns-vision/n5k/sample.json")
        assumeTrue("no Nutrition5k sample", sample.exists())
        val names = Json.parseToJsonElement(sample.readText()).jsonArray
            .flatMap { d -> d.jsonObject["main"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content } }
            .distinct()
        val r = Resolver(InMemoryPersonalStore(), TestDbs.usda)
        val out = buildJsonObject {
            for (n in names) {
                val g = r.resolve(n).items.firstOrNull()?.grams ?: continue
                put(n, JsonPrimitive(g))
            }
        }
        File(System.getProperty("aiCacheDir"), "portion_defaults.json").writeText(out.toString())
    }
}
