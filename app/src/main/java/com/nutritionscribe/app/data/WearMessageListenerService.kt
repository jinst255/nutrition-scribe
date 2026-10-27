package com.nutritionscribe.app.data

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.shared.model.Confidence
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.sync.TodaySummary
import com.nutritionscribe.shared.sync.WatchSyncPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Phone-side relay: receives a raw entry logged on the watch, runs it through the same
 * pipeline as a phone entry (Section 4, watch flow step 5), and pushes the confirmed
 * entry plus an updated today-summary cache back to the watch.
 */
class WearMessageListenerService : com.google.android.gms.wearable.WearableListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WatchSyncPaths.MESSAGE_LOG_ENTRY) return
        val rawText = String(event.data)
        scope.launch {
            // A remembered food logs straight from the watch. A first-time one logs
            // on its best guess — today's total should not be missing it — and waits
            // on the phone for a glance; nothing is learned from it until then.
            val pipeline = ServiceLocator.logPipeline
            val tag = java.util.UUID.randomUUID().toString()
            ServiceLocator.debugEvents.event("watch_message", "text" to rawText, "tag" to tag)
            val r = pipeline.resolve(rawText, tag = tag, how = "watch")
            val entry = if (r.silent) pipeline.logNow(r, tag = tag) else pipeline.logProvisional(r, tag = tag)
            notifyWatch(entry)
            pushTodaySummary()
        }
    }

    private suspend fun notifyWatch(entry: Entry) {
        val nodes = Wearable.getNodeClient(this).connectedNodes.await()
        val payload = Json.encodeToString(entry).toByteArray()
        nodes.forEach { node ->
            Wearable.getMessageClient(this).sendMessage(node.id, WatchSyncPaths.MESSAGE_ENTRY_CONFIRMED, payload)
        }
    }

    private suspend fun pushTodaySummary() {
        val goal = ServiceLocator.settingsRepository.dailyGoalGrams.first()
        val startOfDay = com.nutritionscribe.shared.model.LogDays.start(com.nutritionscribe.shared.model.LogDays.today())
        val total = ServiceLocator.currentEntryRepository().observeProteinTotal(startOfDay, Long.MAX_VALUE).first()

        val summary = TodaySummary(total, goal, System.currentTimeMillis())
        val request = PutDataMapRequest.create(WatchSyncPaths.DATA_TODAY_SUMMARY).apply {
            dataMap.putDouble("proteinGrams", summary.proteinGrams)
            dataMap.putDouble("goalGrams", summary.goalGrams)
            dataMap.putLong("lastUpdatedEpochMillis", summary.lastUpdatedEpochMillis)
        }
        Wearable.getDataClient(this).putDataItem(request.asPutDataRequest().setUrgent()).await()
    }
}
