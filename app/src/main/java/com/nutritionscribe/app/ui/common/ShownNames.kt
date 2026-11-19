package com.nutritionscribe.app.ui.common

import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.resolve.ShortName

/**
 * What an entry is called on screen (review L2). Its stored name is the joined
 * database names unless the person named it; that join is shown with names a
 * person would say ("Apples, Cottage cheese"), and a name they typed is theirs and
 * shown as typed.
 */
fun Entry.shownName(): String {
    if (items.isEmpty()) return name
    val joined = items.joinToString(", ") { it.name.substringBefore(",") }.take(80)
    val generated = name == joined || name == items.singleOrNull()?.name || name == joined.ifBlank { rawText }
    if (!generated) return name
    return items.joinToString(", ") { ShortName.of(it.name) }.ifBlank { name }
}

/** A food's name on screen: short, with the source's own name left for its detail. */
fun shownFoodName(name: String): String = ShortName.of(name)

/**
 * A Log again chip: "Banana" for "Banana, raw"; "Cottage cheese + Pineapple" for a
 * two-food entry. Cut at the first comma it read "Cheese + Pineapple" (review L1).
 */
fun logAgainName(entry: Entry): String {
    // The product, not the maker: "Ghirardelli Chocolate Company — Dark…" was cut before it said which chocolate.
    fun product(n: String) = ShortName.of(n).substringAfter(" — ")
    val base = if (entry.items.size > 1) entry.items.joinToString(" + ") { product(it.name) } else product(entry.shownName())
    return if (base.length > 28) base.take(26).trimEnd() + "…" else base
}
