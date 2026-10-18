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
        /** [alternates] are their other brands of this food, offered as one-tap swaps. */
        val otherBrands: Boolean = false,
        /**
         * The food is one of theirs (a label they scanned, a food they confirmed). The
         * AI may say how much, never what: their tuna label lost to a 340 g guess.
         */
        val mine: Boolean = false,
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
        usualWithSwap(text)?.let { return it }

        val items = ItemParser.parse(text, ::isFoodWord) { w -> labels?.isBrandWord(w) == true }
        if (items.isEmpty()) return Resolution(text, listOf(unknown(ItemParser.Item(said = text, food = text), PersonalKeys.ofText(text))))

        val resolved = items.map { resolveLocal(it) }.toMutableList()

        // "a hamburger from Costco and it had cheese": the cheese is ON the burger,
        // a slice, not the 80 g of cheese remembered from a sandwich. A remembered
        // food said as part of the one before it goes to the AI with the sentence.
        // Likewise "a little bit of cheese" and anything inside "two burritos, each
        // with…": the remembered FOOD is right but its usual amount is not, so the
        // model reads the amount from the sentence and the remembered numbers are
        // scaled to it (see [withAi]). Pinned, the cheese was their usual 80 g and the
        // model was told to leave it out, so it never saw the two burritos.
        // Reading a package aloud ("a chewy snack bar, whole oats and flax ... is what it
        // says") names one product. Pinned, "whole oats" logged their oats beside it.
        val readsPackage = READS_PACKAGE.containsMatchIn(text.lowercase())
        fun pinned(i: Int, r: ResolvedItem) = r.status == Status.REMEMBERED &&
            !(aiMode == AiMode.SENTENCE && ai is SentenceLookup &&
                (readsPackage || (i > 0 && describesPrevious(r.item.said)) || r.item.little || r.item.group != null))

        // Layer 4 fills the cards the user will see anyway — never a remembered item.
        val fresh = resolved.withIndex().filter {
            val r = it.value
            !pinned(it.index, r) && when (aiMode) {
                AiMode.ALL_NEW, AiMode.SENTENCE -> true
                AiMode.WEAK_ONLY -> r.weak || r.status == Status.UNKNOWN
                AiMode.TRUSTED_LOCAL -> r.weak || r.status == Status.UNKNOWN ||
                    (r.choice?.origin != Origin.CURATED && r.choice?.origin != Origin.LABEL && !isCuratedUsda(r))
            }
        }
        val lookup = ai
        if (lookup == null || fresh.isEmpty()) return Resolution(text, resolved)
        if (aiMode == AiMode.SENTENCE && lookup is SentenceLookup) {
            val known = resolved.filterIndexed { i, r -> pinned(i, r) }
            val answers = try {
                lookup.lookupSentence(text, known.map { it.item })
            } catch (e: Exception) {
                return Resolution(text, resolved, aiProblem = e.message ?: "AI lookup failed")
            }
            // Nothing new besides what is remembered: the rest the app split off was
            // a remark. "A kiwi, though it's not very ripe" logged a raw plantain.
            if (answers.isEmpty()) return Resolution(text, known.ifEmpty { resolved }, usedAi = true)
            return Resolution(text, known + alignSentence(resolved.filterIndexed { i, r -> !pinned(i, r) }, answers), usedAi = true)
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

    /**
     * "my typical protein shake, except that instead of milk I used water": their
     * saved meal, with one part swapped. Logged as a new food it came to 0 kcal,
     * because "protein shake" alone was a 15 g scoop "mixed with water" from once
     * before. Null when no saved meal fits; the sentence is then read as usual.
     */
    private fun usualWithSwap(text: String): Resolution? {
        val low = ItemParser.normaliseText(text)
        val m = Regex("\\b(?:typical|usual|normal|regular|standard|same)\\s+([a-z' ]+?)\\s*,?\\s+(?:except|but|only)\\b(.*)$").find(low) ?: return null
        val tail = m.groupValues[2]
        val swap = Regex("instead of (?:the |my )?([a-z' ]+?)\\s*,?\\s+(?:that )?(?:i |we )?(?:used|had|put|with|it was|went with|use|added)\\s+(?:some |a |an )?([a-z0-9' .]+)").find(tail)
            ?.let { it.groupValues[1] to it.groupValues[2] }
            ?: Regex("(?:with|used|had)\\s+(?:some |a |an )?([a-z0-9' .]+?)\\s+instead of\\s+(?:the |my )?([a-z' ]+)").find(tail)
                ?.let { it.groupValues[2] to it.groupValues[1] }
            ?: Regex("(?:no|without)\\s+(?:the |any )?([a-z' ]+)").find(tail)?.let { it.groupValues[1] to "" }
            ?: return null
        val want = FoodQuery.contentWords(m.groupValues[1]).toSet()
        if (want.isEmpty()) return null
        // A shake is made of a powder: "protein shake" finds "chocolate protein powder, whole milk".
        val alias = mapOf("shake" to setOf("shake", "powder", "smoothie"), "smoothie" to setOf("smoothie", "shake"))
        fun covers(f: PersonalFood): Boolean {
            val words = (FoodQuery.contentWords(f.name) + FoodQuery.contentWords(f.phrase) +
                f.parts.flatMap { FoodQuery.contentWords(it.name) }).toSet()
            return want.all { w -> (alias[w] ?: setOf(w)).any { it in words } }
        }
        val out = FoodQuery.contentWords(swap.first).toSet()
        if (out.isEmpty()) return null
        // The one that HAS the part being swapped ("instead of milk": a shake made
        // with milk), the plainest such, the latest.
        val meal = personal.all().filter { it.isMeal && covers(it) && it.parts.any { p -> FoodQuery.contentWords(p.name).any { w -> w in out } } }
            .sortedWith(compareBy<PersonalFood> { it.parts.size }.thenByDescending { it.updatedAt })
            .firstOrNull() ?: return null
        val kept = meal.parts.filterNot { p -> FoodQuery.contentWords(p.name).any { it in out } }
        if (kept.size == meal.parts.size) return null
        val items = kept.map { p ->
            val item = ItemParser.Item(said = p.said, food = p.name.lowercase())
            ResolvedItem(
                item = item, status = Status.REMEMBERED,
                choice = FoodCandidate(id = "mine:${PersonalKeys.of(p.name)}", name = p.name, origin = Origin.PERSONAL),
                grams = p.grams, portionNote = "your usual", nutrients = p.nutrients, key = PersonalKeys.of(p.name),
            )
        }
        val added = swap.second.trim().takeIf { FoodQuery.contentWords(it).isNotEmpty() }
            ?.let { ItemParser.parse(it, ::isFoodWord) { w -> labels?.isBrandWord(w) == true } }.orEmpty()
            .map { resolveLocal(it) }
        return Resolution(text, items + added)
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
        val brandedCurated = item.brand?.let { b -> Curated.exact("$b ${item.food}", usda) }
        val curated = brandedCurated ?: if (item.brand == null) Curated.lookup(item.food, usda) else null
        val usdaQuery = if (restaurant) "${item.brand} ${item.food}" else item.food
        // "vitamin D supplements" shares a word with "Protein supplement, Muscle Milk"
        // and with "Milk, with added vitamin D". A supplement is only ever a supplement.
        val supplement = words.any { it in SUPPLEMENT_WORDS }
        val usdaMatches = (listOfNotNull(curated) + usda?.search(usdaQuery, limit = 6).orEmpty()
            .filter { credible(words, it.name) })
            .filter { !supplement || isSupplement(it.name) }
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
                .filter { !supplement || isSupplement(it.name) }
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
        val useLabel = brandedCurated == null && fitting.isNotEmpty() && (curated == null || brandSaid) &&
            (item.brand != null || looksPackaged || usdaWeak || brandSaid)

        val ordered = when {
            useLabel -> medianFirst(item, fitting) + labelMatches + usdaMatches
            else -> usdaMatches + labelMatches
        }.distinctBy { it.id }
        val choice = ordered.firstOrNull()?.let { if (it.origin == Origin.LABEL) completeLabel(it, usdaMatches) else it }
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

    /**
     * A label prints five micronutrients; the same food in USDA knows the rest. The
     * yogurt from the label pack logged no magnesium, zinc or vitamin A at all.
     */
    private fun completeLabel(c: FoodCandidate, usdaMatches: List<FoodCandidate>): FoodCandidate = runCatching {
        val offline = usdaMatches + usda?.search(c.name, limit = 4).orEmpty()
        MicroFill.complete(c, offline.distinctBy { it.id })
    }.getOrDefault(c)

    private val SUPPLEMENT_WORDS = setOf("vitamin", "supplement", "multivitamin", "gummy", "gummies", "capsule", "tablet", "softgel", "pill")
        .map { FoodQuery.stem(it) }.toSet()

    private fun isSupplement(name: String): Boolean {
        val n = name.lowercase()
        return Regex("^(?:vitamin|multi|supplement|dietary supplement)|\\bsupplement\\b|\\bgumm(?:y|ies)\\b|\\bmultivitamin").containsMatchIn(n) &&
            !Regex("\\b(?:protein|milk|margarine|cereal|juice|water|drink)\\b").containsMatchIn(n)
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
        val exact = personal.get(key)
            ?: personal.all().firstOrNull { !it.isMeal && PersonalKeys.sameFood(it.key, key) }
        val brands = if (item.brand == null && exact?.candidate?.origin != Origin.LABEL) brandsFor(item, key) else emptyList()
        // Their own label, said another way: "my typical 20 gram protein Chobani", "half
        // my normal vitamin D supliments", "great value whole wheat bread". Never silent
        // the first time it is said like that, but their label, not a guess.
        val scanned = if (exact == null && brands.isEmpty()) scannedFor(item) else null
        val food = brands.firstOrNull() ?: exact ?: scanned ?: return null
        val others = brands.drop(1).mapNotNull { it.candidate }
        val direct = scale(food, item)
        val scaled = direct
            // Their label, but never measured this way: "a bowl of Life" when the box
            // was scanned as "1 cup (42 g)". The label's own measures, or their usual
            // amount of the generic food they had saved, size it — on a card, once,
            // with their food already picked. Before, it fell through to a fresh
            // lookup and the scanned box lost to an AI guess.
            ?: exact?.takeIf { it !== food }?.let { usual -> scale(usual, item)?.first }?.let { g ->
                food.grams?.takeIf { it > 0 }?.let { fg -> g to food.nutrients.times(g / fg) }
            }
            ?: food.candidate?.let { c -> Portions.gramsFor(c, item)?.let { p -> p.grams to c.nutrientsFor(p.grams, item.amount ?: 1.0) } }
                ?.takeIf { it.second.isNotEmpty() }
            ?: return null
        return ResolvedItem(
            item = item,
            status = if (direct != null && food !== scanned) Status.REMEMBERED else Status.NEW,
            choice = food.candidate ?: FoodCandidate(id = "mine:${food.key}", name = food.name, origin = Origin.PERSONAL),
            grams = scaled.first,
            portionNote = when {
                Portions.filling(item) != null -> "your usual, as a filling"
                item.little -> "a little of your usual"
                else -> "your usual"
            },
            nutrients = scaled.second,
            alternates = others,
            // Said loosely, it is learned under the words used now, so next time is silent.
            key = if (food === scanned) key else food.key,
            otherBrands = others.isNotEmpty(),
            mine = true,
        )
    }

    /**
     * A label they scanned or picked, matched to words said loosely: the brand, a
     * typo, the protein they read off it. Strict about what the food IS — every word
     * said has to be in its name, its head noun has to be one of them, and a stated
     * protein has to agree with the label's — because the answer is trusted over the
     * AI.
     */
    fun scannedFor(item: ItemParser.Item): PersonalFood? {
        val said = (FoodQuery.contentWords(item.food) + item.brand?.let { FoodQuery.contentWords(it) }.orEmpty())
            .map { SAID_ALIASES[it] ?: it }.distinct()
        if (said.isEmpty() || said.all { it in PACKAGED_WORDS }) return null
        fun near(a: String, b: String) = a == b ||
            (minOf(a.length, b.length) >= 5 && PersonalKeys.editDistance(a, b) <= 1) ||
            (minOf(a.length, b.length) >= 8 && editDistance2(a, b) <= 2)
        return personal.all().asSequence()
            .filter { !it.isMeal && it.candidate?.origin == Origin.LABEL && it.nutrients.isNotEmpty() }
            .mapNotNull { f ->
                val c = f.candidate!!
                val names = listOf(f.name, c.name, c.brand.orEmpty())
                val words = names.flatMap { FoodQuery.contentWords(it) }.toSet()
                if (!said.all { w -> words.any { near(it, w) } }) return@mapNotNull null
                val heads = listOf(f.name, c.name).mapNotNull { n -> FoodQuery.contentWords(n.substringBefore(",")).lastOrNull() }
                if (heads.none { h -> said.any { near(it, h) } }) return@mapNotNull null
                if ((words - said.toSet()).any { it in OTHER_FOOD && said.none { s -> near(s, it) } }) return@mapNotNull null
                item.statedProteinG?.let { stated ->
                    val perServing = c.labelServingGrams?.let { g -> c.nutrientsFor(g)[Metric.PROTEIN.key] }
                        ?: f.nutrients[Metric.PROTEIN.key]?.let { it / (f.amount ?: 1.0) }
                    if (perServing == null || kotlin.math.abs(perServing - stated) > maxOf(2.0, 0.15 * stated)) return@mapNotNull null
                }
                f to said.count { w -> w in words }
            }
            .sortedWith(compareByDescending<Pair<PersonalFood, Int>> { it.second }.thenByDescending { it.first.updatedAt })
            .firstOrNull()?.first
    }

    /** Edit distance up to 2 (insertions, deletions, substitutions); 3 means "further". */
    private fun editDistance2(a: String, b: String): Int {
        if (kotlin.math.abs(a.length - b.length) > 2) return 3
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        return d[a.length][b.length]
    }

    /**
     * The brands of [item] they have scanned or picked, latest first: "milk" is the
     * Great Value whole milk they always buy, "peanut butter" whichever jar they
     * logged last, with the others a tap away on the card. Only real labels, and
     * only the same food: "cheese" is not their string cheese.
     */
    fun brandsFor(item: ItemParser.Item, key: String): List<PersonalFood> {
        // Toast is their bread, toasted.
        val said = key.substringBefore('#').split(" ").filter { it.isNotBlank() }.map { SAID_ALIASES[it] ?: it }.toSet()
        if (said.isEmpty() || item.statedProteinG != null) return emptyList()
        // Staples, not products: "a bar" or "a protein shake" is not whichever bar they
        // scanned last — the brand IS the product there.
        if (said.all { it in PACKAGED_WORDS }) return emptyList()
        return personal.all().asSequence()
            .filter { !it.isMeal && it.candidate?.origin == Origin.LABEL && it.key != key }
            .filter { f ->
                // Its name, not the words it was saved under: a whole sentence once
                // saved as Dole Baby Spinach made "baby carrots" spinach for weeks.
                val words = (listOf(f.name, f.candidate!!.name, f.candidate.brand.orEmpty())
                    .flatMap { FoodQuery.contentWords(it) }).map { SAID_ALIASES[it] ?: it }.toSet()
                val extra = words - said
                // "milk" is not their milk chocolate: a word that makes it another food
                // rules it out. And the thing it IS must be what was said: "cheese" is
                // not their El Monterey bean and cheese (burrito).
                val heads = listOf(f.candidate.name, f.name).mapNotNull { n -> FoodQuery.contentWords(n.substringBefore(",")).lastOrNull() }
                said.all { it in words } && extra.none { it in OTHER_FOOD } &&
                    heads.none { it in DISH_HEADS && it !in said } &&
                    (said.size > 1 || heads.any { (SAID_ALIASES[it] ?: it) in said })
            }
            .sortedByDescending { it.updatedAt }
            .distinctBy { it.candidate?.id ?: it.key }
            .toList()
    }

    private val DISH_HEADS = setOf("burrito", "sandwich", "taco", "pizza", "wrap", "quesadilla", "salad", "soup",
        "casserole", "lasagna", "enchilada", "pie", "muffin", "bagel", "dinner", "meal", "bowl").map { FoodQuery.stem(it) }.toSet()

    private val OTHER_FOOD = setOf(
        "string", "cottage", "cream", "chocolate", "almond", "oat", "soy", "coconut", "rice", "frosting",
        "sauce", "dressing", "dip", "cake", "cookie", "cracker", "bar", "powder", "shake", "candy", "ice",
        "condensed", "evaporated", "buttermilk", "goat", "sweet", "pudding", "cereal", "chip", "jerky",
    ).map { FoodQuery.stem(it) }.toSet()

    /**
     * Their remembered numbers, scaled to the amount said now. Null when the new
     * amount cannot be related to the remembered one without guessing — then the
     * item gets a card, once, and the answer is remembered for that unit too.
     */
    fun scale(food: PersonalFood, item: ItemParser.Item): Pair<Double?, Map<String, Double>>? {
        // "some spinach" in each of two burritos: a filling's worth each, at their
        // own numbers per gram. Their usual 85 g was a bowl's worth.
        val fg = food.grams?.takeIf { it > 0 }
        if (fg != null) Portions.filling(item)?.let { (perDish, _) ->
            val g = perDish * (item.amount ?: 1.0) * item.scale * (if (item.little) 0.6 else 1.0)
            return g to food.nutrients.times(g / fg)
        }
        if (item.little) {
            val usual = scale(food, item.copy(little = false)) ?: return null
            return usual.first?.times(Portions.LITTLE) to usual.second.times(Portions.LITTLE)
        }
        // A size is part of the portion: "1 pork chop (smallish)" once does not make
        // every pork chop small, and "a large coffee" is not their usual coffee.
        val sameSize = item.size == food.size
        // Nothing said: their usual portion, exactly.
        // "a decent amount of butter" scales the weight with the numbers: butter at 14 g
        // and 153 kcal was 11 kcal a gram.
        if (item.amount == null && item.unit == null && sameSize) return food.grams?.times(item.scale) to food.nutrients.times(item.scale)

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
        // The Life cereal box they scanned on Monday was replaced on Wednesday by the
        // AI's guess at "a bowl of Life cereal", under the same words. A real label
        // stays what the food IS; only the new amount is learned. Numbers they typed
        // themselves (MANUAL) or another label still replace it.
        val oldGrams = old?.grams?.takeIf { it > 0 }
        if (old != null && !old.isMeal && old.candidate?.origin == Origin.LABEL &&
            candidate?.origin != Origin.LABEL && candidate?.origin != Origin.MANUAL && candidate?.origin != Origin.PERSONAL
        ) {
            val kept = if (grams != null && oldGrams != null) {
                old.copy(amount = item.amount, unit = item.unit, size = item.size, grams = grams,
                    nutrients = old.nutrients.times(grams / oldGrams))
            } else old
            val food = kept.copy(unitGrams = unitGrams, timesUsed = old.timesUsed + 1, updatedAt = clock())
            personal.put(food)
            return food
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
            kinds = kindsAfter(old, candidate),
            kindUses = candidate?.let { c -> old?.kindUses.orEmpty().let { it + (Kinds.label(c) to (it[Kinds.label(c)] ?: 0) + 1) } } ?: old?.kindUses.orEmpty(),
            askKind = old?.askKind ?: PersonalFood.ASK_WHEN_DIFFERENT,
            photo = old?.photo?.takeIf { candidate?.id == old?.candidate?.id },
        )
        personal.put(food)
        return food
    }

    /**
     * The kinds kept beside the new answer: the vanilla they had until now stays one
     * tap away when they switch to strawberry. Another product altogether is not a kind.
     */
    private fun kindsAfter(old: PersonalFood?, now: FoodCandidate?): List<FoodCandidate> {
        if (old == null || now == null) return old?.kinds.orEmpty()
        val prev = old.candidate
        val all = (old.kinds + listOfNotNull(prev?.takeIf { Kinds.sameProduct(it, now) }))
        return all.filter { it.id != now.id && Kinds.sameProduct(it, now) }.distinctBy { Kinds.label(it).lowercase() }
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

    /**
     * Forget what these words were taken to mean: the sentence's saved meal and each
     * remembered answer. A label they scanned themselves stays (it is still their
     * food); only what the app worked out is dropped, so it asks again next time.
     */
    fun forget(text: String?, keys: Collection<String>): Int {
        var n = 0
        text?.let { t -> PersonalKeys.ofText(t).takeIf { it.isNotBlank() }?.let { k ->
            if (personal.get(k)?.isMeal == true) { personal.remove(k); n++ }
        } }
        for (k in keys.toSet()) {
            val f = personal.get(k) ?: continue
            if (f.isMeal || f.candidate?.origin == Origin.LABEL) continue
            personal.remove(k); n++
        }
        return n
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
        // Their own food or a real label, with no measure said ("some pulled pork",
        // "a little bit of cheese" in two burritos): the FOOD is the trusted one, and
        // the model, which read the whole sentence, says how much. Before, the
        // label's "1/3 package" or their usual 80 g stood, whatever the sentence said.
        // The model named a food but gave it no numbers (a "Shaka plate" it did not
        // know): logged as nothing, the meal read as 0 calories. It asks instead.
        if (answer.nutrients[Metric.CALORIES.key] == null) {
            return if (local.nutrients.isNotEmpty()) local.copy(alternates = local.alternates.take(6))
            else local.copy(status = Status.UNKNOWN, choice = null, grams = null, nutrients = emptyMap(), weak = true, offerBreakdown = true)
        }
        val yours = local.mine || local.status == Status.REMEMBERED || local.choice?.origin == Origin.PERSONAL
        // A label the app found on the words alone is trusted only when it is the
        // food the model read: "a gala apple" found sour-apple candy, "cilantro lime
        // dressing" a bottled one that then took the whole salad's 650 g.
        val trusted = yours || (local.choice?.origin == Origin.LABEL && !local.weak && sameFood(local, answer))
        val small = item.little || item.topping || item.unit == "sprinkle"
        val unmeasured = small || (item.unit == null && (item.amount == null || item.group != null))
        val aiGrams = answer.grams?.takeIf { it > 0 }
        val localGrams = local.grams?.takeIf { it > 0 }
        if (trusted && unmeasured && aiGrams != null && localGrams != null && local.nutrients.isNotEmpty()) {
            // "A little", "a drizzle" is never more than half their usual, whatever the model read.
            val grams = if (small) minOf(aiGrams, localGrams * 0.5) else aiGrams
            val f = grams / localGrams
            return local.copy(
                status = Status.NEW,
                grams = grams,
                portionNote = if (grams == aiGrams) answer.portion ?: "amount read from what you said" else "a little of your usual",
                nutrients = local.nutrients.mapValues { it.value * f },
                alternates = (local.alternates + answer.asCandidate()).take(6),
            )
        }
        // Their own food, with an amount they said: theirs stands, whatever the model thinks.
        if (local.mine && local.nutrients.isNotEmpty()) {
            return local.copy(alternates = (local.alternates + answer.asCandidate()).take(6))
        }
        // A real label beats an AI's memory of one: measured, its sodium ran 10% high
        // and one packaged food in three was off by more than a fifth. But the two
        // check each other: when the label the app picked disagrees with the AI by
        // more than a third, the pick is the likelier mistake (a zero-calorie row
        // filed as regular Sprite), and the AI's answer leads with the label behind it.
        val aiKcal0 = answer.nutrients[Metric.CALORIES.key]
        val localKcal = local.nutrients[Metric.CALORIES.key]
        // The brand the model read must be the label's: a "Melaleuca SimplyFit" bar kept a
        // Clover Valley fig bar because both came to about 65 kcal.
        val brandAgrees = answer.brand == null || local.choice?.origin != Origin.LABEL || run {
            val want = LabelPackSource.words(answer.brand)
            val have = LabelPackSource.words("${local.choice.brand.orEmpty()} ${local.choice.owner.orEmpty()} ${local.choice.name}").toSet()
            want.isEmpty() || want.any { it in have }
        }
        val agree = brandAgrees && sameFood(local, answer) && (aiKcal0 == null || localKcal == null ||
            kotlin.math.abs(localKcal - aiKcal0) <= maxOf(20.0, 0.35 * maxOf(aiKcal0, localKcal)))
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
                    // Measured in a unit the label defines ("1.5 cups" of a cereal whose
                    // label says 3/4 cup = 32 g), the label's weight is the better one:
                    // the model put 1.5 cups of Life at 42 g. Then the two need only agree
                    // on calories per gram.
                    val gap = kotlin.math.abs(k - aiKcal) / aiKcal
                    val perGram = answer.grams?.takeIf { it > 0 && item.unit != null && portion.how.contains("label") }?.let { g ->
                        val mine = k / (portion.grams * (asSaid.amount ?: 1.0))
                        val theirs = aiKcal / g
                        if (kotlin.math.abs(mine - theirs) <= 0.25 * maxOf(mine, theirs)) 0.0 else null
                    }
                    Triple(c, portion, n) to (perGram ?: gap)
                }
                .filter { it.second <= 0.30 }
                .minByOrNull { it.second }?.first
            if (fitting != null) {
                val (best0, portion, _) = fitting
                val best = completeLabel(best0, emptyList())
                var nutrients = best.nutrientsFor(portion.grams, asSaid.amount ?: 1.0)
                item.statedProteinG?.let { nutrients = nutrients + (Metric.PROTEIN.key to it) }
                return local.copy(
                    status = Status.NEW, choice = best, grams = portion.grams, portionNote = portion.how,
                    nutrients = nutrients, weak = false,
                    alternates = (listOf(answer.asCandidate()) + listOfNotNull(local.choice)).take(6),
                    offerScan = false,
                )
            }
        }
        var candidate = answer.asCandidate()
        var nutrients = answer.nutrients
        item.statedProteinG?.let { nutrients = nutrients + (Metric.PROTEIN.key to it) }
        // The AI gives no micronutrients. Borrow them from the offline match for the
        // same food when it really is the same food (see MicroFill.sameFood); the
        // AI's own calories and macros stand.
        val offline = listOfNotNull(local.choice) + local.alternates +
            (usda?.let { u -> runCatching { u.search(answer.name, limit = 3) }.getOrDefault(emptyList()) }.orEmpty())
        MicroFill.sameFood(offline, nutrients, answer.grams)?.let { same ->
            nutrients = nutrients + MicroFill.missing(nutrients, same.per100g, answer.grams)
            candidate = MicroFill.withMicros(candidate, same)
        }
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
     * The label the app found and the model's answer are one food: the model's brand
     * is on it, the last word of the model's name (its kind: "fries", "apple") is in
     * it, and the two agree on calories per gram within about a third.
     */
    private fun sameFood(local: ResolvedItem, answer: AiItemAnswer): Boolean {
        val c = local.choice ?: return true
        if (c.origin != Origin.LABEL) return true
        val have = LabelPackSource.words("${c.brand.orEmpty()} ${c.owner.orEmpty()} ${c.name}").toSet()
        val brand = LabelPackSource.words(answer.brand.orEmpty())
        if (brand.isNotEmpty() && brand.none { it in have }) return false
        val kind = LabelPackSource.words(answer.name).lastOrNull { w -> w.none { it.isDigit() } }
        if (kind != null && kind !in have) return false
        val lk = local.nutrients[Metric.CALORIES.key]
        val ak = answer.nutrients[Metric.CALORIES.key]
        val lg = local.grams?.takeIf { it > 0 }
        val ag = answer.grams?.takeIf { it > 0 }
        if (lk == null || ak == null || lg == null || ag == null) return true
        val mine = lk / lg
        val theirs = ak / ag
        return kotlin.math.abs(mine - theirs) <= maxOf(0.4, 0.35 * maxOf(mine, theirs))
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

    private fun describesPrevious(said: String): Boolean =
        Regex("^\\s*(?:and\\s+)?(?:it|that|which|they)\\s+(?:had|has|have|came with|comes with|was topped with|were topped with)\\b|" +
            "^\\s*(?:topped with|on it|on top)\\b").containsMatchIn(said.lowercase())

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
        // A flavour or kind they said must be on the label: "Great Value caramel sauce"
        // is not its barbecue sauce, though both are Great Value sauces.
        if (foodSaid.any { it in DISTINGUISHING && it !in descWithSynonyms }) return false
        // And the label must not be another kind of food: "whole milk" is not Whole Milk
        // Greek Yogurt, "whole wheat bread" not hamburger buns, a Milky Way bar not
        // Milky Way ice cream.
        if (foodSaid.isNotEmpty()) {
            val saidKinds = said + said.flatMap { LabelPackSource.synonymsOf(it) }
            val kinds = desc.filter { it in KIND_WORDS }
            if (kinds.isNotEmpty() && kinds.none { it in saidKinds }) return false
            if (desc.any { it in FORM_WORDS && it !in saidKinds }) return false
        }
        return when {
            foodSaid.isEmpty() -> true
            brand.isNotEmpty() || said.any { it in brandWords } -> shared >= 1
            else -> shared >= foodSaid.size * 0.6
        }
    }

    /** What a change said to an entry means, read with this resolver's word knowledge. */
    fun changes(text: String, lines: List<Changes.Line>): List<Changes.Change> =
        Changes.interpret(text, lines, ::isFoodWord) { w -> labels?.isBrandWord(w) == true }

    private fun isFoodWord(word: String): Boolean =
        usda?.knowsWord(word) == true || labels?.knowsWord(word) == true ||
            personal.all().any { word in it.key.split(" ") }

    private fun Map<String, Double>.times(f: Double) = mapValues { it.value * f }


    companion object {
        private val PACKAGE_UNITS = setOf("can", "bottle", "bag", "packet", "container", "bar")

        private val READS_PACKAGE = Regex("\\b(?:it says|is what it says|what it says|the (?:label|package|box|wrapper|bag) says|says on the)\\b")

        /** Flavours and kinds that make a different product when missing from a label. */
        private val DISTINGUISHING = setOf(
            "caramel", "chocolate", "vanilla", "strawberry", "blueberry", "raspberry", "peanut", "almond", "cinnamon",
            "honey", "maple", "bbq", "barbecue", "ranch", "cheddar", "mint", "coconut", "banana", "cherry", "lemon",
            "mango", "peach", "pumpkin", "coffee", "mocha", "salted", "spicy", "jalapeno", "garlic", "buffalo",
            "teriyaki", "sriracha", "pepperoni", "sausage", "bacon", "turkey", "chicken", "beef", "pork", "ham", "tuna",
            "salmon", "shrimp", "bean", "cheese", "egg", "skim", "nonfat", "decaf", "diet", "zero", "unsweetened",
        ).map { FoodQuery.stem(it) }.toSet()

        /** Words that name what a product IS. A label of another kind is another food. */
        private val KIND_WORDS = setOf(
            "yogurt", "bun", "roll", "bagel", "muffin", "cake", "cookie", "cracker", "chip", "cream", "sauce",
            "dressing", "dip", "bar", "cereal", "pudding", "smoothie", "shake", "powder", "candy", "pie",
            "pastry", "donut", "waffle", "pancake", "flapjack", "tortilla", "pizza", "soup", "jerky", "frosting",
            "syrup", "juice", "creamer", "cheesecake", "brownie", "granola",
        ).map { FoodQuery.stem(it) }.toSet()

        /** A form that changes the food entirely: Milky Way ice cream is not the candy bar. */
        private val FORM_WORDS = setOf("ice", "frozen", "powder", "creamer", "syrup", "smoothie")
            .map { FoodQuery.stem(it) }.toSet()

        /** What a word said means on a label: toast is their bread, oatmeal their oats. */
        private val SAID_ALIASES = mapOf("toast" to "bread", "oatmeal" to "oat", "yoghurt" to "yogurt")
            .mapKeys { FoodQuery.stem(it.key) }.mapValues { FoodQuery.stem(it.value) }

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
