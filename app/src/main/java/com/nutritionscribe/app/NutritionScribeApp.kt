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

class NutritionScribeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
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
