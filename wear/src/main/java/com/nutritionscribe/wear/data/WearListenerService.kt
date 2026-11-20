package com.nutritionscribe.wear.data

import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.nutritionscribe.shared.sync.TodaySummary
import com.nutritionscribe.shared.sync.WatchProtocol
import com.nutritionscribe.shared.sync.WatchSyncPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WearListenerService : WearableListenerService() {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val repository by lazy { WearRepository(applicationContext) }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WatchProtocol.MESSAGE_REPLY) return
        val reply = WatchProtocol.decodeReply(event.data) ?: return
        WearRepository.onReply(reply)
        if (reply.kind == WatchProtocol.Kind.LOGGED) {
            WearRepository.lastLogged.value?.takeIf { it.requestId == reply.id }?.let {
                WearRepository.lastLogged.value = it.copy(ids = reply.loggedIds)
            }
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            val map = DataMapItem.fromDataItem(event.dataItem).dataMap
            when (event.dataItem.uri.path) {
                WatchProtocol.DATA_STATE -> map.getString("json")?.let { json ->
                    scope.launch { repository.cacheState(json); Refresh.all(applicationContext) }
                }
                WatchSyncPaths.DATA_TODAY_SUMMARY -> scope.launch {
                    repository.cacheTodaySummary(TodaySummary(map.getDouble("proteinGrams"), map.getDouble("goalGrams"), map.getLong("lastUpdatedEpochMillis")))
                }
            }
        }
    }
}
