package com.nutritionscribe.app.ui.myfoods

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.app.ui.home.SectionLabel
import com.nutritionscribe.app.ui.scan.ScanPhotos
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.model.NutrientSanity
import com.nutritionscribe.shared.resolve.Origin
import com.nutritionscribe.shared.resolve.PersonalFood
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class LabelSort { USED, NAME, LOOK }

/**
 * Every package label they have scanned or picked, as a list to look through and
 * fix: the photo it came from, its numbers, and how often it is used. A label
 * whose numbers can't all be true is marked, so a misread found later is fixed
 * once, here, rather than on every log.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MyLabelsScreen() {
    val store = ServiceLocator.personalStore
    val events = ServiceLocator.debugEvents
    val labels by remember {
        ServiceLocator.database.personalFoodDao().observeAll().map { rows ->
            rows.mapNotNull { store.decode(it) }.filter { !it.isMeal && (it.candidate?.origin == Origin.LABEL || it.candidate?.id?.startsWith("kindguess:") == true) }
        }
    }.collectAsState(initial = null)
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(LabelSort.USED) }
    var open by remember { mutableStateOf<PersonalFood?>(null) }
    LaunchedEffect(Unit) { events.event("my_labels_view") }

    open?.let { f ->
        BackHandler { open = null }
        LabelDetail(f, onDone = { open = null })
        return
    }

    val all = labels ?: return
    if (all.isEmpty()) {
        Text(
            "No labels yet. Scan a package's Nutrition Facts once and it's kept here, with the photo, so you can check or fix it later.",
            modifier = Modifier.padding(24.dp),
            color = MaterialTheme.colorScheme.secondary,
        )
        return
    }
    val looks = all.filter { needsLook(it) != null }
    val shown = all
        .filter { query.isBlank() || "${it.name} ${it.candidate?.brand.orEmpty()}".contains(query.trim(), ignoreCase = true) }
        .filter { sort != LabelSort.LOOK || needsLook(it) != null }
        .let { list -> if (sort == LabelSort.NAME) list.sortedBy { it.name.lowercase() } else list.sortedByDescending { it.timesUsed } }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search your labels") },
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = sort == LabelSort.USED, onClick = { sort = LabelSort.USED }, label = { Text("Most used") })
                FilterChip(selected = sort == LabelSort.NAME, onClick = { sort = LabelSort.NAME }, label = { Text("A–Z") })
                if (looks.isNotEmpty()) FilterChip(selected = sort == LabelSort.LOOK, onClick = { sort = LabelSort.LOOK },
                    label = { Text("Needs a look · ${looks.size}") })
            }
        }
        // Two to a row, each with its photo (note S8): a label with none says so and how to add one.
        items(shown.chunked(2), key = { row -> row.joinToString { it.key } }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { f -> LabelCard(f, Modifier.weight(1f)) { events.event("my_label_open", "key" to f.key); open = f } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item {
            Text("Tap a label to see the photo you took and its numbers, and to fix any of them.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(vertical = 12.dp))
        }
    }
}

/** What is wrong with a saved label, in a few words, or null. */
private fun needsLook(f: PersonalFood): String? {
    val grams = f.candidate?.labelServingGrams ?: f.grams
    val per = f.candidate?.let { c -> grams?.let { c.nutrientsFor(it) }?.takeIf { it.isNotEmpty() } ?: c.perServing } ?: f.nutrients
    NutrientSanity.check(per, grams).firstOrNull()?.let { return "${it.message}. Tap to fix" }
    if (f.candidate?.id?.startsWith("kindguess:") == true) return "Numbers borrowed from another kind. Scan its label"
    return null
}

