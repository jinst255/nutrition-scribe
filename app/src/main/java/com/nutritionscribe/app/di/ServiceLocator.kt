package com.nutritionscribe.app.di

import android.content.Context
import com.nutritionscribe.app.data.cache.FixedFoodCache
import com.nutritionscribe.app.data.food.FoodDatabase
import com.nutritionscribe.app.data.db.NutritionScribeDatabase
import com.nutritionscribe.app.data.db.toEntry
import com.nutritionscribe.app.data.db.toNutrientRows
import com.nutritionscribe.app.data.db.toRow
import com.nutritionscribe.app.data.repository.AiBackendKind
import com.nutritionscribe.app.data.repository.EntryRepository
import com.nutritionscribe.app.data.repository.GoalsRepository
import com.nutritionscribe.app.data.repository.LookupSource
import com.nutritionscribe.app.data.repository.ResetManager
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Small manual dependency container. No DI framework — the module count doesn't
 * justify Hilt's build-time cost for a personal-scale app (Section 15 keeps the
 * dependency list intentionally short).
 */
object ServiceLocator {
    lateinit var appContext: Context
        private set
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
    lateinit var goalsRepository: GoalsRepository
        private set
    lateinit var resetManager: ResetManager
        private set
    lateinit var personalStore: com.nutritionscribe.app.data.resolve.RoomPersonalStore
        private set
    lateinit var labelPack: com.nutritionscribe.app.data.resolve.LabelPack
        private set
    lateinit var logPipeline: com.nutritionscribe.app.data.resolve.LogPipeline
        private set
    lateinit var debugEvents: com.nutritionscribe.app.ui.debuglog.DebugEvents
        private set
    lateinit var usageMeter: com.nutritionscribe.app.data.usage.UsageMeter
        private set
    lateinit var microBackfill: com.nutritionscribe.app.data.resolve.MicroBackfill
        private set

    /** The older OkHttp backends, metered like everything else. */
    private val meteredClient by lazy {
        okhttp3.OkHttpClient.Builder().addInterceptor(usageMeter.interceptor()).build()
    }

    /**
     * Warm cache of the current backend choice, kept in sync in the background so
     * [currentEntryRepository] never has to block the calling (often main) thread on a
     * DataStore read — that blocking was a real contributor to slow cold starts.
     */
    private val backendCache = MutableStateFlow(AiBackendKind.OPENAI)
    private val lookupSourceCache = MutableStateFlow(LookupSource.AI)
    /** The user's AI consent, warm for the same reason: every send checks it. */
    private val consentCache = MutableStateFlow<Set<String>>(emptySet())
    private val profileCache = MutableStateFlow<com.nutritionscribe.shared.model.Profile?>(null)

    /** The body the user described, or null; for code that can't collect a flow. */
    fun profileNow(): com.nutritionscribe.shared.model.Profile? = profileCache.value

    /** True once the user agreed to send their data to [kind] (see AiConsent). */
    fun aiAllowed(kind: AiBackendKind): Boolean =
        com.nutritionscribe.app.data.repository.AiConsent.agreed(consentCache.value, kind)

