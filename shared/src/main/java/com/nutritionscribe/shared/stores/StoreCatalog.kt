package com.nutritionscribe.shared.stores

/**
 * The stores a pack can be downloaded for. Mirrors tools/store_packs.json
 * (StoreCatalogParityTest checks they agree): the app needs the list offline, to
 * notice that someone buys a lot of one store's brand before it has ever
 * fetched the online catalogue.
 *
 * [approxProducts] is what Open Food Facts listed for US products in Nov 2026; the
 * online catalogue (stores.json on the store-packs release) has the exact counts,
 * sizes and checksums.
 */
data class Store(
    val id: String,
    val title: String,
    val shop: String,
    /** Permanent: product ids are slot × 1e9 + n. Never reuse or renumber. */
    val slot: Int,
    /** How the brand appears in a food's name, lower case. */
    val aliases: List<String>,
    val approxProducts: Int,
)

object StoreCatalog {
    val BUILT_IN = listOf(
        Store("great-value", "Great Value", "Walmart", 1, listOf("great value"), 1300),
        Store("kirkland", "Kirkland Signature", "Costco", 2, listOf("kirkland signature", "kirkland"), 1000),
        Store("members-mark", "Member's Mark", "Sam's Club", 3, listOf("member's mark", "members mark"), 300),
        Store("trader-joes", "Trader Joe's", "Trader Joe's", 4, listOf("trader joe's", "trader joes"), 5700),
        Store("kroger", "Kroger and Simple Truth", "Kroger", 5, listOf("kroger", "simple truth", "private selection"), 1500),
        Store("publix", "Publix", "Publix", 6, listOf("publix"), 1100),
        Store("food-club", "Food Club", "independent grocers", 7, listOf("food club"), 400),
        Store("365", "365 by Whole Foods Market", "Whole Foods", 8,
            listOf("365 whole foods market", "365 everyday value", "whole foods market", "365"), 400),
        Store("good-gather", "Good & Gather", "Target", 9, listOf("good & gather", "good and gather", "good gather", "market pantry"), 600),
        Store("western-family", "Western Family", "independent grocers", 10, listOf("western family"), 60),
        Store("heb", "H-E-B", "H-E-B", 11, listOf("h-e-b", "heb", "hill country fare"), 500),
        Store("meijer", "Meijer", "Meijer", 12, listOf("meijer"), 400),
    )

    fun byId(id: String): Store? = BUILT_IN.firstOrNull { it.id == id }

    /** The store pack a food came from, by its candidate id ("label:2000123456"), or null. */
    fun forCandidate(candidateId: String?): Store? {
        val n = candidateId?.removePrefix("label:")?.takeIf { it != candidateId }?.toLongOrNull() ?: return null
        val slot = StorePackViews.slotOf(n) ?: return null
        return BUILT_IN.firstOrNull { it.slot == slot }
    }

    /** Where the packs and their catalogue live: assets of the GitHub release "store-packs". */
    const val RELEASE_BASE = "https://github.com/jinst255/nutrition-scribe/releases/download/store-packs/"
    const val CATALOG_FILE = "stores.json"

    private fun norm(s: String) = s.lowercase().replace('’', '\'').replace(Regex("[^a-z0-9&' -]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    /**
     * The store whose brand a food carries, from its brand field or the
     * "Brand — Product" name the app gives packaged foods. Whole words only: the
     * alias "365" must not match "365 kcal".
     */
    fun storeOf(brand: String?, name: String?): Store? {
        val fromName = name?.substringBefore(" — ", "")?.takeIf { it.isNotBlank() }
        for (raw in listOfNotNull(brand, fromName)) {
            val b = norm(raw)
            BUILT_IN.firstOrNull { s -> s.aliases.any { a -> b == a || b.startsWith("$a ") } }?.let { return it }
        }
        return null
    }
}
