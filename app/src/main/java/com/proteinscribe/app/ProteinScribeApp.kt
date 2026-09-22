package com.proteinscribe.app

import android.app.Application
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.proteinscribe.app.di.ServiceLocator
import com.proteinscribe.app.work.RetentionCompactionWorker
import com.proteinscribe.app.work.RetryUnprocessedWorker
import java.util.concurrent.TimeUnit

class ProteinScribeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        scheduleBackgroundWork()
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
