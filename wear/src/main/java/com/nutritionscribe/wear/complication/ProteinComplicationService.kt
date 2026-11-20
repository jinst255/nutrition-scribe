package com.nutritionscribe.wear.complication

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.GoalProgressComplicationData
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.nutritionscribe.shared.sync.WatchState
import com.nutritionscribe.wear.data.WearRepository
import com.nutritionscribe.wear.ui.WearMainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Your main goal in a watch-face slot (Ultra Info Board and others; owner, 11-19):
 * a small circle (grams to go), the side slot and big box (a value with a range,
 * drawn by the face as a bar), and the long bar ("Protein 107 / 160 g"). The face
 * draws each slot its own way; this hands it the words and numbers. Reads the
 * cached state, never the network.
 */
class ProteinComplicationService : ComplicationDataSourceService() {

    override fun onComplicationRequest(request: ComplicationRequest, listener: ComplicationRequestListener) {
        val state = runBlocking { WearRepository(applicationContext).state.first() }
        val goal = Slots.main(state) ?: Slots.PREVIEW
        listener.onComplicationData(build(request.complicationType, goal, open()))
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, Slots.PREVIEW, null)

    private fun open() = PendingIntent.getActivity(this, 0, Intent(this, WearMainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun text(s: String) = PlainComplicationText.Builder(s).build()

    private fun build(type: ComplicationType, g: WatchState.Goal, tap: PendingIntent?): ComplicationData? {
        val describe = text(Slots.spoken(g))
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(Slots.short(g)), describe)
                .setTitle(text(if (g.limit) g.unit else "to go")).setTapAction(tap).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text(Slots.long(g)), describe)
                .setTitle(text("Nutrition Scribe")).setTapAction(tap).build()
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                g.value.toFloat().coerceAtMost(g.target.toFloat()), 0f, g.target.toFloat(), describe,
            ).setText(text(Slots.short(g))).setTapAction(tap).build()
            ComplicationType.GOAL_PROGRESS -> GoalProgressComplicationData.Builder(g.value.toFloat(), g.target.toFloat(), describe)
                .setText(text(Slots.short(g))).setTapAction(tap).build()
            else -> null
        }
    }
}

/** What each slot says, kept apart so it reads the same everywhere. */
object Slots {
    val PREVIEW = WatchState.Goal("Protein", 107.0, 160.0, "g", false)

    /** The first goal to reach (protein, for most), else the first goal. */
    fun main(s: WatchState): WatchState.Goal? = s.goals.firstOrNull { !it.limit } ?: s.goals.firstOrNull()

    private fun n(v: Double) = Math.round(v).toString()

    /** "53 g" left to reach; for a limit, what's been had ("1,643"). */
    fun short(g: WatchState.Goal): String =
        if (g.limit) "%,d".format(Math.round(g.value)) else "${n((g.target - g.value).coerceAtLeast(0.0))} ${g.unit}"

    fun long(g: WatchState.Goal): String = "${g.name} ${n(g.value)} / ${n(g.target)} ${g.unit}"

    fun spoken(g: WatchState.Goal): String = "${g.name}, ${n(g.value)} of ${n(g.target)} ${g.unit}"
}
