package com.nutritionscribe.wear.data

import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.sync.TodaySummary
import com.nutritionscribe.shared.sync.WatchSyncPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class WearListenerService : WearableListenerService() {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val repository by lazy { WearRepository(applicationContext) }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WatchSyncPaths.MESSAGE_ENTRY_CONFIRMED) return
        val entry = runCatching { Json.decodeFromString<Entry>(String(event.data)) }.getOrNull() ?: return
        repository.onEntryConfirmed(entry)
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.dataItem.uri.path != WatchSyncPaths.DATA_TODAY_SUMMARY) return@forEach
            val map = DataMapItem.fromDataItem(event.dataItem).dataMap
            val summary = TodaySummary(
                proteinGrams = map.getDouble("proteinGrams"),
                goalGrams = map.getDouble("goalGrams"),
                lastUpdatedEpochMillis = map.getLong("lastUpdatedEpochMillis"),
            )
            scope.launch { repository.cacheTodaySummary(summary) }
        }
    }
}
