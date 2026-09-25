package com.nutritionscribe.shared.resolve

import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** [SqlReader] over sqlite-jdbc, so the tests run the app's own search code. */
class JdbcSql(private val conn: Connection) : SqlReader {
    override fun <T> query(sql: String, args: List<String>, map: (SqlRow) -> T): List<T> =
        conn.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setString(i + 1, a) }
            st.executeQuery().use { rs ->
                val row = object : SqlRow {
                    override fun long(i: Int) = rs.getLong(i + 1)
                    override fun doubleOrNull(i: Int): Double? {
                        val v = rs.getDouble(i + 1)
                        return if (rs.wasNull()) null else v
                    }
                    override fun string(i: Int): String? = rs.getString(i + 1)
                }
                buildList { while (rs.next()) add(map(row)) }
            }
        }
}

object TestDbs {
    private fun open(path: String): SqlReader {
        require(File(path).exists()) { "missing test database: $path" }
        return JdbcSql(DriverManager.getConnection("jdbc:sqlite:file:$path?mode=ro", java.util.Properties().apply {
            setProperty("open_mode", "1") // SQLITE_OPEN_READONLY
        }))
    }

    val usda: UsdaFoodSource by lazy { UsdaFoodSource(open(System.getProperty("foodDb"))) }

    /** Null unless a label pack path was passed with -DlabelPack=... */
    val labels: LabelPackSource? by lazy {
        System.getProperty("labelPack")?.takeIf { it.isNotBlank() && File(it).exists() }
            ?.let { LabelPackSource(open(it)) }
    }

    fun report(name: String, text: String) {
        val dir = File(System.getProperty("reportDir") ?: "build/reports/resolver")
        dir.mkdirs()
        File(dir, name).writeText(text)
    }
}
