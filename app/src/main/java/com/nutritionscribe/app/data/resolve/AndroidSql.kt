package com.nutritionscribe.app.data.resolve

import android.database.sqlite.SQLiteDatabase
import com.nutritionscribe.shared.resolve.SqlReader
import com.nutritionscribe.shared.resolve.SqlRow

/** The shared resolver's view of an Android SQLite file. */
class AndroidSql(private val db: SQLiteDatabase) : SqlReader {
    override fun <T> query(sql: String, args: List<String>, map: (SqlRow) -> T): List<T> =
        db.rawQuery(sql, args.toTypedArray()).use { c ->
            val row = object : SqlRow {
                override fun long(i: Int) = c.getLong(i)
                override fun doubleOrNull(i: Int): Double? = if (c.isNull(i)) null else c.getDouble(i)
                override fun string(i: Int): String? = if (c.isNull(i)) null else c.getString(i)
            }
            buildList { while (c.moveToNext()) add(map(row)) }
        }
}
