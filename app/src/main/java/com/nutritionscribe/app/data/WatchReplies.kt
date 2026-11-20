package com.nutritionscribe.app.data

import com.nutritionscribe.shared.resolve.Resolver
import com.nutritionscribe.shared.resolve.SpokenBarcode
import com.nutritionscribe.shared.sync.WatchProtocol

/**
 * What the watch is told about something said (watch design 11-19): the foods to
 * check before Log it, or "too tricky", which keeps it on the phone to fix with AI.
 */
object WatchReplies {
    /** Nothing at all, or a line nobody could work out: better fixed on the phone than guessed on a wrist. */
    fun tooTricky(r: Resolver.Resolution): Boolean =
        r.items.isEmpty() || r.items.any { it.status == Resolver.Status.UNKNOWN }

    /** How a watch Log it is saved. */
    enum class LogAs {
        /** All their own foods: logged and remembered, as on the phone. */
        REMEMBERED,
        /** They saw the foods on the watch's check screen: that was the glance, so it's learned (11-20). */
        CHECKED,
        /** Nobody has looked (kept while the phone was away, or too tricky): counted on a best guess, waits in From your watch. */
        WAITS,
    }

    fun logAs(silent: Boolean, tricky: Boolean, seen: Boolean): LogAs = when {
        silent -> LogAs.REMEMBERED
        seen && !tricky -> LogAs.CHECKED
        else -> LogAs.WAITS
    }

    fun lines(r: Resolver.Resolution): List<WatchProtocol.Line> = r.items.map { i ->
        WatchProtocol.Line(
            name = shortName(i.displayName),
            sub = listOfNotNull(i.portionNote ?: i.item.amount?.let { a -> listOfNotNull(fmt(a), i.item.unit).joinToString(" ") }).joinToString(""),
            kcal = Math.round(i.nutrients["calories"] ?: 0.0).toInt(),
        )
    }

    fun heard(id: String, text: String, r: Resolver.Resolution) = WatchProtocol.Reply(
        id, WatchProtocol.Kind.HEARD, heard = text, lines = lines(r), barcode = SpokenBarcode.parse(text) != null,
    )

    /** The product, not the maker, as Log again shows it. */
    fun shortName(name: String): String = name.substringAfter(" — ").trim()

    private fun fmt(a: Double) = if (a == Math.floor(a)) a.toLong().toString() else "%.1f".format(java.util.Locale.US, a)
}