    fun init(context: Context) {
        appContext = context.applicationContext
        database = NutritionScribeDatabase.get(appContext)
        settingsRepository = SettingsRepository(appContext)
        debugEvents = com.nutritionscribe.app.ui.debuglog.DebugEvents(appContext)
        debugLogger = DebugLogger(appContext, debugEvents)
        usageMeter = com.nutritionscribe.app.data.usage.UsageMeter(database.aiUsageDao(), debugEvents)
        fixedFoodCache = FixedFoodCache(database.fixedFoodCacheDao())
        exportImportManager = ExportImportManager(
            appContext, database.entryDao(), database.personalFoodDao(),
            onPersonalFoodsChanged = { personalStore.invalidate() },
        )
        foodDatabase = FoodDatabase(appContext)
        goalsRepository = GoalsRepository(database.metricGoalDao(), settingsRepository)

        personalStore = com.nutritionscribe.app.data.resolve.RoomPersonalStore(database.personalFoodDao())
        labelPack = com.nutritionscribe.app.data.resolve.LabelPack(appContext)
        logPipeline = com.nutritionscribe.app.data.resolve.LogPipeline(
            entryDao = database.entryDao(),
            personal = personalStore,
            usda = { foodDatabase.reader()?.let { com.nutritionscribe.shared.resolve.UsdaFoodSource(it) } },
            labels = labelPack,
            ai = ::currentAiLookup,
            debugLogger = debugLogger,
            events = debugEvents,
            meter = usageMeter,
        )
        resetManager = ResetManager(
            entryDao = database.entryDao(),
            unprocessedDao = database.unprocessedDao(),
            fixedFoodCacheDao = database.fixedFoodCacheDao(),
            metricGoalDao = database.metricGoalDao(),
            settingsRepository = settingsRepository,
            personalFoodDao = database.personalFoodDao(),
            onPersonalFoodsCleared = { personalStore.invalidate() },
            aiUsageDao = database.aiUsageDao(),
            labelPack = labelPack,
        )
        backgroundScope.launch {
            // Carries a pre-Goals-tab protein goal and baseline across from
            // DataStore, which the Room migration had no way to read.
            goalsRepository.ensureSeeded(
                legacyGoalGrams = settingsRepository.dailyGoalGrams.first(),
                legacyBaselineGrams = settingsRepository.dailyBaselineGrams.first(),
            )
            if (!settingsRepository.goalLinesV2.first()) {
                val cleared = goalsRepository.clearAutomaticLimits()
                settingsRepository.setGoalLinesV2()
                debugEvents.always("goal_lines_cleared", "count" to cleared)
            }
        }
        microBackfill = com.nutritionscribe.app.data.resolve.MicroBackfill(
            entryDao = database.entryDao(),
            personal = personalStore,
            usda = { foodDatabase.reader()?.let { com.nutritionscribe.shared.resolve.UsdaFoodSource(it) } },
            labels = { labelPack.source() },
            events = debugEvents,
        )
        labelPack.onInstalled = { backgroundScope.launch { runCatching { microBackfill.run() } } }
        backgroundScope.launch {
            // First, so the backfill below can already borrow the pack's micros.
            val bundled = runCatching { labelPack.installBundled() }
                .onSuccess { it?.let { st -> debugEvents.always("label_pack_bundled", "status" to st.toString()) } }
                .onFailure { debugLogger.log("LabelPack", "bundled install failed: ${it.message}") }
                .getOrNull()
            // A new pack can fill in foods already logged, same as an install from Settings.
            if (bundled is com.nutritionscribe.app.data.resolve.LabelPack.Status.Installed ||
                settingsRepository.microBackfillVersion.first() < com.nutritionscribe.app.data.resolve.MicroBackfill.VERSION) {
                runCatching { microBackfill.run() }
                    .onSuccess { settingsRepository.setMicroBackfillVersion(com.nutritionscribe.app.data.resolve.MicroBackfill.VERSION) }
                    .onFailure { debugLogger.log("MicroBackfill", "failed: ${it.message}") }
            }
            // After the backfill, in the same coroutine: both rewrite entries, and two
            // passes racing would each put back the other's older copy.
            if (settingsRepository.nutrientRepairVersion.first() < NUTRIENT_REPAIR_VERSION) {
                runCatching { repairSavedFood() }
                    .onSuccess { settingsRepository.setNutrientRepairVersion(NUTRIENT_REPAIR_VERSION) }
                    .onFailure { debugLogger.log("NutrientRepair", "failed: ${it.message}") }
            }
        }
        backgroundScope.launch {
            settingsRepository.backend.collect { backendCache.value = it }
        }
        backgroundScope.launch {
            settingsRepository.lookupSource.collect { lookupSourceCache.value = it }
        }
        backgroundScope.launch {
            settingsRepository.aiConsent.collect { consentCache.value = it }
        }
        backgroundScope.launch {
            settingsRepository.profile.collect { profileCache.value = it }
        }
        backgroundScope.launch {
            settingsRepository.ensureInstalledAt(System.currentTimeMillis())
            settingsRepository.testerName.collect { debugEvents.testerName = it.ifBlank { null } }
        }
        backgroundScope.launch {
            var first = true
            settingsRepository.debugMode.collect { on ->
                debugEvents.enabled = on
                if (on && first) debugEvents.event("app_start", appSnapshot() + ("last_exit" to lastExits(1).firstOrNull()))
                first = false
            }
        }
    }

