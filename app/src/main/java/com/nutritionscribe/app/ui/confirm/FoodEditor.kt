package com.nutritionscribe.app.ui.confirm

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.app.ui.home.SectionLabel
import com.nutritionscribe.app.ui.theme.NumberStyle
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.model.NutrientSanity
import com.nutritionscribe.shared.resolve.FoodCandidate
import com.nutritionscribe.shared.resolve.Origin
import com.nutritionscribe.shared.resolve.Resolver
import kotlinx.coroutines.launch

/** What a nutrient box says about where its number came from (the legend on the editor). */
enum class BoxKind { SOLID, DASHED, RED, BLANK, TYPED }

/** The minerals a US label has to print; the others on a label food were borrowed from the food list. */
private val LABEL_PRINTED = setOf(Metric.CALCIUM.key, Metric.IRON.key, Metric.POTASSIUM.key, Metric.VITAMIN_D.key)

/**
 * The box for [key] in [draft]: typed over, borrowed or guessed (dashed amber),
 * looks off (red), not known (blank), or its own label or food list (solid).
 */
internal fun boxKind(draft: ItemDraft, key: String, issues: Set<String>): BoxKind {
    val n = draft.nutrients
    return when {
        key in issues -> BoxKind.RED
        key in draft.overrides -> BoxKind.TYPED
        key !in n -> BoxKind.BLANK
        draft.candidate?.origin == Origin.AI -> BoxKind.DASHED
        draft.candidate?.origin == Origin.LABEL && Metric.fromKey(key)?.isMicro == true && key !in LABEL_PRINTED -> BoxKind.DASHED
        draft.candidate == null && draft.status != Resolver.Status.REMEMBERED -> BoxKind.DASHED
        else -> BoxKind.SOLID
    }
}

/** Every nutrient, in label order, in the editor's two sections. */
internal val EDITOR_MACROS = listOf(
    Metric.CALORIES, Metric.PROTEIN, Metric.CARBS, Metric.FIBER, Metric.SUGAR, Metric.ADDED_SUGAR,
    Metric.FAT, Metric.SATURATED_FAT, Metric.TRANS_FAT, Metric.CHOLESTEROL, Metric.SODIUM, Metric.CAFFEINE,
)
internal val EDITOR_MICROS: List<Metric> get() = com.nutritionscribe.shared.model.Extras.shown(Metric.MICROS).filter { it !in EDITOR_MACROS }

/**
 * The one editor (notes S3, S16, S23): the same screen wherever a food is fixed —
 * Check it once, Fix, and a food tapped on its entry's page. How much in servings
 * or any unit with grams beside it, every nutrient in a box that shows where its
 * number came from, and "looks off" with a one-tap fix.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FoodEditor(
    draft: ItemDraft,
    /** The entry it's part of, for the title line ("In: Oats, milk +1"). */
    partOf: String?,
    search: suspend (String) -> List<FoodCandidate>,
    saveLabel: String,
    /** Whether to show "Use these numbers next time too"; with it, its starting value. */
    learnDefault: Boolean?,
    onSave: (ItemDraft, learn: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var d by remember { mutableStateOf(draft) }
    var percent by remember { mutableStateOf(false) }
    var learnChosen by remember { mutableStateOf<Boolean?>(null) }
    var typedByHand by remember { mutableStateOf(false) }
    // On for amounts and swaps; numbers typed by hand are often a one-off, so off.
    val learn = learnChosen ?: ((learnDefault ?: true) && !typedByHand)
    val scope = rememberCoroutineScope()
    val issues = remember(d) { NutrientSanity.check(d.nutrients, d.grams) }
    val fixes = remember(d) { NutrientSanity.suggest(d.nutrients, d.grams) }
    val red = issues.mapNotNull { it.metrics.firstOrNull() }.toSet()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back without saving") }
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        partOf?.let { Text("In: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1) }
                    }
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        if (d.status == Resolver.Status.REMEMBERED) "Your usual" else ItemDraft.label(d.candidate?.origin) +
                            (d.candidate?.labelServingText?.let { " · label serving $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary,
                    )
                    HowMuch(d) { d = it }
                    fixes.forEach { (key, value) ->
                        val m = Metric.fromKey(key) ?: return@forEach
                        val issue = issues.firstOrNull { it.metrics.firstOrNull() == key }
                        Flag("${m.label} ${fmt(d.nutrients[key] ?: 0.0)} ${m.unit}? ${issue?.message ?: "This looks off."}", "Use ${fmt(value)}") {
                            ServiceLocator.debugEvents.event("editor_use_fix", "metric" to key, "from" to d.nutrients[key], "to" to value)
                            d = d.withNutrient(key, value)
                        }
                    }
                    issues.filter { it.metrics.firstOrNull() !in fixes.keys }.forEach { Flag(it.message, null) {} }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("Every nutrient", Modifier.weight(1f))
                        PercentSwitch(percent) { percent = it }
                    }
                    Legend()
                    NutrientGrid("Macros", EDITOR_MACROS, d, red, percent) { key, v -> d = d.withNutrient(key, v); typedByHand = true }
                    NutrientGrid("Minerals & vitamins", EDITOR_MICROS, d, red, percent) { key, v -> d = d.withNutrient(key, v); typedByHand = true }
                    if (percent) Text("% of the label Daily Value. Type a % from the label and it's turned into an amount.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    NotThis(d, search) { d = d.withCandidate(it) }
                }
                Column(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (learnDefault != null) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { learnChosen = !learn }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = learn, onCheckedChange = { learnChosen = it })
                            Text("Use these numbers next time too", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Button(onClick = { onSave(d, learn) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(saveLabel) }
                }
            }
        }
    }
}