@Composable
private fun LabelCard(f: PersonalFood, modifier: Modifier, onClick: () -> Unit) {
    val look = needsLook(f)
    Column(modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(120.dp)) {
            if (f.photo != null) LabelPhoto(f.photo, Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)))
            else Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Outlined.CameraAlt, null, tint = MaterialTheme.colorScheme.secondary)
                    Text(if (f.candidate?.id?.startsWith("label:") == true) "From the label list" else "No photo",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                    Text("Scan it to add one", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                }
            }
            if (look != null) Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Text("Needs a look", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
        Text(f.name, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(look ?: (summary(f) + " \u00B7 " + if (f.timesUsed == 1) "used once" else "used ${f.timesUsed}\u00D7"), fontSize = 12.sp,
            color = if (look != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary, maxLines = 2)
    }
}

@Composable
private fun LabelRow(f: PersonalFood, onClick: () -> Unit) {
    val look = needsLook(f)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = if (look != null) BorderStroke(2.dp, MaterialTheme.colorScheme.error) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            LabelPhoto(f.photo, Modifier.size(width = 44.dp, height = 56.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(f.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(look ?: summary(f), fontSize = 13.sp,
                    color = if (look != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                    fontWeight = if (look != null) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2)
                Text(if (f.timesUsed == 1) "Used once" else "Used ${f.timesUsed} times", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

private fun summary(f: PersonalFood): String {
    val c = f.candidate
    val serving = c?.labelServingText ?: c?.labelServingGrams?.let { "${it.toInt()} g" }
    val kcal = c?.labelServingGrams?.let { c.nutrientsFor(it)[Metric.CALORIES.key] } ?: c?.perServing?.get(Metric.CALORIES.key)
    return listOfNotNull(c?.brand, serving, kcal?.let { "${Math.round(it)} kcal" }).joinToString(" · ")
}

@Composable
internal fun LabelPhoto(name: String?, modifier: Modifier) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, name) { value = withContext(Dispatchers.IO) { ScanPhotos.load(name) } }
    val b = bitmap
    Box(
        modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (b != null) Image(b.asImageBitmap(), contentDescription = "The label you scanned", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Outlined.ReceiptLong, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
    }
}

/**
 * One label: the photo, its numbers for one serving as the label prints them, all
 * editable in place, and how the kinds of it are asked about. Saving rewrites the
 * food, so the next log uses the fixed numbers; past entries are left as they were.
 */
@Composable
internal fun LabelDetail(food: PersonalFood, onDone: () -> Unit) {
    val store = ServiceLocator.personalStore
    val events = ServiceLocator.debugEvents
    val scope = rememberCoroutineScope()
    val c = food.candidate
    val servingG = c?.labelServingGrams
    // Edited per serving of the label, the way it is printed.
    val start = remember(food) { c?.let { cand -> servingG?.let { cand.nutrientsFor(it) } ?: cand.perServing } ?: food.nutrients }
    var name by remember { mutableStateOf(food.name) }
    var serving by remember { mutableStateOf(servingG?.let { fmt(it) }.orEmpty()) }
    val values = remember {
        // As a label prints them: whole numbers from 10 up, one decimal below.
        mutableStateMapOf<Metric, String>().apply { Metric.entries.forEach { m -> start[m.key]?.let { put(m, fmt(if (it >= 10) Math.round(it).toDouble() else Math.round(it * 10) / 10.0)) } } }
    }
    var zoom by remember { mutableStateOf(false) }
    var forget by remember { mutableStateOf(false) }
    val photo by produceState<android.graphics.Bitmap?>(null, food.photo) { value = withContext(Dispatchers.IO) { ScanPhotos.load(food.photo) } }
    val now = values.mapNotNull { (m, v) -> v.toDoubleOrNull()?.let { m.key to it } }.toMap()
    val issues = NutrientSanity.check(now, serving.toDoubleOrNull())
    val suggested = NutrientSanity.suggest(now, serving.toDoubleOrNull())

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            LabelPhoto(food.photo, Modifier.size(width = 84.dp, height = 110.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                .clickable(enabled = photo != null) { zoom = true })
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (food.timesUsed == 1) "Used once" else "Used ${food.timesUsed} times", fontWeight = FontWeight.SemiBold)
                Text(
                    if (photo != null) "Tap the photo to check a number against it." else "No photo kept for this one. Scan it again to keep one.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = serving, onValueChange = { serving = it }, label = { Text("Serving (grams)") }, singleLine = true,
            supportingText = { Text(c?.labelServingText?.let { "Label says: $it" } ?: "The weight of one serving, as the label prints it") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        SectionLabel("Per serving")
        (Metric.MACROS + Metric.MICROS.filter { it.key in start }).distinct().forEach { m ->
            val issue = issues.firstOrNull { it.metrics.firstOrNull() == m.key }
            val likely = suggested[m.key]
            OutlinedTextField(
                value = values[m].orEmpty(), onValueChange = { values[m] = it },
                label = { Text("${m.panelLabel} (${m.unit})") }, singleLine = true, isError = issue != null,
                supportingText = issue?.let { i -> { Text(if (likely != null) "${i.message}. Probably ${fmt(likely)}." else i.message) } },
                trailingIcon = if (likely != null) ({ TextButton(onClick = { values[m] = fmt(likely) }) { Text("Use ${fmt(likely)}") } }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
            )
        }
        KindSettings(food)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = {
                val g = serving.toDoubleOrNull()?.takeIf { it > 0 }
                val per = now
                val cand = c?.copy(
                    name = name.trim().ifBlank { c.name },
                    perServing = per,
                    per100g = g?.let { gg -> per.mapValues { it.value * 100.0 / gg } } ?: c.per100g,
                    labelServingGrams = g ?: c.labelServingGrams,
                    defaultGrams = g ?: c.defaultGrams,
                )
                // Their usual amount keeps its size; its numbers follow the fixed label.
                val usual = cand?.let { cc -> food.grams?.let { cc.nutrientsFor(it).takeIf { n -> n.isNotEmpty() } } }
                    ?: food.amount?.let { a -> per.mapValues { it.value * a } } ?: per
                val changed = per.filter { (k, v) -> start[k]?.let { Math.round(it * 10) != Math.round(v * 10) } ?: true }
                events.event("my_label_saved", "key" to food.key, "changed" to changed, "serving" to g)
                scope.launch {
                    withContext(Dispatchers.IO) {
                        store.put(food.copy(name = name.trim().ifBlank { food.name }, candidate = cand, nutrients = usual, updatedAt = System.currentTimeMillis()))
                    }
                    onDone()
                }
            }) { Text("Save") }
            OutlinedButton(onClick = onDone) { Text("Cancel") }
        }
        TextButton(onClick = { forget = true }) { Text("Forget this label", color = MaterialTheme.colorScheme.error) }
        Text("Saving changes how it logs from now on. Past entries are not changed.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary)
    }

    if (zoom) photo?.let { b ->
        Dialog(onDismissRequest = { zoom = false }) {
            Surface(shape = RoundedCornerShape(16.dp)) {
                Image(b.asImageBitmap(), contentDescription = "The label you scanned", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().clickable { zoom = false })
            }
        }
    }
    if (forget) {
        AlertDialog(
            onDismissRequest = { forget = false },
            title = { Text("Forget this label?") },
            text = { Text("Next time you log it, it will ask again. Your past entries are not changed.") },
            confirmButton = {
                TextButton(onClick = {
                    events.event("my_label_forgot", "key" to food.key)
                    scope.launch {
                        withContext(Dispatchers.IO) { store.remove(food.key); ScanPhotos.delete(food.photo) }
                        onDone()
                    }
                }) { Text("Forget", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { forget = false }) { Text("Keep") } },
        )
    }
}

/**
 * "Your kinds" and "When I log it": the kinds of this food they have had, and
 * whether logging it asks which one. Only shown for a food that has kinds.
 */
@Composable
fun KindSettings(food: PersonalFood) {
    val scope = rememberCoroutineScope()
    var ask by remember(food.key) { mutableStateOf(food.askKind) }
    val kinds = food.allKinds
    if (kinds.size < 2) return
    val numbers = kinds.map { k -> com.nutritionscribe.shared.resolve.Kinds.nutrientsFor(k, food.grams, food.amount ?: 1.0) }
    val differ = com.nutritionscribe.shared.resolve.Kinds.differ(numbers)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
        SectionLabel("Your kinds")
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                kinds.forEachIndexed { i, k ->
                    val label = com.nutritionscribe.shared.resolve.Kinds.label(k)
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(label, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Text(numbers[i][Metric.CALORIES.key]?.let { "${Math.round(it)} kcal" }.orEmpty(),
                            style = com.nutritionscribe.app.ui.theme.NumberStyle, color = MaterialTheme.colorScheme.secondary)
                        val uses = food.kindUses[label] ?: 0
                        if (uses > 0) Text(if (uses == 1) "once" else "$uses times", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
        SectionLabel("When I log it", Modifier.padding(top = 10.dp))
        listOf(
            PersonalFood.ASK_WHEN_DIFFERENT to ("Ask only when the kinds differ" to
                if (differ) "These differ, so the Logged card shows your kinds to tap." else "These are close, so it logs your last kind and keeps it one tap away."),
            PersonalFood.ASK_ALWAYS to ("Always ask" to "Shows your kinds every time."),
            PersonalFood.ASK_NEVER to ("Never ask" to "Logs the last kind. You can still say the flavor."),
        ).forEach { (value, text) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                    ask = value
                    scope.launch { ServiceLocator.logPipeline.setAskKind(food.key, value) }
                }.padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top,
            ) {
                androidx.compose.material3.RadioButton(selected = ask == value, onClick = null, modifier = Modifier.padding(top = 2.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text.first, fontSize = 16.sp)
                    Text(text.second, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary, lineHeight = 18.sp)
                }
            }
        }
        Text("Kinds come from the label list and your own scans. A new one is added the first time you pick it or scan its label.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 4.dp))
    }
}

private fun fmt(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(java.util.Locale.ROOT, "%.1f", v)
