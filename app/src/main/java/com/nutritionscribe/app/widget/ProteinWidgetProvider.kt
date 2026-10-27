package com.nutritionscribe.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.text.Text
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.shared.model.Metric
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId

/**
 * Home screen widget (Section 6): reads the locally cached running total only —
 * never triggers a live LLM/network call on render, so it opens instantly.
 */
class ProteinWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val startOfDay = com.nutritionscribe.shared.model.LogDays.start(com.nutritionscribe.shared.model.LogDays.today())
        val total = ServiceLocator.database.entryDao().observeMetricTotal(Metric.PROTEIN.key, startOfDay, Long.MAX_VALUE).first()
        val goal = ServiceLocator.settingsRepository.dailyGoalGrams.first()

        provideContent { WidgetContent(total, goal) }
    }
}

@Composable
private fun WidgetContent(total: Double, goal: Double) {
    Column(modifier = androidx.glance.GlanceModifier.fillMaxSize()) {
        Text("${total.toInt()} / ${goal.toInt()}g")
        Text("protein today")
    }
}

class ProteinWidgetProvider : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ProteinWidget()
}
