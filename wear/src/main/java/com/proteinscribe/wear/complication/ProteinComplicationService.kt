package com.proteinscribe.wear.complication

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.GoalProgressComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.proteinscribe.wear.data.WearRepository
import com.proteinscribe.wear.ui.WearMainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Ultra Info Board complication (Section 9): implements both GOAL_PROGRESS (progress
 * bar) and SHORT_TEXT (icon + "118/160g") so the watch face slot picks whichever it
 * supports. Reads the same locally cached summary the home screen uses (Section 6) —
 * no network call on render.
 */
class ProteinComplicationService : ComplicationDataSourceService() {

    override fun onComplicationRequest(request: ComplicationRequest, listener: ComplicationRequestListener) {
        val summary = runBlocking { WearRepository(applicationContext).todaySummary.first() }
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, WearMainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val data: ComplicationData = when (request.complicationType) {
            androidx.wear.watchface.complications.data.ComplicationType.GOAL_PROGRESS ->
                GoalProgressComplicationData.Builder(
                    value = summary.proteinGrams.toFloat(),
                    targetValue = summary.goalGrams.toFloat(),
                    contentDescription = PlainComplicationText.Builder("Protein progress").build(),
                )
                    .setText(PlainComplicationText.Builder("${summary.proteinGrams.toInt()}/${summary.goalGrams.toInt()}g").build())
                    .setTapAction(tapIntent)
                    .build()

            androidx.wear.watchface.complications.data.ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder("${summary.proteinGrams.toInt()}/${summary.goalGrams.toInt()}g").build(),
                    contentDescription = PlainComplicationText.Builder("Protein today").build(),
                )
                    .setTapAction(tapIntent)
                    .build()

            else -> return
        }

        listener.onComplicationData(data)
    }

    override fun getPreviewData(type: androidx.wear.watchface.complications.data.ComplicationType): ComplicationData? {
        val previewText = PlainComplicationText.Builder("118/160g").build()
        return when (type) {
            androidx.wear.watchface.complications.data.ComplicationType.GOAL_PROGRESS ->
                GoalProgressComplicationData.Builder(118f, 160f, PlainComplicationText.Builder("Protein progress").build())
                    .setText(previewText)
                    .build()
            androidx.wear.watchface.complications.data.ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(previewText, PlainComplicationText.Builder("Protein today").build()).build()
            else -> null
        }
    }
}
