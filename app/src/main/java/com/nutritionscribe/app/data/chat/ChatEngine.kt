package com.nutritionscribe.app.data.chat

import com.nutritionscribe.app.data.db.toEntry
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.model.GoalDirection
import com.nutritionscribe.shared.model.LogAgain
import com.nutritionscribe.shared.model.LogDays
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.model.MetricGoal
import com.nutritionscribe.shared.model.Needs
import com.nutritionscribe.shared.model.NutrientDay
import com.nutritionscribe.shared.resolve.AskClient
import com.nutritionscribe.shared.resolve.OpenAiItemLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What an Ask bubble draws besides its words. */
@Serializable
data class ChatExtra(
    val ideas: List<ChatIdea> = emptyList(),
    /** "Where the tuna wrap puts you today". */
    val barsFor: String? = null,
    val bars: List<WhereBar> = emptyList(),
    /** "Lunch plan saved · not logged yet". */
    val planSaved: String? = null,
)

@Serializable
data class ChatIdea(val name: String, val nutrients: Map<String, Double>, val parts: List<PlanItem>)

@Serializable
data class PlanItem(val name: String, val grams: Double)

@Serializable
data class WhereBar(val metric: String, val now: Double, val after: Double, val target: Double)

/**
 * Ask's side of the conversation: what goes to the model, what comes back, and
 * the plan it may save. Nothing here logs food: a plan waits for Home's
 * "Did you have …?" after the meal.
 */
object ChatEngine {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    sealed interface Outcome {
        data class Answered(val chatId: String) : Outcome
        data class Failed(val chatId: String, val message: String) : Outcome
    }

    fun extraOf(row: ChatMessageRow): ChatExtra? = row.extraJson?.let { runCatching { json.decodeFromString(ChatExtra.serializer(), it) }.getOrNull() }

