package com.nutritionscribe.app.data

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.model.GoalDirection
import com.nutritionscribe.shared.model.LogAgain
import com.nutritionscribe.shared.model.LogDays
import com.nutritionscribe.shared.model.NeedTip
import com.nutritionscribe.shared.model.NutrientDay
import com.nutritionscribe.shared.model.UsualMeals
import com.nutritionscribe.shared.sync.WatchProtocol
import com.nutritionscribe.shared.sync.WatchState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import java.time.ZoneId

/**
 * Keeps the watch's one data item ([WatchState]) current: the goals, what is
 * furthest behind with a free tip from their own log, Log again and usual meals,
 * and debug mode. Sent only when a watch is connected; nothing leaves the phone
 * otherwise. Note: the phone↔watch relay has never been tested on real hardware.
 */
object WatchSync {

    fun state(all: List<Entry>, goals: List<com.nutritionscribe.shared.model.MetricGoal>, debug: Boolean, now: Long): WatchState {
        val zone = ZoneId.systemDefault()
        val today = LogDays.today(zone, now)
        val todays = all.filter { LogDays.dayOf(it.timestampEpochMillis, zone) == today }
        val totals = NutrientDay.totals(todays)
        val on = goals.filter { it.enabled && it.target != null }.sortedBy { it.sortOrder }
        val goalRows = on.map { g ->
            WatchState.Goal(g.metric.shortLabel, totals[g.metric]?.amount ?: 0.0, g.target!!, g.metric.unit,
                limit = g.effectiveDirection == GoalDirection.LIMIT)
        }
        val behind = on.filter { it.effectiveDirection == GoalDirection.REACH }
            .minByOrNull { (totals[it.metric]?.amount ?: 0.0) / it.target!! }
        val need = behind?.let { g ->
            val have = totals[g.metric]?.amount ?: 0.0
            val target = g.target ?: return@let null
            WatchState.Need(g.metric.shortLabel, have, target, g.metric.unit,
                tip = NeedTip.of(g.metric, target - have, all, now))
        }
        val again = LogAgain.smart(LogAgain.choices(all, now, zone), now, limit = 6).map { c ->
            val e = c.entry
            val portion = e.items.singleOrNull()?.portion
            WatchState.Again(e.id, com.nutritionscribe.app.ui.common.logAgainName(e),
                listOfNotNull(portion, e.nutrients["calories"]?.let { "${Math.round(it)} kcal" }).joinToString(" · "))
        }
        val meals = UsualMeals.of(all, now, zone).map { m ->
            WatchState.Meal(m.entries.map { it.id }, m.label, m.names, Math.round(m.calories).toInt())
        }
        return WatchState(goalRows, need, again, meals, debug, now)
    }

    /** Builds and sends the state; quietly does nothing with no watch around. */
    suspend fun push(context: Context) {
        runCatching {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            if (nodes.isEmpty()) return
            val all = ServiceLocator.currentEntryRepository().observeEntries().first()
            val goals = ServiceLocator.goalsRepository.goals.first()
            val debug = ServiceLocator.settingsRepository.debugMode.first()
            val s = state(all, goals, debug, System.currentTimeMillis())
            val request = PutDataMapRequest.create(WatchProtocol.DATA_STATE).apply {
                dataMap.putString("json", WatchProtocol.encode(s))
                dataMap.putLong("at", s.updatedAt)
            }
            Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent()).await()
            ServiceLocator.debugEvents.event("watch_state_sent", "goals" to s.goals.size, "again" to s.again.size, "meals" to s.meals.size)
        }.onFailure {
            // No Wear OS app on this phone fails every time: say so once, not on every change.
            if (!warned) { warned = true; ServiceLocator.debugEvents.event("watch_state_failed", "error" to it) }
        }
    }

    @Volatile private var warned = false
}
