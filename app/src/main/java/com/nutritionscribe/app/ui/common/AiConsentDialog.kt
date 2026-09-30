package com.nutritionscribe.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nutritionscribe.app.data.repository.AiBackendKind
import com.nutritionscribe.app.data.repository.AiConsent

/**
 * Asked once per provider before anything is sent to it: what goes, what the
 * provider says it does with it, and that the app can't vouch for that.
 */
@Composable
fun AiConsentDialog(kind: AiBackendKind, onAnswer: (Boolean) -> Unit) {
    val p = AiConsent.provider(kind)
    val uri = LocalUriHandler.current
    AlertDialog(
        // Dismissing is not an answer; they'll be asked next time.
        onDismissRequest = {},
        title = { Text("Send food descriptions to ${p.name}?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Highest quality mode uses ${p.name}'s AI through the API key on this phone. ${p.name} would get:", style = MaterialTheme.typography.bodyMedium)
                p.sends.forEach { Row { Text("•  "); Text(it, style = MaterialTheme.typography.bodyMedium) } }
                Text("What ${p.name} says it does with it", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(p.training, style = MaterialTheme.typography.bodyMedium)
                Text(p.retention, style = MaterialTheme.typography.bodyMedium)
                Text(AiConsent.CAVEAT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                TextButton(onClick = { runCatching { uri.openUri(p.policyUrl) } }) { Text("Read ${p.name}'s policy") }
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("Allow") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Keep it on my phone") } },
    )
}
