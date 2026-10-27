package com.nutritionscribe.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nutritionscribe.app.data.repository.SettingsRepository
import com.nutritionscribe.app.ui.theme.NumberStyle
import com.nutritionscribe.shared.model.Streak
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * The top of Home: the streak, a small date, and this week's days. It replaced the
 * big date, which said little and took a row (note S5).
 */
@Composable
internal fun StreakStrip(streak: Streak.Result, today: LocalDate, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val days = streak.current
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Open the streak", onClick = onOpen)
                .semantics { contentDescription = "Streak: $days ${if (days == 1) "day" else "days"}. Open the streak" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(40.dp).background(scheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.LocalFireDepartment, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(22.dp))
            }
            Column {
                Text(if (days == 1) "1 day" else "$days days", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 22.sp)
                Text("streak · " + today.format(DateTimeFormatter.ofPattern("EEE, MMM d")), style = MaterialTheme.typography.bodySmall, color = scheme.secondary)
            }
        }
        Spacer(Modifier.weight(1f))
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            (0L..6L).forEach { i ->
                val d = monday.plusDays(i)
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "MTWTFSS"[i.toInt()].toString(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        color = if (d == today) scheme.onBackground else scheme.secondary,
                    )
                    DayDot(streak.state(d), d == today, d.isAfter(today), size = 22)
                }
            }
        }
    }
}

/** One day of the strip or the month: filled when it counted, dashed for a rest day, ringed for today. */
@Composable
private fun DayDot(state: Streak.DayState, today: Boolean, future: Boolean, size: Int, label: String? = null) {
    val scheme = MaterialTheme.colorScheme
    val ring = if (today) Modifier.border(2.dp, scheme.primary, CircleShape).padding(3.dp) else Modifier
    Box(Modifier.size((size + if (today) 6 else 0).dp).then(ring), contentAlignment = Alignment.Center) {
        val m = Modifier.size(size.dp)
        when {
            state == Streak.DayState.COUNTED -> Box(m.background(scheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                if (label != null) Text(label, style = NumberStyle.copy(fontSize = 12.5.sp), color = scheme.onPrimary, fontWeight = FontWeight.SemiBold)
                else Icon(Icons.Filled.Check, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size((size * 0.6).dp))
            }
            state == Streak.DayState.REST -> {
                val c = scheme.primary
                Box(m.drawBehindDashed(c), contentAlignment = Alignment.Center) {
                    if (label != null) Text(label, style = NumberStyle.copy(fontSize = 12.5.sp))
                }
            }
            else -> Box(m.background(if (label != null) androidx.compose.ui.graphics.Color.Transparent else scheme.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
                if (label != null) Text(
                    label, style = NumberStyle.copy(fontSize = 12.5.sp),
                    color = if (future) scheme.outline.copy(alpha = 0.6f) else scheme.secondary,
                )
            }
        }
    }
}

private fun Modifier.drawBehindDashed(color: androidx.compose.ui.graphics.Color): Modifier = drawBehind {
    drawCircle(
        color, radius = size.minDimension / 2 - 1.dp.toPx(),
        style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
    )
}

/** Tapping the streak: the month, the best run, the rules. No red anywhere: a missed day is just a plain date. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StreakSheet(
    streak: Streak.Result,
    settings: SettingsRepository.StreakSettings,
    today: LocalDate,
    onRules: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    com.nutritionscribe.app.ui.feedback.ReportableSheet("Streak", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(52.dp).background(scheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.LocalFireDepartment, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(28.dp))
                }
                Column {
                    Text(
                        when (streak.current) { 0 -> "No streak yet"; 1 -> "1 day"; else -> "${streak.current} days in a row" },
                        fontSize = 26.sp, fontWeight = FontWeight.ExtraBold,
                    )
                    if (streak.best > 0) Text(
                        "Your best: ${streak.best} ${if (streak.best == 1) "day" else "days"}" +
                            (streak.bestMonth?.let { ", in " + it.format(DateTimeFormatter.ofPattern("MMMM")) } ?: ""),
                        style = MaterialTheme.typography.bodyMedium, color = scheme.secondary,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Last month") }
                IconButton(onClick = { month = month.plusMonths(1) }, enabled = month < YearMonth.from(today)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
                }
            }
            MonthGrid(month, streak, today)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Legend(true, "Counted")
                Legend(false, "Rest day used")
            }
            Surface(shape = RoundedCornerShape(14.dp), color = scheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.clickable(onClick = onRules).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("A day counts when you ${settings.rule.label.replaceFirstChar { it.lowercase() }}.", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${restText(settings.restDays)} · your day ends at ${endText(settings.dayEndHour)}",
                        style = MaterialTheme.typography.bodySmall, color = scheme.secondary,
                    )
                    Text("Change the rules in Goals ›", style = MaterialTheme.typography.labelLarge, color = scheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

internal fun restText(n: Int) = when (n) { 0 -> "No rest days"; 1 -> "1 rest day a week"; else -> "$n rest days a week" }
internal fun endText(h: Int) = when (h) { 0 -> "midnight"; else -> "$h AM" }

@Composable
private fun Legend(filled: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DayDot(if (filled) Streak.DayState.COUNTED else Streak.DayState.REST, today = false, future = false, size = 14, label = "")
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun MonthGrid(month: YearMonth, streak: Streak.Result, today: LocalDate) {
    val first = month.atDay(1)
    val lead = first.dayOfWeek.value - 1
    val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            "MTWTFSS".forEach {
                Text(it.toString(), modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
            }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                (0 until 7).forEach { i ->
                    val d = week.getOrNull(i)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (d != null) DayDot(streak.state(d), d == today, d.isAfter(today), size = 34, label = d.dayOfMonth.toString())
                    }
                }
            }
        }
    }
}
