package com.nutritionscribe.app.data.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nutritionscribe.shared.model.Confidence
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.model.UnprocessedEntry
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "entries",
    indices = [androidx.room.Index("timestampEpochMillis"), androidx.room.Index("name")],
)
data class EntryRow(
    @PrimaryKey val id: String,
    val timestampEpochMillis: Long,
    @ColumnInfo(defaultValue = "") val rawText: String,
    val name: String,
    val isFixedFood: Boolean,
    val source: String,
    val confidence: String,
    /** true once detail has been compacted away by retention (Section 12). */
    val detailCompacted: Boolean = false,
    /** see [Entry.timeUnspecified] — the stored timestamp is a noon placeholder. */
    @ColumnInfo(defaultValue = "0") val timeUnspecified: Boolean = false,
    /** see [Entry.needsDetail] — logged words with no identifiable food in them. */
    @ColumnInfo(defaultValue = "0") val needsDetail: Boolean = false,
    /** Row in the bundled food database this resolved to, when it came from there. */
    @ColumnInfo(defaultValue = "NULL") val foodDbId: Long? = null,
    /** see [Entry.items] — JSON, because it is only ever read whole. */
    @ColumnInfo(defaultValue = "NULL") val itemsJson: String? = null,
    /** see [Entry.needsConfirm] — logged from the watch, waiting for a glance. */
    @ColumnInfo(defaultValue = "0") val needsConfirm: Boolean = false,
)

/**
 * One of the user's own foods (layer 1 of the lookup): what a phrase means to
 * them, confirmed once and trusted after. The value is the shared
 * [com.nutritionscribe.shared.resolve.PersonalFood] as JSON — it is always read and
 * written whole, and keeping it opaque means the resolver can grow a field without
 * a migration over the user's data.
 */
@Entity(tableName = "personal_food")
data class PersonalFoodRow(
    @PrimaryKey val key: String,
    val name: String,
    val json: String,
    val timesUsed: Int,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val isMeal: Boolean = false,
)

/**
 * One nutrient value for one entry.
 *
 * Normalised rather than a column per nutrient: this is what lets a new metric ship
 * without a Room migration over the user's only copy of their food log.
 */
