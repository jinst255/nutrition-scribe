package com.nutritionscribe.shared.stores

/**
 * Store packs read as part of the label pack.
 *
 * A store pack (tools/build_store_pack.py) has the label pack's tables. The app
 * ATTACHes each one to the label pack's connection as `store<slot>` and runs
 * [views]: TEMP views named like the pack's own tables, which SQLite looks up
 * before main's. So LabelPackSource, FrontMatcher and every barcode, search
 * and byId call read the built-in labels and every store pack as one pack,
 * with no change to their code.
 *
 * Product ids in a store pack start at slot × [SLOT_SIZE], so they never collide
 * with USDA ids (under 10 million) or another store's, and a food logged from a
 * store pack still finds its product after other packs come and go.
 */
object StorePackViews {
    const val SLOT_SIZE = 1_000_000_000L

    /** The product columns every pack has, in the order the views list them. */
    val BASE_COLUMNS = listOf(
        "id", "brand", "description", "serving_g", "serving_text", "count", "count_unit", "measure", "measure_unit",
        "kcal", "protein_g", "carbs_g", "fat_g", "fiber_g", "sugar_g", "sat_fat_g", "sodium_mg", "caffeine_mg",
        "pop", "owner",
    )

    /** Format 4's label micronutrients. Store packs always have them. */
    val MICRO_COLUMNS = listOf("calcium_mg", "iron_mg", "potassium_mg", "vitamin_d_ug", "cholesterol_mg")

    private val TABLES = listOf("product", "gtin", "pword", "brand_word")

    fun schemaName(slot: Int): String {
        require(slot in 1..999) { "store slot out of range: $slot" }
        return "store$slot"
    }

    /** Which store a product id came from, or null for the built-in pack. */
    fun slotOf(productId: Long): Int? = (productId / SLOT_SIZE).toInt().takeIf { it > 0 }

    /** SQL that removes the views, so main's own tables show again. */
    fun dropViews(): List<String> = TABLES.map { "DROP VIEW IF EXISTS temp.$it" }

    /**
     * The views over main plus the attached stores. [mainHasMicros]: main is a
     * format 4 pack; when it isn't, the micronutrient columns are left out of the
     * product view, because the source reading it won't ask for them either.
     */
    fun views(mainHasMicros: Boolean, slots: List<Int>, includeMain: Boolean = true): List<String> {
        if (slots.isEmpty()) return dropViews()
        // No built-in pack: main is an empty in-memory database, and only stores are read.
        val schemas = (if (includeMain) listOf("main") else emptyList()) + slots.distinct().sorted().map(::schemaName)
        val productCols = (BASE_COLUMNS + if (mainHasMicros) MICRO_COLUMNS else emptyList()).joinToString(", ")
        fun union(table: String, cols: String, all: Boolean = true) =
            schemas.joinToString(if (all) " UNION ALL " else " UNION ") { "SELECT $cols FROM $it.$table" }
        return dropViews() + listOf(
            "CREATE TEMP VIEW product AS ${union("product", productCols)}",
            "CREATE TEMP VIEW gtin AS ${union("gtin", "gtin, id")}",
            "CREATE TEMP VIEW pword AS ${union("pword", "word, id, in_brand")}",
            "CREATE TEMP VIEW brand_word AS ${union("brand_word", "word", all = false)}",
        )
    }

    /**
     * Whether a pack's own meta says it is the store pack the catalogue promised.
     * A wrong or damaged download must never be attached: its ids could land in
     * another store's range and point logged foods at the wrong product.
     */
    fun metaProblem(meta: Map<String, String>, expectedId: String, expectedSlot: Int): String? = when {
        meta["kind"] != "store" -> "not a store pack"
        meta["store"] != expectedId -> "it is the ${meta["store"]} pack, not $expectedId"
        meta["slot"]?.toIntOrNull() != expectedSlot -> "slot ${meta["slot"]} instead of $expectedSlot"
        meta["format"] != "4" -> "format ${meta["format"]} isn't one this app reads"
        (meta["products"]?.toIntOrNull() ?: 0) <= 0 -> "it has no products"
        else -> null
    }
}
