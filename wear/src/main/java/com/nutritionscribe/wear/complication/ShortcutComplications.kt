package com.nutritionscribe.wear.complication

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountUpTimeReference
import androidx.wear.watchface.complications.data.EmptyComplicationData
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.nutritionscribe.shared.sync.WatchShortcuts
import com.nutritionscribe.shared.sync.WatchState
import com.nutritionscribe.wear.R
import com.nutritionscribe.wear.data.WearRepository
import com.nutritionscribe.wear.ui.WearMainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZonedDateTime

/**
 * The watch-face shortcuts (watch canvas B2 and B3, approved 11-20). Each is its
 * own entry in the face's slot picker, so the person chooses which goes where:
 * long-press the face › Customise › tap a slot › Nutrition Scribe › pick one.
 * What each shows and does is worked out in [WatchShortcuts]; this hands it to the
 * face, which draws it in its own style. Reads the cached state, never the network.
 */
abstract class ShortcutComplication : ComplicationDataSourceService() {

    /** What to show; null shows nothing (the face leaves the slot blank). */
    protected abstract fun build(type: ComplicationType, s: WatchState, hour: Double, now: Long, live: Boolean): ComplicationData?

    override fun onComplicationRequest(request: ComplicationRequest, listener: ComplicationRequestListener) {
        val s = runBlocking { WearRepository(applicationContext).state.first() }
        val t = ZonedDateTime.now()
        listener.onComplicationData(build(request.complicationType, s, t.hour + t.minute / 60.0, System.currentTimeMillis(), true)
            ?: EmptyComplicationData())
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, PREVIEW, 12.5, PREVIEW_NOW, false)

    protected fun text(s: String): ComplicationText = PlainComplicationText.Builder(s).build()
    protected fun image(res: Int) = MonochromaticImage.Builder(Icon.createWithResource(this, res)).build()

    /** Opens the watch app; [extras] say what for. Request codes differ so each slot keeps its own. */
    protected fun open(code: Int, live: Boolean, extras: Intent.() -> Unit = {}): PendingIntent? = if (!live) null else
        PendingIntent.getActivity(this, code,
            Intent(this, WearMainActivity::class.java).apply(extras).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** One tap logs these entries again, with the home's Logged · Undo for 5 seconds. */
    protected fun logs(code: Int, live: Boolean, ids: List<String>, kcal: Int, what: String) = open(code, live) {
        putExtra(WearMainActivity.EXTRA_LOG_IDS, ids.toTypedArray())
        putExtra(WearMainActivity.EXTRA_LOG_KCAL, kcal)
        putExtra(WearMainActivity.EXTRA_LOG_WHAT, what)
    }

    companion object {
        private const val PREVIEW_NOW = 1_795_000_000_000L
        /** For the face's slot picker only: sample numbers, nobody's log. */
        val PREVIEW = WatchState(
            need = WatchState.Need("Fiber", 9.0, 30.0, "g", foods = listOf(WatchState.Fill("p1", "Oatmeal", 4.0, 150))),
            again = listOf(WatchState.Again("p2", "Coffee", "1 cup · 5 kcal", 5, 12.5)),
            meals = listOf(WatchState.Meal(listOf("p3", "p4"), "Your usual lunch", listOf("Veggie bag", "Bean burrito"), 460, 12.5)),
            lastAt = PREVIEW_NOW - 4 * 3_600_000L, toCheck = 2,
        )

        /** "Your usual lunch" → "lunch", for the slot's second line. */
        fun mealWord(label: String) = label.removePrefix("Your usual ").ifBlank { "meal" }

        /** Short slots fit about seven characters. */
        fun short(name: String, max: Int = 7) = name.substringAfter(" — ").let { if (it.length <= max) it else it.take(max - 1).trimEnd() + "…" }

        /** After new numbers from the phone: every shortcut redraws. */
        val ALL: List<Class<out ComplicationDataSourceService>> = listOf(
            NeedSayComplicationService::class.java, UsualMealComplicationService::class.java, AgainComplicationService::class.java,
            GapComplicationService::class.java, SinceComplicationService::class.java, QueueComplicationService::class.java,
        )
    }
}

/** B2 · Need most + Say: a mic, then what you're furthest behind on. Tap: Say, or one of your foods that closes the gap. */
class NeedSayComplicationService : ShortcutComplication() {
    override fun build(type: ComplicationType, s: WatchState, hour: Double, now: Long, live: Boolean): ComplicationData? {
        val mic = image(R.drawable.ic_mic)
        val need = WatchShortcuts.needSay(s)
        val n = s.need
        // Every goal met: just Say, and a tap starts listening.
        val tap = if (need == null) open(10, live) { putExtra(WearMainActivity.EXTRA_SAY, true) }
            else open(10, live) { putExtra(WearMainActivity.EXTRA_OPEN, WearMainActivity.OPEN_NEED) }
        val describe = text(if (need == null || n == null) "Say what you ate" else "${n.name}: ${need.first} to go. Tap to say what you ate")
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(need?.first ?: "Say"), describe)
                .setMonochromaticImage(mic).apply { need?.let { setTitle(text(it.second)) } }.setTapAction(tap).build()
            ComplicationType.RANGED_VALUE -> if (n == null || need == null) null else
                RangedValueComplicationData.Builder(n.value.toFloat().coerceAtMost(n.target.toFloat()), 0f, n.target.toFloat(), describe)
                    .setText(text(need.first)).setTitle(text(need.second)).setMonochromaticImage(mic).setTapAction(tap).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text(if (need == null) "Say what you ate" else "${need.second} · ${need.first} to go"), describe,
            ).setTitle(text("Tap to say")).setMonochromaticImage(mic).setTapAction(tap).build()
            ComplicationType.MONOCHROMATIC_IMAGE -> MonochromaticImageComplicationData.Builder(mic, describe).setTapAction(tap).build()
            else -> null
        }
    }
}

