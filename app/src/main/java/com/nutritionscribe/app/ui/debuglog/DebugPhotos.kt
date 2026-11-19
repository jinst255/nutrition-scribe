package com.nutritionscribe.app.ui.debuglog

/**
 * Which kept photos to delete once there are too many. Label photos ("scan-…")
 * and the screenshots saved with shake notes ("spot-…") are kept apart, each to
 * its own limit, newest first.
 *
 * Before, one limit of 20 covered both. Screenshots sort ahead of label photos, so
 * once there were 20 screenshots every label photo was deleted, and screenshots
 * past 20 went too: the 10-25 log had lost 36 of 46 screenshots and all its label
 * photos. (Two screenshots named in the 11-18 log were missing as well; that
 * cause isn't known, so each save and delete is now in the debug log.)
 */
object DebugPhotos {
    fun toDelete(names: Collection<String>, maxScans: Int, maxSpots: Int): List<String> {
        val (spots, scans) = names.partition { it.startsWith("spot-") }
        return scans.sortedDescending().drop(maxScans) + spots.sortedDescending().drop(maxSpots)
    }
}
