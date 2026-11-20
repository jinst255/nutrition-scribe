package com.nutritionscribe.app.data

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.shared.model.Confidence
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.sync.TodaySummary
import com.nutritionscribe.shared.sync.WatchProtocol
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
        when (event.path) {
            WatchProtocol.MESSAGE_REQUEST -> WatchProtocol.decodeRequest(event.data)?.let { r -> scope.launch { answer(event.sourceNodeId, r) } }
            WatchProtocol.MESSAGE_DEBUG -> WatchProtocol.decodeDebug(event.data)?.let { batch ->
                // Phone in debug mode: the watch's own events join the phone's log, marked as the watch's.
                batch.events.forEach { e ->
                    ServiceLocator.debugEvents.event("watch_" + e.name, e.data + ("watch_at" to e.at.toString()))
                }
            }
            WatchSyncPaths.MESSAGE_LOG_ENTRY -> legacy(String(event.data))
        }
    }

    /** The older watch app sends bare text and logs at once. */
    private fun legacy(rawText: String) {
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

    private suspend fun answer(node: String, req: WatchProtocol.Request) {
        val pipeline = ServiceLocator.logPipeline
        val events = ServiceLocator.debugEvents
        events.event("watch_request", "kind" to req.kind, "id" to req.id, "text" to req.text, "ids" to req.ids, "at" to req.at)
        val reply: WatchProtocol.Reply = runCatching {
            when (req.kind) {
                WatchProtocol.Kind.RESOLVE -> {
                    val text = req.text.orEmpty()
                    val r = pipeline.resolve(text, tag = req.id, how = "watch")
                    if (WatchReplies.tooTricky(r)) {
                        // Kept on the phone, at the time it was said, to fix with AI there.
                        pipeline.logProvisional(r, timestamp = req.at, tag = req.id)
                        WatchProtocol.Reply(req.id, WatchProtocol.Kind.TRICKY, heard = text)
                    } else {
                        pending[req.id] = r
                        WatchReplies.heard(req.id, text, r)
                    }
                }
                WatchProtocol.Kind.LOG -> {
                    val r = pending.remove(req.id) ?: pipeline.resolve(req.text.orEmpty(), tag = req.id, how = "watch")
                    val e = if (r.silent) pipeline.logNow(r, timestamp = req.at, tag = req.id)
                        else pipeline.logProvisional(r, timestamp = req.at, tag = req.id)
                    WatchProtocol.Reply(req.id, WatchProtocol.Kind.LOGGED, loggedIds = listOf(e.id), lines = WatchReplies.lines(r))
                }
                WatchProtocol.Kind.LOG_AGAIN -> {
                    val repo = ServiceLocator.currentEntryRepository()
                    val all = repo.observeEntries().first().associateBy { it.id }
                    val added = req.ids.mapNotNull { all[it] }.map { pipeline.logAgain(it, req.at, false) }
                    WatchProtocol.Reply(req.id, WatchProtocol.Kind.LOGGED, loggedIds = added.map { it.id },
                        lines = added.map { WatchProtocol.Line(it.name, "", Math.round(it.nutrients["calories"] ?: 0.0).toInt()) })
                }
                WatchProtocol.Kind.UNDO -> {
                    val all = ServiceLocator.currentEntryRepository().observeEntries().first().associateBy { it.id }
                    req.ids.mapNotNull { all[it] }.forEach { pipeline.delete(it) }
                    WatchProtocol.Reply(req.id, WatchProtocol.Kind.LOGGED)
                }
                else -> WatchProtocol.Reply(req.id, WatchProtocol.Kind.FAILED, message = "Update the phone app")
            }
        }.getOrElse { e ->
            events.event("watch_request_failed", "kind" to req.kind, "error" to e)
            WatchProtocol.Reply(req.id, WatchProtocol.Kind.FAILED, message = "The phone couldn't work that out")
        }
        runCatching { Wearable.getMessageClient(this).sendMessage(node, WatchProtocol.MESSAGE_REPLY, WatchProtocol.encode(reply)).await() }
        WatchSync.push(this)
        pushTodaySummary()
    }

    companion object {
        /** Resolved, waiting for the watch's Log it. In memory: a lost one is resolved again from its words. */
        private val pending = java.util.concurrent.ConcurrentHashMap<String, com.nutritionscribe.shared.resolve.Resolver.Resolution>()
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
