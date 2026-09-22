package com.nutritionscribe.app.di

import android.content.Context
import com.nutritionscribe.app.data.cache.FixedFoodCache
import com.nutritionscribe.app.data.food.FoodDatabase
import com.nutritionscribe.app.data.db.NutritionScribeDatabase
import com.nutritionscribe.app.data.repository.AiBackendKind
import com.nutritionscribe.app.data.repository.EntryRepository
import com.nutritionscribe.app.data.repository.LookupSource
import com.nutritionscribe.app.data.repository.SettingsRepository
import com.nutritionscribe.app.network.anthropic.AnthropicBackend
import com.nutritionscribe.app.network.backend.AiBackend
import com.nutritionscribe.app.network.openai.OpenAiBackend
import com.nutritionscribe.app.ui.debuglog.DebugLogger
import com.nutritionscribe.app.ui.exportimport.ExportImportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Small manual dependency container. No DI framework — the module count doesn't
 * justify Hilt's build-time cost for a personal-scale app (Section 15 keeps the
 * dependency list intentionally short).
 */
object ServiceLocator {
    private lateinit var appContext: Context
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var database: NutritionScribeDatabase
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var debugLogger: DebugLogger
        private set
    lateinit var fixedFoodCache: FixedFoodCache
        private set
    lateinit var exportImportManager: ExportImportManager
        private set
    lateinit var foodDatabase: FoodDatabase
        private set

    /**
     * Warm cache of the current backend choice, kept in sync in the background so
     * [currentEntryRepository] never has to block the calling (often main) thread on a
     * DataStore read — that blocking was a real contributor to slow cold starts.
     */
    private val backendCache = MutableStateFlow(AiBackendKind.OPENAI)
    private val lookupSourceCache = MutableStateFlow(LookupSource.AI)

    fun init(context: Context) {
        appContext = context.applicationContext
        database = NutritionScribeDatabase.get(appContext)
        settingsRepository = SettingsRepository(appContext)
        debugLogger = DebugLogger(appContext)
        fixedFoodCache = FixedFoodCache(database.fixedFoodCacheDao())
        exportImportManager = ExportImportManager(appContext, database.entryDao())
        foodDatabase = FoodDatabase(appContext)
        backgroundScope.launch {
            settingsRepository.backend.collect { backendCache.value = it }
        }
        backgroundScope.launch {
            settingsRepository.lookupSource.collect { lookupSourceCache.value = it }
        }
    }

    /** Builds a fresh repository bound to whichever backend/key is currently configured. */
    fun currentEntryRepository(): EntryRepository {
        val backendKind = backendCache.value
        val apiKey = settingsRepository.getApiKey(backendKind).orEmpty()
        val backend: AiBackend = when (backendKind) {
            AiBackendKind.OPENAI -> OpenAiBackend(apiKey)
            AiBackendKind.ANTHROPIC -> AnthropicBackend(apiKey)
        }
        return EntryRepository(
            entryDao = database.entryDao(),
            unprocessedDao = database.unprocessedDao(),
            fixedFoodCache = fixedFoodCache,
            backend = backend,
            debugLogger = debugLogger,
            foodDatabase = foodDatabase,
            // Falls back to the offline database when AI is preferred but no key
            // is set, so someone handed the app still gets numbers, not an error.
            lookupSource = settingsRepository.effectiveLookupSource(lookupSourceCache.value),
        )
    }
}