/** B3 · Usual meal now: changes with the time of day. Tap: logs every food in it. Between meals: opens Log again. */
class UsualMealComplicationService : ShortcutComplication() {
    override fun build(type: ComplicationType, s: WatchState, hour: Double, now: Long, live: Boolean): ComplicationData? {
        val icon = image(R.drawable.ic_fork)
        val m = WatchShortcuts.mealNow(s, hour)
        val tap = if (m != null) logs(11, live, m.ids, m.kcal, "usual_meal")
            else open(11, live) { putExtra(WearMainActivity.EXTRA_OPEN, WearMainActivity.OPEN_AGAIN) }
        val describe = text(if (m != null) "${m.label}, ${m.kcal} calories. Tap to log it" else "Log again")
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(if (m != null) "Usual" else "Again"), describe)
                .setTitle(text(if (m != null) mealWord(m.label) else "log")).setMonochromaticImage(icon).setTapAction(tap).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text(m?.let { "${it.label} · ${it.kcal} kcal" } ?: "Log again"), describe)
                .apply { m?.let { setTitle(text(it.names.joinToString(", "))) } }.setMonochromaticImage(icon).setTapAction(tap).build()
            else -> null
        }
    }
}

/** B3 · Again: what you usually log around now. Tap: logs it again, same amount. */
class AgainComplicationService : ShortcutComplication() {
    override fun build(type: ComplicationType, s: WatchState, hour: Double, now: Long, live: Boolean): ComplicationData? {
        val icon = image(R.drawable.ic_again)
        val a = WatchShortcuts.againNow(s, hour)
        val tap = if (a != null) logs(12, live, listOf(a.id), a.kcal, "again")
            else open(12, live) { putExtra(WearMainActivity.EXTRA_OPEN, WearMainActivity.OPEN_AGAIN) }
        val describe = text(if (a != null) "${a.name} again. Tap to log it" else "Log again")
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(a?.let { short(it.name) } ?: "Again"), describe)
                .setTitle(text(if (a != null) "again" else "log")).setMonochromaticImage(icon).setTapAction(tap).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text(a?.let { "${it.name} again" } ?: "Log again"), describe)
                .apply { a?.sub?.takeIf { it.isNotBlank() }?.let { setTitle(text(it)) } }.setMonochromaticImage(icon).setTapAction(tap).build()
            else -> null
        }
    }
}

