package com.nutritionscribe.app.data.repository

import com.nutritionscribe.app.data.db.EntryDao
import com.nutritionscribe.app.data.db.FixedFoodCacheDao
import com.nutritionscribe.app.data.db.MetricGoalDao
import com.nutritionscribe.app.data.db.UnprocessedDao

/**
 * Erasing the app's own data, a category at a time.
 *
 * The app stores state in three unrelated places — Room, DataStore and
 * EncryptedSharedPreferences — so "start again" was previously only possible by
 * uninstalling, which is a blunt thing to ask of someone who just wants to clear
 * a week of bad test entries but keep their goals and their key.
 *
 * The three categories are what a person would actually name, not how the
 * storage happens to be split:
 *
 *   food log  — entries and their nutrients, the unprocessed queue, the old
 *               fixed-food cache and the user's own foods. Those are learned
 *               from logging, so keeping them after wiping the log would log
 *               foods silently from a history that no longer exists.
 *   settings  — every preference AND the metric goals, because someone who says
 *               "reset my settings" means the goals too; they are only in a
 *               different table for query reasons.
 *   API key   — every stored key, for every backend.
 *   label pack — the 40 MB of package labels. Kept by default: it is not the
 *               user's data, it is slow to get back, and nothing in it is personal.
 *
 * The AI spending history goes with the food log: its rows point at entries.
 */
class ResetManager(
    private val entryDao: EntryDao,
    private val unprocessedDao: UnprocessedDao,
    private val fixedFoodCacheDao: FixedFoodCacheDao,
    private val metricGoalDao: MetricGoalDao,
    private val settingsRepository: SettingsRepository,
    private val personalFoodDao: com.nutritionscribe.app.data.db.PersonalFoodDao? = null,
    /** The same foods through the store, to tell scanned labels from the rest. */
    private val personalStore: com.nutritionscribe.shared.resolve.PersonalStore? = null,
    private val onPersonalFoodsCleared: () -> Unit = {},
    private val aiUsageDao: com.nutritionscribe.app.data.usage.AiUsageDao? = null,
    private val labelPack: com.nutritionscribe.app.data.resolve.LabelPack? = null,
) {

    /** What a reset will destroy, so the confirmation can say so in numbers. */
    data class Scope(
        /** The logged entries, the unprocessed queue and the AI spending history. */
        val foodLog: Boolean,
        val settings: Boolean,
        val apiKey: Boolean,
        val labelPack: Boolean = false,
        /**
         * Labels they scanned or picked. Kept apart because starting a fresh log is
         * not starting from nothing: their milk is still their milk.
         */
        val scannedFoods: Boolean = foodLog,
        /** Everything else learned from logging: remembered portions, AI answers, saved meals. */
        val learnedFoods: Boolean = foodLog,
    ) {
        val erasesAnything: Boolean get() = foodLog || settings || apiKey || labelPack || scannedFoods || learnedFoods
    }

    fun hasLabelPack(): Boolean = labelPack?.isInstalled == true

    suspend fun loggedEntryCount(): Int = entryDao.count()

    /** Saved foods by kind: scanned labels, and everything else learned. */
    fun savedFoodCounts(): Pair<Int, Int> {
        val all = personalStore?.all().orEmpty()
        val scanned = all.count { !it.isMeal && it.candidate?.origin == com.nutritionscribe.shared.resolve.Origin.LABEL }
        return scanned to (all.size - scanned)
    }

    fun hasApiKey(): Boolean = settingsRepository.hasAnyApiKey()

    /**
     * Erases the chosen categories and leaves the rest untouched.
     *
     * Deliberately NOT transactional across the three stores: Room, DataStore and
     * EncryptedSharedPreferences have no shared transaction, so a failure part-way
     * leaves some categories erased. That is the honest behaviour for a
     * destructive action — a half-finished reset that reports success would be
     * worse than one that stops.
     *
     * Runs on the IO dispatcher: the personal store deletes one food at a time with
     * blocking calls, which Room refuses on the main thread (1.3-test crashed here).
     */
    suspend fun reset(scope: Scope) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (scope.foodLog) {
            // Order matters only for readability; entry_nutrient goes with its
            // entries by ON DELETE CASCADE.
            entryDao.deleteAll()
            unprocessedDao.deleteAll()
            aiUsageDao?.deleteAll()
        }
        if (scope.learnedFoods) fixedFoodCacheDao.deleteAll()
        if (scope.scannedFoods && scope.learnedFoods) {
            personalFoodDao?.deleteAll()
            onPersonalFoodsCleared()
        } else if (scope.scannedFoods || scope.learnedFoods) {
            val store = personalStore
            if (store != null) {
                for (f in store.all()) {
                    val scanned = !f.isMeal && f.candidate?.origin == com.nutritionscribe.shared.resolve.Origin.LABEL
                    if ((scanned && scope.scannedFoods) || (!scanned && scope.learnedFoods)) store.remove(f.key)
                }
            }
        }
        if (scope.settings) {
            metricGoalDao.deleteAll()
            settingsRepository.clearAllPreferences()
        }
        if (scope.apiKey) {
            settingsRepository.clearApiKeys()
        }
        if (scope.labelPack) {
            labelPack?.remove()
        }
    }
}
