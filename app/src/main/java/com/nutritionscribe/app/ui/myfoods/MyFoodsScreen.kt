package com.nutritionscribe.app.ui.myfoods

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.app.ui.home.SectionLabel
import com.nutritionscribe.app.ui.theme.NumberStyle
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.resolve.FoodCandidate
import com.nutritionscribe.shared.resolve.ItemParser
import com.nutritionscribe.shared.resolve.Origin
import com.nutritionscribe.shared.resolve.PersonalFood
import com.nutritionscribe.shared.resolve.PersonalKeys
import com.nutritionscribe.shared.resolve.Resolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class FoodFilter(val label: String) { ALL("All"), RECIPES("Recipes"), MEALS("Meals"), KINDS("With kinds") }
private enum class FoodSort(val label: String) { USED("Most used"), RECENT("Recent"), NAME("A–Z") }

/**
 * Everything the app has learned from the user (note B1): what each phrase means
 * to them, and how much they usually have. Theirs to find, open, change, add to and
 * forget — this is what logs silently, so all of it has to be inspectable.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MyFoodsScreen(
    onLog: (String) -> Unit = {},
    onScanToSave: () -> Unit = {},
    /** Check it once in recipe mode, set up in LogSession. */
    onRecipe: () -> Unit = {},
    startOnLabels: Boolean = false,
) {
    val store = ServiceLocator.personalStore
    val events = ServiceLocator.debugEvents
    val foods by remember {
        ServiceLocator.database.personalFoodDao().observeAll().map { rows -> rows.mapNotNull { store.decode(it) } }
    }.collectAsState(initial = null)
    var tab by remember { mutableStateOf(if (startOnLabels) 1 else 0) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(FoodFilter.ALL) }
    var sort by remember { mutableStateOf(FoodSort.USED) }
    var sortOpen by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var addHow by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { events.event("my_foods_view") }

    val all = foods ?: return
    open?.let { key ->
        val f = all.firstOrNull { it.key == key }
        if (f == null) { open = null; return@let }
        BackHandler { open = null }
        FoodPage(f, onLog = { open = null; onLog(f.phrase) }, onClose = { open = null }, onEditRecipe = {
            val res = ServiceLocator.logPipeline
            com.nutritionscribe.app.ui.confirm.LogSession.startRecipe(
                com.nutritionscribe.shared.resolve.Recipes.activeParts(f, System.currentTimeMillis()).map { p ->
                    com.nutritionscribe.app.ui.confirm.ItemDraft.from(res.partLine(p))
                }, f.name)
            com.nutritionscribe.app.ui.confirm.LogSession.recipe = com.nutritionscribe.app.ui.confirm.LogSession.RecipeMode(f.name)
            events.event("recipe_edit_open", "key" to f.key)
            open = null
            onRecipe()
        })
        return
    }

    val labels = all.filter { isLabel(it) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
                listOf("Foods · ${all.size}", "Labels · ${labels.size}").forEachIndexed { i, t ->
                    SegmentedButton(selected = tab == i, onClick = { tab = i }, shape = SegmentedButtonDefaults.itemShape(i, 2), icon = {},
                        modifier = Modifier.heightIn(min = 44.dp)) { Text(t) }
                }
            }
            if (tab == 1) {
                MyLabelsScreen()
                return@Column
            }
            if (all.isEmpty()) {
                Text("Nothing yet. Every food you confirm is remembered here, so the next time you log it, it goes straight in. " +
                    "Or add one now with Add a food.", modifier = Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.secondary)
                return@Column
            }
            val shown = all
                .filter { query.isBlank() || "${it.name} ${it.phrase} ${it.candidate?.brand.orEmpty()}".contains(query.trim(), ignoreCase = true) }
                .filter { when (filter) { FoodFilter.ALL -> true; FoodFilter.RECIPES -> it.isRecipe; FoodFilter.MEALS -> it.isMeal; FoodFilter.KINDS -> it.allKinds.size > 1 } }
                .let { list -> when (sort) {
                    FoodSort.USED -> list.sortedByDescending { it.timesUsed }
                    FoodSort.RECENT -> list.sortedByDescending { it.updatedAt }
                    FoodSort.NAME -> list.sortedBy { it.name.lowercase() }
                } }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                item {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        placeholder = { Text("Search my foods") }, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // The chips scroll in their own space: with Recipes added, the sort button was
                        // squeezed to nothing and its text stood one letter a line, a tall gap above and below.
                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FoodFilter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label, maxLines = 1) }) }
                        }
                        Box {
                            TextButton(onClick = { sortOpen = true }) {
                                Text(sort.label, maxLines = 1, softWrap = false); Icon(Icons.Filled.ArrowDropDown, null)
                            }
                            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                                FoodSort.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label) }, onClick = { sort = s; sortOpen = false }) }
                            }
                        }
                    }
                }
                item {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 12.dp)) {
                            if (shown.isEmpty()) Text("Nothing matches.", modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.secondary)
                            shown.forEachIndexed { i, f ->
                                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { events.event("my_food_open", "key" to f.key); open = f.key }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    FoodThumb(f, Modifier.size(44.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(com.nutritionscribe.app.ui.common.shownFoodName(f.name), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(rowSub(f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1,
                                            overflow = TextOverflow.Ellipsis)
                                    }
                                    Text("${f.timesUsed}×", style = NumberStyle, color = MaterialTheme.colorScheme.secondary)
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                // Room under the last row, so the button never covers it.
                item { Spacer(Modifier.height(88.dp)) }
            }
        }
        if (tab == 0) ExtendedFloatingActionButton(
            onClick = { adding = true },
            icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add a food") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (adding) {
        com.nutritionscribe.app.ui.feedback.ReportableSheet("Add a food", onDismissRequest = { adding = false }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Add a food", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(horizontal = 6.dp)) {
                        AddOption(Icons.Outlined.CameraAlt, "Scan its label", "The barcode or the Nutrition Facts") { adding = false; onScanToSave() }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        AddOption(Icons.Outlined.Mic, "Say or type it", "Saves it without logging it. Several foods are saved as a meal") { adding = false; addHow = "say" }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        AddOption(Icons.Outlined.Keyboard, "Enter the numbers myself", "From a package or a website") { adding = false; addHow = "numbers" }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        AddOption(Icons.Outlined.Layers, "A recipe", "Type, say or photograph the ingredients; log it a bowl at a time") { adding = false; addHow = "recipe" }
                    }
                }
            }
        }
    }
    when (addHow) {
        "say" -> SayToSave(onDone = { text ->
            addHow = null
            scope.launch {
                val items = ServiceLocator.logPipeline.resolveItem(text, why = "my_foods_add").filter { it.nutrients.isNotEmpty() }
                if (items.isEmpty()) {
                    com.nutritionscribe.app.ui.common.AppMessages.say("Couldn't work that out. Try other words, or enter the numbers.", long = true)
                } else {
                    ServiceLocator.logPipeline.remember(text, items.map { com.nutritionscribe.app.data.resolve.LogPipeline.confirmedFrom(it).copy(unchangedMemory = false) }, "said")
                    com.nutritionscribe.app.ui.common.AppMessages.say("Saved to My foods. Say “$text” to log it.", long = true)
                }
            }
        }, onDismiss = { addHow = null })
        "recipe" -> NewRecipeDialog(onNext = { name, ingredients ->
            addHow = "recipe_working"
            scope.launch {
                val tag = java.util.UUID.randomUUID().toString()
                val r = ServiceLocator.logPipeline.resolve(ingredients, tag = tag, how = "recipe")
                com.nutritionscribe.app.ui.confirm.LogSession.startRecipe(r, name)
                com.nutritionscribe.app.ui.confirm.LogSession.tag = tag
                addHow = null
                onRecipe()
            }
        }, onDismiss = { addHow = null })
        // Looking up every ingredient takes a few seconds; the screen said nothing meanwhile.
        "recipe_working" -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Working out the ingredients…") },
            text = { androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth()) },
        )
        "numbers" -> NumbersDialog(null, onSave = { f ->
            addHow = null
            scope.launch { withContext(Dispatchers.IO) { store.put(f) } }
            events.event("food_saved", "how" to "numbers", "name" to f.name, "nutrients" to f.nutrients)
        }, onDismiss = { addHow = null })
    }
}

