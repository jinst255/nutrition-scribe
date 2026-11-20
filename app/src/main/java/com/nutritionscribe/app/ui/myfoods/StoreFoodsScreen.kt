package com.nutritionscribe.app.ui.myfoods

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nutritionscribe.app.data.db.toEntry
import com.nutritionscribe.app.data.resolve.StorePacks
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.app.ui.home.StoreFoods
import com.nutritionscribe.app.ui.home.mb
import com.nutritionscribe.app.ui.theme.SectionLabelStyle
import com.nutritionscribe.shared.stores.Store
import com.nutritionscribe.shared.stores.StoreCatalog
import com.nutritionscribe.shared.stores.StoreSuggest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Settings › Store foods: extra package labels for the stores someone shops at.
 * People choose a store; where the data comes from is said once, at the bottom
 * (owner, 11-19: "let the user choose … or we abstract it for them").
 */
@Composable
fun StoreFoodsScreen() {
    val packs = ServiceLocator.storePacks
    val settings = ServiceLocator.settingsRepository
    val events = ServiceLocator.debugEvents
    val scope = rememberCoroutineScope()
    val installed by packs.installed.collectAsState()
    val remote by packs.remote.collectAsState()
    val jobs by packs.jobs.collectAsState()
    val suggestOn by settings.storeSuggest.collectAsState(initial = true)
    val builtIn by ServiceLocator.labelPack.status.collectAsState()
    var loading by remember { mutableStateOf(true) }
    var removing by remember { mutableStateOf<Store?>(null) }
    val suggestion by remember {
        combine(
            ServiceLocator.database.entryDao().observeAll().map { rows -> StoreFoods.of(rows.map { it.toEntry() }) },
            packs.installed, settings.storeSnoozes,
        ) { foods, inst, _ ->
            // The list itself shows the suggestion even when "Not now" was tapped on Home.
            StoreSuggest.suggest(foods, System.currentTimeMillis(), inst.map { it.store.id }.toSet(), emptyMap())
        }
    }.collectAsState(initial = null)

    LaunchedEffect(Unit) {
        events.event("store_foods_view", "installed" to installed.map { it.store.id })
        packs.refreshCatalog()
        loading = false
    }

    fun get(store: Store, where: String) {
        events.event("store_pack_download_tap", "store" to store.id, "where" to where)
        scope.launch {
            val ok = packs.download(store.id)
            events.event("store_pack_download", "store" to store.id, "ok" to ok, "where" to where,
                "problem" to (packs.jobs.value[store.id] as? StorePacks.Job.Failed)?.message)
        }
    }

    val installedIds = installed.map { it.store.id }.toSet()
    val suggested = suggestion?.store?.takeIf { it.id !in installedIds }
    val others = StoreCatalog.BUILT_IN.filter { it.id !in installedIds && it.id != suggested?.id }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Extra package labels for the stores you shop at. Their barcodes are found at once, even offline, " +
                    "and saying “Kirkland almonds” finds the real label.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { Label("ON THIS PHONE") }
        item {
            Card {
                val b = builtIn as? com.nutritionscribe.app.data.resolve.LabelPack.Status.Installed
                PackRow(
                    "Built-in labels",
                    if (b != null) "%,d products · always on".format(b.products) else "Not installed",
                )
                installed.forEach { p ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PackRow(p.store.title, "%,d products · %s".format(p.products, mb(p.bytes)) +
                        (if (p.built.isNotBlank()) " · ${p.built}" else "")) {
                        OutlinedButton(onClick = { removing = p.store }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove") }
                    }
                }
            }
        }
        if (suggested != null) {
            item { Label("SUGGESTED FOR YOU") }
            item {
                Card(highlight = true) {
                    StoreRow(suggested, remote.firstOrNull { it.id == suggested.id }, jobs[suggested.id],
                        why = "${suggestion!!.storeFoods} of your ${suggestion!!.packagedFoods} packaged foods this month",
                        primary = true) { get(suggested, "store_foods_suggested") }
                }
            }
        }
        item { Label("MORE STORES") }
        item {
            Card {
                others.forEachIndexed { i, s ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    StoreRow(s, remote.firstOrNull { it.id == s.id }, jobs[s.id], why = s.shop.takeIf { it != s.title }) {
                        get(s, "store_foods")
                    }
                }
            }
        }
        if (!loading && remote.isEmpty()) item {
            Text(
                "Couldn't reach the list of packs. Check the connection; Get tries again.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Suggest a store from what I eat", style = MaterialTheme.typography.bodyLarge)
                    Text("A card on Home when one store's brand is a big part of your packaged food.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = suggestOn, onCheckedChange = { on ->
                    events.event("setting_changed", "setting" to "store_suggest", "value" to on)
                    scope.launch { settings.setStoreSuggest(on) }
                })
            }
        }
        item {
            Text(
                "Data from Open Food Facts contributors (openfoodfacts.org), under the Open Database License. " +
                    "Labels change; if your package says something else, scanning its Nutrition Facts wins.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    removing?.let { s ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${s.title}?") },
            text = { Text("Foods you already logged keep their numbers. New scans of its barcodes will need the label again.") },
            confirmButton = {
                TextButton(onClick = {
                    events.event("store_pack_remove", "store" to s.id)
                    packs.remove(s.id)
                    removing = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep it") } },
        )
    }
}

@Composable
private fun Label(text: String) = Text(text, style = SectionLabelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Card(highlight: Boolean = false, content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) { Column { content() } }
}

@Composable
private fun PackRow(title: String, subtitle: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
private fun StoreRow(
    store: Store, remote: StorePacks.Remote?, job: StorePacks.Job?, why: String?,
    primary: Boolean = false, onGet: () -> Unit,
) {
    val count = remote?.products ?: store.approxProducts
    val size = remote?.bytes?.let { " · ${mb(it)}" }.orEmpty()
    Column(Modifier.fillMaxWidth()) {
        PackRow(store.title, (if (remote != null) "%,d products" else "about %,d products").format(count) + size +
            (why?.let { " · $it" }.orEmpty())) {
            if (job !is StorePacks.Job.Downloading) {
                if (primary) Button(onClick = onGet, modifier = Modifier.heightIn(min = 48.dp)) { Text("Get") }
                else OutlinedButton(onClick = onGet, modifier = Modifier.heightIn(min = 48.dp)
                    .semantics { contentDescription = "Get ${store.title}" }) { Text("Get") }
            }
        }
        when (job) {
            is StorePacks.Job.Downloading -> LinearProgressIndicator(
                progress = { if (job.total > 0) (job.bytes.toFloat() / job.total).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
            is StorePacks.Job.Failed -> Text(
                job.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
            null -> {}
        }
    }
}
