package com.nutritionscribe.app.ui.goals

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.shared.model.Extras
import com.nutritionscribe.shared.model.Metric
import kotlinx.coroutines.launch

/** One switch in More to track: its metrics, its name, where it comes from. */
private data class Extra(val metrics: List<Metric>, val name: String, val from: String)

private val EXTRAS = listOf(
    Extra(listOf(Metric.OMEGA3), "Omega-3, from fish (EPA + DHA)", "Fish, seafood, eggs \u00B7 labels rarely list it"),
    Extra(listOf(Metric.OMEGA3_ALA), "Omega-3, from plants (ALA)", "Walnuts, flax, chia, canola oil"),
    Extra(listOf(Metric.COPPER), "Copper", "Nuts, whole grains, beans, shellfish"),
    Extra(listOf(Metric.MANGANESE), "Manganese", "Oats, nuts, tea, whole grains"),
    Extra(listOf(Metric.PHOSPHORUS), "Phosphorus", "Dairy, meat, beans"),
    Extra(listOf(Metric.CHOLINE), "Choline", "Eggs, meat, fish, soy"),
    Extra(Extras.B_VITAMINS, "B vitamins (B1, B2, B3, B6)", "One row each once on"),
    Extra(listOf(Metric.ALCOHOL), "Alcohol", "Beer, wine, spirits \u00B7 in grams; a drink is 14 g"),
)

/**
 * Goals › More to track (decision 4, C): nutrients labels rarely print, off until
 * switched on. Once on, each food's amount comes from its closest match in the
 * food list, or its label when the label prints it, marked ≈ on Home.
 */
@Composable
internal fun MoreToTrack() {
    val settings = ServiceLocator.settingsRepository
    val on by settings.extras.collectAsState(initial = Extras.on)
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("More to track", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 4.dp))
        Text("Off until you turn one on. Labels rarely print these, so the app estimates them and marks them \u2248.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(horizontal = 4.dp))
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 14.dp)) {
                EXTRAS.forEachIndexed { i, x ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val isOn = x.metrics.all { it.key in on }
                    fun set(v: Boolean) {
                        ServiceLocator.debugEvents.event("extra_tracked", "metrics" to x.metrics.map { it.key }, "on" to v)
                        scope.launch { x.metrics.forEach { settings.setExtra(it.key, v) } }
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { set(!isOn) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 10.dp)) {
                            Text(x.name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Normal)
                            Text(x.from, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                        Switch(checked = isOn, onCheckedChange = { set(it) })
                    }
                }
            }
        }
        Text("Not offered yet: iodine, chromium, molybdenum and biotin. Too few foods have measured amounts to estimate them well.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(horizontal = 4.dp))
    }
}
