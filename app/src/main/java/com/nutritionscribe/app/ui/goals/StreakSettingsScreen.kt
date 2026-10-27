package com.nutritionscribe.app.ui.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nutritionscribe.app.data.repository.SettingsRepository
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.app.ui.home.SectionLabel
import com.nutritionscribe.app.ui.theme.NumberStyle
import com.nutritionscribe.shared.model.Streak
import kotlinx.coroutines.launch

/** Goals › Streak: what counts as a day, rest days, and when the day ends. */
@Composable
fun StreakSettingsScreen() {
    val repo = ServiceLocator.settingsRepository
    val scope = rememberCoroutineScope()
    val s by repo.streak.collectAsState(initial = SettingsRepository.StreakSettings(true, Streak.Rule.ANY, 1, 3))
    fun save(n: SettingsRepository.StreakSettings) {
        ServiceLocator.debugEvents.event("streak_rules", "show" to n.show, "rule" to n.rule.key, "rest" to n.restDays, "end" to n.dayEndHour)
        scope.launch { repo.setStreak(n) }
    }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Show on Home", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = s.show, onCheckedChange = { save(s.copy(show = it)) })
            }
        }
        SectionLabel("A day counts when I…", Modifier.padding(top = 6.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                Streak.Rule.entries.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .selectable(selected = s.rule == r, role = Role.RadioButton) { save(s.copy(rule = r)) }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = s.rule == r, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
                        Column {
                            Text(r.label, style = MaterialTheme.typography.bodyLarge)
                            r.sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                        }
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Rest days a week", style = MaterialTheme.typography.bodyLarge)
                    Text("A missed day uses one by itself", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
                OutlinedIconButton(onClick = { save(s.copy(restDays = (s.restDays - 1).coerceAtLeast(0))) }, enabled = s.restDays > 0) {
                    Icon(Icons.Filled.Remove, contentDescription = "One fewer")
                }
                Text("${s.restDays}", style = NumberStyle.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize), modifier = Modifier.padding(horizontal = 12.dp))
                OutlinedIconButton(onClick = { save(s.copy(restDays = (s.restDays + 1).coerceAtMost(3))) }, enabled = s.restDays < 3) {
                    Icon(Icons.Filled.Add, contentDescription = "One more")
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("My day ends at", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Midnight", 3 to "3 AM", 5 to "5 AM").forEach { (h, label) ->
                        FilterChip(selected = s.dayEndHour == h, onClick = { save(s.copy(dayEndHour = h)) }, label = { Text(label) })
                    }
                }
                Text(
                    if (s.dayEndHour == 0) "A snack after midnight counts for the new day."
                    else "A snack at 12:30 AM still counts for the day before.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}
