package com.nutritionscribe.app.data.resolve

import com.nutritionscribe.app.data.db.PersonalFoodDao
import com.nutritionscribe.app.data.db.PersonalFoodRow
import com.nutritionscribe.shared.resolve.PersonalFood
import com.nutritionscribe.shared.resolve.PersonalStore
import kotlinx.serialization.json.Json

/**
 * The user's own foods, in Room.
 *
 * Every lookup scans them for near matches, so they are held in memory after the
 * first read — a few hundred small objects — and every write goes to both. Blocking
 * calls: the resolver runs on an IO thread.
 */
class RoomPersonalStore(private val dao: PersonalFoodDao) : PersonalStore {

    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var cache: LinkedHashMap<String, PersonalFood>? = null

    private fun loaded(): LinkedHashMap<String, PersonalFood> = cache ?: synchronized(this) {
        cache ?: LinkedHashMap<String, PersonalFood>().also { map ->
            for (row in dao.allBlocking()) {
                decode(row)?.let { map[it.key] = it }
            }
            cache = map
        }
    }

    fun decode(row: PersonalFoodRow): PersonalFood? =
        runCatching { json.decodeFromString(PersonalFood.serializer(), row.json) }.getOrNull()

    fun encode(food: PersonalFood): PersonalFoodRow = PersonalFoodRow(
        key = food.key,
        name = food.name,
        json = json.encodeToString(PersonalFood.serializer(), food),
        timesUsed = food.timesUsed,
        updatedAt = food.updatedAt,
        isMeal = food.isMeal,
    )

    override fun get(key: String): PersonalFood? = loaded()[key]

    override fun all(): List<PersonalFood> = synchronized(this) { loaded().values.toList() }

    override fun put(food: PersonalFood) {
        dao.upsertBlocking(encode(food))
        synchronized(this) { loaded()[food.key] = food }
    }

    override fun remove(key: String) {
        dao.deleteBlocking(key)
        synchronized(this) { loaded().remove(key) }
    }

    /** After an import or a reset replaced the table underneath. */
    fun invalidate() {
        cache = null
    }
}