private fun isLabel(f: PersonalFood) = !f.isMeal && (f.candidate?.origin == Origin.LABEL || f.candidate?.id?.startsWith("kindguess:") == true)

private fun rowSub(f: PersonalFood): String = listOfNotNull(
    when {
        f.isRecipe -> recipeLine(f)
        f.isMeal -> "Meal · ${f.parts.size} parts"
        else -> usual(f)
    },
    f.nutrients[Metric.CALORIES.key]?.let { "${Math.round(it)} kcal" },
    f.nutrients[Metric.PROTEIN.key]?.let { "${fmt1(it)} g protein" },
).joinToString(" · ")

private fun usual(f: PersonalFood): String? {
    val amount = f.amount?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else "%.2g".format(it) }
    val said = listOfNotNull(amount, f.unit, f.size).joinToString(" ")
    val g = f.grams?.let { com.nutritionscribe.shared.model.Liquids.amount(it, f.name, f.candidate?.labelServingText) }
    return listOfNotNull(said.ifBlank { null }, g?.let { if (said.isBlank()) it else "($it)" }).joinToString(" ").ifBlank { null }
}

private fun fmt1(v: Double) = if (v >= 10 || v == v.toLong().toDouble()) Math.round(v).toString() else String.format(java.util.Locale.ROOT, "%.1f", v)

