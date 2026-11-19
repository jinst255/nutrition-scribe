package com.nutritionscribe.shared.resolve

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Review V3: Settings told people the label pack was "About 40 MB" in one row and
 * "about 70 MB" in another; it unpacks to about 71 MB. Both rows now read
 * [LabelPackFacts.SIZE_TEXT]. With -DlabelPack=<unzipped pack>, this checks the
 * stated size against the real file, so a new, bigger pack makes this fail
 * until the text is updated. Skipped without a pack.
 */
class LabelPackFactsTest {
    @Test
    fun `the label pack size we tell people matches the real pack`() {
        val path = System.getProperty("labelPack")?.takeIf { it.isNotBlank() }
        assumeTrue("needs -DlabelPack", path != null && File(path).exists())
        val mb = File(path!!).length() / 1_000_000.0
        assertTrue("pack is ${"%.0f".format(mb)} MB, text says ${LabelPackFacts.SIZE_TEXT}",
            Math.abs(mb - LabelPackFacts.UNPACKED_MB) <= 10)
    }

    @Test
    fun `the size text names the size`() {
        assertTrue(LabelPackFacts.SIZE_TEXT.contains("${LabelPackFacts.UNPACKED_MB} MB"))
    }
}