@Entity(
    tableName = "entry_nutrient",
    primaryKeys = ["entryId", "metric"],
    indices = [androidx.room.Index("entryId"), androidx.room.Index("metric")],
    foreignKeys = [
        ForeignKey(
            entity = EntryRow::class,
            parentColumns = ["id"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EntryNutrientRow(
    val entryId: String,
    /** [Metric.key]; stored as text so an unknown future metric round-trips safely. */
    val metric: String,
    val amount: Double,
)

/** What the user chose to track, and what they are aiming at. */
@Entity(tableName = "metric_goal")
data class MetricGoalRow(
    @PrimaryKey val metric: String,
    val enabled: Boolean,
    /** null = tracked, but the user set no number. */
    val target: Double?,
    /** Below this reads red. Optional; blank is a valid answer. */
    val floorValue: Double?,
    /** Above this reads red — for sodium, sugar, saturated fat. */
    val ceilingValue: Double?,
    val sortOrder: Int,
    val pinned: Boolean = false,
    /**
     * "REACH"/"LIMIT" when the user has overridden the metric's own default,
     * null when they have not. See MetricGoal.direction.
     */
    val direction: String? = null,
)

data class EntryWithNutrients(
    @Embedded val entry: EntryRow,
    @Relation(parentColumn = "id", entityColumn = "entryId")
    val nutrients: List<EntryNutrientRow>,
)

fun EntryWithNutrients.toEntry() = Entry(
    id = entry.id,
    timestampEpochMillis = entry.timestampEpochMillis,
    rawText = entry.rawText,
    name = entry.name,
    nutrients = nutrients.associate { it.metric to it.amount },
    isFixedFood = entry.isFixedFood,
    source = entry.source,
    confidence = Confidence.valueOf(entry.confidence),
    timeUnspecified = entry.timeUnspecified,
    needsDetail = entry.needsDetail,
    foodDbId = entry.foodDbId,
    items = entry.itemsJson?.let { decodeItems(it) }.orEmpty(),
    needsConfirm = entry.needsConfirm,
)

private val itemsJsonFormat = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

private fun decodeItems(json: String): List<com.nutritionscribe.shared.model.LoggedItem> =
    runCatching {
        itemsJsonFormat.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(com.nutritionscribe.shared.model.LoggedItem.serializer()), json,
        )
    }.getOrDefault(emptyList())

fun Entry.toRow() = EntryRow(
    id = id,
    timestampEpochMillis = timestampEpochMillis,
    rawText = rawText,
    name = name,
    isFixedFood = isFixedFood,
    source = source,
    confidence = confidence.name,
    timeUnspecified = timeUnspecified,
    needsDetail = needsDetail,
    foodDbId = foodDbId,
    itemsJson = items.takeIf { it.isNotEmpty() }?.let {
        itemsJsonFormat.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(com.nutritionscribe.shared.model.LoggedItem.serializer()), it,
        )
    },
    needsConfirm = needsConfirm,
)

fun Entry.toNutrientRows(): List<EntryNutrientRow> =
    nutrients.map { (metric, amount) -> EntryNutrientRow(id, metric, amount) }

@Entity(tableName = "unprocessed_entries")
data class UnprocessedRow(
    @PrimaryKey val id: String,
    val timestampEpochMillis: Long,
    val rawText: String,
    val errorCode: String,
)

fun UnprocessedRow.toModel() = UnprocessedEntry(id, timestampEpochMillis, rawText, errorCode)
fun UnprocessedEntry.toRow() = UnprocessedRow(id, timestampEpochMillis, rawText, errorCode)

/** Fixed-food cache row: exact-name lookup avoids re-calling the LLM for packaged items. */
@Entity(tableName = "fixed_food_cache")
data class FixedFoodCacheRow(
    @PrimaryKey val normalizedName: String,
    val name: String,
    val proteinGrams: Double,
    val calories: Double?,
    val carbsGrams: Double?,
    val fatGrams: Double?,
    val source: String,
)

/**
 * A food the user logs often, used to populate the manual-entry quick-pick so
 * repeat meals are one tap instead of retyping a name and a number every time.
 */
data class FrequentFood(
    val name: String,
    val proteinGrams: Double,
    val useCount: Int,
    val lastUsedEpochMillis: Long,
)

@Dao
interface EntryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRow(entry: EntryRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNutrients(rows: List<EntryNutrientRow>)

    @Query("DELETE FROM entry_nutrient WHERE entryId = :entryId")
    suspend fun clearNutrients(entryId: String)

    /** Entry and its nutrients always move together, so they are written together. */
    @Transaction
    suspend fun upsert(entry: EntryRow, nutrients: List<EntryNutrientRow>) {
        insertRow(entry)
        clearNutrients(entry.id)
        insertNutrients(nutrients)
    }

    @Update
    suspend fun update(entry: EntryRow)

    @Delete
    suspend fun delete(entry: EntryRow)

    @Query("DELETE FROM entries WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Transaction
    @Query("SELECT * FROM entries ORDER BY timestampEpochMillis DESC")
    fun observeAll(): Flow<List<EntryWithNutrients>>

    @Transaction
    @Query(
        "SELECT * FROM entries WHERE timestampEpochMillis BETWEEN :startEpochMillis AND :endEpochMillis" +
            " ORDER BY timestampEpochMillis ASC"
    )
    fun observeBetween(startEpochMillis: Long, endEpochMillis: Long): Flow<List<EntryWithNutrients>>

    @Transaction
    @Query(
        "SELECT * FROM entries WHERE name LIKE '%' || :query || '%' OR rawText LIKE '%' || :query || '%'" +
            " ORDER BY timestampEpochMillis DESC"
    )
    fun search(query: String): Flow<List<EntryWithNutrients>>

    /** Total of one metric over a window — replaces the protein-only sum. */
    @Query(
        "SELECT COALESCE(SUM(n.amount), 0) FROM entry_nutrient n" +
            " JOIN entries e ON e.id = n.entryId" +
            " WHERE n.metric = :metric AND e.timestampEpochMillis BETWEEN :startEpochMillis AND :endEpochMillis"
    )
    fun observeMetricTotal(metric: String, startEpochMillis: Long, endEpochMillis: Long): Flow<Double>

    @Query(
        "SELECT e.name AS name," +
            " COALESCE(MAX(CASE WHEN n.metric = 'protein' THEN n.amount END), 0) AS proteinGrams," +
            " COUNT(*) AS useCount, MAX(e.timestampEpochMillis) AS lastUsedEpochMillis" +
            " FROM entries e LEFT JOIN entry_nutrient n ON n.entryId = e.id" +
            " WHERE e.detailCompacted = 0 AND e.name != '' AND e.needsDetail = 0" +
            " GROUP BY e.name ORDER BY useCount DESC, lastUsedEpochMillis DESC LIMIT :limit"
    )
    fun observeFrequentFoods(limit: Int): Flow<List<FrequentFood>>

    @Query(
        "UPDATE entries SET rawText = '', name = 'Compacted entry', source = '', detailCompacted = 1" +
            " WHERE timestampEpochMillis < :cutoffEpochMillis AND detailCompacted = 0"
    )
    suspend fun compactDetailBefore(cutoffEpochMillis: Long)

    @Query("DELETE FROM entries WHERE timestampEpochMillis < :cutoffEpochMillis")
    suspend fun deleteBefore(cutoffEpochMillis: Long)

    @Query("SELECT COUNT(*) FROM entries")
    suspend fun count(): Int

    @Transaction
    @Query("SELECT * FROM entries WHERE id = :id LIMIT 1")
    suspend fun find(id: String): EntryWithNutrients?

    /** Watch entries waiting for a glance on the phone. */
    @Transaction
    @Query("SELECT * FROM entries WHERE needsConfirm = 1 ORDER BY timestampEpochMillis DESC")
    fun observeNeedsConfirm(): Flow<List<EntryWithNutrients>>

    /**
     * Every logged entry. entry_nutrient is declared ON DELETE CASCADE, so the
     * numbers go with the rows — but only while foreign keys are actually
     * enforced, which is why ResetTest checks the nutrient table is empty too
     * rather than trusting the declaration.
     */
    @Query("DELETE FROM entries")
    suspend fun deleteAll()
}

@Dao
interface MetricGoalDao {
    @Query("SELECT * FROM metric_goal ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<MetricGoalRow>>

    @Query("SELECT * FROM metric_goal WHERE enabled = 1 ORDER BY sortOrder ASC")
    fun observeEnabled(): Flow<List<MetricGoalRow>>

    @Query("SELECT * FROM metric_goal WHERE metric = :metric LIMIT 1")
    suspend fun find(metric: String): MetricGoalRow?

    @Query("SELECT COUNT(*) FROM metric_goal")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: MetricGoalRow)

    @Query("DELETE FROM metric_goal")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<MetricGoalRow>)
}

@Dao
interface UnprocessedDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: UnprocessedRow)

    @Delete
    suspend fun delete(entry: UnprocessedRow)

    @Query("SELECT * FROM unprocessed_entries ORDER BY timestampEpochMillis DESC")
    fun observeAll(): Flow<List<UnprocessedRow>>

    @Query("DELETE FROM unprocessed_entries")
    suspend fun deleteAll()
}

