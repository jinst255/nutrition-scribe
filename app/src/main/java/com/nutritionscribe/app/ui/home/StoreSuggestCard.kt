package com.nutritionscribe.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nutritionscribe.app.data.resolve.StorePacks
import com.nutritionscribe.app.ui.theme.NumberStyle
import com.nutritionscribe.shared.stores.StoreSuggest

/**
 * "You eat a lot of Great Value": the store-pack suggestion on Home (owner, 11-19).
 * Drawn on the Now and Next canvas; numbers from [StoreSuggest].
 */
@Composable
internal fun StoreSuggestCard(
    s: StoreSuggest.Suggestion,
    /** Download size, once the online catalogue is known. */
    bytes: Long?,
    job: StorePacks.Job?,
    onDownload: () -> Unit,
    onNotNow: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.ShoppingBag, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("You eat a lot of ${s.store.title} products", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${s.storeFoods} of your ${s.packagedFoods} packaged foods this month. Its pack puts " +
                            "their real labels on your phone, so saying or scanning them works even offline.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val figures = listOfNotNull(s.guessesText?.let { it to "foods a month from real labels, not AI guesses" }, s.centsText?.let { it to "of web searches a month" })
            if (figures.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                figures.forEach { (big, small) ->
                    Column(
                        Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .semantics(mergeDescendants = true) { contentDescription = "$big $small" },
                    ) {
                        Text(big, style = NumberStyle.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize))
                        Text(small, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            when (job) {
                is StorePacks.Job.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(
                        progress = { if (job.total > 0) (job.bytes.toFloat() / job.total).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                    )
                    Text("Downloading…", style = MaterialTheme.typography.bodySmall)
                }
                else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onDownload, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (bytes != null) "Download · ${mb(bytes)}" else "Download")
                    }
                    TextButton(onClick = onNotNow, modifier = Modifier.heightIn(min = 48.dp)) { Text("Not now") }
                }
            }
            if (job is StorePacks.Job.Failed) {
                Text(job.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Text(
                (if (figures.isNotEmpty()) "Up to: worked out from your last 30 days. " else "") +
                    "Data from Open Food Facts (ODbL). Settings › Store foods",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun mb(bytes: Long): String = if (bytes < 1_000_000) "${(bytes / 1000).coerceAtLeast(1)} KB"
else "%.1f MB".format(bytes / 1_000_000.0)
