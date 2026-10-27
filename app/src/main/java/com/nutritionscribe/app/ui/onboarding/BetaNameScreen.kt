package com.nutritionscribe.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nutritionscribe.app.di.ServiceLocator
import kotlinx.coroutines.launch

/** This build's versionCode, which "Still testing as …?" is asked once for. */
fun appVersionCode(context: android.content.Context): Int = runCatching {
    @Suppress("DEPRECATION")
    context.packageManager.getPackageInfo(context.packageName, 0).versionCode
}.getOrDefault(0)

/**
 * The tester's name, in test versions only: typed once at first open, and
 * confirmed once after each update. It goes on every note, report and log they
 * send, so the owner can tell whose is whose (the owner uses the same build).
 */
@Composable
fun BetaNameScreen(current: String, onDone: () -> Unit) {
    val settings = ServiceLocator.settingsRepository
    val scope = rememberCoroutineScope()
    val version = remember { appVersionCode(ServiceLocator.appContext) }
    var editing by remember { mutableStateOf(current.isBlank()) }
    var name by remember { mutableStateOf(current) }
    fun save(n: String) {
        ServiceLocator.debugEvents.always("tester_name_confirmed", "changed" to (n.trim() != current), "version" to version)
        scope.launch { settings.confirmName(n.trim(), version); onDone() }
    }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 24.dp).padding(top = 64.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        }
        Text(
            if (editing) "Who's testing?" else "Still testing as ${current.trim()}?",
            fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 34.sp,
        )
        Text(
            if (editing) "This is a test version. Your name goes on the notes and logs you send, so we know who asked for what. It stays on this phone otherwise."
            else "There's a new test version. Your name stays on the notes and logs you send.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.secondary,
        )
        if (editing) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text("Your first name") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) save(name) }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.weight(1f))
        if (editing) {
            Button(onClick = { save(name) }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Continue") }
            Text("You can change it in Settings › Beta testing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        } else {
            Button(onClick = { save(current) }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Yes, that's me") }
            OutlinedButton(onClick = { editing = true; name = "" }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Change it") }
        }
    }
}
