package com.nutritionscribe.app.ui.history

import org.junit.Assert.assertEquals
import org.junit.Test

/** Charts are drawn on a canvas, which TalkBack can't read; this is what it says instead (item 13). */
class ChartWordsTest {
    @Test
    fun `days, average, highest, lowest and the goal`() {
        assertEquals("Chart of 3 days. Average 1,400, highest 1,600, lowest 1,200. Goal 2,200.",
            ChartWords.strip(listOf(1200.0, 1400.0, 1600.0), 2200.0))
    }

    @Test
    fun `one day, no goal, and nothing yet`() {
        assertEquals("Chart of 1 day. Average 92, highest 92, lowest 92.", ChartWords.strip(listOf(92.0), null))
        assertEquals("Chart: no days logged yet.", ChartWords.strip(emptyList(), 130.0))
    }
}
