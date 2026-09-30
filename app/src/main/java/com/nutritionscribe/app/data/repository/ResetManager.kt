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
    private val onPersonalFoodsCleared: () -> Unit = {},
    private val aiUsageDao: com.nutritionscribe.app.data.usage.AiUsageDao? = null,
    private val labelPack: com.nutritionscribe.app.data.resolve.LabelPack? = null,
) {

    /** What a reset will destroy, so the confirmation can say so in numbers. */
    data class Scope(
        val foodLog: Boolean,
        val settings: Boolean,
        val apiKey: Boolean,
        val labelPack: Boolean = false,
    ) {
        val erasesAnything: Boolean get() = foodLog || settings || apiKey || labelPack
    }

    fun hasLabelPack(): Boolean = labelPack?.isInstalled == true

    suspend fun loggedEntryCount(): Int = entryDao.count()

    fun hasApiKey(): Boolean = settingsRepository.hasAnyApiKey()

    /**
     * Erases the chosen categories and leaves the rest untouched.
     *
     * Deliberately NOT transactional across the three stores: Room, DataStore and
     * EncryptedSharedPreferences have no shared transaction, so a failure part-way
     * leaves some categories erased. That is the honest behaviour for a
     * destructive action — a half-finished reset that reports success would be
     * worse than one that stops.
     */
    suspend fun reset(scope: Scope) {
        if (scope.foodLog) {
            // Order matters only for readability; entry_nutrient goes with its
            // entries by ON DELETE CASCADE.
            entryDao.deleteAll()
            unprocessedDao.deleteAll()
            fixedFoodCacheDao.deleteAll()
            personalFoodDao?.deleteAll()
            onPersonalFoodsCleared()
            aiUsageDao?.deleteAll()
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