/** The label photo, a meal's stack, or the first letter: something to find a food by at a glance. */
@Composable
private fun FoodThumb(f: PersonalFood, modifier: Modifier) {
    when {
        f.photo != null -> LabelPhoto(f.photo, modifier.clip(RoundedCornerShape(10.dp)))
        else -> Box(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            if (f.isMeal || f.isRecipe) Icon(Icons.Outlined.Layers, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            else Text(f.name.trim().take(1).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun AddOption(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

/**
 * One food: its numbers for the usual amount, the units it's counted in, its
 * kinds, what they call it, a meal's parts, and Log now / Edit / Forget.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FoodPage(f: PersonalFood, onLog: () -> Unit, onClose: () -> Unit, onEditRecipe: () -> Unit = {}) {
    val store = ServiceLocator.personalStore
    val events = ServiceLocator.debugEvents
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var editLabel by remember { mutableStateOf(false) }
    var forget by remember { mutableStateOf(false) }
    if (editLabel) {
        BackHandler { editLabel = false }
        LabelDetail(f, onDone = { editLabel = false })
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        if (f.photo != null) LabelPhoto(f.photo, Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp)))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (f.isRecipe) recipeLine(f) + " · per serving" else if (f.isMeal) "Meal · ${f.parts.size} parts" else usual(f) ?: "One serving", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f))
            Text(listOfNotNull(if (f.timesUsed == 1) "Used once" else "Used ${f.timesUsed} times", lastUsed(f.updatedAt)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        if (f.unitGrams.isNotEmpty()) {
            Text("Units you use: " + f.unitGrams.entries.joinToString(" · ") { (u, g) -> "1 $u = ${Math.round(g)} g" },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        val tiles = listOf(Metric.CALORIES, Metric.PROTEIN, Metric.CARBS, Metric.FAT, Metric.FIBER, Metric.SODIUM)
        tiles.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { m ->
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(f.nutrients[m.key]?.let { fmt1(it) } ?: "—", style = NumberStyle.copy(fontSize = 16.sp))
                                Text(" " + if (m == Metric.CALORIES) "kcal" else m.unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            }
                            Text(m.shortLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
        }
        if (f.isRecipe) RecipeBatch(f)
        if (f.isMeal || f.isRecipe) {
            SectionLabel(if (f.isRecipe) "Ingredients, for the whole pot" else "Parts", Modifier.padding(top = 6.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp)) {
                    val shownParts = if (f.isRecipe) com.nutritionscribe.shared.resolve.Recipes.activeParts(f, System.currentTimeMillis()) else f.parts
                    shownParts.forEachIndexed { i, p ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                p.grams?.let { Text("${Math.round(it)} g", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                            }
                            Text(p.nutrients[Metric.CALORIES.key]?.let { "${Math.round(it)} kcal" } ?: "—", style = NumberStyle)
                            if (f.isMeal && f.parts.size > 1) TextButton(onClick = {
                                val kept = f.parts - p
                                events.event("my_meal_part_removed", "key" to f.key, "part" to p.name)
                                scope.launch { withContext(Dispatchers.IO) {
                                    store.put(f.copy(parts = kept, nutrients = com.nutritionscribe.shared.resolve.sumNutrients(kept.map { it.nutrients }),
                                        grams = kept.mapNotNull { it.grams }.takeIf { it.size == kept.size }?.sum(), updatedAt = System.currentTimeMillis()))
                                } }
                            }) { Text("Remove") }
                        }
                    }
                }
            }
            Text(if (f.isRecipe) "Say “a bowl of ${f.name.lowercase()}”, “half a serving” or “300 g” to log a portion. " +
                "Say “made ${f.name.lowercase()}, added …” when a batch is different."
                else "Say “${f.phrase}” to log all of it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        if (!f.isMeal && !f.isRecipe) KindSettings(f)
        SectionLabel("You call it", Modifier.padding(top = 6.dp))
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Text("“${f.phrase}”", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }
        Text("Saying this, or close to it, logs it straight in.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            Button(onClick = { events.event("my_food_log_now", "key" to f.key); onLog() }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Text(" Log now")
            }
            OutlinedButton(onClick = { if (f.isRecipe) onEditRecipe() else if (isLabel(f)) editLabel = true else editing = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Edit") }
        }
        TextButton(onClick = { forget = true }) { Text("Forget this food", color = MaterialTheme.colorScheme.error) }
    }
    if (editing) NumbersDialog(f, onSave = { updated ->
        editing = false
        events.event("my_food_saved", "key" to f.key, "nutrients" to updated.nutrients)
        scope.launch { withContext(Dispatchers.IO) { store.put(updated) } }
    }, onDismiss = { editing = false })
    if (forget) {
        val extra = listOfNotNull(
            (f.allKinds.size - 1).takeIf { it > 0 }?.let { if (it == 1) "its other kind" else "its $it other kinds" },
            if (f.photo != null) "its label photo" else null,
        )
        AlertDialog(
            onDismissRequest = { forget = false },
            title = { Text("Forget ${f.name}?") },
            text = { Text("Entries you've already logged keep their numbers. It won't log by name any more" +
                (if (extra.isNotEmpty()) ", and ${extra.joinToString(" and ")} ${if (extra.size > 1) "are" else "is"} deleted too." else ".")) },
            confirmButton = {
                TextButton(onClick = {
                    events.event("my_food_forgot", "key" to f.key)
                    scope.launch {
                        withContext(Dispatchers.IO) { store.remove(f.key); com.nutritionscribe.app.ui.scan.ScanPhotos.delete(f.photo) }
                        forget = false; onClose()
                    }
                }) { Text("Forget it", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { forget = false }) { Text("Cancel") } },
        )
    }
}

private fun recipeLine(f: PersonalFood): String {
    val now = System.currentTimeMillis()
    val n = com.nutritionscribe.shared.resolve.Recipes.servings(f, now)
    val batch = if (com.nutritionscribe.shared.resolve.Recipes.activeBatch(f, now) != null) " · this week's batch" else ""
    return "Recipe · makes ${com.nutritionscribe.shared.resolve.Units.format(n)}$batch"
}

/** This week's batch, when it differs: what changed, and keep it or go back to the recipe. */
@Composable
private fun RecipeBatch(f: PersonalFood) {
    val now = System.currentTimeMillis()
    val batch = com.nutritionscribe.shared.resolve.Recipes.activeBatch(f, now) ?: return
    val store = ServiceLocator.personalStore
    val events = ServiceLocator.debugEvents
    val scope = rememberCoroutineScope()
    val made = java.time.Instant.ofEpochMilli(batch.madeAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val until = made.plusDays(com.nutritionscribe.shared.resolve.Recipes.BATCH_DAYS.toLong())
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("This week's batch", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text("Made ${made.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d"))}" +
                (if (batch.changes.isNotEmpty()) ": ${batch.changes.joinToString(", ")}." else ".") +
                " Servings use it until ${until.format(java.time.format.DateTimeFormatter.ofPattern("EEE"))}, then the recipe again.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    events.event("recipe_keep_batch", "key" to f.key, "changes" to batch.changes)
                    scope.launch { withContext(Dispatchers.IO) { store.put(com.nutritionscribe.shared.resolve.Recipes.keepBatch(f, System.currentTimeMillis())) } }
                }) { Text("Keep this change") }
                OutlinedButton(onClick = {
                    events.event("recipe_drop_batch", "key" to f.key)
                    scope.launch { withContext(Dispatchers.IO) { store.put(com.nutritionscribe.shared.resolve.Recipes.dropBatch(f, System.currentTimeMillis())) } }
                }) { Text("Back to the recipe") }
            }
        }
    }
}

/**
 * A new recipe: its name and ingredients, typed, said, or read off a photo of the
 * recipe card on the phone (nothing leaves it). The ingredients then go to Check it once.
 */
@Composable
private fun NewRecipeDialog(onNext: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var ingredients by remember { mutableStateOf("") }
    var saying by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val events = ServiceLocator.debugEvents
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        reading = true
        runCatching {
            val image = com.google.mlkit.vision.common.InputImage.fromFilePath(context, uri)
            com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
                .process(image)
                .addOnSuccessListener { text ->
                    val found = com.nutritionscribe.shared.resolve.Recipes.cardIngredients(text.text)
                    events.event("recipe_card_read", "lines" to text.text.lines().size, "ingredients" to found.size)
                    if (found.isEmpty()) note = "Couldn't find ingredient lines on that photo. Type them, or try a closer photo."
                    else { ingredients = (ingredients.lines().filter { it.isNotBlank() } + found).joinToString("\n"); note = "Read ${found.size} lines. Check them, then Next." }
                    if (name.isBlank()) text.text.lines().firstOrNull { it.isNotBlank() && it.length < 40 && !it.first().isDigit() }?.let { name = it.trim() }
                    reading = false
                }
                .addOnFailureListener { note = "Couldn't read that photo."; reading = false }
        }.onFailure { note = "Couldn't open that photo."; reading = false }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New recipe") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, placeholder = { Text("Mom's chili") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = ingredients, onValueChange = { ingredients = it }, label = { Text("Ingredients, for the whole pot") },
                    placeholder = { Text("1 lb ground beef\n2 cans kidney beans\nabout half a zucchini") },
                    trailingIcon = { androidx.compose.material3.IconButton(onClick = { saying = true }) { Icon(Icons.Outlined.Mic, "Say them") } },
                    minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { pick.launch("image/*") }, enabled = !reading) {
                    Icon(Icons.Outlined.CameraAlt, null, Modifier.size(18.dp)); Text(if (reading) " Reading…" else " From a photo of the card")
                }
                Text(note ?: "Rough amounts are fine (“about half a zucchini”). You check each one on the next screen.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        },
        confirmButton = {
            TextButton(onClick = { onNext(name.trim(), ingredients.lines().map { it.trim() }.filter { it.isNotBlank() }.joinToString(", ")) },
                enabled = name.isNotBlank() && ingredients.isNotBlank()) { Text("Next") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (saying) com.nutritionscribe.app.ui.speech.VoiceDialog(title = "Say the ingredients", tag = null,
        onResult = { ingredients = listOf(ingredients, it).filter { s -> s.isNotBlank() }.joinToString("\n"); saying = false },
        onDismiss = { saying = false })
}

private fun lastUsed(at: Long): String? {
    if (at <= 0) return null
    val day = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val today = java.time.LocalDate.now()
    return "last " + when {
        day == today -> "today"
        day == today.minusDays(1) -> "yesterday"
        day.isAfter(today.minusDays(7)) -> day.format(java.time.format.DateTimeFormatter.ofPattern("EEE"))
        else -> day.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
    }
}

/** "Say or type it": words in, saved to My foods, nothing logged. */
@Composable
private fun SayToSave(onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var saying by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Say or type it") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text("e.g. a cup of greek yogurt with honey") },
                    trailingIcon = { androidx.compose.material3.IconButton(onClick = { saying = true }) { Icon(Icons.Outlined.Mic, "Say it") } },
                    modifier = Modifier.fillMaxWidth())
                Text("Saved as you said it, with the amount. It's not logged.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary)
            }
        },
        confirmButton = { TextButton(onClick = { onDone(text.trim()) }, enabled = text.isNotBlank()) { Text("Save, don't log") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (saying) com.nutritionscribe.app.ui.speech.VoiceDialog(title = "Say the food", tag = null,
        onResult = { text = it; saying = false }, onDismiss = { saying = false })
}

/** A food's numbers for its usual amount, typed: a new one from a recipe, or a fix to a saved one. */
@Composable
private fun NumbersDialog(food: PersonalFood?, onSave: (PersonalFood) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(food?.name.orEmpty()) }
    var grams by remember { mutableStateOf(food?.grams?.let { fmt1(it) }.orEmpty()) }
    val metrics = Metric.MACROS + Metric.MICROS.filter { food?.nutrients?.containsKey(it.key) == true }
    val values = remember {
        mutableStateMapOf<Metric, String>().apply {
            Metric.entries.forEach { m -> food?.nutrients?.get(m.key)?.let { put(m, String.format(java.util.Locale.ROOT, "%.1f", it).removeSuffix(".0")) } }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (food == null) "Enter the numbers" else "Edit ${food.name}") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(value = grams, onValueChange = { grams = it }, label = { Text("One serving (grams, optional)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                    Text("Numbers for one serving. A blank box is “not known”, not 0.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 4.dp))
                }
                items(metrics) { m ->
                    OutlinedTextField(
                        value = values[m].orEmpty(), onValueChange = { values[m] = it },
                        label = { Text("${m.label} (${m.unit})") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && values.values.any { it.toDoubleOrNull() != null }, onClick = {
                val n = values.mapNotNull { (m, v) -> v.trim().toDoubleOrNull()?.let { m.key to it } }.toMap()
                val g = grams.trim().toDoubleOrNull()?.takeIf { it > 0 }
                val now = System.currentTimeMillis()
                onSave(food?.copy(name = name.trim(), nutrients = n, grams = g ?: food.grams, updatedAt = now) ?: run {
                    val key = PersonalKeys.of(name.trim())
                    PersonalFood(
                        key = key, phrase = name.trim().lowercase(), name = name.trim(),
                        candidate = FoodCandidate(id = "manual:$key", name = name.trim(), origin = Origin.MANUAL, perServing = n,
                            per100g = g?.let { gg -> n.mapValues { it.value * 100.0 / gg } }, defaultGrams = g),
                        amount = 1.0, unit = "serving", grams = g, nutrients = n, timesUsed = 0, updatedAt = now,
                    )
                })
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
