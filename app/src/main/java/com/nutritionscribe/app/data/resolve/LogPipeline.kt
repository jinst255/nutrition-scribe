package com.nutritionscribe.app.data.resolve

import com.nutritionscribe.shared.model.NutrientSanity
import com.nutritionscribe.app.data.db.EntryDao
import com.nutritionscribe.app.data.db.toNutrientRows
import com.nutritionscribe.app.data.db.toRow
import com.nutritionscribe.app.ui.debuglog.DebugLogger
import com.nutritionscribe.shared.model.Confidence
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.model.LoggedItem
import com.nutritionscribe.shared.resolve.AiItemLookup
import com.nutritionscribe.shared.resolve.FoodCandidate
import com.nutritionscribe.shared.resolve.ItemParser
import com.nutritionscribe.shared.resolve.MealPart
import com.nutritionscribe.shared.resolve.Origin
import com.nutritionscribe.shared.resolve.PersonalKeys
import com.nutritionscribe.shared.resolve.Resolver
import com.nutritionscribe.shared.resolve.UsdaFoodSource
import com.nutritionscribe.shared.resolve.sumNutrients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The logging flow, end to end:
 *
 *   say it -> [resolve] -> every part remembered?  -> [logNow], no card
 *                        -> anything new?          -> the confirm card, then [confirm]
 *
 * and whatever the user confirms or corrects is written back to their own foods,
 * so the same food never asks twice.
 */
