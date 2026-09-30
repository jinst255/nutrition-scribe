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
        copy.items.forEach { runCatching { res.touch(it.key) } }
        events?.event("logged", "how" to "again", "entry_id" to copy.id, "from" to original.id, "name" to copy.name,
            "nutrients" to copy.nutrients)
        copy
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
            LoggedItem(
                name = c.name,
                said = c.item.said,
                key = c.key,
                portion = portionText(c),
                grams = c.grams,
                origin = (c.candidate?.origin ?: Origin.MANUAL).name,
                candidateId = c.candidate?.id,
                nutrients = c.nutrients,
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
            nutrients = sumNutrients(items.map { it.nutrients }),
            source = logged.map { it.origin.lowercase() }.distinct().joinToString("+").ifBlank { "manual" },
            confidence = if (stated && items.size == 1) Confidence.STATED else Confidence.ESTIMATED,
            needsDetail = items.isEmpty() || items.all { it.nutrients.isEmpty() },
            items = logged,
        )
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
