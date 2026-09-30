package com.nutritionscribe.app.data.resolve

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
) {

    private fun resolver() = Resolver(personal, usda(), labels.source(), ai())

    suspend fun resolve(text: String): Resolver.Resolution = withContext(Dispatchers.IO) {
        val r = resolver().resolve(text)
        r.aiProblem?.let { debugLogger.log("LogPipeline", "AI lookup skipped: $it") }
        r
    }

    /** Resolve one item on its own — a fix, a breakdown part, a retyped name. */
    suspend fun resolveItem(text: String): List<Resolver.ResolvedItem> = withContext(Dispatchers.IO) {
        resolver().resolve(text).items
    }

    /** A silent hit: every part is the user's own, so it logs with no card. */
    suspend fun logNow(r: Resolver.Resolution, timestamp: Long = System.currentTimeMillis()): Entry =
        withContext(Dispatchers.IO) {
            val res = resolver()
            r.meal?.let { res.touch(it.key) }
            r.items.forEach { res.touch(it.key) }
            val entry = entryFor(r.text, r.items.map { it.toConfirmed(unchanged = true) }, r.meal?.name, timestamp)
            entryDao.upsert(entry.toRow(), entry.toNutrientRows())
            entry
        }

    /**
     * A first-time food from the watch: logged now on its best guess, so today's
     * total is not missing it, but flagged — nothing is learned from it until the
     * user has looked on the phone.
     */
    suspend fun logProvisional(r: Resolver.Resolution, timestamp: Long = System.currentTimeMillis()): Entry =
        withContext(Dispatchers.IO) {
            val entry = entryFor(r.text, r.items.map { it.toConfirmed(unchanged = it.status == Resolver.Status.REMEMBERED) }, null, timestamp)
                .copy(needsConfirm = true, needsDetail = r.items.any { it.status == Resolver.Status.UNKNOWN })
            entryDao.upsert(entry.toRow(), entry.toNutrientRows())
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
    ): Entry = withContext(Dispatchers.IO) {
        val res = resolver()
        res.confirm(text, items)
        if (!saveAsMine.isNullOrBlank()) {
            res.saveMeal(text, saveAsMine, items.map { MealPart(it.name, it.item.said, it.grams, it.nutrients) }, asName = saveAsMine)
        }
        val entry = entryFor(text, items, saveAsMine?.takeIf { it.isNotBlank() }, replacing?.timestampEpochMillis ?: timestamp)
            .let { e -> if (replacing != null) e.copy(id = replacing.id, timeUnspecified = replacing.timeUnspecified) else e }
        entryDao.upsert(entry.toRow(), entry.toNutrientRows())
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

    companion object {
        fun confirmedFrom(i: Resolver.ResolvedItem) = Resolver.Confirmed(
            item = i.item, key = i.key, name = i.displayName, candidate = i.choice, grams = i.grams,
            nutrients = i.nutrients, unchangedMemory = i.status == Resolver.Status.REMEMBERED,
        )
    }
}
