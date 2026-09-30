package com.nutritionscribe.app.ui.goals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nutritionscribe.shared.model.Profile
import com.nutritionscribe.shared.model.TargetCalculator
import kotlin.math.roundToInt

/**
 * "About you": what every bar on Home is scaled to. Folded to one line once
 * filled in; the rare extras (plasma, pregnancy, diet) fold away again inside.
 */
@Composable
fun ProfileCard(
    profile: Profile?,
    onSave: (Profile) -> Unit,
    onUseForGoals: (Profile) -> Unit,
) {
    var open by remember(profile == null) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("About you", style = MaterialTheme.typography.titleMedium)
                    Text(
                        profile?.let(::summary)
                            ?: "Add your height, weight and activity so every bar shows what you need, not a label's one-size figure.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = if (open) "Hide" else "Edit")
            }
            if (open) ProfileForm(profile, onSave = { onSave(it); open = false }, onUseForGoals = onUseForGoals)
        }
    }
}

private fun summary(p: Profile): String {
    val inches = (p.heightCm / 2.54).roundToInt()
    val lb = (p.weightKg / TargetCalculator.KG_PER_LB).roundToInt()
    return listOfNotNull(
        if (p.sex == TargetCalculator.Sex.MALE) "Male" else "Female",
        "${p.ageYears}",
        "${inches / 12}'${inches % 12}\"",
        "$lb lb",
        p.activity.label,
        p.stage.takeIf { it != Profile.LifeStage.NONE }?.label,
        p.diet.takeIf { it != Profile.Diet.ANY }?.label,
        "plasma donor".takeIf { p.plasmaDonor },
    ).joinToString(" · ")
}

@Composable
private fun ProfileForm(profile: Profile?, onSave: (Profile) -> Unit, onUseForGoals: (Profile) -> Unit) {
    var sex by remember { mutableStateOf(profile?.sex ?: TargetCalculator.Sex.MALE) }
    var age by remember { mutableStateOf(profile?.ageYears?.toString() ?: "") }
    val inchesTotal = profile?.let { (it.heightCm / 2.54).roundToInt() }
    var feet by remember { mutableStateOf(inchesTotal?.let { (it / 12).toString() } ?: "") }
    var inches by remember { mutableStateOf(inchesTotal?.let { (it % 12).toString() } ?: "") }
    var pounds by remember { mutableStateOf(profile?.let { (it.weightKg / TargetCalculator.KG_PER_LB).roundToInt().toString() } ?: "") }
    var activity by remember { mutableStateOf(profile?.activity ?: TargetCalculator.Activity.LIGHT) }
    var aim by remember { mutableStateOf(profile?.aim ?: TargetCalculator.Aim.MAINTAIN) }
    var stage by remember { mutableStateOf(profile?.stage ?: Profile.LifeStage.NONE) }
    var diet by remember { mutableStateOf(profile?.diet ?: Profile.Diet.ANY) }
    var plasma by remember { mutableStateOf(profile?.plasmaDonor ?: false) }
    val extrasSet = profile != null && (profile.stage != Profile.LifeStage.NONE || profile.diet != Profile.Diet.ANY || profile.plasmaDonor)
    var extras by remember { mutableStateOf(extrasSet) }

    val built = run {
        val a = age.toIntOrNull()?.takeIf { it in 13..110 } ?: return@run null
        val ft = feet.toIntOrNull() ?: return@run null
        val inch = inches.toDoubleOrNull() ?: 0.0
        val lb = pounds.toDoubleOrNull()?.takeIf { it in 60.0..700.0 } ?: return@run null
        val cm = TargetCalculator.feetInchesToCm(ft, inch).takeIf { it in 120.0..240.0 } ?: return@run null
        Profile(sex, a, cm, TargetCalculator.poundsToKg(lb), activity, aim,
            stage = if (sex == TargetCalculator.Sex.FEMALE) stage else Profile.LifeStage.NONE, diet = diet, plasmaDonor = plasma)
    }

    Chips(TargetCalculator.Sex.entries, sex, { if (it == TargetCalculator.Sex.MALE) "Male" else "Female" }) { sex = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("Age", age, null, Modifier.weight(1f)) { age = it }
        NumberField("Feet", feet, "ft", Modifier.weight(1f)) { feet = it }
        NumberField("Inches", inches, "in", Modifier.weight(1f)) { inches = it }
    }
    NumberField("Weight", pounds, "lb", Modifier.fillMaxWidth()) { pounds = it }
    Text("How active are you?", style = MaterialTheme.typography.titleSmall)
    Chips(TargetCalculator.Activity.entries, activity, { it.label }) { activity = it }
    Text(activity.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    Text("What are you after?", style = MaterialTheme.typography.titleSmall)
    Chips(TargetCalculator.Aim.entries, aim, { it.label }) { aim = it }

    Row(Modifier.fillMaxWidth().clickable { extras = !extras }.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Anything else that changes your needs?", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Icon(if (extras) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
    }
    if (extras) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("I donate plasma", style = MaterialTheme.typography.bodyMedium)
                Text("Adds a button on Home for donation days, which puts back the protein a donation takes.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            Switch(checked = plasma, onCheckedChange = { plasma = it })
        }
        if (sex == TargetCalculator.Sex.FEMALE) {
            Chips(Profile.LifeStage.entries, stage, { it.label }) { stage = it }
        }
        Chips(Profile.Diet.entries, diet, { it.label }) { diet = it }
        if (diet != Profile.Diet.ANY) {
            Text("Iron and zinc from plants absorb less well, so you need more of both.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { built?.let(onSave) }, enabled = built != null) { Text("Save") }
        TextButton(onClick = { built?.let { onSave(it); onUseForGoals(it) } }, enabled = built != null) { Text("Save and set my goal targets") }
    }
    Text(
        "Estimates from the US Dietary Reference Intakes and your height, weight, age and activity: a starting point, not medical advice. Stays on this phone.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary,
    )
}

@Composable
private fun NumberField(label: String, value: String, suffix: String?, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        suffix = suffix?.let { { Text(it) } }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = modifier,
    )
}

@Composable
private fun <T> Chips(options: List<T>, selected: T, labelOf: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(labelOf(o)) }) }
    }
}
