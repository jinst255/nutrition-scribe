package com.nutritionscribe.shared.resolve

import com.nutritionscribe.shared.food.FoodQuery
import com.nutritionscribe.shared.model.Metric

/**
 * The lookup, in four layers, tried in order:
 *
 *   1. The user's own confirmed foods ([PersonalStore]). A hit logs silently.
 *   2. The bundled database — USDA plus the optional label pack.
 *   3. Assists, offered when 1–2 are weak: a barcode/label scan for packaged food,
 *      breaking a homemade dish into ingredients.
 *   4. The opt-in AI lookup ([AiItemLookup]), only when the user set a key.
 *
 * Confidence is STRUCTURAL, not a guess about the match: anything not already in
 * layer 1 is shown to the user before it is logged, and anything in layer 1 is
 * not, because they already looked at it. On a real week of entries the old
 * heuristic flagged one of 27 wrong answers; this cannot miss one, because it does
 * not try to tell which first-time answers are wrong — it shows them all, once.
 *
 * Blocking by design (SQLite and OkHttp both are); the app calls it off the main
 * thread.
 */
class Resolver(
    private val personal: PersonalStore,
    private val usda: UsdaFoodSource?,
    private val labels: LabelPackSource? = null,
    private val ai: AiItemLookup? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Which first-time cards the AI fills. See [AiMode]. */
    private val aiMode: AiMode = AiMode.SENTENCE,
) {

    /**
     * Measured on the real week: the curated and label answers beat the AI's own
     * estimate where they exist (15% of first-time cards wrong vs 26%), and the AI
     * beats them where they are weak (brands, restaurants, rambling sentences).
     */
    enum class AiMode {
        /** The AI fills every first-time card. */
        ALL_NEW,
        /** Only cards the local lookup is unsure of. */
        WEAK_ONLY,
        /** Every card, except where a person's curated answer or a real label exists. */
        TRUSTED_LOCAL,
        /**
         * The AI reads the whole sentence itself, and its items are matched back to
         * the user's own words so they are remembered under them.
         */
        SENTENCE,
    }

    enum class Status {
        /** Layer 1: confirmed before. Logs without asking. */
        REMEMBERED,
        /** First time: shown on the confirm card with the best answer filled in. */
        NEW,
        /** Nothing credible anywhere; the card asks what it was. */
        UNKNOWN,
    }

    data class ResolvedItem(
        val item: ItemParser.Item,
        val status: Status,
        val choice: FoodCandidate?,
        val grams: Double?,
        val portionNote: String?,
        val nutrients: Map<String, Double>,
        val alternates: List<FoodCandidate> = emptyList(),
        /** Weak match: the card says "best guess" and offers the assists. */
        val weak: Boolean = false,
        /** Looks packaged: offer a barcode / label scan. */
        val offerScan: Boolean = false,
        /** Looks homemade: offer breaking it into ingredients. */
        val offerBreakdown: Boolean = false,
        /** What will be written to the personal cache when this is confirmed. */
        val key: String,
        /** The remembered food a NEW item was near to, offered first on its card. */
        val nearMiss: PersonalFood? = null,
    ) {
        val displayName: String
            get() = choice?.let { c ->
                val b = c.brand?.takeIf { it.isNotBlank() && !c.name.contains(it, ignoreCase = true) }
                if (b != null) "$b \u2014 ${c.name}" else c.name
            } ?: item.food
    }

    data class Resolution(
        val text: String,
        val items: List<ResolvedItem>,
        /** The whole sentence matched a saved meal or food ("my usual veggies"). */
        val meal: PersonalFood? = null,
        /** Set when the AI lookup was wanted but could not be made; the card says so. */
        val aiProblem: String? = null,
        val usedAi: Boolean = false,
    ) {
        /** Every part is remembered, so the entry can log with no card at all. */
        val silent: Boolean get() = meal != null || (items.isNotEmpty() && items.all { it.status == Status.REMEMBERED })
        val needsConfirm: List<ResolvedItem> get() = if (meal != null) emptyList() else items.filter { it.status != Status.REMEMBERED }
        val total: Map<String, Double>
            get() = meal?.nutrients ?: sumNutrients(items.map { it.nutrients })
    }

    // ------------------------------------------------------------------

    fun resolve(text: String): Resolution {
        if (text.isBlank()) return Resolution(text, emptyList())

        // A saved meal, or the same sentence confirmed before as one food.
        val textKey = PersonalKeys.ofText(text)
        personal.get(textKey)?.takeIf { it.isMeal }?.let { return Resolution(text, emptyList(), meal = it) }

        val items = ItemParser.parse(text, ::isFoodWord) { w -> labels?.isBrandWord(w) == true }
        if (items.isEmpty()) return Resolution(text, listOf(unknown(ItemParser.Item(said = text, food = text), PersonalKeys.ofText(text))))

        val resolved = items.map { resolveLocal(it) }.toMutableList()

        // Layer 4 fills the cards the user will see anyway — never a remembered item.
        val fresh = resolved.withIndex().filter {
            val r = it.value
            r.status != Status.REMEMBERED && when (aiMode) {
                AiMode.ALL_NEW, AiMode.SENTENCE -> true
                AiMode.WEAK_ONLY -> r.weak || r.status == Status.UNKNOWN
                AiMode.TRUSTED_LOCAL -> r.weak || r.status == Status.UNKNOWN ||
                    (r.choice?.origin != Origin.CURATED && r.choice?.origin != Origin.LABEL && !isCuratedUsda(r))
            }
        }
        val lookup = ai
        if (lookup == null || fresh.isEmpty()) return Resolution(text, resolved)
        if (aiMode == AiMode.SENTENCE && lookup is SentenceLookup) {
            val known = resolved.filter { it.status == Status.REMEMBERED }
            val answers = try {
                lookup.lookupSentence(text, known.map { it.item })
            } catch (e: Exception) {
                return Resolution(text, resolved, aiProblem = e.message ?: "AI lookup failed")
            }
            if (answers.isEmpty()) return Resolution(text, resolved, usedAi = true)
            return Resolution(text, known + alignSentence(resolved.filter { it.status != Status.REMEMBERED }, answers), usedAi = true)
        }
        val answers = try {
            lookup.lookup(text, fresh.map { f -> f.value.item })
        } catch (e: Exception) {
            // Offline, bad key, rate-limited: the local answer stands, and the card
            // says the AI was not asked. Nothing the user said is lost.
            return Resolution(text, resolved, aiProblem = e.message ?: "AI lookup failed")
        }
        fresh.forEachIndexed { n, (index, local) ->
            val answer = answers.getOrNull(n) ?: return@forEachIndexed
            resolved[index] = withAi(local, answer)
        }
        return Resolution(text, resolved, usedAi = true)
    }

    /** Layers 1–3 for one item. */
    fun resolveLocal(said: ItemParser.Item): ResolvedItem {
        var item = said
        val key = PersonalKeys.of(item)
        remembered(item, key)?.let { return it }
        // "a solid breakfast", "some food at the party" name no food; any match
        // would be invented.
        if (FoodQuery.isVague(item.food) || vagueWords(item.food)) return unknown(item, key)

        val near = personal.all().filter { !it.isMeal }
            .map { it to PersonalKeys.similarity(it.key, key) }
            .filter { it.second >= 0.5 }.maxByOrNull { it.second }?.first

        val words = FoodQuery.contentWords(item.food)
        val restaurant = item.brand?.let { b -> ItemParser.RESTAURANTS.any { it == b } } == true
        // A brand said means the brand: a curated generic never stands in for it.
        val curated = if (item.brand == null) Curated.lookup(item.food, usda) else null
        val usdaQuery = if (restaurant) "${item.brand} ${item.food}" else item.food
        val usdaMatches = (listOfNotNull(curated) + usda?.search(usdaQuery, limit = 6).orEmpty()
            .filter { credible(words, it.name) })
            .distinctBy { it.id }
        val unknownWords = words.filter { w -> usda?.knowsWord(w) == false }
        val looksPackaged = !restaurant && (item.brand != null || unknownWords.isNotEmpty() || words.any { it in PACKAGED_WORDS })
        val usdaCoverage = usdaMatches.firstOrNull()?.let { FoodQuery.coverage(words, it.name) } ?: 0.0

        // The label pack is searched for anything it could be — a chain's food never.
        val counted = item.amount != null && item.unit == null
        // Only where a label could be the answer: plain eggs and rice have none, and
        // searching 137,000 labels for them cost most of a lookup's time.
        val brandy = words.any { labels?.isBrandWord(it) == true }
        val worthLabels = item.brand != null || looksPackaged || usdaWeak0(usdaMatches, usdaCoverage, unknownWords) || brandy ||
            (curated == null && item.unit in PACKAGE_UNITS)
        var labelMatches = if (labels != null && !restaurant && worthLabels) {
            labels.search(item.food, brand = item.brand, limit = 8, counted = counted, unit = item.unit)
        } else emptyList()

        // "fairlife core power 26g", "premier protein 30g": products sold by their
        // protein, so a bare number of grams is almost always the protein on the
        // label, not 26 grams of drink. It is read that way only when a matching
        // label agrees.
        val a = item.amount
        if (item.unit == "g" && a != null && a in 5.0..60.0 && item.statedProteinG == null && labelMatches.isNotEmpty()) {
            val agrees = labelMatches.any { c ->
                val p = c.nutrientsFor(c.labelServingGrams)[Metric.PROTEIN.key]
                p != null && kotlin.math.abs(p - a) <= maxOf(2.0, 0.15 * a)
            }
            if (agrees) {
                item = item.copy(amount = null, unit = null, statedProteinG = a)
                labelMatches = labels!!.search(item.food, brand = item.brand, limit = 8, counted = false)
            }
        }
        val fitting = labelMatches.filter { labelCovers(item, it, unknownWords) }
        val usdaWeak = usdaMatches.isEmpty() || usdaCoverage < 0.5 || unknownWords.isNotEmpty()
        // "nacho cheese doritos", "pepperoni hot pocket": the brand said anywhere,
        // matching the label's own brand, makes it that product.
        val brandSaid = fitting.firstOrNull()?.let { top ->
            val bw = LabelPackSource.words(top.brand.orEmpty()).toSet()
            words.any { it in bw && labels?.isBrandWord(it) == true }
        } == true
        val useLabel = fitting.isNotEmpty() && (curated == null || brandSaid) &&
            (item.brand != null || looksPackaged || usdaWeak || brandSaid)

        val ordered = when {
            useLabel -> medianFirst(item, fitting) + labelMatches + usdaMatches
            else -> usdaMatches + labelMatches
        }.distinctBy { it.id }
        val choice = ordered.firstOrNull()
            ?: return unknown(item, key, offerScan = looksPackaged, near = near)

        var weak = weakMatch(choice, curated, unknownWords, words, usdaMatches) || (restaurant && choice.origin != Origin.LABEL)
        var portion = Portions.gramsFor(choice, item)
        // "a Greek yogurt that contains 20 g of protein": the protein they read off
        // the label says how big it was, when it is the same kind of food. Only
        // within a factor of two of the usual portion — "30 g of protein in those
        // pancakes" means protein pancakes, not eight ordinary ones.
        val statedP = item.statedProteinG
        val p100 = choice.per100g?.get(Metric.PROTEIN.key)
        if (statedP != null && (item.amount ?: 1.0) == 1.0 && item.unit == null && p100 != null && p100 > 1 &&
            portion != null && !weak && choice.origin != Origin.LABEL
        ) {
            val implied = statedP / p100 * 100.0
            if (implied in portion.grams * 0.5..portion.grams * 2.0) {
                portion = Portions.Answer(implied, "from the protein you stated")
            }
        }
        // "10 almonds": a count of small things, where the database only knows a
        // serving. Ten servings of almonds is 1,670 calories; nobody means that.
        // When the count's per-piece weight is really a serving weight, the count
        // cannot be trusted — log one serving and say so on the card.
        val count = item.amount
        if (count != null && count >= 3 && item.unit == null && portion != null &&
            portion.how in setOf("typical serving", "one serving (never one chip)") && portion.grams / count >= 25
        ) {
            portion = Portions.Answer(portion.grams / count, "couldn't tell how big each one is \u2014 check the amount")
            weak = true
        }
        var nutrients = choice.nutrientsFor(portion?.grams, item.amount ?: 1.0)
        item.statedProteinG?.let { nutrients = nutrients + (Metric.PROTEIN.key to it) }
        // No single food in a log is 2,500 calories. If the arithmetic says so,
        // something was misread — say so rather than log it.
        if ((nutrients[Metric.CALORIES.key] ?: 0.0) > 2500) weak = true
        return ResolvedItem(
            item = item,
            status = Status.NEW,
            choice = choice,
            grams = portion?.grams,
            portionNote = portion?.how,
            nutrients = nutrients,
            alternates = ordered.drop(1).take(5),
            weak = weak,
            offerScan = looksPackaged && choice.origin != Origin.LABEL,
            offerBreakdown = (weak && !looksPackaged && !restaurant) || words.any { it in DISH_WORDS },
            key = key,
            nearMiss = near,
        )
    }

    private fun isCuratedUsda(r: ResolvedItem): Boolean =
        r.choice?.origin == Origin.USDA && Curated.lookup(r.item.food, usda)?.id == r.choice.id

    private fun usdaWeak0(m: List<FoodCandidate>, coverage: Double, unknown: List<String>) =
        m.isEmpty() || coverage < 0.5 || unknown.isNotEmpty()

    /** Could this row be the thing named at all? It must share a word with it. */
    private fun credible(words: List<String>, description: String): Boolean {
        val d = Regex("[a-z]+").findAll(description.lowercase()).map { FoodQuery.stem(it.value) }.toSet()
        return words.any { it in d }
    }

    private fun vagueWords(food: String): Boolean {
        val w = FoodQuery.contentWords(food)
        return w.isNotEmpty() && w.all { it in VAGUE }
    }

    private fun weakMatch(
        choice: FoodCandidate,
        curated: FoodCandidate?,
        unknownWords: List<String>,
        words: List<String>,
        usdaMatches: List<FoodCandidate>,
    ): Boolean {
        if (choice.origin == Origin.LABEL) return false
        if (choice == curated && unknownWords.isEmpty()) return false
        val coverage = usdaMatches.firstOrNull()?.let { FoodQuery.coverage(words, it.name) } ?: 0.0
        return unknownWords.isNotEmpty() || coverage < 0.5 || (coverage < 0.67 && words.size >= 3)
    }

    /** Layer 1. Null unless the item can log silently. */
    private fun remembered(item: ItemParser.Item, key: String): ResolvedItem? {
        val food = personal.get(key)
            ?: personal.all().firstOrNull { !it.isMeal && PersonalKeys.sameFood(it.key, key) }
            ?: return null
        val scaled = scale(food, item) ?: return null
        return ResolvedItem(
            item = item,
            status = Status.REMEMBERED,
            choice = food.candidate ?: FoodCandidate(id = "mine:${food.key}", name = food.name, origin = Origin.PERSONAL),
            grams = scaled.first,
            portionNote = "your usual",
            nutrients = scaled.second,
            key = food.key,
        )
    }

    /**
     * Their remembered numbers, scaled to the amount said now. Null when the new
     * amount cannot be related to the remembered one without guessing — then the
     * item gets a card, once, and the answer is remembered for that unit too.
     */
    fun scale(food: PersonalFood, item: ItemParser.Item): Pair<Double?, Map<String, Double>>? {
        // A size is part of the portion: "1 pork chop (smallish)" once does not make
        // every pork chop small, and "a large coffee" is not their usual coffee.
        val sameSize = item.size == food.size
        // Nothing said: their usual portion, exactly.
        if (item.amount == null && item.unit == null && sameSize) return food.grams to food.nutrients.times(item.scale)

        val baseAmount = food.amount ?: 1.0
        // Same unit as before (a bare count counts as the same "unit").
        if (item.unit == food.unit && sameSize) {
            val ratio = (item.amount ?: 1.0) / baseAmount * item.scale
            return food.grams?.times(ratio) to food.nutrients.times(ratio)
        }
        // Different unit: only by weight, and only through grams they confirmed.
        val baseGrams = food.grams ?: return null
        val perUnit = when (item.unit) {
            "g" -> 1.0
            "oz" -> 28.3495
            "lb" -> 453.592
            else -> food.unitGrams[unitKey(item.unit, item.size)]
        } ?: return null
        val grams = perUnit * (item.amount ?: 1.0) * item.scale
        return grams to food.nutrients.times(grams / baseGrams)
    }

    private fun unitKey(unit: String?, size: String?) = (unit ?: "each") + (size?.let { ":$it" } ?: "")

    // ------------------------------------------------------------------
    // Writing back: whatever the user confirms or corrects becomes the answer.

    /**
     * Remember [item] as [name] with these [nutrients] for this portion. A
     * correction calls this too, and overwrites — the user is never asked about
     * the same food again.
     */
    fun learn(
        item: ItemParser.Item,
        name: String,
        candidate: FoodCandidate?,
        grams: Double?,
        nutrients: Map<String, Double>,
        key: String = PersonalKeys.of(item),
    ): PersonalFood {
        val old = personal.get(key)
        val unitGrams = (old?.unitGrams ?: emptyMap()).toMutableMap()
        if (grams != null) {
            val per = grams / (item.amount ?: 1.0) / item.scale
            unitGrams[unitKey(item.unit, item.size)] = per
        }
        val food = PersonalFood(
            key = key,
            phrase = item.food,
            name = name,
            candidate = candidate,
            amount = item.amount,
            unit = item.unit,
            size = item.size,
            grams = grams,
            nutrients = nutrients,
            unitGrams = unitGrams,
            timesUsed = (old?.timesUsed ?: 0) + 1,
            updatedAt = clock(),
        )
        personal.put(food)
        return food
    }

    /** One line of a confirm card as the user left it. */
    data class Confirmed(
        val item: ItemParser.Item,
        val key: String,
        val name: String,
        val candidate: FoodCandidate?,
        val grams: Double?,
        val nutrients: Map<String, Double>,
        /** Already remembered and left alone: nothing to write for it. */
        val unchangedMemory: Boolean = false,
    )

    /**
     * Tapping Confirm. Every new or edited item becomes the permanent answer for its
     * phrase, and a sentence with several parts is also remembered whole, so saying
     * the same thing tomorrow is one silent entry.
     */
    fun confirm(text: String, confirmed: List<Confirmed>) {
        for (c in confirmed) {
            if (c.unchangedMemory) { touch(c.key); continue }
            if (c.nutrients.isEmpty()) continue
            learn(c.item, c.name, c.candidate, c.grams, c.nutrients, c.key)
        }
        if (confirmed.size > 1 && confirmed.all { it.nutrients.isNotEmpty() }) {
            saveMeal(text, confirmed.joinToString(", ") { it.name.substringBefore(",") }, confirmed.map { MealPart(it.name, it.item.said, it.grams, it.nutrients) })
        }
    }

    /** Count a silent use, so the most-used foods can be shown first. */
    fun touch(key: String) {
        personal.get(key)?.let { personal.put(it.copy(timesUsed = it.timesUsed + 1, updatedAt = clock())) }
    }

    /**
     * "Save as mine": a whole breakdown becomes one food, loggable afterwards by the
     * same sentence — or by [asName], if they gave it one ("my usual bag of veggies").
     */
    fun saveMeal(text: String, name: String, parts: List<MealPart>, asName: String? = null): PersonalFood {
        val total = sumNutrients(parts.map { it.nutrients })
        val grams = parts.mapNotNull { it.grams }.takeIf { it.size == parts.size }?.sum()
        val meal = PersonalFood(
            key = PersonalKeys.ofText(text),
            phrase = text,
            name = name,
            grams = grams,
            nutrients = total,
            parts = parts,
            updatedAt = clock(),
        )
        personal.put(meal)
        asName?.takeIf { it.isNotBlank() }?.let { personal.put(meal.copy(key = PersonalKeys.ofText(it), phrase = it)) }
        return meal
    }

    // ------------------------------------------------------------------

    private fun withAi(local: ResolvedItem, answer: AiItemAnswer): ResolvedItem {
        val item = local.item
        if (answer.needsDetail) {
            return local.copy(status = Status.UNKNOWN, choice = null, grams = null, nutrients = emptyMap(), weak = true)
        }
        // A real label beats an AI's memory of one: measured, its sodium ran 10% high
        // and one packaged food in three was off by more than a fifth. But the two
        // check each other: when the label the app picked disagrees with the AI by
        // more than a third, the pick is the likelier mistake (a zero-calorie row
        // filed as regular Sprite), and the AI's answer leads with the label behind it.
        val aiKcal0 = answer.nutrients[Metric.CALORIES.key]
        val localKcal = local.nutrients[Metric.CALORIES.key]
        val agree = aiKcal0 == null || localKcal == null ||
            kotlin.math.abs(localKcal - aiKcal0) <= maxOf(40.0, 0.35 * maxOf(aiKcal0, localKcal))
        if (local.choice?.origin == Origin.LABEL && !local.weak && agree) {
            return local.copy(alternates = (local.alternates + answer.asCandidate()).take(6))
        }
        val search = answer.productSearch
        val aiKcal = answer.nutrients[Metric.CALORIES.key]
        if (labels != null && answer.brand != null && search != null && aiKcal != null && aiKcal > 0) {
            val asSaid = item.copy(
                amount = answer.count ?: item.amount,
                unit = answer.unit?.let { ItemParser.UNITS[it.lowercase()] } ?: item.unit,
            )
            // Several labels fit the words (every size of Mission tortilla does), and
            // the first is not reliably the one eaten. The AI's own estimate picks
            // among them, and a label only replaces it when they agree within 30% —
            // then the label's exact numbers (sodium above all) are the better ones.
            val fitting = labels.search(search, brand = answer.brand, limit = 6)
                .filter { labelCovers(item.copy(food = search, brand = answer.brand), it, emptyList()) }
                .mapNotNull { c ->
                    val portion = Portions.gramsFor(c, asSaid) ?: return@mapNotNull null
                    val n = c.nutrientsFor(portion.grams, asSaid.amount ?: 1.0)
                    val k = n[Metric.CALORIES.key] ?: return@mapNotNull null
                    Triple(c, portion, n) to kotlin.math.abs(k - aiKcal) / aiKcal
                }
                .filter { it.second <= 0.30 }
                .minByOrNull { it.second }?.first
            if (fitting != null) {
                val (best, portion, n) = fitting
                var nutrients = n
                item.statedProteinG?.let { nutrients = nutrients + (Metric.PROTEIN.key to it) }
                return local.copy(
                    status = Status.NEW, choice = best, grams = portion.grams, portionNote = portion.how,
                    nutrients = nutrients, weak = false,
                    alternates = (listOf(answer.asCandidate()) + listOfNotNull(local.choice)).take(6),
                    offerScan = false,
                )
            }
        }
        val candidate = answer.asCandidate()
        var nutrients = answer.nutrients
        item.statedProteinG?.let { nutrients = nutrients + (Metric.PROTEIN.key to it) }
        return local.copy(
            status = Status.NEW,
            choice = candidate,
            grams = answer.grams,
            portionNote = answer.portion,
            nutrients = nutrients,
            alternates = listOfNotNull(local.choice).plus(local.alternates).take(6),
            weak = false,
        )
    }

    /**
     * Pair the model's items with the app's own reading of the sentence, by shared
     * words, so each is remembered under what the user said. A model item with no
     * counterpart (it split "a PB&J" into bread, peanut butter and jelly) is its
     * own line, keyed by its name; the sentence as a whole is remembered too.
     */
    private fun alignSentence(local: List<ResolvedItem>, answers: List<AiItemAnswer>): List<ResolvedItem> {
        val free = local.toMutableList()
        return answers.map { a ->
            val aw = FoodQuery.contentWords(a.name).toSet()
            val match = free.maxByOrNull { l -> FoodQuery.contentWords(l.item.food).count { it in aw } }
                ?.takeIf { l -> FoodQuery.contentWords(l.item.food).any { it in aw } || (answers.size == 1 && free.size == 1) }
            if (match != null) {
                free.remove(match)
                withAi(match, a)
            } else {
                val item = ItemParser.Item(
                    said = a.name, food = a.name.lowercase(), amount = a.count,
                    unit = a.unit?.let { ItemParser.UNITS[it.lowercase()] }, brand = a.brand,
                )
                withAi(unknown(item, PersonalKeys.of(item)), a)
            }
        }
    }

    private fun unknown(item: ItemParser.Item, key: String, offerScan: Boolean = false, near: PersonalFood? = null) =
        ResolvedItem(
            item = item, status = Status.UNKNOWN, choice = null, grams = null, portionNote = null,
            nutrients = emptyMap(), weak = true, offerScan = offerScan,
            offerBreakdown = !offerScan, key = key, nearMiss = near,
        )

    /**
     * Several labels fit "mission flour tortillas" — every size Mission sells, plus
     * the odd mislabelled row. The one in the middle is the safe answer: an outlier
     * either way (a 70-calorie "soft taco" row) cannot be picked.
     */
    private fun medianFirst(item: ItemParser.Item, fitting: List<FoodCandidate>): List<FoodCandidate> {
        if (fitting.size < 3) return fitting
        // The median is only taken among versions of the SAME product — the one
        // that ranked first, in its other sizes and label revisions. Across
        // different products it picked a turtle-cheesecake ice cream for "halo top
        // vanilla bean".
        val top = fitting.first()
        val same = fitting.filter { it.name.equals(top.name, ignoreCase = true) && it.brand.equals(top.brand, ignoreCase = true) }
        if (same.size < 3) return fitting
        val kcal = same.take(7).map { c ->
            val g = Portions.gramsFor(c, item)?.grams
            c to (c.nutrientsFor(g, item.amount ?: 1.0)[Metric.CALORIES.key] ?: 0.0)
        }.sortedBy { it.second }
        val median = kcal[kcal.size / 2].first
        return listOf(median) + fitting.filter { it != median }
    }

    /**
     * A label is the right one when it has the brand said and most of the food
     * words — in the label's own words ("flapjack" for pancake). With the brand
     * matched, one shared food word is enough: "kodiak protein pancakes" is
     * "Kodiak Power Cakes Flapjack & Waffle Mix".
     */
    private fun labelCovers(item: ItemParser.Item, label: FoodCandidate, unknownWords: List<String>): Boolean {
        val said = LabelPackSource.words(item.food).toSet()
        val brandWords = LabelPackSource.words("${label.brand} ${label.owner.orEmpty()}").toSet()
        // Food words must be in the product's DESCRIPTION: "blue diamond almonds"
        // is not Almond Breeze almond milk just because "almond" is in the brand.
        val desc = LabelPackSource.words(label.name).toSet()
        val descWithSynonyms = desc + desc.flatMap { LabelPackSource.synonymsOf(it) }
        val brand = item.brand?.let { LabelPackSource.words(it) }.orEmpty()
        if (brand.isNotEmpty() && !brand.all { it in brandWords || it in desc }) return false
        if (!unknownWords.all { FoodQuery.stem(it) in desc || it in desc || it in brandWords }) return false
        val foodSaid = said - brandWords
        val shared = foodSaid.count { it in descWithSynonyms }
        return when {
            foodSaid.isEmpty() -> true
            brand.isNotEmpty() || said.any { it in brandWords } -> shared >= 1
            else -> shared >= foodSaid.size * 0.6
        }
    }

    private fun isFoodWord(word: String): Boolean =
        usda?.knowsWord(word) == true || labels?.knowsWord(word) == true ||
            personal.all().any { word in it.key.split(" ") }

    private fun Map<String, Double>.times(f: Double) = mapValues { it.value * f }


    companion object {
        private val PACKAGE_UNITS = setOf("can", "bottle", "bag", "packet", "container", "bar")

        private val SPECIAL_WORDS = setOf("diet", "zero", "light", "lite", "free", "reduced", "low", "lo", "mini",
            "variety", "fudge", "dipped", "covered", "thin", "keto", "carb", "decaf").map { FoodQuery.stem(it) }.toSet()

        private val VAGUE = setOf("food", "party", "stuff", "thing", "something", "snack", "bite", "leftover",
            "meal", "breakfast", "lunch", "dinner", "supper", "brunch", "dessert", "treat", "usual", "event",
            "buffet", "potluck", "random", "whatever", "junk", "some").map { FoodQuery.stem(it) }.toSet()

        /** Words that nearly always mean a product off a shelf. */
        val PACKAGED_WORDS = setOf(
            "bar", "shake", "drink", "chip", "cereal", "cracker", "cookie", "soda", "cola",
            "frosty", "nugget", "fry", "baconator", "whopper", "mcmuffin", "lunchable",
            "poptart", "oreo", "protein", "powder", "gatorade", "energy", "jerky", "frozen",
        ).map { FoodQuery.stem(it) }.toSet()

        /** Words that mean a dish someone made: a candidate for "break it down". */
        val DISH_WORDS = setOf(
            "casserole", "sandwich", "salad", "soup", "stew", "stir", "curry", "chili",
            "bowl", "wrap", "burrito", "taco", "pasta", "lasagna", "omelet", "omelette",
            "smoothie", "homemade", "leftover", "meal", "plate", "dish",
        ).map { FoodQuery.stem(it) }.toSet()
    }
}

