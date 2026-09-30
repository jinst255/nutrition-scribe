package com.nutritionscribe.app.ui.addentry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.app.ui.confirm.LogDay
import com.nutritionscribe.app.ui.confirm.LogSession
import com.nutritionscribe.app.ui.home.LastLogged
import com.nutritionscribe.shared.model.Entry
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Words in, entry out: the one path every way of logging takes — the mic on Home,
 * the Log food screen, a past day from History.
 *
 * A food that is entirely the user's own logs straight away; anything new goes
 * to the check card. It logs onto [LogDay] either way.
 */
object LogFlow {

    sealed interface Outcome {
        data class Logged(val entry: Entry) : Outcome
        data object Review : Outcome
        data class Failed(val message: String) : Outcome
    }

    suspend fun submit(text: String, how: String, tag: String? = null): Outcome {
        val t = tag ?: java.util.UUID.randomUUID().toString()
        val pipeline = ServiceLocator.logPipeline
        ServiceLocator.debugEvents.event("submit_text", "how" to how, "text" to text, "tag" to t, "day" to LogDay.date?.toString())
        return try {
            val r = pipeline.resolve(text, tag = t, how = how)
            if (r.silent) {
                val (stamp, unspecified) = LogDay.stamp()
                val entry = pipeline.logNow(r, stamp, tag = t, timeUnspecified = unspecified)
                announce(entry, "your usual", undoable = false)
                Outcome.Logged(entry)
            } else {
                LogSession.start(r, tag = t, how = how)
                Outcome.Review
            }
        } catch (e: Exception) {
            ServiceLocator.debugLogger.log("LogFlow", "resolve failed: ${e.message}")
            ServiceLocator.debugEvents.event("resolve_failed", "text" to text, "error" to e)
            Outcome.Failed("Something went wrong reading that. Your words are kept — try again, or type it.")
        }
    }

    /** "The same again", onto [LogDay]. */
    suspend fun logAgain(entry: Entry, where: String): Entry {
        ServiceLocator.debugEvents.event("log_again_tap", "where" to where, "entry_id" to entry.id, "day" to LogDay.date?.toString())
        val (stamp, unspecified) = LogDay.stamp()
        val copy = ServiceLocator.logPipeline.logAgain(entry, stamp, unspecified)
        announce(copy, "again", undoable = true)
        return copy
    }

    /** Home's "Logged — Undo" note is about today; a past day's entry is confirmed where it was added. */
    private fun announce(entry: Entry, how: String, undoable: Boolean) {
        if (!LogDay.isPast) LastLogged.show(entry, how, undoable)
    }
}

/**
 * "Adding to Tuesday, Sep 23 · Time not set": says which day this is for, and lets
 * the time be given — or left unset, which is stored honestly as unset.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PastDayBanner(modifier: Modifier = Modifier) {
    var time by remember { mutableStateOf(LogDay.time) }
    var picking by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Adding to ${LogDay.label()}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (time == null) "No time — it's saved as \"time not set\"" else "at ${time!!.format(DateTimeFormatter.ofPattern("h:mm a"))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            TextButton(onClick = { picking = true }) { Text(if (time == null) "Set time" else "Change") }
            if (time != null) TextButton(onClick = { time = null; LogDay.time = null }) { Text("Clear") }
        }
    }
    if (picking) {
        val state = rememberTimePickerState(initialHour = time?.hour ?: 12, initialMinute = time?.minute ?: 0)
        AlertDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    val t = LocalTime.of(state.hour, state.minute)
                    time = t; LogDay.time = t; picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
            text = { TimePicker(state = state) },
        )
    }
}