/**
 * Blocking on purpose: the resolver that reads these runs on an IO thread and is
 * plain Kotlin, shared with the JVM tests, so it cannot suspend.
 */
@Dao
interface PersonalFoodDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertBlocking(row: PersonalFoodRow)

    @Query("SELECT * FROM personal_food WHERE `key` = :key LIMIT 1")
    fun findBlocking(key: String): PersonalFoodRow?

    @Query("SELECT * FROM personal_food")
    fun allBlocking(): List<PersonalFoodRow>

    @Query("DELETE FROM personal_food WHERE `key` = :key")
    fun deleteBlocking(key: String)

    @Query("SELECT * FROM personal_food ORDER BY timesUsed DESC, updatedAt DESC")
    fun observeAll(): Flow<List<PersonalFoodRow>>

    @Query("DELETE FROM personal_food")
    suspend fun deleteAll()

    @Query("SELECT * FROM personal_food")
    suspend fun all(): List<PersonalFoodRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<PersonalFoodRow>)
}

@Dao
interface FixedFoodCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: FixedFoodCacheRow)

    @Query("SELECT * FROM fixed_food_cache WHERE normalizedName = :normalizedName LIMIT 1")
    suspend fun find(normalizedName: String): FixedFoodCacheRow?

    @Query("DELETE FROM fixed_food_cache")
    suspend fun deleteAll()
}

@Database(
    entities = [
        EntryRow::class,
        EntryNutrientRow::class,
        MetricGoalRow::class,
        UnprocessedRow::class,
        FixedFoodCacheRow::class,
        PersonalFoodRow::class,
    ],
    version = 5,
)
abstract class NutritionScribeDatabase : RoomDatabase() {
    abstract fun entryDao(): EntryDao
    abstract fun metricGoalDao(): MetricGoalDao
    abstract fun unprocessedDao(): UnprocessedDao
    abstract fun fixedFoodCacheDao(): FixedFoodCacheDao
    abstract fun personalFoodDao(): PersonalFoodDao

