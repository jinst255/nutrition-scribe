package com.nutritionscribe.app.data

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await

/**
 * Is there a watch with Nutrition Scribe on it, and is it in reach? For Home's
 * Watch button and Settings › Watch (P1, P2; approved 11-20). Asked of Wear OS
 * when those screens open; a phone with no Wear OS app answers "no watch".
 */
object WatchLink {
    /** Declared by the watch app (wear/res/values/wear.xml). */
    const val CAPABILITY = "nutrition_scribe_watch"

    data class Status(val paired: Boolean, val connected: Boolean, val name: String?)

    private val _status = MutableStateFlow(Status(false, false, null))
    val status: StateFlow<Status> = _status

    suspend fun refresh(context: Context): Status {
        val s = runCatching {
            val all = Wearable.getCapabilityClient(context).getCapability(CAPABILITY, CapabilityClient.FILTER_ALL).await().nodes
            val near = Wearable.getCapabilityClient(context).getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE).await().nodes
            Status(paired = all.isNotEmpty(), connected = near.isNotEmpty(), name = (near.firstOrNull() ?: all.firstOrNull())?.displayName)
        }.getOrDefault(Status(false, false, null))
        _status.value = s
        return s
    }
}
