package com.nutritionscribe.app.ui.history

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow

data class DailyPoint(val epochDay: Long, val proteinGrams: Double)
data class BucketPoint(val startEpochDay: Long, val endEpochDay: Long, val averageProtein: Double)

enum class ChartGranularity { DAY, WEEK, MONTH, YEAR }

/**
 * Continuous pinch-to-zoom math (Section 12). zoom is 0-100 (or a raw pinch scale
 * factor mapped into that range); 0 shows the tightest window (7 days), 100 shows the
 * full history. The underlying dataset stays daily-resolution — only the display
 * bucketing changes, so zooming regroups points instead of swapping datasets.
 */
object ChartBucketing {

    fun daysVisible(totalDays: Int, zoom: Int): Double {
        val z = zoom.coerceIn(0, 100)
        val ratio = max(1.0, totalDays / 7.0)
        return 7.0 * ratio.pow(z / 100.0)
    }

    fun bucketSizeDays(daysVisible: Double, targetPoints: Int = 11): Int =
        max(1, ceil(daysVisible / targetPoints).toInt())

    fun granularityFor(bucketSizeDays: Int): ChartGranularity = when {
        bucketSizeDays <= 6 -> ChartGranularity.DAY
        bucketSizeDays <= 27 -> ChartGranularity.WEEK
        bucketSizeDays <= 299 -> ChartGranularity.MONTH
        else -> ChartGranularity.YEAR
    }

    /** Takes the most recent [daysVisible] days from [daily] (which must be sorted ascending, one entry per day, no gaps), then re-buckets them. */
    fun visibleBuckets(daily: List<DailyPoint>, zoom: Int): List<BucketPoint> {
        if (daily.isEmpty()) return emptyList()
        val visible = daysVisible(daily.size, zoom)
        val visibleCount = visible.toInt().coerceIn(1, daily.size)
        val window = daily.takeLast(visibleCount)
        val bucketSize = bucketSizeDays(visible)
        return window.chunked(bucketSize).map { chunk ->
            BucketPoint(
                startEpochDay = chunk.first().epochDay,
                endEpochDay = chunk.last().epochDay,
                averageProtein = chunk.sumOf { it.proteinGrams } / chunk.size,
            )
        }
    }
}