/** B3 · Close the gap: the one food of yours that covers what's left. Tap: logs it. Blank once you're there. */
class GapComplicationService : ShortcutComplication() {
    override fun build(type: ComplicationType, s: WatchState, hour: Double, now: Long, live: Boolean): ComplicationData? {
        val f = WatchShortcuts.gap(s) ?: return null
        val n = s.need ?: return null
        val icon = image(R.drawable.ic_plus)
        val gives = WatchShortcuts.gives(f, n.unit)
        val tap = logs(13, live, listOf(f.id), f.kcal, "close_gap")
        val describe = text("${f.name} gives ${Math.round(f.amount)} ${n.unit} ${n.name.lowercase()}. Tap to log it")
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(gives), describe)
                .setTitle(text(short(f.name))).setMonochromaticImage(icon).setTapAction(tap).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text("${f.name} · $gives ${n.name.lowercase()}"), describe)
                .setTitle(text("Tap to log")).setMonochromaticImage(icon).setTapAction(tap).build()
            else -> null
        }
    }
}

/** B3 · Since you logged: a nudge when you forget. The face counts it up by itself. Tap: Say. */
class SinceComplicationService : ShortcutComplication() {
    override fun build(type: ComplicationType, s: WatchState, hour: Double, now: Long, live: Boolean): ComplicationData? {
        val icon = image(R.drawable.ic_clock)
        val tap = open(14, live) { putExtra(WearMainActivity.EXTRA_SAY, true) }
        val last = s.lastAt
        val describe = text(if (last == null) "Nothing logged yet. Tap to say what you ate" else "Time since you last logged. Tap to say what you ate")
        // Counts up on the face with no wake-ups; a face that can't shows the fixed text.
        fun since(template: String): ComplicationText = if (last == null) text("—") else
            TimeDifferenceComplicationText.Builder(TimeDifferenceStyle.SHORT_SINGLE_UNIT, CountUpTimeReference(Instant.ofEpochMilli(last)))
                .setText(template).build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(since("^1"), describe)
                .setTitle(text("ago")).setMonochromaticImage(icon).setTapAction(tap).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                if (last == null) text("Nothing logged yet") else since("Logged ^1 ago"), describe,
            ).setTitle(text("Tap to say")).setMonochromaticImage(icon).setTapAction(tap).build()
            else -> null
        }
    }
}

/** B3 · Waiting on phone: only while a watch entry needs you on the phone. Tap: says where to find them. */
class QueueComplicationService : ShortcutComplication() {
    override fun build(type: ComplicationType, s: WatchState, hour: Double, now: Long, live: Boolean): ComplicationData? {
        val count = WatchShortcuts.toCheck(s) ?: return null
        val icon = image(R.drawable.ic_phone)
        val tap = open(15, live) { putExtra(WearMainActivity.EXTRA_OPEN, WearMainActivity.OPEN_QUEUE) }
        val describe = text("$count from your watch to check on your phone")
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text("$count"), describe)
                .setTitle(text("to check")).setMonochromaticImage(icon).setTapAction(tap).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text("$count to check on your phone"), describe)
                .setMonochromaticImage(icon).setTapAction(tap).build()
            else -> null
        }
    }
}

/** Redraws every shortcut slot (new numbers from the phone, or something just logged). */
fun refreshShortcuts(context: Context) {
    for (c in ShortcutComplication.ALL) {
        runCatching {
            androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
                .create(context, android.content.ComponentName(context, c)).requestUpdateAll()
        }
    }
}