    companion object {
        @Volatile private var instance: NutritionScribeDatabase? = null

        /**
         * Adds the backdated-entry flag. Written by hand rather than letting Room
         * fall back to destructive migration — this database is the only copy of the
         * user's food log, so wiping it on a schema bump is never acceptable.
         */
        /**
         * Lets a goal override its metric's direction. Nullable, because null
         * means "whatever the metric says" — which is what every existing goal
         * has been doing, so there is nothing to backfill.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE metric_goal ADD COLUMN direction TEXT DEFAULT NULL")
            }
        }

        /**
         * The cache-first lookup: the user's own foods, and each entry's items.
         * Additive only — every existing entry keeps every value it had, and simply
         * has no item breakdown (it was logged before there was one).
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN itemsJson TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE entries ADD COLUMN needsConfirm INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `personal_food` (`key` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`json` TEXT NOT NULL, `timesUsed` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "`isMeal` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`key`))"
                )
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN timeUnspecified INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Normalises nutrients out of `entries` and into `entry_nutrient`, and adds
         * the per-metric goals table.
         *
         * Every existing protein/calorie/carb/fat value is COPIED ACROSS before the
         * old columns go; `entries` then has to be rebuilt, because SQLite cannot
         * drop columns on the versions this app supports. Nothing here is allowed to
         * lose a row — see MigrationTest, which replays a real v2 database through it.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `entry_nutrient` (" +
                        "`entryId` TEXT NOT NULL, `metric` TEXT NOT NULL, `amount` REAL NOT NULL, " +
                        "PRIMARY KEY(`entryId`, `metric`), " +
                        "FOREIGN KEY(`entryId`) REFERENCES `entries`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_entry_nutrient_entryId` ON `entry_nutrient` (`entryId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_entry_nutrient_metric` ON `entry_nutrient` (`metric`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `metric_goal` (" +
                        "`metric` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `target` REAL, " +
                        "`floorValue` REAL, `ceilingValue` REAL, `sortOrder` INTEGER NOT NULL, " +
                        "`pinned` INTEGER NOT NULL, PRIMARY KEY(`metric`))"
                )

                // Carry the old columns over. NULL means "never recorded" and must stay
                // absent rather than becoming a zero the user never logged.
                for ((column, metric) in listOf(
                    "proteinGrams" to Metric.PROTEIN.key,
                    "calories" to Metric.CALORIES.key,
                    "carbsGrams" to Metric.CARBS.key,
                    "fatGrams" to Metric.FAT.key,
                )) {
                    db.execSQL(
                        "INSERT OR REPLACE INTO entry_nutrient (entryId, metric, amount) " +
                            "SELECT id, '$metric', $column FROM entries WHERE $column IS NOT NULL"
                    )
                }

                db.execSQL(
                    "CREATE TABLE `entries_new` (" +
                        "`id` TEXT NOT NULL, `timestampEpochMillis` INTEGER NOT NULL, " +
                        "`rawText` TEXT NOT NULL DEFAULT '', `name` TEXT NOT NULL, " +
                        "`isFixedFood` INTEGER NOT NULL, `source` TEXT NOT NULL, " +
                        "`confidence` TEXT NOT NULL, `detailCompacted` INTEGER NOT NULL, " +
                        "`timeUnspecified` INTEGER NOT NULL DEFAULT 0, " +
                        "`needsDetail` INTEGER NOT NULL DEFAULT 0, " +
                        "`foodDbId` INTEGER DEFAULT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT INTO entries_new (id, timestampEpochMillis, rawText, name, isFixedFood, " +
                        "source, confidence, detailCompacted, timeUnspecified, needsDetail, foodDbId) " +
                        "SELECT id, timestampEpochMillis, rawText, name, isFixedFood, source, " +
                        "confidence, detailCompacted, timeUnspecified, 0, NULL FROM entries"
                )
                db.execSQL("DROP TABLE entries")
                db.execSQL("ALTER TABLE entries_new RENAME TO entries")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_entries_timestampEpochMillis` ON `entries` (`timestampEpochMillis`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_entries_name` ON `entries` (`name`)")
            }
        }

        fun get(context: Context): NutritionScribeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NutritionScribeDatabase::class.java,
                    // Deliberately still the old name. The file on disk is invisible to
                    // the user, but renaming it would leave every existing log stranded
                    // in a file nothing opens any more — a rebrand must not cost data.
                    "protein-scribe.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build().also { instance = it }
            }
    }
}
