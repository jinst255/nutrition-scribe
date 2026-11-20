package com.nutritionscribe.app.ui.about

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.app.ui.home.SectionLabel

/**
 * About (owner approved 11-19, items 13–14): help, what leaves the phone, where the
 * numbers come from (Open Food Facts' licence asks for its credit to be shown) and
 * the licences, in one place. Opened from the end of Settings.
 */
@Composable
fun AboutScreen(onOpenGuide: () -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    LaunchedEffect(Unit) { ServiceLocator.debugEvents.event("about_open") }
    fun open(url: String, what: String) {
        ServiceLocator.debugEvents.event("about_link", "what" to what)
        runCatching { uri.openUri(url) }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Eco, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
            }
            Column {
                Text("Nutrition Scribe", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text("$version · open source", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }

        SectionLabel("Help", Modifier.padding(top = 6.dp).semantics { heading() })
        Group {
            LinkRow("How to use it", "Saying, scanning, fixing an entry", onClick = onOpenGuide)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Expandable(
                "What leaves your phone",
                "Only what you say or type, and only with an AI key",
                "Your log, goals, body numbers and photos stay on this phone. With an AI key, what you type or say goes to the AI " +
                    "company you picked, to work out the food (with an OpenAI key, the recording itself is turned into words there); " +
                    "Settings › AI privacy says exactly what. A barcode the packs don't know is looked up in Open Food Facts by its number alone. " +
                    "Store foods downloads come from this app's GitHub page and send nothing about you.",
            )
        }

        SectionLabel("Where the numbers come from", Modifier.padding(top = 6.dp).semantics { heading() })
        Group {
            LinkRow("USDA FoodData Central", "About 13,400 foods (SR Legacy, FNDDS) and the label pack's 230,000 packaged products · public domain",
                external = true, onClick = { open("https://fdc.nal.usda.gov/", "usda") })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LinkRow("Open Food Facts", "Store packs and barcode lookups · © Open Food Facts contributors, under the Open Database License (ODbL)",
                external = true, onClick = { open("https://world.openfoodfacts.org/", "open_food_facts") })
        }

        SectionLabel("The app", Modifier.padding(top = 6.dp).semantics { heading() })
        Group {
            Expandable(
                "Open-source licences",
                "Fonts and libraries",
                "Public Sans and IBM Plex Mono: SIL Open Font License 1.1. Android Jetpack, Compose, CameraX, Room, Retrofit, " +
                    "OkHttp and Kotlin libraries: Apache License 2.0. Google ML Kit (barcode and text reading on the phone): " +
                    "Google APIs Terms of Service. Open Food Facts data: Open Database License 1.0. USDA data: public domain.",
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LinkRow("Source code", "github.com/jinst255/nutrition-scribe · MIT License", external = true,
                onClick = { open("https://github.com/jinst255/nutrition-scribe", "source") })
        }
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column { content() }
    }
}

@Composable
private fun LinkRow(title: String, sub: String, external: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        Icon(if (external) Icons.AutoMirrored.Filled.OpenInNew else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = if (external) "Opens in your browser" else null, tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(if (external) 18.dp else 24.dp))
    }
}

@Composable
private fun Expandable(title: String, sub: String, body: String) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (open) "Hide" else "Show") { open = !open }
            .padding(horizontal = 14.dp, vertical = 10.dp).heightIn(min = 36.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        }
        if (open) Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}
