package com.nutritionscribe.app

import android.app.Application
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.app.work.RetentionCompactionWorker
import com.nutritionscribe.app.work.RetryUnprocessedWorker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
class NutritionScribeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        followDayEnd()
        followExtras()
        recordCrashes()
        scheduleBackgroundWork()
    }

    /**
     * A crash is written down before the app dies, so "it just closed" becomes a
     * stack trace. The plain error log always gets it; the debug log too when on.
     */
    private fun recordCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                ServiceLocator.debugLogger.log("Crash", "${thread.name}: ${e.stackTraceToString().take(4000)}")
                ServiceLocator.debugEvents.event("crash", "thread" to thread.name, "error" to e)
                ServiceLocator.debugEvents.flush()
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Keeps [com.nutritionscribe.shared.model.LogDays] on the day end chosen in Goals › Streak. */
    /**
     * Goals › More to track, for every screen that lists nutrients. An extra that is
     * already a goal (omega-3, before it moved there) stays shown.
     */
    private fun followExtras() {
        val settings = ServiceLocator.settingsRepository
        val goals = ServiceLocator.goalsRepository.goals
        fun of(on: Set<String>, gs: List<com.nutritionscribe.shared.model.MetricGoal>) =
            on + gs.filter { it.enabled && com.nutritionscribe.shared.model.Extras.isExtra(it.metric) }.map { it.metric.key }
        com.nutritionscribe.shared.model.Extras.on = kotlinx.coroutines.runBlocking { of(settings.extras.first(), goals.first()) }
        kotlinx.coroutines.GlobalScope.launch {
            kotlinx.coroutines.flow.combine(settings.extras, goals) { on, gs -> of(on, gs) }
                .collect { com.nutritionscribe.shared.model.Extras.on = it }
        }
    }

    private fun followDayEnd() {
        val settings = ServiceLocator.settingsRepository
        com.nutritionscribe.shared.model.LogDays.endHour =
            kotlinx.coroutines.runBlocking { settings.streak.first().dayEndHour }
        kotlinx.coroutines.GlobalScope.launch {
            settings.streak.collect { com.nutritionscribe.shared.model.LogDays.endHour = it.dayEndHour }
        }
    }

    private fun scheduleBackgroundWork() {
        val workManager = WorkManager.getInstance(this)

        val retryRequest = PeriodicWorkRequestBuilder<RetryUnprocessedWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniquePeriodicWork(
            "retry-unprocessed",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            retryRequest,
        )

        val retentionRequest = PeriodicWorkRequestBuilder<RetentionCompactionWorker>(1, TimeUnit.DAYS).build()
        workManager.enqueueUniquePeriodicWork(
            "retention-compaction",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            retentionRequest,
        )
    }
}