    /**
     * Layer 4, or null. Only ever with the user's own key, and only while they
     * have AI help switched on: the app works fully without it.
     */
    fun currentAiLookup(): com.nutritionscribe.shared.resolve.AiItemLookup? {
        if (lookupSourceCache.value != LookupSource.AI) return null
        val kind = backendCache.value
        // Nothing leaves the phone for a provider the user hasn't agreed to.
        if (!aiAllowed(kind)) return null
        val key = settingsRepository.getApiKey(kind)?.takeIf { it.isNotBlank() } ?: return null
        return when (kind) {
            AiBackendKind.OPENAI -> com.nutritionscribe.shared.resolve.OpenAiItemLookup(key, transport = usageMeter.transport())
            AiBackendKind.ANTHROPIC -> com.nutritionscribe.shared.resolve.AnthropicItemLookup(key, transport = usageMeter.transport())
        }
    }

    /** Highest quality mode's speech, or null: needs the mode on and an OpenAI key. */
    fun cloudTranscriber(): com.nutritionscribe.app.data.speech.OpenAiTranscriber? {
        if (lookupSourceCache.value != LookupSource.AI) return null
        if (!aiAllowed(AiBackendKind.OPENAI)) return null
        val key = settingsRepository.openAiKey() ?: return null
        return com.nutritionscribe.app.data.speech.OpenAiTranscriber(key, usageMeter)
    }

