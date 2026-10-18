package com.nutritionscribe.shared.resolve

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.sql.DriverManager

/**
 * The front of a box, as the phone's text recogniser read it. Needs the label pack
 * (-DlabelPack=...); skipped without it. The Life box is a real reading from a test
 * phone: "QUARER", and no "life" at all, because the logo is drawn, not printed.
 */
class FrontMatcherTest {
    private fun matcher(): FrontMatcher? {
        val labels = TestDbs.labels ?: return null
        val path = System.getProperty("labelPack")!!
        val sql = JdbcSql(DriverManager.getConnection("jdbc:sqlite:file:$path?mode=ro"))
        return FrontMatcher(sql, labels)
    }

    private val cases = listOf(
        // (lines with heights, words the answer's brand + name must contain)
        listOf("MEGA SIZEs" to 235, "QUARER" to 81, "keep it" to 66, "MULTIGRAIN CEREAL" to 49, "original" to 182,
            "Serving Suyoeslian" to 41, "Heart" to 46, "Healthy" to 58) to listOf("quaker", "multigrain", "original"),
        listOf("CHOBANI" to 120, "GREEK YOGURT" to 60, "20g PROTEIN" to 90, "VANILLA" to 70, "NO ADDED SUGAR" to 30) to listOf("chobani", "vanilla"),
        listOf("fairlife" to 110, "core power" to 90, "26g protein" to 70, "chocolate" to 60, "elite" to 20) to listOf("fairlife", "chocolate"),
        listOf("Great Value" to 70, "Old Fashioned" to 100, "OATS" to 160, "100% Whole Grain" to 40) to listOf("great", "value", "oat"),
        listOf("KODIAK" to 120, "POWER CAKES" to 100, "BUTTERMILK" to 70, "Flapjack & Waffle Mix" to 50) to listOf("kodiak", "buttermilk"),
        listOf("Nature Valley" to 90, "CRUNCHY" to 80, "OATS 'N HONEY" to 100, "granola bars" to 50) to listOf("nature", "valley", "honey"),
    )

    @Test
    fun `fronts are recognised`() {
        val m = matcher()
        assumeTrue(m != null)
        val out = StringBuilder()
        var right = 0
        for ((lines, want) in cases) {
            val r = m!!.match(lines)
            val got = r?.let { "${it.candidate.brand} | ${it.candidate.name}" } ?: "none"
            val words = got.lowercase()
            val ok = r != null && want.all { w -> w in words }
            if (ok) right++
            out.append("${if (ok) "OK  " else "MISS"} sure=${r?.sure} $got   <- ${lines.joinToString(" / ") { it.first }}\n")
            out.append("      words=${r?.words}  others=${r?.others?.map { "${it.brand} | ${it.name}" }}\n")
        }
        TestDbs.report("front.txt", out.toString())
        println(out)
        assert(right >= cases.size - 1) { out.toString() }
    }

    @Test
    fun `a flavour no label has is a new kind of the product`() {
        val m = matcher()
        assumeTrue(m != null)
        val r = m!!.match(listOf("QUAKER" to 80, "MANGO" to 150, "MULTIGRAIN CEREAL" to 50, "limited edition" to 30, "original" to 20))
        println("new kind: ${r?.newKind} on ${r?.candidate?.brand} | ${r?.candidate?.name}")
        assert(r?.newKind == "Mango") { "${r?.newKind} / ${r?.candidate?.name}" }
        assert(r!!.candidate.name.contains("Multigrain", ignoreCase = true))
        assert(!r.sure)
        // A flavour the pack does have is that product, not a new kind.
        val c = m.match(listOf("QUAKER" to 80, "CINNAMON" to 150, "MULTIGRAIN CEREAL" to 50))
        assert(c?.newKind == null && c?.candidate?.name?.contains("Cinnamon") == true) { "${c?.newKind} / ${c?.candidate?.name}" }
    }
}
