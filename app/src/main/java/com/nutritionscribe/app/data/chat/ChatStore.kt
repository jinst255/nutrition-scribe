package com.nutritionscribe.app.data.chat

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** One Ask conversation, listed under ☰. */
@Entity(tableName = "chat")
data class ChatRow(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * One message. [extraJson] carries what the bubble draws besides its words: the
 * ideas, the "where you'd be" bars, and a saved plan (see [ChatExtra]).
 */
@Entity(tableName = "chat_message", indices = [Index("chatId")])
data class ChatMessageRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: String,
    /** "user" or "assistant". */
    val role: String,
    val text: String,
    val at: Long,
    val extraJson: String? = null,
)

/**
 * A meal picked in Ask: a plan, never a log. After [planFor] Home asks
 * "Did you have …?" and only the answer logs anything.
 */
@Entity(tableName = "meal_plan", indices = [Index("planFor")])
data class MealPlanRow(
    @PrimaryKey val id: String,
    val chatId: String?,
    val name: String,
    /** [PlanItem] list as JSON: each part with its grams, so "Half" can scale it. */
    val itemsJson: String,
    /** "lunch", "dinner", "a snack": what Home's question calls it. */
    val mealLabel: String,
    val planFor: Long,
    /** PLANNED, LOGGED, SKIPPED (something else, or not now). */
    val status: String,
    val createdAt: Long,
    val answeredAt: Long? = null,
    val entryId: String? = null,
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat ORDER BY updatedAt DESC")
    fun observeChats(): Flow<List<ChatRow>>

    @Query("SELECT * FROM chat WHERE id = :id")
    suspend fun chat(id: String): ChatRow?

    @Upsert
    suspend fun upsertChat(row: ChatRow)

    @Query("DELETE FROM chat WHERE id = :id")
    suspend fun deleteChat(id: String)

    @Query("DELETE FROM chat_message WHERE chatId = :id")
    suspend fun deleteMessages(id: String)

    @Query("SELECT * FROM chat_message WHERE chatId = :chatId ORDER BY at, id")
    fun observeMessages(chatId: String): Flow<List<ChatMessageRow>>

    @Query("SELECT * FROM chat_message WHERE chatId = :chatId ORDER BY at, id")
    suspend fun messages(chatId: String): List<ChatMessageRow>

    @Query("SELECT chatId FROM chat_message WHERE text LIKE '%' || :q || '%' GROUP BY chatId")
    suspend fun chatsMatching(q: String): List<String>

    @Insert
    suspend fun insertMessage(row: ChatMessageRow): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlan(row: MealPlanRow)

    @Query("SELECT * FROM meal_plan WHERE status = 'PLANNED' ORDER BY planFor")
    fun observePlanned(): Flow<List<MealPlanRow>>

    @Query("SELECT * FROM meal_plan WHERE id = :id")
    suspend fun plan(id: String): MealPlanRow?

    @Query("SELECT * FROM chat_message ORDER BY at DESC, id DESC LIMIT 1")
    fun observeLatestMessage(): Flow<ChatMessageRow?>
}
