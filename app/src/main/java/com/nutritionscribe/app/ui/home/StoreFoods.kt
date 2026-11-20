package com.nutritionscribe.app.ui.home

import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.stores.StoreSuggest

/** Logged entries as the foods [StoreSuggest] counts: one per item, or the entry itself when it has none. */
object StoreFoods {
    fun of(entries: List<Entry>): List<StoreSuggest.Food> = entries.flatMap { e ->
        if (e.items.isNotEmpty()) e.items.map { StoreSuggest.Food(it.name, it.candidateId, it.origin, e.timestampEpochMillis) }
        else listOf(StoreSuggest.Food(e.name, null, e.source.uppercase(), e.timestampEpochMillis))
    }
}