    /** Who and what, at the top of a debug log: enough to reproduce a problem. */
    fun appSnapshot(): Map<String, Any?> {
        val pm = appContext.packageManager
        val info = runCatching { pm.getPackageInfo(appContext.packageName, 0) }.getOrNull()
        return mapOf(
            "tester" to debugEvents.testerName,
            "app_version" to info?.versionName,
            "app_version_code" to info?.let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() },
            "installed_at" to info?.lastUpdateTime,
            "device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "android_sdk" to android.os.Build.VERSION.SDK_INT,
            "lookup_source" to lookupSourceCache.value,
            "backend" to backendCache.value,
            "has_openai_key" to (settingsRepository.getApiKey(AiBackendKind.OPENAI)?.isNotBlank() == true),
            "has_anthropic_key" to (settingsRepository.getApiKey(AiBackendKind.ANTHROPIC)?.isNotBlank() == true),
            "label_pack_installed" to labelPack.isInstalled,
            "food_db_loaded" to (foodDatabase.reader() != null),
            "speech_recognition_available" to android.speech.SpeechRecognizer.isRecognitionAvailable(appContext),
        )
    }

    /**
     * Why the app's process ended the last few times: a crash, an ANR, the phone
     * reclaiming memory, the user swiping it away. The one thing the app cannot
     * log about itself is its own death, and Android keeps a record of it.
     */
    fun lastExits(n: Int = 10): List<Map<String, Any?>> {
        if (android.os.Build.VERSION.SDK_INT < 30) return emptyList()
        val am = appContext.getSystemService(android.app.ActivityManager::class.java) ?: return emptyList()
        return runCatching {
            am.getHistoricalProcessExitReasons(null, 0, n).map { x ->
                mapOf(
                    "at" to java.time.Instant.ofEpochMilli(x.timestamp).atZone(java.time.ZoneId.systemDefault()).toString(),
                    "reason" to exitReason(x.reason),
                    "description" to x.description,
                    "importance" to x.importance,
                    "pss_kb" to x.pss,
                    "status" to x.status,
                    // An ANR's trace is the main thread's stack at the moment it froze.
                    "trace" to if (x.reason == android.app.ApplicationExitInfo.REASON_ANR || x.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE)
                        runCatching { x.traceInputStream?.bufferedReader()?.use { it.readText().take(20_000) } }.getOrNull() else null,
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun exitReason(r: Int): String = when (r) {
        android.app.ApplicationExitInfo.REASON_ANR -> "anr"
        android.app.ApplicationExitInfo.REASON_CRASH -> "crash"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
        android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
        android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
        android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage"
        android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
        android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure"
        android.app.ApplicationExitInfo.REASON_OTHER -> "other"
        else -> "unknown_$r"
    }

    /**
     * The files that go into a debug export beside the events: the log as it is
     * actually stored, so a wrong number can be traced to the item and food it
     * came from even when the event that made it is long gone.
     */
    /**
     * A scanned product carries only the micronutrients its label prints (five,
     * on a US label). The same food offline lends the rest, when it really is the
     * same food by calories and protein per gram (see MicroFill.sameFood).
     */
    fun withBorrowedMicros(c: com.nutritionscribe.shared.resolve.FoodCandidate): com.nutritionscribe.shared.resolve.FoodCandidate =
        runCatching {
            val usda = foodDatabase.reader()?.let { com.nutritionscribe.shared.resolve.UsdaFoodSource(it) }
            val offline = usda?.search(c.name, limit = 3).orEmpty() + labelPack.source()?.search(c.name, limit = 2).orEmpty()
            val n = c.perServing ?: c.per100g ?: return c
            val grams = if (c.perServing != null) c.labelServingGrams ?: c.defaultGrams else 100.0
            com.nutritionscribe.shared.resolve.MicroFill.sameFood(offline.filter { it.id != c.id }, n, grams)
                ?.let { com.nutritionscribe.shared.resolve.MicroFill.withMicros(c, it) } ?: c
        }.getOrDefault(c)

    val openFoodFacts = com.nutritionscribe.shared.resolve.OpenFoodFactsSource()

    suspend fun debugExtras(): Map<String, String> {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val since = System.currentTimeMillis() - 45L * 86_400_000L
        val entries = runCatching {
            database.entryDao().all().map { it.toEntry() }.filter { it.timestampEpochMillis >= since }
        }.getOrDefault(emptyList())
        val entryLines = entries.joinToString("\n") { e ->
            val line = json.encodeToJsonElement(com.nutritionscribe.shared.model.Entry.serializer(), e).toString()
            // The check that flags a scan, run on what was actually logged.
            val suspect = com.nutritionscribe.shared.model.NutrientSanity.codes(e.nutrients) +
                e.items.flatMap { i -> com.nutritionscribe.shared.model.NutrientSanity.codes(i.nutrients, i.grams).map { "${i.name}: $it" } }
            if (suspect.isEmpty()) line else line.dropLast(1) + ",\"suspect\":" + com.nutritionscribe.app.ui.debuglog.DebugEvents.toJson(suspect) + "}"
        }
        val foods = runCatching { personalStore.all() }.getOrDefault(emptyList()).joinToString("\n") {
            json.encodeToJsonElement(com.nutritionscribe.shared.resolve.PersonalFood.serializer(), it).toString()
        }
        val s = settingsRepository
        val state = mapOf(
            "app" to appSnapshot(),
            "session" to debugEvents.session,
            "settings" to mapOf(
                "mic_mode" to runCatching { s.micMode.first() }.getOrNull(),
                "manual_mode" to runCatching { s.manualMode.first() }.getOrNull(),
                "lookup_source" to runCatching { s.lookupSource.first() }.getOrNull(),
                "backend" to runCatching { s.backend.first() }.getOrNull(),
                "theme" to runCatching { s.theme.first() }.getOrNull(),
                "micro_backfill_version" to runCatching { s.microBackfillVersion.first() }.getOrNull(),
                "retain_totals" to runCatching { s.retainProteinTotals.first() }.getOrNull(),
                "retain_details" to runCatching { s.retainFoodDetails.first() }.getOrNull(),
                "ai_consent" to runCatching { s.aiConsent.first() }.getOrNull(),
                "nutrient_repair_version" to runCatching { s.nutrientRepairVersion.first() }.getOrNull(),
                "first_run" to runCatching { s.installedAt.first() }.getOrNull(),
                "log_sent_at" to runCatching { s.logSentAt.first() }.getOrNull(),
            ),
            // Body stats are what every reference on Home comes from; a log without
            // them can't explain why a bar looked full.
            "profile" to runCatching { s.profile.first() }.getOrNull()?.let {
                mapOf("sex" to it.sex, "age" to it.ageYears, "height_cm" to Math.round(it.heightCm), "weight_kg" to Math.round(it.weightKg),
                    "activity" to it.activity, "aim" to it.aim, "stage" to it.stage, "diet" to it.diet, "plasma_donor" to it.plasmaDonor)
            },
            "plasma_donations" to runCatching { s.plasmaDonations.first().map { it.toString() }.sorted() }.getOrNull(),
            "label_pack" to labelPack.status.value.toString(),
            "goals" to runCatching { goalsRepository.goals.first() }.getOrDefault(emptyList()).map {
                mapOf("metric" to it.metric.key, "enabled" to it.enabled, "target" to it.target, "floor" to it.floorValue, "ceiling" to it.ceilingValue)
            },
            "entries_total" to runCatching { database.entryDao().all().size }.getOrNull(),
            "my_foods_total" to runCatching { personalStore.all().size }.getOrNull(),
            "scan_photos" to debugEvents.scanCount(),
        )
        return mapOf(
            "entries_45d.jsonl" to entryLines,
            "my_foods.jsonl" to foods,
            "state.json" to com.nutritionscribe.app.ui.debuglog.DebugEvents.toJson(state).toString(),
            "exits.json" to com.nutritionscribe.app.ui.debuglog.DebugEvents.toJson(lastExits(15)).toString(),
        )
    }

    /** Bump to run [repairSavedFood] again after [com.nutritionscribe.shared.model.NutrientRepair] learns a new fix. */
    private const val NUTRIENT_REPAIR_VERSION = 1

    /**
     * Saved food whose numbers can't all be true (a misread label: 3.5 kcal oats,
     * 73.5 g of sugar in peanut butter) is fixed where the arithmetic settles it,
     * in My Foods first so "log again" stops repeating it, then in the log.
     */
    private suspend fun repairSavedFood() {
        val changes = mutableListOf<Map<String, Any?>>()
        for (food in personalStore.all()) {
            val (fixed, fixes) = com.nutritionscribe.shared.resolve.FoodRepair.repair(food) ?: continue
            personalStore.put(fixed)
            changes += mapOf("food" to food.name, "fixes" to fixes.map { "${it.code}:${it.metric} ${it.from}->${it.to}" })
        }
        val dao = database.entryDao()
        for (row in dao.all()) {
            val entry = row.toEntry()
            val (fixed, fixes) = com.nutritionscribe.shared.model.NutrientRepair.repair(entry) ?: continue
            dao.upsert(fixed.toRow(), fixed.toNutrientRows())
            changes += mapOf("entry" to entry.name, "entry_id" to entry.id, "fixes" to fixes.map { "${it.code}:${it.metric} ${it.from}->${it.to}" })
        }
        debugEvents.always("nutrient_repair", "count" to changes.size, "changes" to changes)
    }

    /** Builds a fresh repository bound to whichever backend/key is currently configured. */
    fun currentEntryRepository(): EntryRepository {
        val backendKind = backendCache.value
        val allowed = aiAllowed(backendKind)
        val apiKey = if (allowed) settingsRepository.getApiKey(backendKind).orEmpty() else ""
        val backend: AiBackend = when (backendKind) {
            AiBackendKind.OPENAI -> OpenAiBackend(apiKey, client = meteredClient)
            AiBackendKind.ANTHROPIC -> AnthropicBackend(apiKey, client = meteredClient)
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
            lookupSource = if (allowed) settingsRepository.effectiveLookupSource(lookupSourceCache.value) else LookupSource.OFFLINE,
        )
    }
}