/** Adds nutrient maps key by key. A metric absent from every part stays absent. */
fun sumNutrients(parts: List<Map<String, Double>>): Map<String, Double> {
    val out = linkedMapOf<String, Double>()
    for (p in parts) for ((k, v) in p) out[k] = (out[k] ?: 0.0) + v
    return out
}

/** An AI lookup that can also read a whole sentence itself (see [Resolver.AiMode.SENTENCE]). */
interface SentenceLookup {
    fun lookupSentence(fullText: String, known: List<ItemParser.Item>): List<AiItemAnswer>
}

/** Layer 4, over the user's own API key ([OpenAiItemLookup]). */
fun interface AiItemLookup {
    /**
     * One answer per item, in order; null where the model had nothing. Throws when
     * the lookup could not be made at all (offline, bad key) — the resolver then
     * falls back to its local answer, it never fails the entry.
     */
    fun lookup(fullText: String, items: List<ItemParser.Item>): List<AiItemAnswer?>
}

data class AiItemAnswer(
    val name: String,
    /** "1 cup (244 g)" — what the numbers are for, in words. */
    val portion: String?,
    val grams: Double?,
    val nutrients: Map<String, Double>,
    val needsDetail: Boolean = false,
    val brand: String? = null,
    val count: Double? = null,
    val unit: String? = null,
    /** brand + product + flavour, for finding its real label. */
    val productSearch: String? = null,
) {
    fun asCandidate() = FoodCandidate(
        id = "ai:${name.lowercase().hashCode()}",
        name = name,
        origin = Origin.AI,
        perServing = nutrients,
        servingLabel = portion,
        per100g = grams?.takeIf { it > 0 }?.let { g -> nutrients.mapValues { it.value * 100.0 / g } },
        defaultGrams = grams,
        brand = brand,
    )
}
