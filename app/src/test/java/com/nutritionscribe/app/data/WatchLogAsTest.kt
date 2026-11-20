package com.nutritionscribe.app.data

import com.nutritionscribe.app.data.WatchReplies.LogAs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A watch Log it after the check screen is the person's glance, so it is saved and
 * learned like the phone's card (11-20). Only what nobody saw (kept while the phone
 * was away) or what was too tricky waits in From your watch. Before, every first-time
 * food from the check screen also landed in the queue, so the queue filled with
 * entries the person had already checked.
 */
class WatchLogAsTest {
    @Test
    fun `checked on the watch is saved, not queued`() {
        assertEquals(LogAs.CHECKED, WatchReplies.logAs(silent = false, tricky = false, seen = true))
    }

    @Test
    fun `nobody looked, or too tricky, waits on the phone`() {
        assertEquals(LogAs.WAITS, WatchReplies.logAs(silent = false, tricky = false, seen = false))
        assertEquals(LogAs.WAITS, WatchReplies.logAs(silent = false, tricky = true, seen = true))
        assertEquals(LogAs.WAITS, WatchReplies.logAs(silent = false, tricky = true, seen = false))
    }

    @Test
    fun `their own foods log as remembered either way`() {
        assertEquals(LogAs.REMEMBERED, WatchReplies.logAs(silent = true, tricky = false, seen = false))
        assertEquals(LogAs.REMEMBERED, WatchReplies.logAs(silent = true, tricky = false, seen = true))
    }
}
