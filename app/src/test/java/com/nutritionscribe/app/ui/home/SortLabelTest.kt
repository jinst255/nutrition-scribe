package com.nutritionscribe.app.ui.home

import com.nutritionscribe.app.data.repository.HomeSort
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Sort: Lowest first" beside Home's Macros / Minerals & vitamins tabs was cut to
 * "Sort: Lowest" on a 1080-px phone (owner's screenshots 11-14 and 11-19). The
 * switch now shows an icon and one short word, which fits.
 */
class SortLabelTest {
    @Test
    fun `the sort switch shows one short word`() {
        assertTrue(HomeSort.entries.all { it.short.length <= 7 && ' ' !in it.short })
        val src = java.io.File("src/main/java/com/nutritionscribe/app/ui/home/HomeRows.kt").readText()
        assertTrue("the switch draws the short word", src.contains("Text(sort.short"))
        assertTrue("never the long label on the button", !src.contains("Text(\"Sort: \${sort.label}\""))
    }
}
