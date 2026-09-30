package com.nutritionscribe.app.ui.myfoods

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.resolve.PersonalFood
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything the app has learned from the user: what each phrase means to them,
 * and how much they usually have. Theirs to read, change and delete — this is what
 * logs silently, so it has to be inspectable.
 */
@Composable
fun MyFoodsScreen() {
    val store = ServiceLocator.personalStore
    val foods by remember {
        ServiceLocator.database.personalFoodDao().observeAll().map { rows -> rows.mapNotNull { store.decode(it) } }
    }.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<PersonalFood?>(null) }
    val scope = rememberCoroutineScope()

    if (foods.isEmpty()) {
        Text(
            "Nothing yet. Every food you confirm is remembered here, so the next time you log it, it goes straight in.",
            modifier = Modifier.padding(24.dp),
            color = MaterialTheme.colorScheme.secondary,
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("${foods.size} foods. These log without asking; tap one to change or remove it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 8.dp))
        }
        items(foods, key = { it.key }) { f ->
            Card(Modifier.fillMaxWidth().clickable { editing = f }) {
                Column(Modifier.padding(12.dp)) {
                    Text(f.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        listOfNotNull(
                            "“${f.phrase}”",
                            if (f.isMeal) "${f.parts.size} parts" else usual(f),
                            "used ${f.timesUsed}×",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        listOfNotNull(
                            f.nutrients[Metric.CALORIES.key]?.let { "${it.toInt()} kcal" },
                            f.nutrients[Metric.PROTEIN.key]?.let { "${"%.1f".format(it)} g protein" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }

    editing?.let { f ->
        FoodEditor(
            food = f,
            onSave = { updated ->
                scope.launch { withContext(Dispatchers.IO) { store.put(updated) } }
                editing = null
            },
            onDelete = {
                scope.launch { withContext(Dispatchers.IO) { store.remove(f.key) } }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

private fun usual(f: PersonalFood): String? {
    val amount = f.amount?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else "%.2g".format(it) }
    val said = listOfNotNull(amount, f.unit, f.size).joinToString(" ")
    val g = f.grams?.let { "${it.toInt()} g" }
    return listOfNotNull(said.ifBlank { null }, g).joinToString(" = ").ifBlank { null }
}

@Composable
private fun FoodEditor(food: PersonalFood, onSave: (PersonalFood) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(food.name) }
    val values = remember {
        mutableStateMapOf<Metric, String>().apply {
            Metric.entries.forEach { m -> food.nutrients[m.key]?.let { put(m, String.format(java.util.Locale.ROOT, "%.1f", it)) } }
        }
    }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("“${food.phrase}”") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                    Text("Numbers for ${usual(food) ?: "one serving"}", style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 6.dp))
                }
                items(Metric.entries.toList()) { m ->
                    OutlinedTextField(
                        value = values[m].orEmpty(), onValueChange = { values[m] = it },
                        label = { Text("${m.label} (${m.unit})") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                item {
                    TextButton(onClick = { confirmDelete = true }) { Text("Forget this food", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = values.mapNotNull { (m, v) -> v.toDoubleOrNull()?.let { m.key to it } }.toMap()
                onSave(food.copy(name = name.ifBlank { food.name }, nutrients = n, updatedAt = System.currentTimeMillis()))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Forget “${food.phrase}”?") },
            text = { Text("Next time you log it, it will ask again. Your past entries are not changed.") },
            confirmButton = { TextButton(onClick = onDelete) { Text("Forget", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}