class LogPipeline(
    private val entryDao: EntryDao,
    private val personal: RoomPersonalStore,
    private val usda: () -> UsdaFoodSource?,
    private val labels: LabelPack,
    private val ai: () -> AiItemLookup?,
    private val debugLogger: DebugLogger,
    private val events: com.nutritionscribe.app.ui.debuglog.DebugEvents? = null,
    private val meter: com.nutritionscribe.app.data.usage.UsageMeter? = null,
) {

    private fun resolver() = Resolver(personal, usda(), labels.source(), ai())

    private fun <T> tagged(tag: String?, block: () -> T): T = meter?.withTag(tag, block) ?: block()

    /**
     * [tag] groups the AI calls made for one entry, so their cost can be pinned
     * to it once it is logged. [how] is for the debug log: voice, typed, edited…
     */
    suspend fun resolve(text: String, tag: String? = null, how: String = "typed"): Resolver.Resolution = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val r = tagged(tag) { resolver().resolve(text) }
        r.aiProblem?.let { debugLogger.log("LogPipeline", "AI lookup skipped: $it") }
        events?.event(
            "resolution", "how" to how, "text" to text, "ms" to System.currentTimeMillis() - t0, "tag" to tag,
            "silent" to r.silent, "used_ai" to r.usedAi, "ai_problem" to r.aiProblem, "ai_on" to (ai() != null),
            "label_pack" to labels.isInstalled, "meal" to r.meal?.name,
            "items" to r.items.map { describe(it) },
        )
        flagSuspect("resolution", text, r.items.map { Triple(it.displayName, it.nutrients, it.grams) })
        r
    }

    /** Resolve one item on its own — a fix, a breakdown part, a retyped name. */
    suspend fun resolveItem(text: String, tag: String? = null, why: String = "item"): List<Resolver.ResolvedItem> = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val items = tagged(tag) { resolver().resolve(text).items }
        events?.event("resolve_item", "why" to why, "text" to text, "ms" to System.currentTimeMillis() - t0,
            "items" to items.map { describe(it) })
        items
    }

    /** "actually 2 slices", "remove the cheese": what a change to these lines means. */
    suspend fun changes(text: String, lines: List<com.nutritionscribe.shared.resolve.Changes.Line>): List<com.nutritionscribe.shared.resolve.Changes.Change> =
        withContext(Dispatchers.IO) {
            val c = resolver().changes(text, lines)
            events?.event("change_read", "text" to text, "lines" to lines.map { it.name }, "changes" to c.map { it.toString() })
            c
        }

    /** A silent hit: every part is the user's own, so it logs with no card. */
    suspend fun logNow(
        r: Resolver.Resolution,
        timestamp: Long = System.currentTimeMillis(),
        tag: String? = null,
        timeUnspecified: Boolean = false,
    ): Entry =
        withContext(Dispatchers.IO) {
            val res = resolver()
            r.meal?.let { res.touch(it.key) }
            r.items.forEach { res.touch(it.key) }
            val entry = entryFor(r.text, r.items.map { it.toConfirmed(unchanged = true) }, r.meal?.name, timestamp)
                .copy(timeUnspecified = timeUnspecified)
            entryDao.upsert(entry.toRow(), entry.toNutrientRows())
            entry.items.forEach { countKind(it.key, it.candidateId) }
            meter?.attach(tag, entry.id)
            events?.event("logged", "how" to "silent", "entry_id" to entry.id, "name" to entry.name, "text" to r.text,
                "nutrients" to entry.nutrients, "items" to entry.items.map { it.name })
            events?.endFlow("logged", "entry_id" to entry.id)
            entry
        }

    /**
     * "I had another one of those": the same food, the same amounts, the time now.
     * Nothing is looked up and nothing is learned — it is a copy of something the
     * person already accepted.
     */
    suspend fun logAgain(
        original: Entry,
        timestamp: Long = System.currentTimeMillis(),
        timeUnspecified: Boolean = false,
    ): Entry = withContext(Dispatchers.IO) {
        val copy = original.copy(
            id = UUID.randomUUID().toString(),
            timestampEpochMillis = timestamp,
            timeUnspecified = timeUnspecified,
            needsConfirm = false,
        )
        entryDao.upsert(copy.toRow(), copy.toNutrientRows())
        val res = resolver()
        copy.items.forEach { runCatching { res.touch(it.key) }; countKind(it.key, it.candidateId) }
        events?.event("logged", "how" to "again", "entry_id" to copy.id, "from" to original.id, "name" to copy.name,
            "nutrients" to copy.nutrients)
        copy
    }

    /** One kind of a logged food, with its numbers for the amount that was logged. */
    data class KindOption(val label: String, val candidate: FoodCandidate, val nutrients: Map<String, Double>, val uses: Int)

    /**
     * The kinds a just-logged food comes in: [mine] are the ones they have had (the
     * logged one first), [more] the label pack's others. Null when there is only
     * the one kind, or the entry is more than one food.
     */
    data class KindChoice(
        val now: String,
        val mine: List<KindOption>,
        val more: List<KindOption>,
        val ask: String,
        val differ: Boolean,
        val spread: Double,
        /** The product without its kind: "Chobani Blended Nonfat Greek Yogurt". */
        val food: String,
        val brand: String?,
    )

    suspend fun kindsFor(entry: Entry): KindChoice? = withContext(Dispatchers.IO) {
        runCatching {
            val item = entry.items.singleOrNull() ?: return@runCatching null
            val food = personal.get(item.key)?.takeIf { !it.isMeal } ?: return@runCatching null
            val all = food.allKinds
            val current = all.firstOrNull { it.id == item.candidateId } ?: food.candidate ?: return@runCatching null
            fun option(c: FoodCandidate): KindOption {
                val label = com.nutritionscribe.shared.resolve.Kinds.label(c)
                return KindOption(label, c, kindNumbers(c, item), food.kindUses[label] ?: 0)
            }
            val mine = (listOf(current) + all.filter { it.id != current.id }).map(::option).filter { it.nutrients.isNotEmpty() }
            val pack = if (current.origin == Origin.LABEL && current.id.startsWith("label:")) labels.source()?.siblings(current).orEmpty() else emptyList()
            val more = pack.map(::option).filter { o -> o.nutrients.isNotEmpty() && mine.none { it.label.equals(o.label, ignoreCase = true) } }
            if (mine.size < 2 && more.isEmpty()) return@runCatching null
            val numbers = mine.map { it.nutrients }
            KindChoice(
                now = mine.first().label, mine = mine, more = more, ask = food.askKind,
                differ = com.nutritionscribe.shared.resolve.Kinds.differ(numbers),
                spread = com.nutritionscribe.shared.resolve.Kinds.spread(numbers),
                food = com.nutritionscribe.shared.resolve.Kinds.productName(current),
                brand = current.brand,
            )
        }.getOrNull()
    }

    private fun kindNumbers(c: FoodCandidate, item: LoggedItem): Map<String, Double> {
        val servings = when {
            item.grams != null && c.labelServingGrams != null && c.labelServingGrams!! > 0 -> item.grams!! / c.labelServingGrams!!
            else -> item.amount ?: 1.0
        }
        val n = com.nutritionscribe.shared.resolve.Kinds.nutrientsFor(c, item.grams, servings)
        return com.nutritionscribe.shared.resolve.Sugars.withAddedSugar(n, c.name, c.origin)
    }

    /**
     * "It was the strawberry one": the logged entry becomes that kind, in the same
     * amount, and it is the kind logged next time. The one it was stays in their
     * kinds, so going back is one tap too.
     */
    suspend fun swapKind(entry: Entry, kind: FoodCandidate, where: String): Entry = withContext(Dispatchers.IO) {
        val item = entry.items.singleOrNull() ?: return@withContext entry
        val n = kindNumbers(kind, item)
        if (n.isEmpty()) return@withContext entry
        val name = com.nutritionscribe.app.ui.confirm.ItemDraft.withBrand(kind, " — ")
        val newItem = item.copy(name = name, candidateId = kind.id, origin = kind.origin.name, nutrients = n)
        val updated = entry.copy(
            name = if (entry.name == item.name) name else entry.name,
            items = listOf(newItem),
            nutrients = totalOf(listOf(newItem)),
            source = kind.origin.name.lowercase(),
        )
        entryDao.upsert(updated.toRow(), updated.toNutrientRows())
        personal.get(item.key)?.takeIf { !it.isMeal }?.let { food ->
            val oldLabel = food.candidate?.let { com.nutritionscribe.shared.resolve.Kinds.label(it) }
            val newLabel = com.nutritionscribe.shared.resolve.Kinds.label(kind)
            val uses = food.kindUses.toMutableMap()
            uses[newLabel] = (uses[newLabel] ?: 0) + 1
            if (oldLabel != null && oldLabel != newLabel) uses[oldLabel] = ((uses[oldLabel] ?: 1) - 1).coerceAtLeast(0)
            val grams = food.grams
            val servings = if (grams != null && kind.labelServingGrams != null && kind.labelServingGrams!! > 0) grams / kind.labelServingGrams!! else food.amount ?: 1.0
            personal.put(food.copy(
                name = name,
                candidate = kind,
                nutrients = com.nutritionscribe.shared.resolve.Kinds.nutrientsFor(kind, grams, servings).ifEmpty { food.nutrients },
                kinds = food.allKinds.filter { it.id != kind.id }.distinctBy { com.nutritionscribe.shared.resolve.Kinds.label(it).lowercase() },
                kindUses = uses,
                photo = com.nutritionscribe.app.ui.scan.ScanPhotos.take(kind.id) ?: food.photo.takeIf { kind.id == food.candidate?.id },
                updatedAt = System.currentTimeMillis(),
            ))
        }
        events?.event("kind_swapped", "where" to where, "entry_id" to entry.id, "from" to item.candidateId, "to" to kind.id,
            "name" to name, "nutrients" to n)
        updated
    }

    /** One more log of this kind of a food, for "Vanilla · 12 times" (a confirm card counts in [Resolver.learn]). */
    private fun countKind(key: String, candidateId: String?) {
        val food = personal.get(key)?.takeIf { !it.isMeal && it.allKinds.size > 1 } ?: return
        val c = food.allKinds.firstOrNull { it.id == candidateId } ?: return
        val label = com.nutritionscribe.shared.resolve.Kinds.label(c)
        personal.put(food.copy(kindUses = food.kindUses + (label to (food.kindUses[label] ?: 0) + 1)))
    }

    /** How often a food asks which kind: see [com.nutritionscribe.shared.resolve.PersonalFood.askKind]. */
    suspend fun setAskKind(key: String, ask: String) = withContext(Dispatchers.IO) {
        personal.get(key)?.let { personal.put(it.copy(askKind = ask)) }
        events?.event("kind_ask", "key" to key, "ask" to ask)
    }

    suspend fun delete(entry: Entry) = withContext(Dispatchers.IO) {
        entryDao.delete(entry.toRow())
        events?.event("entry_deleted", "entry_id" to entry.id, "name" to entry.name)
    }

    /**
     * A first-time food from the watch: logged now on its best guess, so today's
     * total is not missing it, but flagged — nothing is learned from it until the
     * user has looked on the phone.
     */
    suspend fun logProvisional(r: Resolver.Resolution, timestamp: Long = System.currentTimeMillis(), tag: String? = null): Entry =
        withContext(Dispatchers.IO) {
            val entry = entryFor(r.text, r.items.map { it.toConfirmed(unchanged = it.status == Resolver.Status.REMEMBERED) }, null, timestamp)
                .copy(needsConfirm = true, needsDetail = r.items.any { it.status == Resolver.Status.UNKNOWN })
            entryDao.upsert(entry.toRow(), entry.toNutrientRows())
            meter?.attach(tag, entry.id)
            events?.event("logged", "how" to "watch_provisional", "entry_id" to entry.id, "name" to entry.name,
                "text" to r.text, "nutrients" to entry.nutrients)
            entry
        }

    /**
     * The entry was reported wrong, or a remembered line removed from a card: what
     * those words were taken to mean is forgotten (see [Resolver.forget]), so the
     * same mistake is not logged silently again tomorrow.
     */
    suspend fun forget(text: String?, keys: Collection<String>, why: String): Int = withContext(Dispatchers.IO) {
        val n = resolver().forget(text, keys)
        events?.event("forgot", "why" to why, "text" to text, "keys" to keys.toList(), "removed" to n)
        n
    }

    /**
     * Tapping Log on the confirm card, or saving a fix. Writes the entry (replacing
     * [replacing] when this is a correction) and teaches every item back.
     */
    suspend fun confirm(
        text: String,
        items: List<Resolver.Confirmed>,
        timestamp: Long = System.currentTimeMillis(),
        replacing: Entry? = null,
        saveAsMine: String? = null,
        tag: String? = null,
        how: String = "confirm",
        suggested: List<Resolver.Confirmed>? = null,
        timeUnspecified: Boolean = false,
    ): Entry = withContext(Dispatchers.IO) {
        val res = resolver()
        res.confirm(text, items)
        // A label scanned for this entry: its photo goes with the food, for My labels.
        for (c in items) {
            val photo = com.nutritionscribe.app.ui.scan.ScanPhotos.take(c.candidate?.id) ?: continue
            personal.get(c.key)?.let { f -> if (f.photo != photo) { com.nutritionscribe.app.ui.scan.ScanPhotos.delete(f.photo); personal.put(f.copy(photo = photo)) } }
        }
        if (!saveAsMine.isNullOrBlank()) {
            res.saveMeal(text, saveAsMine, items.map { MealPart(it.name, it.item.said, it.grams, it.nutrients) }, asName = saveAsMine)
        }
        val entry = entryFor(text, items, saveAsMine?.takeIf { it.isNotBlank() }, replacing?.timestampEpochMillis ?: timestamp)
            .let { e -> if (replacing != null) e.copy(id = replacing.id, timeUnspecified = replacing.timeUnspecified) else e.copy(timeUnspecified = timeUnspecified) }
        entryDao.upsert(entry.toRow(), entry.toNutrientRows())
        meter?.attach(tag, entry.id)
        events?.event(
            "logged", "how" to if (replacing != null) "fix" else how, "entry_id" to entry.id, "name" to entry.name,
            "text" to text, "saved_as_mine" to saveAsMine, "nutrients" to entry.nutrients,
            "items" to items.map { c -> mapOf("said" to c.item.said, "name" to c.name, "grams" to c.grams,
                "origin" to c.candidate?.origin, "candidate" to c.candidate?.id, "nutrients" to c.nutrients,
                "unchanged_memory" to c.unchangedMemory) },
            // What the app first offered, so a correction shows up as a difference.
            "suggested" to suggested?.map { c -> mapOf("said" to c.item.said, "name" to c.name, "grams" to c.grams,
                "candidate" to c.candidate?.id, "nutrients" to c.nutrients) },
            "replacing" to replacing?.let { mapOf("id" to it.id, "name" to it.name, "nutrients" to it.nutrients) },
        )
        flagSuspect("logged", text, items.map { Triple(it.name, it.nutrients, it.grams) })
        events?.endFlow("logged", "entry_id" to entry.id, "items" to items.size)
        entry
    }

    private fun entryFor(text: String, items: List<Resolver.Confirmed>, name: String?, timestamp: Long): Entry {
        val logged = items.map { c ->
            // Added sugar, where the label gave it or the kind of food settles it.
            val n = com.nutritionscribe.shared.resolve.Sugars.withAddedSugar(c.nutrients, c.name, c.candidate?.origin)
            LoggedItem(
                name = c.name,
                said = c.item.said,
                key = c.key,
                portion = portionText(c),
                grams = c.grams,
                origin = (c.candidate?.origin ?: Origin.MANUAL).name,
                candidateId = c.candidate?.id,
                nutrients = n,
                amount = c.item.amount,
                unit = c.item.unit,
            )
        }
        val stated = items.any { it.item.statedProteinG != null }
        return Entry(
            id = UUID.randomUUID().toString(),
            timestampEpochMillis = timestamp,
            rawText = text,
            name = name ?: logged.singleOrNull()?.name ?: logged.joinToString(", ") { it.name.substringBefore(",") }.take(80).ifBlank { text },
            nutrients = totalOf(logged),
            source = logged.map { it.origin.lowercase() }.distinct().joinToString("+").ifBlank { "manual" },
            confidence = if (stated && items.size == 1) Confidence.STATED else Confidence.ESTIMATED,
            needsDetail = items.isEmpty() || items.all { it.nutrients.isEmpty() },
            items = logged,
        )
    }

    /**
     * The entry's totals. Added sugar is only a total when every item with sugar
     * has a figure for it; a partial sum would pass for the whole day's added sugar
     * and hide the rest as natural.
     */
    private fun totalOf(items: List<LoggedItem>): Map<String, Double> {
        val total = sumNutrients(items.map { it.nutrients })
        val added = com.nutritionscribe.shared.model.Metric.ADDED_SUGAR.key
        val sugar = com.nutritionscribe.shared.model.Metric.SUGAR.key
        val partial = items.any { (it.nutrients[sugar] ?: 0.0) > 0.0 && added !in it.nutrients }
        return if (partial) total - added else total
    }

    private fun portionText(c: Resolver.Confirmed): String? {
        val amount = c.item.amount?.let { a -> if (a == a.toLong().toDouble()) a.toLong().toString() else "%.2g".format(a) }
        val said = listOfNotNull(amount, c.item.unit, c.item.size).joinToString(" ").ifBlank { null }
        val g = c.grams?.let { "${it.toInt()} g" }
        return listOfNotNull(said, g).joinToString(" · ").ifBlank { null }
    }

    /** The items of a logged entry, back as editable card lines. */
    fun toConfirmed(entry: Entry): List<Resolver.Confirmed> =
        entry.items.map { li ->
            // The food's own measures (cup, slice, tbsp), so the steppers can switch
            // to them. The numbers stay the entry's own, whatever was fixed by hand.
            val measures = runCatching {
                val id = li.candidateId.orEmpty()
                when {
                    id.startsWith("usda:") -> usda()?.byId(id.removePrefix("usda:").toLong())?.portions
                    id.startsWith("label:") -> labels.source()?.byId(id.removePrefix("label:").toLong())?.portions
                    else -> null
                }
            }.getOrNull().orEmpty()
            Resolver.Confirmed(
                item = ItemParser.Item(said = li.said, food = li.said, amount = li.amount, unit = li.unit),
                key = li.key,
                name = li.name,
                candidate = FoodCandidate(
                    id = li.candidateId ?: "mine:${li.key}",
                    name = li.name,
                    origin = runCatching { Origin.valueOf(li.origin) }.getOrDefault(Origin.MANUAL),
                    per100g = li.grams?.takeIf { it > 0 }?.let { g -> li.nutrients.mapValues { it.value * 100.0 / g } },
                    perServing = li.nutrients,
                    portions = measures,
                ),
                grams = li.grams,
                nutrients = li.nutrients,
            )
        }.ifEmpty {
            // Logged before entries had items (or typed by hand): one line, the whole entry.
            listOf(
                Resolver.Confirmed(
                    item = ItemParser.Item(said = entry.rawText.ifBlank { entry.name }, food = entry.rawText.ifBlank { entry.name }),
                    key = PersonalKeys.of(entry.rawText.ifBlank { entry.name }),
                    name = entry.name,
                    candidate = FoodCandidate(id = "manual:${entry.id}", name = entry.name, origin = Origin.MANUAL, perServing = entry.nutrients),
                    grams = null,
                    nutrients = entry.nutrients,
                ),
            )
        }

    private fun Resolver.ResolvedItem.toConfirmed(unchanged: Boolean) = Resolver.Confirmed(
        item = item, key = key, name = displayName, candidate = choice, grams = grams, nutrients = nutrients,
        unchangedMemory = unchanged,
    )

    private fun describe(i: Resolver.ResolvedItem): Map<String, Any?> = mapOf(
        "said" to i.item.said, "food" to i.item.food, "amount" to i.item.amount, "unit" to i.item.unit,
        "size" to i.item.size, "brand" to i.item.brand, "status" to i.status, "weak" to i.weak,
        "choice" to i.choice?.let { mapOf("id" to it.id, "name" to it.name, "brand" to it.brand, "origin" to it.origin) },
        "grams" to i.grams, "portion" to i.portionNote, "nutrients" to i.nutrients,
        "alternates" to i.alternates.take(4).map { "${it.origin}:${it.name}" },
        "offer_scan" to i.offerScan, "offer_breakdown" to i.offerBreakdown, "near_miss" to i.nearMiss?.name,
        "suspect" to NutrientSanity.codes(i.nutrients, i.grams).takeIf { it.isNotEmpty() },
    )

    /**
     * A separate, greppable line whenever something about to be shown or saved
     * doesn't add up, with enough around it to see where it came from.
     */
    private fun flagSuspect(where: String, text: String?, parts: List<Triple<String, Map<String, Double>, Double?>>) {
        val found = parts.mapNotNull { (name, n, g) ->
            NutrientSanity.check(n, g).takeIf { it.isNotEmpty() }?.let { issues ->
                mapOf("name" to name, "issues" to issues.map { it.code }, "nutrients" to n, "grams" to g)
            }
        }
        if (found.isNotEmpty()) events?.event("suspect", "where" to where, "text" to text, "items" to found)
    }

    companion object {
        fun confirmedFrom(i: Resolver.ResolvedItem) = Resolver.Confirmed(
            item = i.item, key = i.key, name = i.displayName, candidate = i.choice, grams = i.grams,
            nutrients = i.nutrients, unchangedMemory = i.status == Resolver.Status.REMEMBERED,
        )
    }
}
