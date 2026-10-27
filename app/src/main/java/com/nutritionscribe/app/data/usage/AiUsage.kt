package com.nutritionscribe.app.data.usage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One paid call to an AI provider, as it happened.
 *
 * The cost is worked out and stored at the moment of the call rather than
 * recomputed later, so a price change never rewrites what an old month cost —
 * the tokens are kept alongside so it can still be checked.
 */
@Entity(
    tableName = "ai_usage",
    indices = [Index("timestampEpochMillis"), Index("entryId"), Index("tag")],
)
data class AiUsageRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampEpochMillis: Long,
    /** "openai", "anthropic". */
    val provider: String,
    val model: String,
    /** What it was for: [UsageKind] by name. */
    val kind: String,
    val inputTokens: Long = 0,
    val cachedInputTokens: Long = 0,
    val outputTokens: Long = 0,
    /** For speech, which is billed by the minute. */
    val audioSeconds: Double = 0.0,
    val costUsd: Double,
    val durationMillis: Long = 0,
    /** HTTP status; a failed call can still be billed, and the chart should say it happened. */
    val status: Int = 200,
    /** Groups the calls made while one entry was being logged, until it has an id. */
    val tag: String? = null,
    /** The entry these calls produced, once it was logged. */
    val entryId: String? = null,
)

enum class UsageKind(val label: String) {
    FOOD_LOOKUP("Food lookups"),
    SPEECH("Speech to text"),
    ASK("Ask chat"),
    OTHER("Other"),
}

@Dao
interface AiUsageDao {
    @Insert
    fun insertBlocking(row: AiUsageRow): Long

    @Query("SELECT * FROM ai_usage ORDER BY timestampEpochMillis ASC")
    fun observeAll(): Flow<List<AiUsageRow>>

    @Query("SELECT * FROM ai_usage WHERE entryId = :entryId")
    fun observeForEntry(entryId: String): Flow<List<AiUsageRow>>

    @Query("UPDATE ai_usage SET entryId = :entryId WHERE tag = :tag AND entryId IS NULL")
    suspend fun attach(tag: String, entryId: String)

    @Query("SELECT COUNT(*) FROM ai_usage")
    suspend fun count(): Int

    @Query("SELECT COALESCE(SUM(costUsd), 0) FROM ai_usage WHERE timestampEpochMillis >= :since")
    fun observeCostSince(since: Long): Flow<Double>

    @Query("DELETE FROM ai_usage")
    suspend fun deleteAll()
}
