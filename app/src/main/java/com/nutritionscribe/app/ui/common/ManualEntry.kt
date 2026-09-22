package com.nutritionscribe.app.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nutritionscribe.app.data.db.FrequentFood
import com.nutritionscribe.shared.model.Entry
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

/**
 * What the user typed into the manual form, ready to hand to
 * `EntryRepository.logManual`. [timeOfDay] is null when they didn't say what time
 * they ate — which is a real, preservable answer, not a missing value to paper over.
 */
data class ManualEntryInput(
    val name: String,
    val proteinGrams: Double,
    val calories: Double?,
    val carbsGrams: Double?,
    val fatGrams: Double?,
    val timeOfDay: LocalTime?,
)

/** Clock time for an entry, or an explicit "time not set" for a backdated one. */
fun Entry.timeLabel(zone: ZoneId = ZoneId.systemDefault()): String =
    if (timeUnspecified) {
        "Time not set"
    } else {
        Instant.ofEpochMilli(timestampEpochMillis).atZone(zone).toLocalTime().format(TIME_FORMAT)
    }

/**
 * Resolves a form result to the epoch millis actually stored, plus whether that
 * timestamp is real. With no time given we store NOON so the entry sorts inside its
 * own day instead of jumping to a boundary, and flag it so nothing downstream mistakes
 * the placeholder for something the user reported.
 */
fun ManualEntryInput.resolveTimestamp(
    date: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): Pair<Long, Boolean> {
    val time = timeOfDay
    return if (time == null) {
        date.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli() to true
    } else {
        date.atTime(time).atZone(zone).toInstant().toEpochMilli() to false
    }
}

/**
 * The no-API-key logging form, also reused for adding an entry to a past day.
 * [defaultTime] is the time pre-filled when the sheet opens: "now" for a same-day
 * entry, null when backdating so the user isn't handed a fabricated time by default.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualEntrySheet(
    title: String,
    frequentFoods: List<FrequentFood>,
    defaultTime: LocalTime?,
    onDismiss: () -> Unit,
    onSave: (ManualEntryInput) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        ManualEntryForm(
            title = title,
            frequentFoods = frequentFoods,
            defaultTime = defaultTime,
            onSave = onSave,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualEntryForm(
    title: String,
    frequentFoods: List<FrequentFood>,
    defaultTime: LocalTime?,
    onSave: (ManualEntryInput) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var proteinText by remember { mutableStateOf("") }
    var caloriesText by remember { mutableStateOf("") }
    var carbsText by remember { mutableStateOf("") }
    var fatText by remember { mutableStateOf("") }
    var showMore by remember { mutableStateOf(false) }
    var time by remember { mutableStateOf(defaultTime) }
    var pickingTime by remember { mutableStateOf(false) }

    val protein = proteinText.trim().toDoubleOrNull()
    val canSave = name.isNotBlank() && protein != null

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)

        if (frequentFoods.isNotEmpty()) {
            Text(
                "Log again",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
            )
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                frequentFoods.forEach { food ->
                    AssistChip(
                        onClick = {
                            // Fills the form rather than saving outright, so the amount
                            // stays adjustable — portions vary even for a repeat food.
                            name = food.name
                            proteinText = trimNumber(food.proteinGrams)
                        },
                        label = { Text("${food.name} · ${food.proteinGrams.toInt()}g") },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("What did you eat") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
        OutlinedTextField(
            value = proteinText,
            onValueChange = { proteinText = it },
            label = { Text("Protein (g)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Schedule,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
            Text(
                time?.format(TIME_FORMAT) ?: "Time not set",
                color = if (time == null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = { pickingTime = true }) { Text(if (time == null) "Set time" else "Change") }
            if (time != null) {
                TextButton(onClick = { time = null }) { Text("Clear") }
            }
        }
        if (time == null) {
            Text(
                "Optional. Left blank, this entry is saved without a time rather than a guessed one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        TextButton(
            onClick = { showMore = !showMore },
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Icon(if (showMore) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            Text(if (showMore) "Fewer details" else "Add calories, carbs, fat")
        }

        if (showMore) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Calories", caloriesText, { caloriesText = it }, Modifier.weight(1f))
                NumberField("Carbs (g)", carbsText, { carbsText = it }, Modifier.weight(1f))
                NumberField("Fat (g)", fatText, { fatText = it }, Modifier.weight(1f))
            }
        }

        Button(
            onClick = {
                onSave(
                    ManualEntryInput(
                        name = name.trim(),
                        proteinGrams = protein ?: 0.0,
                        calories = caloriesText.trim().toDoubleOrNull(),
                        carbsGrams = carbsText.trim().toDoubleOrNull(),
                        fatGrams = fatText.trim().toDoubleOrNull(),
                        timeOfDay = time,
                    )
                )
            },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        ) { Text("Save entry") }
    }

    if (pickingTime) {
        val initial = time ?: LocalTime.now()
        val pickerState = rememberTimePickerState(
            initialHour = initial.hour,
            initialMinute = initial.minute,
        )
        Dialog(onDismissRequest = { pickingTime = false }) {
            androidx.compose.material3.Surface(
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("What time?", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    TimePicker(state = pickerState)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { pickingTime = false }) { Text("Cancel") }
                        TextButton(
                            onClick = {
                                time = LocalTime.of(pickerState.hour, pickerState.minute)
                                pickingTime = false
                            },
                        ) { Text("OK") }
                    }
                }
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

/** 17.0 -> "17", 17.5 -> "17.5" — avoids prefilling the form with a trailing ".0". */
private fun trimNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
