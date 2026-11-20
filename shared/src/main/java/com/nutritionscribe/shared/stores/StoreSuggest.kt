package com.nutritionscribe.shared.stores

import kotlin.math.roundToInt

/**
 * "You eat a lot of Great Value — download its pack?"
 *
 * Owner, 11-19: "if we detect that they scan a high proportion or percentage of
 * items from a specific place and there is data available, we just have a little
 * recommendation pop up and say, hey, we've noticed you use a lot of this food.
 * Maybe download the database. It's projected that you'll save this many cents."
 *
 * Looks at the foods logged in the last 30 days. A store is suggested when at least
 * [MIN_ITEMS] of them carry its brand and they are at least [MIN_SHARE] of all the
 * packaged foods, its pack isn't installed, and it wasn't waved off recently.
 *
 * What it claims is only what a pack can deliver. A pack is built from Open Food
 * Facts, which the app already asks online after a barcode miss, so a pack never
 * saves a label read while online (checked 11-19 against the owner's 26 misses:
 * none were in Open Food Facts). What it does change: foods said or typed by name
 * come from a real label instead of an AI guess ("ai:") or a web search ("web:",
 * about 2.3¢ each), and barcodes work offline. So the card counts those guesses
 * and the web-search cents, per month, as an "up to".
 */
object StoreSuggest {
    const val MIN_ITEMS = 5
    const val MIN_SHARE = 0.2
    const val WINDOW_DAYS = 30
    const val SNOOZE_DAYS = 30
    /** Median cost of one web-search lookup in the owner's log (11-19). */
    const val WEB_LOOKUP_CENTS = 2.3
    private const val DAY = 86_400_000L

    /** One food from a logged entry. */
    data class Food(val name: String, val candidateId: String?, val origin: String, val at: Long)

    data class Suggestion(
        val store: Store,
        /** Foods of this store's brand in the window. */
        val storeFoods: Int,
        /** All packaged (branded or label) foods in the window. */
        val packagedFoods: Int,
        /** This store's foods a month whose numbers were an AI guess or a web search, not a label. */
        val guessesPerMonth: Int,
        val centsPerMonth: Int,
    ) {
        /** "~4", or null when there were none. */
        val guessesText: String? get() = if (guessesPerMonth >= 1) "~$guessesPerMonth" else null
        val centsText: String? get() = if (centsPerMonth >= 1) "~$centsPerMonth¢" else null
    }

    fun isPackaged(f: Food): Boolean =
        f.origin == "LABEL" || f.name.contains(" — ") || f.candidateId?.startsWith("web:") == true

    fun suggest(
        foods: List<Food>,
        now: Long,
        installed: Set<String>,
        /** Store id → time until which it stays quiet. */
        snoozedUntil: Map<String, Long>,
        stores: List<Store> = StoreCatalog.BUILT_IN,
    ): Suggestion? {
        val recent = foods.filter { it.at in (now - WINDOW_DAYS * DAY)..now }
        val packaged = recent.filter(::isPackaged)
        if (packaged.size < MIN_ITEMS) return null
        val byStore = packaged.groupBy { f -> StoreCatalog.storeOf(null, f.name)?.id }
        val best = byStore.entries
            .filter { (id, list) -> id != null && list.size >= MIN_ITEMS && list.size >= MIN_SHARE * packaged.size }
            .mapNotNull { (id, list) -> stores.firstOrNull { it.id == id }?.let { it to list } }
            .filter { (s, _) -> s.id !in installed && (snoozedUntil[s.id] ?: 0L) <= now }
            .maxByOrNull { it.second.size } ?: return null
        val (store, list) = best
        // Per product, not per meal: once confirmed, a food is remembered and costs nothing again.
        fun distinct(prefix: String) = list.filter { it.candidateId?.startsWith(prefix) == true }.distinctBy { it.name.lowercase() }.size
        val webs = distinct("web:")
        val guesses = distinct("ai:") + webs
        // A log younger than the window is scaled up to a month, but never from under a week.
        val first = recent.minOf { it.at }
        val days = ((now - first) / DAY.toDouble()).coerceIn(7.0, WINDOW_DAYS.toDouble())
        val scale = WINDOW_DAYS / days
        return Suggestion(
            store = store,
            storeFoods = list.size,
            packagedFoods = packaged.size,
            guessesPerMonth = (guesses * scale).roundToInt(),
            centsPerMonth = (webs * WEB_LOOKUP_CENTS * scale).roundToInt(),
        )
    }

    /** "great-value:1795000000000,kirkland:0" ⇄ map, for one settings string. */
    fun parseSnoozes(text: String?): Map<String, Long> = text.orEmpty().split(',').mapNotNull { part ->
        val id = part.substringBefore(':', "").trim()
        val until = part.substringAfter(':', "").trim().toLongOrNull()
        if (id.isEmpty() || until == null) null else id to until
    }.toMap()

    fun formatSnoozes(map: Map<String, Long>): String = map.entries.joinToString(",") { "${it.key}:${it.value}" }
}
