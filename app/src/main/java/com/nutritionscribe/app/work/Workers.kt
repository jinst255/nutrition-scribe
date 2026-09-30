package com.nutritionscribe.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nutritionscribe.app.data.db.toModel
import com.nutritionscribe.app.di.ServiceLocator
import kotlinx.coroutines.flow.first

/** Background retry for the Unprocessed Queue (Section 4 step 9, Section 15). */
class RetryUnprocessedWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = ServiceLocator.currentEntryRepository()
        val pending = ServiceLocator.database.unprocessedDao().observeAll().first().map { it.toModel() }
        for (entry in pending) {
            repository.retryUnprocessed(entry)
        }
        return Result.success()
    }
}

/** Daily retention pass: compacts food detail and drops protein totals past their windows (Section 12). */
class RetentionCompactionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        val entryDao = ServiceLocator.database.entryDao()

        val detailWindow = ServiceLocator.settingsRepository.retainFoodDetails.first()
        detailWindow.cutoffEpochMillis(now)?.let { cutoff -> entryDao.compactDetailBefore(cutoff) }

        val totalsWindow = ServiceLocator.settingsRepository.retainProteinTotals.first()
        totalsWindow.cutoffEpochMillis(now)?.let { cutoff -> entryDao.deleteBefore(cutoff) }

        return Result.success()
    }
}