    fun itemsOf(plan: MealPlanRow): List<PlanItem> =
        runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(PlanItem.serializer()), plan.itemsJson) }.getOrDefault(emptyList())

    suspend fun send(chatId: String?, text: String): Outcome {
        val dao = ServiceLocator.database.chatDao()
        val now = System.currentTimeMillis()
        val id = chatId ?: java.util.UUID.randomUUID().toString()
        if (chatId == null || dao.chat(id) == null) dao.upsertChat(ChatRow(id, "New chat", now, now))
        dao.insertMessage(ChatMessageRow(chatId = id, role = "user", text = text.trim(), at = now))
        val events = ServiceLocator.debugEvents
        val client = ServiceLocator.askClient()
            ?: return Outcome.Failed(id, ServiceLocator.askBlocked() ?: "Ask isn't available right now.").also { fail(id, it.message) }
        val history = dao.messages(id).takeLast(16).map { AskClient.Turn(it.role, it.text) }
        val (context, today, refs) = context()
        events.event("ask_send", "chat" to id, "text" to text, "turns" to history.size)
        val reply = try {
            withContext(Dispatchers.IO) { ServiceLocator.usageMeter.withTag("ask:$id") { client.ask(context, history) } }
        } catch (e: OpenAiItemLookup.Failure) {
            val msg = when (e) {
                is OpenAiItemLookup.Failure.NoNetwork -> "No connection. Your question is kept; send it again when you're online."
                is OpenAiItemLookup.Failure.Auth -> "The AI key was refused. Check it in Settings › Advanced."
                else -> "Ask couldn't answer just now (${e.message}). Try again."
            }
            events.event("ask_failed", "chat" to id, "error" to e)
            return Outcome.Failed(id, msg).also { fail(id, msg) }
        }
        events.event("ask_reply", "chat" to id, "ideas" to reply.ideas.map { it.name }, "plan" to reply.plan?.ideaName)

        val ideas = reply.ideas.map { i -> ChatIdea(i.name, i.nutrients, i.parts.map { PlanItem(it.name, it.grams) }) }
        // The idea the bars are drawn for: the one just picked, or the first offered.
        val earlier = dao.messages(id).mapNotNull { extraOf(it) }.flatMap { it.ideas }
        val picked = reply.plan?.let { p -> (ideas + earlier).lastOrNull { it.name.equals(p.ideaName, ignoreCase = true) } ?: (ideas + earlier).firstOrNull { p.ideaName.contains(it.name, true) || it.name.contains(p.ideaName, true) } }
        var planSaved: String? = null
        if (reply.plan != null) {
            val p = reply.plan!!
            val zone = ZoneId.systemDefault()
            val at = planTime(p, zone, now)
            val name = picked?.name ?: p.ideaName
            val parts = picked?.parts ?: emptyList()
            dao.upsertPlan(MealPlanRow(
                id = java.util.UUID.randomUUID().toString(), chatId = id, name = name,
                itemsJson = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(PlanItem.serializer()), parts),
                mealLabel = p.meal.lowercase().ifBlank { "meal" }, planFor = at, status = "PLANNED", createdAt = now,
            ))
            planSaved = "${p.meal.replaceFirstChar { it.uppercase() }} plan saved · not logged yet"
            events.event("ask_plan_saved", "chat" to id, "name" to name, "for" to at)
        }
        val barsIdea = if (reply.plan == null) ideas.firstOrNull() else null
        val bars = barsIdea?.let { whereBars(it, today, refs) }.orEmpty()
        val extra = ChatExtra(ideas = ideas, barsFor = barsIdea?.name, bars = bars, planSaved = planSaved)
        dao.insertMessage(ChatMessageRow(chatId = id, role = "assistant", text = reply.text, at = System.currentTimeMillis(),
            extraJson = json.encodeToString(ChatExtra.serializer(), extra)))
        val chat = dao.chat(id)
        if (chat != null) dao.upsertChat(chat.copy(title = if (chat.title == "New chat" && reply.title.isNotBlank()) reply.title.take(40) else chat.title,
            updatedAt = System.currentTimeMillis()))
        return Outcome.Answered(id)
    }

    private suspend fun fail(id: String, message: String) {
        val dao = ServiceLocator.database.chatDao()
        dao.insertMessage(ChatMessageRow(chatId = id, role = "error", text = message, at = System.currentTimeMillis()))
    }

    /** When to ask "Did you have …?": the time they said, or the meal's usual time. */
    internal fun planTime(p: AskClient.Plan, zone: ZoneId, now: Long): Long {
        val today = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val (h, m) = when {
            p.hour != null -> p.hour!! to (p.minute ?: 0)
            p.meal.contains("breakfast", true) -> 8 to 0
            p.meal.contains("lunch", true) -> 12 to 30
            p.meal.contains("dinner", true) || p.meal.contains("supper", true) -> 18 to 30
            else -> today.plusMinutes(30).hour to today.plusMinutes(30).minute
        }
        var t = today.withHour(h.coerceIn(0, 23)).withMinute(m.coerceIn(0, 59)).withSecond(0)
        // "Lunch" said at 2 PM is today's, already eaten or about to be; never yesterday's.
        if (t.isBefore(today.minusHours(3))) t = t.plusDays(1)
        return t.atZone(zone).toInstant().toEpochMilli()
    }

    /** Today, the goals and needs, and what they usually eat: everything Ask is told. */
    private suspend fun context(): Triple<String, Map<Metric, Double>, Map<Metric, Double>> {
        val zone = ZoneId.systemDefault()
        val all = ServiceLocator.database.entryDao().observeAll().first().map { it.toEntry() }
        val day = LogDays.today(zone)
        val todayEntries = all.filter { LogDays.dayOf(it.timestampEpochMillis, zone) == day }.sortedBy { it.timestampEpochMillis }
        val goals = ServiceLocator.goalsRepository.goals.first()
        val profile = ServiceLocator.profileNow()
        val needs = Needs.forProfile(profile)
        val refs = Metric.entries.associateWith { m -> goals.firstOrNull { it.metric == m && it.enabled }?.target ?: needs.getValue(m) }
        val totals = NutrientDay.totals(todayEntries)
        val amounts = Metric.entries.associateWith { totals[it]?.amount ?: 0.0 }
        val time = DateTimeFormatter.ofPattern("EEEE h:mm a")
        val text = buildString {
            append("Now: ").append(LocalDateTime.now(zone).format(time)).append(".\n")
            profile?.let { append("About them: ${it.sex.name.lowercase()}, ${it.ageYears}, ${Math.round(it.weightKg)} kg, ${it.activity.name.lowercase()} activity, aim ${it.aim.name.lowercase()}.\n") }
            append("Logged today:")
            if (todayEntries.isEmpty()) append(" nothing yet.\n") else {
                append("\n")
                todayEntries.forEach { e ->
                    append("- ").append(if (e.timeUnspecified) "time not set" else Instant.ofEpochMilli(e.timestampEpochMillis).atZone(zone).format(DateTimeFormatter.ofPattern("h:mm a")))
                    append(" ").append(e.name).append(" (").append(e.calories?.let { "${Math.round(it)} kcal" } ?: "no numbers").append(")\n")
                }
            }
            append("Today vs their targets (goal = they chose it):\n")
            listOf(Metric.CALORIES, Metric.PROTEIN, Metric.CARBS, Metric.FAT, Metric.FIBER, Metric.ADDED_SUGAR, Metric.SODIUM).forEach { m ->
                append(line(m, amounts.getValue(m), refs.getValue(m), goals))
            }
            val shortMicros = com.nutritionscribe.shared.model.Extras.shown(Metric.MICROS).filter { it.direction == GoalDirection.REACH && (totals[it]?.trustworthy == true) }
                .sortedBy { amounts.getValue(it) / refs.getValue(it).coerceAtLeast(0.001) }.take(4)
            shortMicros.forEach { append(line(it, amounts.getValue(it), refs.getValue(it), goals)) }
            val often = LogAgain.often(LogAgain.choices(all, System.currentTimeMillis(), zone)).take(25)
            if (often.isNotEmpty()) {
                append("Foods they often log (use these when you can):\n")
                append(often.joinToString("; ") { it.entry.name.take(60) }).append("\n")
            }
        }
        return Triple(text, amounts, refs)
    }

    private fun line(m: Metric, have: Double, target: Double, goals: List<MetricGoal>): String {
        val g = goals.firstOrNull { it.metric == m && it.enabled }
        val dir = (g?.effectiveDirection ?: m.direction)
        return "- ${m.label}: ${Math.round(have)} of ${if (dir == GoalDirection.LIMIT) "at most " else ""}${Math.round(target)} ${m.unit}${if (g != null) " (goal)" else ""}\n"
    }

    /** Calories and the three things this idea helps most with, today. */
    private fun whereBars(idea: ChatIdea, today: Map<Metric, Double>, refs: Map<Metric, Double>): List<WhereBar> {
        val reach = idea.nutrients.mapNotNull { (k, v) -> Metric.fromKey(k)?.takeIf { it.direction == GoalDirection.REACH && it != Metric.CALORIES && v > 0 }?.let { it to v } }
            .filter { (m, _) -> today.getValue(m) < refs.getValue(m) }
            .sortedByDescending { (m, v) -> v / refs.getValue(m).coerceAtLeast(0.001) }.take(2)
        val kcal = idea.nutrients["calories"]?.let { Metric.CALORIES to it }
        return (reach + listOfNotNull(kcal)).map { (m, v) -> WhereBar(m.key, today.getValue(m), today.getValue(m) + v, refs.getValue(m)) }
    }
}
