package com.nutritionscribe.shared.retention

import kotlinx.serialization.Serializable

enum class RetentionUnit { DAYS, WEEKS, MONTHS, YEARS, INDEFINITE }

@Serializable
data class RetentionWindow(
    val amount: Int,
    val unit: RetentionUnit,
) {
    /** Null means indefinite (never expires). */
    fun cutoffEpochMillis(nowEpochMillis: Long): Long? {
        if (unit == RetentionUnit.INDEFINITE) return null
        val days = when (unit) {
            RetentionUnit.DAYS -> amount
            RetentionUnit.WEEKS -> amount * 7
            RetentionUnit.MONTHS -> amount * 30
            RetentionUnit.YEARS -> amount * 365
            RetentionUnit.INDEFINITE -> return null
        }
        return nowEpochMillis - days.toLong() * 24 * 60 * 60 * 1000
    }

    companion object {
        val FOREVER = RetentionWindow(0, RetentionUnit.INDEFINITE)

        /**
         * Both Forever (review F2): a year of logs is a few MB, and the old defaults
         * (1 year, 3 months) dropped people's history without telling them. A shorter
         * window is only ever set after a warning in Settings.
         */
        val DEFAULT_PROTEIN_TOTALS = FOREVER
        val DEFAULT_FOOD_DETAILS = FOREVER
    }
}