/** How much: − amount +, the unit, grams, and the common amounts as chips (note S23: "2 servings", no grams sums). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HowMuch(d: ItemDraft, onChange: (ItemDraft) -> Unit) {
    var unitMenu by remember { mutableStateOf(false) }
    var typingGrams by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("How much")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedIconButton(onClick = { onChange(d.withAmount(com.nutritionscribe.shared.resolve.Units.bump(d.item.unit, d.amount, -1))) },
                    modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Remove, contentDescription = "Less") }
                var amountText by remember(d.amount) { mutableStateOf(com.nutritionscribe.shared.resolve.Units.format(d.amount)) }
                Box(Modifier.heightIn(min = 48.dp).width(76.dp).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center) {
                    BasicTextField(
                        value = amountText,
                        onValueChange = { t -> amountText = t; parseAmount(t)?.let { onChange(d.withAmount(it)) } },
                        singleLine = true,
                        textStyle = NumberStyle.copy(fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                OutlinedIconButton(onClick = { onChange(d.withAmount(com.nutritionscribe.shared.resolve.Units.bump(d.item.unit, d.amount, +1))) },
                    modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Add, contentDescription = "More") }
                Box {
                    Surface(
                        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = d.unitOptions.size > 1) { unitMenu = true },
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(d.unitLabel, style = MaterialTheme.typography.bodyLarge)
                            if (d.unitOptions.size > 1) Icon(Icons.Filled.ArrowDropDown, contentDescription = "Change the unit")
                        }
                    }
                    DropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
                        d.unitOptions.forEach { o ->
                            DropdownMenuItem(text = { Text(o.label, fontWeight = if (o.unit == d.item.unit) FontWeight.SemiBold else FontWeight.Normal) },
                                onClick = { unitMenu = false; onChange(d.withUnitKept(o.unit)) })
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(d.portionNote ?: d.candidate?.labelServingText?.let { "1 serving = $it" } ?: "", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f))
                d.grams?.let { g ->
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { typingGrams = true }) {
                        Text(d.amountText(g) + " ✎", style = NumberStyle, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.5, 1.0, 1.5, 2.0).forEach { a ->
                    FilterChip(selected = d.amount == a, onClick = { onChange(d.withAmount(a)) },
                        label = { Text(com.nutritionscribe.shared.resolve.Units.format(a)) }, modifier = Modifier.heightIn(min = 40.dp))
                }
            }
            d.candidate?.portions?.take(4)?.takeIf { it.isNotEmpty() }?.let { portions ->
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    portions.forEach { p ->
                        FilterChip(selected = d.grams == p.grams, onClick = { onChange(d.withGrams(p.grams)) }, label = { Text("${p.label} · ${d.amountText(p.grams)}") })
                    }
                }
            }
        }
    }
    if (typingGrams) {
        val liquid = d.liquid
        val foodName = d.candidate?.name ?: d.item.food
        var t by remember { mutableStateOf(d.grams?.let { g -> Math.round(if (liquid) com.nutritionscribe.shared.model.Liquids.ml(g, foodName) else g).toString() }.orEmpty()) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { typingGrams = false },
            title = { Text(if (liquid) "How many mL?" else "How many grams?") },
            text = { OutlinedTextField(value = t, onValueChange = { t = it }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)) },
            confirmButton = {
                TextButton(enabled = t.toDoubleOrNull()?.let { it > 0 } == true, onClick = {
                    val v = t.toDouble()
                    onChange(d.withGrams(if (liquid) com.nutritionscribe.shared.model.Liquids.grams(v, foodName) else v)); typingGrams = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { typingGrams = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Flag(text: String, use: String?, onUse: () -> Unit) {
    val c = MaterialTheme.colorScheme.error
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f), border = BorderStroke(1.5.dp, c),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = c, modifier = Modifier.size(19.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (use != null) FilledTonalButton(onClick = onUse) { Text(use) }
        }
    }
}

@Composable
internal fun PercentSwitch(percent: Boolean, onChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(scheme.surfaceContainerHigh)) {
            listOf(false to "Amount", true to "%DV").forEach { (v, label) ->
                val on = percent == v
                Box(Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(if (on) scheme.secondaryContainer else Color.Transparent)
                    .clickable { onChange(v) }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(BoxKind.SOLID to "Your label or typed", BoxKind.DASHED to "Borrowed or AI guess", BoxKind.RED to "Looks off").forEach { (k, t) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(18.dp, 12.dp).boxBorder(k, 3.dp))
                Text(t, style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable
private fun Modifier.boxBorder(kind: BoxKind, radius: Dp): Modifier {
    val scheme = MaterialTheme.colorScheme
    val amber = com.nutritionscribe.app.ui.theme.limitColors().over
    return when (kind) {
        BoxKind.SOLID -> border(1.dp, scheme.outline, RoundedCornerShape(radius))
        BoxKind.TYPED -> border(1.5.dp, scheme.primary, RoundedCornerShape(radius))
        BoxKind.RED -> background(scheme.errorContainer.copy(alpha = 0.4f), RoundedCornerShape(radius)).border(2.dp, scheme.error, RoundedCornerShape(radius))
        BoxKind.DASHED, BoxKind.BLANK -> {
            val c = if (kind == BoxKind.DASHED) amber else scheme.outline
            val fill = if (kind == BoxKind.DASHED) amber.copy(alpha = 0.10f) else Color.Transparent
            drawBehind {
                val r = CornerRadius(radius.toPx())
                drawRoundRect(fill, cornerRadius = r)
                drawRoundRect(c, cornerRadius = r, style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))
            }
        }
    }
}

/** One section of boxes, two to a row. Blank is "not known", never 0 (note S3). */
@Composable
internal fun NutrientGrid(title: String, metrics: List<Metric>, d: ItemDraft, red: Set<String>, percent: Boolean, onSet: (String, Double?) -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(title)
            metrics.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { m ->
                        NutrientBox(m, d.nutrients[m.key], boxKind(d, m.key, red), percent, Modifier.weight(1f)) { onSet(m.key, it) }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun NutrientBox(m: Metric, value: Double?, kind: BoxKind, percent: Boolean, modifier: Modifier, onSet: (Double?) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dv = m.defaultTarget
    val asPct = percent && m != Metric.CALORIES && dv > 0
    var text by remember(value, asPct) {
        mutableStateOf(value?.let { if (asPct) Math.round(it / dv * 100).toString() else fmt(it) }.orEmpty())
    }
    val labelColor = when (kind) { BoxKind.DASHED -> com.nutritionscribe.app.ui.theme.limitColors().over; BoxKind.RED -> scheme.error; else -> scheme.secondary }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(m.panelLabel.replace("Total carbohydrate", "Carbs"), style = MaterialTheme.typography.labelMedium, color = labelColor, maxLines = 1)
        Row(Modifier.fillMaxWidth().height(44.dp).boxBorder(kind, 10.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (text.isEmpty()) Text("not known", style = MaterialTheme.typography.bodySmall, color = scheme.secondary)
                BasicTextField(
                    value = text,
                    onValueChange = { t ->
                        text = t
                        val v = t.replace(',', '.').toDoubleOrNull()
                        onSet(if (t.isBlank()) null else v?.let { if (asPct) it / 100.0 * dv else it } ?: value)
                    },
                    singleLine = true,
                    textStyle = NumberStyle.copy(fontSize = 14.5.sp, color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(if (asPct) "%" else m.unit, style = MaterialTheme.typography.labelMedium, color = scheme.secondary)
        }
    }
}

/** "Not this? Pick another": search the food list and labels for a closer match. */
@Composable
private fun NotThis(d: ItemDraft, search: suspend (String) -> List<FoodCandidate>, onPick: (FoodCandidate) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(d.alternates) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel("Not this? Pick another", Modifier.padding(top = 6.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Search foods and labels") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = { TextButton(onClick = { scope.launch { results = search(query) } }, enabled = query.isNotBlank()) { Text("Go") } },
            keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { scope.launch { results = search(query) } }),
            shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth(),
        )
        results.take(8).forEach { c ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPick(c) }.padding(vertical = 8.dp, horizontal = 4.dp)) {
                Text(listOfNotNull(c.brand, c.name).distinct().joinToString(" — "), style = MaterialTheme.typography.bodyMedium)
                Text(ItemDraft.label(c.origin) + (c.labelServingText?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}
