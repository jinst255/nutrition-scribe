package com.nutritionscribe.app.data.repository

import android.annotation.SuppressLint
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nutritionscribe.shared.model.Profile
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.nutritionscribe.shared.retention.RetentionUnit
import com.nutritionscribe.shared.retention.RetentionWindow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = SettingsRepository.PRODUCTION_STORE)

/**
 * Where settings live. Named so a test can point at its own files instead of the
 * real ones: a test that writes to the production store erases the settings and
 * the API key of whoever's device it happens to run on, which makes the whole
 * suite unsafe to run against a phone someone is using.
 */
data class SettingsStorage(val preferencesName: String, val secureName: String) {
    companion object {
        val PRODUCTION = SettingsStorage(
            preferencesName = SettingsRepository.PRODUCTION_STORE,
            secureName = SettingsRepository.PRODUCTION_SECURE_STORE,
        )
    }
}

private val scratchStores = mutableMapOf<String, androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>>()

enum class AiBackendKind { OPENAI, ANTHROPIC }
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * How the microphone button works. People split cleanly on this: some want to tap
 * once and talk as long as they like, others want to hold it like a walkie-talkie
 * so letting go is the "done". Neither stops on a pause.
 */
enum class MicMode { TAP, HOLD }

/** How Home orders its rows: goals first (the default), or by how full each bar is. */
/**
 * [short] is what the switch beside Home's tabs shows: "Sort: Lowest first" was cut
 * to "Sort: Lowest" on the owner's phone (11-14, 11-19), with no room left by the
 * tabs. The icon says "sort"; the word says which.
 */
enum class HomeSort(val label: String, val short: String) {
    GOALS("Goals first", "Goals"), LOWEST("Lowest first", "Lowest"), HIGHEST("Highest first", "Highest")
}

/**
 * Where a food's nutrition numbers come from.
 *
 * [AI] asks the configured model, which handles anything you can say in words —
 * brands, restaurant meals, "a solid breakfast" — but needs an API key and a
 * network round trip.
 *
 * [OFFLINE] uses the ~13,400-food USDA database bundled in the app: about 50 ms,
 * no key, no network, no cost, and nothing leaves the phone. It does not know
 * brands or compound dishes.
 *
 * Neither is strictly better, which is why this is a setting and not a guess.
 */
enum class LookupSource { AI, OFFLINE }

/**
 * Non-secret settings live in DataStore; the API key lives in EncryptedSharedPreferences
 * (Jetpack Security) so it is never written in plaintext (Section 3, Section 11).
 */
class SettingsRepository(
    private val context: Context,
    private val storage: SettingsStorage = SettingsStorage.PRODUCTION,
) {

    companion object {
        // Both keep their pre-rename names on purpose: changing either would
        // strand the settings and the API key of every existing install.
        const val PRODUCTION_STORE = "protein_scribe_settings"
        const val PRODUCTION_SECURE_STORE = "protein_scribe_secure_prefs"
    }

    /**
     * The production store goes through the `by preferencesDataStore` delegate,
     * which is the only way to get the one-instance-per-file guarantee DataStore
     * requires. A named test store is built and cached here instead.
     */
    private val store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
        get() = if (storage.preferencesName == PRODUCTION_STORE) {
            context.dataStore
        } else {
            synchronized(scratchStores) {
                scratchStores.getOrPut(storage.preferencesName) {
                    androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
                        context.preferencesDataStoreFile(storage.preferencesName)
                    }
                }
            }
        }

    private object Keys {
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val MANUAL_MODE = booleanPreferencesKey("manual_mode")
        val DAILY_GOAL_GRAMS = doublePreferencesKey("daily_goal_grams")
        val DAILY_BASELINE_GRAMS = doublePreferencesKey("daily_baseline_grams")
        val BACKEND = stringPreferencesKey("ai_backend")
        val LOOKUP_SOURCE = stringPreferencesKey("lookup_source")
        val THEME = stringPreferencesKey("theme_mode")
        val RETAIN_TOTALS_AMOUNT = intPreferencesKey("retain_totals_amount")
        val RETAIN_TOTALS_UNIT = stringPreferencesKey("retain_totals_unit")
        val RETAIN_DETAILS_AMOUNT = intPreferencesKey("retain_details_amount")
        val RETAIN_DETAILS_UNIT = stringPreferencesKey("retain_details_unit")
        // "_v2": round 3 made hold-to-talk the default for everyone, including
        // installs that had the old tap default stored.
        val MIC_MODE = stringPreferencesKey("mic_mode_v2")
        val HOME_SORT = stringPreferencesKey("home_sort")
        val PLASMA_HIDDEN_ON = stringPreferencesKey("plasma_hidden_on")
        val MICRO_BACKFILL = intPreferencesKey("micro_backfill_version")
        val GOAL_LINES_V2 = booleanPreferencesKey("goal_lines_v2")
        val SHOW_ADVANCED = booleanPreferencesKey("show_advanced")
        val DEBUG_MODE = booleanPreferencesKey("debug_mode")
        /** Settings › Watch: what the watch home shows ("goals", "need", "one") and, for "one", which goal. */
        val WATCH_HOME = stringPreferencesKey("watch_home")
        val WATCH_ONE = stringPreferencesKey("watch_one_metric")
        val SHAKE_TO_REPORT = booleanPreferencesKey("shake_to_report")
        val SPEECH_ON_PHONE = booleanPreferencesKey("speech_on_phone")
        val STORE_SUGGEST = booleanPreferencesKey("store_suggest")
        val STORE_SNOOZES = stringPreferencesKey("store_snoozes")
        val GOAL_CHANGES = androidx.datastore.preferences.core.stringPreferencesKey("goal_changes")
        val PROFILE = stringPreferencesKey("profile_v1")
        val PLASMA_DONATIONS = stringSetPreferencesKey("plasma_donations")
        val IDEAS_DISMISSED = stringSetPreferencesKey("ideas_dismissed")
        val AI_CONSENT = stringSetPreferencesKey("ai_consent")
        val NUTRIENT_REPAIR = intPreferencesKey("nutrient_repair_version")
        val LAST_EXPORT_AT = androidx.datastore.preferences.core.longPreferencesKey("last_export_at")
        val WEB_SEARCH = booleanPreferencesKey("web_search")
        val TESTER_NAME = stringPreferencesKey("tester_name")
        val INSTALLED_AT = androidx.datastore.preferences.core.longPreferencesKey("installed_at")
        val LOG_SENT_AT = androidx.datastore.preferences.core.longPreferencesKey("log_sent_at")
        val LOG_SNOOZE_UNTIL = androidx.datastore.preferences.core.longPreferencesKey("log_snooze_until")
        val PROFILE_NUDGE_CLOSES = intPreferencesKey("profile_nudge_closes")
        val PROFILE_NUDGE_CLOSED_AT = androidx.datastore.preferences.core.longPreferencesKey("profile_nudge_closed_at")
        val SCAN_GUIDE = booleanPreferencesKey("scan_guide")
        val BETA_NOTES = stringPreferencesKey("beta_notes_v1")
        val GOALS_TIP_CLOSED = booleanPreferencesKey("goals_tip_closed")
        val MICROS_PERCENT = booleanPreferencesKey("micros_percent")
        val STREAK_SHOW = booleanPreferencesKey("streak_show")
        val STREAK_RULE = stringPreferencesKey("streak_rule")
        val STREAK_REST = intPreferencesKey("streak_rest_days")
        val DAY_END_HOUR = intPreferencesKey("day_end_hour")
        val IDEA_HIDDEN = stringPreferencesKey("idea_hidden")
        val NAME_CONFIRMED_VERSION = intPreferencesKey("tester_name_confirmed_version")
        val LOG_LEAVE_OUT = stringSetPreferencesKey("log_leave_out")
        val SETUP_DONE_AT = androidx.datastore.preferences.core.longPreferencesKey("setup_done_at")
        val TESTER_INTRO_SEEN = booleanPreferencesKey("tester_intro_seen")
        val EXTRAS = stringSetPreferencesKey("extras_tracked")
        val MODEL_LOOKUP = stringPreferencesKey("model_lookup")
        val MODEL_ASK = stringPreferencesKey("model_ask")
        val MODEL_SPEECH = stringPreferencesKey("model_speech")
        // v2 (2026-11-14): Ask can now send summaries of past days and saved foods, so it asks again.
        val ASK_CONSENT = booleanPreferencesKey("ask_consent_v2")
        val HISTORY_COUNTED = stringSetPreferencesKey("history_counted_days")
    }

    /**
     * Null only when the Android Keystore can't be used at all; then no key is
     * saved and the app runs offline instead of crashing. See [SecureStoreOpener].
     */
    @Volatile private var opened: android.content.SharedPreferences? = null

    /** How the store last opened; RESET means a key that couldn't be decrypted was dropped. */
    @Volatile var secureStoreOutcome: SecureStoreOpener.Outcome? = null
        private set

    private val encryptedPrefs: android.content.SharedPreferences?
        get() {
            if (secureStoreOutcome != null) return opened
            synchronized(this) {
                if (secureStoreOutcome != null) return opened
                val (prefs, outcome) = SecureStoreOpener.open(
                    create = {
                        val masterKey = MasterKey.Builder(context)
                            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                            .build()
                        EncryptedSharedPreferences.create(
                            context,
                            storage.secureName,
                            masterKey,
                            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                        )
                    },
                    wipe = { context.deleteSharedPreferences(storage.secureName) },
                )
                opened = prefs
                secureStoreOutcome = outcome // set last: it marks the store as tried
                return prefs
            }
        }

    fun getApiKey(backend: AiBackendKind): String? =
        SecureStoreOpener.read { encryptedPrefs?.getString("api_key_${backend.name}", null) }

    fun setApiKey(backend: AiBackendKind, key: String) {
        encryptedPrefs?.edit()?.putString("api_key_${backend.name}", key)?.apply()
        keysChanged.value++
    }

    /** Ticks whenever a key is saved, since the encrypted store has no flow to watch. */
    val keysChanged = kotlinx.coroutines.flow.MutableStateFlow(0)

    /**
     * Forgets every stored API key. `commit()` rather than `apply()` on purpose:
     * a reset is followed immediately by the app restarting itself, and apply()
     * writes on a background thread that the restart could outrun, leaving the
     * key on disk after the user was told it was gone.
     */
    @SuppressLint("ApplySharedPref")
    fun clearApiKeys() {
        encryptedPrefs?.edit()?.clear()?.commit()
    }

    /** Forgets every non-secret preference, back to first-launch defaults. */
    suspend fun clearAllPreferences() {
        store.edit { it.clear() }
    }

    /** True if any backend has a usable key — decides whether AI lookup is even offered. */
    fun hasAnyApiKey(): Boolean =
        AiBackendKind.entries.any { !getApiKey(it).isNullOrBlank() }

    /**
     * When true, the app never calls an LLM: the user types the food and the grams
     * themselves. This is the path for someone who was handed the app and has no API
     * key, and it stays available as a deliberate choice even once a key is set —
     * useful offline, or when you already know the number and don't want to spend a call.
     */
    val manualMode: Flow<Boolean> = store.data.map { it[Keys.MANUAL_MODE] ?: false }

    suspend fun setManualMode(value: Boolean) {
        store.edit { it[Keys.MANUAL_MODE] = value }
    }

    val isOnboarded: Flow<Boolean> = store.data.map { it[Keys.ONBOARDED] ?: false }

    suspend fun setOnboarded(value: Boolean) {
        store.edit { it[Keys.ONBOARDED] = value }
    }

    val dailyGoalGrams: Flow<Double> = store.data.map { it[Keys.DAILY_GOAL_GRAMS] ?: 160.0 }

    suspend fun setDailyGoalGrams(grams: Double) {
        store.edit { it[Keys.DAILY_GOAL_GRAMS] = grams }
    }

    /** Second History-chart reference line (Section 12 extension): a below-baseline day reads red instead of blue. */
    val dailyBaselineGrams: Flow<Double> = store.data.map { it[Keys.DAILY_BASELINE_GRAMS] ?: 112.0 }

    suspend fun setDailyBaselineGrams(grams: Double) {
        store.edit { it[Keys.DAILY_BASELINE_GRAMS] = grams }
    }

    /**
     * Defaults to [LookupSource.AI] because it handles the widest range of input.
     * Note that this is only a *preference*: [effectiveLookupSource] falls back to
     * the offline database when no API key is configured, so someone who was handed
     * the app still gets real numbers instead of an error.
     */
    val lookupSource: Flow<LookupSource> = store.data.map {
        LookupSource.valueOf(it[Keys.LOOKUP_SOURCE] ?: LookupSource.AI.name)
    }

    suspend fun setLookupSource(source: LookupSource) {
        store.edit { it[Keys.LOOKUP_SOURCE] = source.name }
    }

    /** What the app will actually do, once a missing API key is taken into account. */
    fun effectiveLookupSource(preferred: LookupSource): LookupSource =
        if (preferred == LookupSource.AI && !hasAnyApiKey()) LookupSource.OFFLINE else preferred

    val backend: Flow<AiBackendKind> = store.data.map {
        AiBackendKind.valueOf(it[Keys.BACKEND] ?: AiBackendKind.OPENAI.name)
    }

    suspend fun setBackend(backend: AiBackendKind) {
        store.edit { it[Keys.BACKEND] = backend.name }
    }

    val theme: Flow<ThemeMode> = store.data.map {
        ThemeMode.valueOf(it[Keys.THEME] ?: ThemeMode.SYSTEM.name)
    }

    suspend fun setTheme(mode: ThemeMode) {
        store.edit { it[Keys.THEME] = mode.name }
    }

    val retainProteinTotals: Flow<RetentionWindow> = store.data.map {
        RetentionWindow(
            amount = it[Keys.RETAIN_TOTALS_AMOUNT] ?: RetentionWindow.DEFAULT_PROTEIN_TOTALS.amount,
            unit = RetentionUnit.valueOf(it[Keys.RETAIN_TOTALS_UNIT] ?: RetentionWindow.DEFAULT_PROTEIN_TOTALS.unit.name),
        )
    }

    suspend fun setRetainProteinTotals(window: RetentionWindow) {
        store.edit {
            it[Keys.RETAIN_TOTALS_AMOUNT] = window.amount
            it[Keys.RETAIN_TOTALS_UNIT] = window.unit.name
        }
    }

    val retainFoodDetails: Flow<RetentionWindow> = store.data.map {
        RetentionWindow(
            amount = it[Keys.RETAIN_DETAILS_AMOUNT] ?: RetentionWindow.DEFAULT_FOOD_DETAILS.amount,
            unit = RetentionUnit.valueOf(it[Keys.RETAIN_DETAILS_UNIT] ?: RetentionWindow.DEFAULT_FOOD_DETAILS.unit.name),
        )
    }

    suspend fun setRetainFoodDetails(window: RetentionWindow) {
        store.edit {
            it[Keys.RETAIN_DETAILS_AMOUNT] = window.amount
            it[Keys.RETAIN_DETAILS_UNIT] = window.unit.name
        }
    }

    val micMode: Flow<MicMode> = store.data.map {
        runCatching { MicMode.valueOf(it[Keys.MIC_MODE] ?: MicMode.HOLD.name) }.getOrDefault(MicMode.HOLD)
    }

    suspend fun setMicMode(mode: MicMode) {
        store.edit { it[Keys.MIC_MODE] = mode.name }
    }

    /** The day the plasma line on Home was closed; it comes back the next day. */
    val plasmaHiddenOn: Flow<String?> = store.data.map { it[Keys.PLASMA_HIDDEN_ON] }

    suspend fun setPlasmaHiddenOn(day: String?) {
        store.edit { if (day == null) it.remove(Keys.PLASMA_HIDDEN_ON) else it[Keys.PLASMA_HIDDEN_ON] = day }
    }

    val homeSort: Flow<HomeSort> = store.data.map {
        runCatching { HomeSort.valueOf(it[Keys.HOME_SORT] ?: HomeSort.GOALS.name) }.getOrDefault(HomeSort.GOALS)
    }

    suspend fun setHomeSort(sort: HomeSort) {
        store.edit { it[Keys.HOME_SORT] = sort.name }
    }

    /** Which [com.nutritionscribe.app.data.resolve.MicroBackfill] version has run. */
    val microBackfillVersion: Flow<Int> = store.data.map { it[Keys.MICRO_BACKFILL] ?: 0 }

    suspend fun setMicroBackfillVersion(v: Int) {
        store.edit { it[Keys.MICRO_BACKFILL] = v }
    }

    /** Whether the automatic red lines of older versions have been cleared (see GoalsRepository). */
    val goalLinesV2: Flow<Boolean> = store.data.map { it[Keys.GOAL_LINES_V2] ?: false }

    suspend fun setGoalLinesV2() {
        store.edit { it[Keys.GOAL_LINES_V2] = true }
    }

    /** Whether Settings shows the parts most people never need. */
    val showAdvanced: Flow<Boolean> = store.data.map { it[Keys.SHOW_ADVANCED] ?: false }

    suspend fun setShowAdvanced(value: Boolean) {
        store.edit { it[Keys.SHOW_ADVANCED] = value }
    }

    /** Debug mode: a detailed record of everything done and typed, for finding problems. */
    // On unless turned off: a quirk noticed today is only findable if it was
    // recorded today. It stays on the phone either way.
    /** Every goal target change, so History judges each day by its own target (GoalTimeline). */
    val goalChanges: Flow<List<com.nutritionscribe.shared.model.GoalTimeline.Change>> =
        store.data.map { com.nutritionscribe.shared.model.GoalTimeline.decode(it[Keys.GOAL_CHANGES]) }

    suspend fun recordGoalChange(metric: String, day: Long, before: Double?, after: Double?) {
        store.edit {
            val now = com.nutritionscribe.shared.model.GoalTimeline.decode(it[Keys.GOAL_CHANGES])
            it[Keys.GOAL_CHANGES] = com.nutritionscribe.shared.model.GoalTimeline.encode(
                com.nutritionscribe.shared.model.GoalTimeline.record(now, metric, day, before, after))
        }
    }

    /** Shake to report a problem (Settings › Beta testing); on unless switched off. */
    val shakeToReport: Flow<Boolean> = store.data.map { it[Keys.SHAKE_TO_REPORT] ?: com.nutritionscribe.app.ui.feedback.ShakeReport.DEFAULT_ON }

    suspend fun setShakeToReport(on: Boolean) {
        store.edit { it[Keys.SHAKE_TO_REPORT] = on }
    }

    /** Speech to text on the phone (the default) or by OpenAI; see [com.nutritionscribe.app.data.speech.SpeechEngine]. */
    val speechOnPhone: Flow<Boolean> = store.data.map { it[Keys.SPEECH_ON_PHONE] ?: com.nutritionscribe.app.data.speech.SpeechEngine.PHONE_BY_DEFAULT }

    suspend fun setSpeechOnPhone(on: Boolean) {
        store.edit { it[Keys.SPEECH_ON_PHONE] = on }
    }

    /** Settings › Store foods › "Suggest a store from what I eat". On unless switched off. */
    val storeSuggest: Flow<Boolean> = store.data.map { it[Keys.STORE_SUGGEST] ?: true }

    suspend fun setStoreSuggest(on: Boolean) {
        store.edit { it[Keys.STORE_SUGGEST] = on }
    }

    /** Store id → when its "Not now" runs out; see StoreSuggest.parseSnoozes. */
    val storeSnoozes: Flow<Map<String, Long>> = store.data.map {
        com.nutritionscribe.shared.stores.StoreSuggest.parseSnoozes(it[Keys.STORE_SNOOZES])
    }

    suspend fun snoozeStore(id: String, until: Long) {
        store.edit { prefs ->
            val now = com.nutritionscribe.shared.stores.StoreSuggest.parseSnoozes(prefs[Keys.STORE_SNOOZES])
            prefs[Keys.STORE_SNOOZES] = com.nutritionscribe.shared.stores.StoreSuggest.formatSnoozes(now + (id to until))
        }
    }

    val debugMode: Flow<Boolean> = store.data.map { it[Keys.DEBUG_MODE] ?: true }

    /** Settings › Watch (owner, 11-20): null until picked, then the watch home follows the phone. */
    val watchHome: Flow<String?> = store.data.map { it[Keys.WATCH_HOME] }
    suspend fun setWatchHome(mode: String) { store.edit { it[Keys.WATCH_HOME] = mode } }
    /** The goal "One number" shows, by [com.nutritionscribe.shared.model.Metric.key]; null is the first goal. */
    val watchOne: Flow<String?> = store.data.map { it[Keys.WATCH_ONE] }
    suspend fun setWatchOne(metricKey: String) { store.edit { it[Keys.WATCH_ONE] = metricKey } }

    suspend fun setDebugMode(value: Boolean) {
        store.edit { it[Keys.DEBUG_MODE] = value }
    }

    /**
     * AI on, as the person sees it: not in offline mode, and a key to use (see
     * [OfflineMode]). It is the existing lookup preference, so nothing already set
     * up changes meaning.
     */
    val aiOn: Flow<Boolean> = lookupSource.map { !OfflineMode.isOffline(it, hasAnyApiKey()) }

    /** Speech goes to OpenAI only with an OpenAI key; an Anthropic key has no speech model. */
    fun openAiKey(): String? = getApiKey(AiBackendKind.OPENAI)?.takeIf { it.isNotBlank() }

    private val profileJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** What the user told the app about their body, or null if they never did. */
    val profile: Flow<Profile?> = store.data.map { p ->
        p[Keys.PROFILE]?.let { runCatching { profileJson.decodeFromString(Profile.serializer(), it) }.getOrNull() }
    }

    suspend fun setProfile(value: Profile?) {
        store.edit {
            if (value == null) it.remove(Keys.PROFILE)
            else it[Keys.PROFILE] = profileJson.encodeToString(Profile.serializer(), value)
        }
    }

    /** Ideas the person said "Not for me" to, by id; they never come back. */
    val ideasDismissed: Flow<Set<String>> = store.data.map { it[Keys.IDEAS_DISMISSED].orEmpty() }

    suspend fun dismissIdea(id: String) {
        store.edit { it[Keys.IDEAS_DISMISSED] = it[Keys.IDEAS_DISMISSED].orEmpty() + id }
    }

    /** Days a plasma donation was logged, as ISO dates. */
    val plasmaDonations: Flow<Set<java.time.LocalDate>> = store.data.map { p ->
        p[Keys.PLASMA_DONATIONS].orEmpty().mapNotNull { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }.toSet()
    }

    suspend fun setPlasmaDonation(day: java.time.LocalDate, donated: Boolean) {
        store.edit {
            val now = it[Keys.PLASMA_DONATIONS].orEmpty()
            // A year is plenty: only the last week decides anything.
            val cutoff = day.minusDays(366).toString()
            val kept = now.filter { d -> d >= cutoff }.toSet()
            it[Keys.PLASMA_DONATIONS] = if (donated) kept + day.toString() else kept - day.toString()
        }
    }

    /**
     * Which AI providers the user agreed to send their words, photos and voice to,
     * as "OPENAI:1". The number is [AiConsent.VERSION]: when what is sent or what
     * the provider promises changes, it goes up and they are asked again.
     */
    val aiConsent: Flow<Set<String>> = store.data.map { it[Keys.AI_CONSENT].orEmpty() }

    /** [agreed] false records a "no", so they aren't asked again until [AiConsent.VERSION] changes. */
    suspend fun setAiConsent(kind: AiBackendKind, agreed: Boolean) {
        store.edit {
            val tag = if (agreed) "${kind.name}:${AiConsent.VERSION}" else "${kind.name}:no:${AiConsent.VERSION}"
            val others = it[Keys.AI_CONSENT].orEmpty().filterNot { t -> t.startsWith("${kind.name}:") }.toSet()
            it[Keys.AI_CONSENT] = others + tag
        }
    }

    /**
     * The first name of whoever the app was handed to, so a log that comes back
     * says whose it is. Optional; asked for once at setup.
     */
    val testerName: Flow<String> = store.data.map { it[Keys.TESTER_NAME].orEmpty() }

    suspend fun setTesterName(name: String) {
        // The API key box is right below this one; a key pasted here would end up
        // in the log's file name. Anything key-shaped is dropped.
        val clean = name.substringBefore("sk-").trim().take(40)
        store.edit { it[Keys.TESTER_NAME] = clean }
    }

    /** When this install first ran; the week-later log reminder counts from it. */
    val installedAt: Flow<Long?> = store.data.map { it[Keys.INSTALLED_AT] }

    suspend fun ensureInstalledAt(now: Long) {
        store.edit { if (it[Keys.INSTALLED_AT] == null) it[Keys.INSTALLED_AT] = now }
    }

    /** When the debug log was last sent from the reminder or Settings. */
    val logSentAt: Flow<Long?> = store.data.map { it[Keys.LOG_SENT_AT] }

    suspend fun setLogSent(now: Long) {
        store.edit { it[Keys.LOG_SENT_AT] = now }
    }

    val logSnoozeUntil: Flow<Long> = store.data.map { it[Keys.LOG_SNOOZE_UNTIL] ?: 0L }

    suspend fun snoozeLogReminder(until: Long) {
        store.edit { it[Keys.LOG_SNOOZE_UNTIL] = until }
    }

    /** How often Home's profile card was closed, and when last (review H2). */
    val profileNudgeClosed: Flow<Pair<Int, Long>> = store.data.map {
        (it[Keys.PROFILE_NUDGE_CLOSES] ?: 0) to (it[Keys.PROFILE_NUDGE_CLOSED_AT] ?: 0L)
    }

    suspend fun closeProfileNudge(now: Long) {
        store.edit {
            it[Keys.PROFILE_NUDGE_CLOSES] = (it[Keys.PROFILE_NUDGE_CLOSES] ?: 0) + 1
            it[Keys.PROFILE_NUDGE_CLOSED_AT] = now
        }
    }

    /** The green box on the scan camera that shows where the label goes. On until they turn it off. */
    val scanGuide: Flow<Boolean> = store.data.map { it[Keys.SCAN_GUIDE] ?: true }

    suspend fun setScanGuide(on: Boolean) {
        store.edit { it[Keys.SCAN_GUIDE] = on }
    }

    /**
     * What a tester has told us from the Beta testing section, for "What I've sent".
     * A note has gone out once the log was sent after it ([logSentAt]).
     */
    val betaNotes: Flow<List<BetaNote>> = store.data.map { p ->
        p[Keys.BETA_NOTES]?.let { runCatching { notesJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(BetaNote.serializer()), it) }.getOrNull() }.orEmpty()
    }

    suspend fun addBetaNote(note0: BetaNote) {
        store.edit { p ->
            val note = if (note0.who.isBlank()) note0.copy(who = p[Keys.TESTER_NAME].orEmpty()) else note0
            val old = p[Keys.BETA_NOTES]?.let { runCatching { notesJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(BetaNote.serializer()), it) }.getOrNull() }.orEmpty()
            p[Keys.BETA_NOTES] = notesJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(BetaNote.serializer()), (listOf(note) + old).take(100))
        }
    }

    private val notesJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /**
     * Whether a lookup may search the web when it is unsure (a restaurant or brand
     * item the food list and the model don't know). On unless turned off (owner,
     * 2026-11-14); only ever used with AI on.
     */
    val webSearch: Flow<Boolean> = store.data.map { it[Keys.WEB_SEARCH] ?: true }

    suspend fun setWebSearch(value: Boolean) {
        store.edit { it[Keys.WEB_SEARCH] = value }
    }

    /** When a backup was last written (Settings › Back up & restore shows it, and nudges after a month). */
    val lastExportAt: Flow<Long?> = store.data.map { it[Keys.LAST_EXPORT_AT] }

    suspend fun setLastExportAt(at: Long) {
        store.edit { it[Keys.LAST_EXPORT_AT] = at }
    }

    /** Which [com.nutritionscribe.shared.model.NutrientRepair] pass has run over saved food. */
    val nutrientRepairVersion: Flow<Int> = store.data.map { it[Keys.NUTRIENT_REPAIR] ?: 0 }

    suspend fun setNutrientRepairVersion(v: Int) {
        store.edit { it[Keys.NUTRIENT_REPAIR] = v }
    }

    /** Goals' one-line tip, closed with its ×. ⓘ in the top bar says the rest. */
    val goalsTipClosed: Flow<Boolean> = store.data.map { it[Keys.GOALS_TIP_CLOSED] ?: false }

    suspend fun closeGoalsTip() {
        store.edit { it[Keys.GOALS_TIP_CLOSED] = true }
    }

    /** The Minerals tab's Amount | % switch: % of this person's own need. */
    val microsAsPercent: Flow<Boolean> = store.data.map { it[Keys.MICROS_PERCENT] ?: false }

    suspend fun setMicrosAsPercent(on: Boolean) {
        store.edit { it[Keys.MICROS_PERCENT] = on }
    }

    data class StreakSettings(val show: Boolean, val rule: com.nutritionscribe.shared.model.Streak.Rule, val restDays: Int, val dayEndHour: Int)

    val streak: Flow<StreakSettings> = store.data.map {
        StreakSettings(
            show = it[Keys.STREAK_SHOW] ?: true,
            rule = com.nutritionscribe.shared.model.Streak.Rule.fromKey(it[Keys.STREAK_RULE]),
            restDays = it[Keys.STREAK_REST] ?: 1,
            dayEndHour = it[Keys.DAY_END_HOUR] ?: 3,
        )
    }

    suspend fun setStreak(s: StreakSettings) {
        store.edit {
            it[Keys.STREAK_SHOW] = s.show
            it[Keys.STREAK_RULE] = s.rule.key
            it[Keys.STREAK_REST] = s.restDays.coerceIn(0, 3)
            it[Keys.DAY_END_HOUR] = s.dayEndHour
        }
        com.nutritionscribe.shared.model.LogDays.endHour = s.dayEndHour
    }

    /** The headline of the Ideas card closed on Home; a new headline shows again. */
    val ideaHidden: Flow<String?> = store.data.map { it[Keys.IDEA_HIDDEN] }

    suspend fun setIdeaHidden(headline: String?) {
        store.edit { if (headline == null) it.remove(Keys.IDEA_HIDDEN) else it[Keys.IDEA_HIDDEN] = headline }
    }

    /** The versionCode the tester last confirmed their name on; asked again after an update. */
    val nameConfirmedVersion: Flow<Int> = store.data.map { it[Keys.NAME_CONFIRMED_VERSION] ?: 0 }

    suspend fun confirmName(name: String, versionCode: Int) {
        setTesterName(name)
        // Each new test version starts with the debug log on: a problem a tester
        // hits is only findable if it was recorded. Turning it off lasts until the next update.
        store.edit { it[Keys.NAME_CONFIRMED_VERSION] = versionCode; it[Keys.DEBUG_MODE] = true }
    }

    /**
     * Parts of the debug log the tester chose to leave out (see
     * [com.nutritionscribe.app.ui.feedback.LogPart]). Empty, so everything goes, until they untick one.
     */
    val logLeaveOut: Flow<Set<String>> = store.data.map { it[Keys.LOG_LEAVE_OUT].orEmpty() }

    suspend fun setLogLeaveOut(parts: Set<String>) {
        store.edit { it[Keys.LOG_LEAVE_OUT] = parts }
    }

    /** The tester welcome (the week, practice hold, what leaves the phone), seen once per install. */
    val testerIntroSeen: Flow<Boolean> = store.data.map { it[Keys.TESTER_INTRO_SEEN] ?: false }

    suspend fun setTesterIntroSeen() {
        store.edit { it[Keys.TESTER_INTRO_SEEN] = true }
    }

    /** When setup finished, for "You're all set" in the Tester guide. */
    val setupDoneAt: Flow<Long?> = store.data.map { it[Keys.SETUP_DONE_AT] }

    suspend fun setSetupDone(now: Long) {
        store.edit { it[Keys.SETUP_DONE_AT] = now }
    }

    /** Goals › More to track: extras turned on, by [com.nutritionscribe.shared.model.Metric.key]. Off by default. */
    val extras: Flow<Set<String>> = store.data.map { it[Keys.EXTRAS].orEmpty() }

    suspend fun setExtra(key: String, on: Boolean) {
        store.edit { it[Keys.EXTRAS] = if (on) it[Keys.EXTRAS].orEmpty() + key else it[Keys.EXTRAS].orEmpty() - key }
    }

    /** Advanced › AI models: which model does each job. null is the app's default. */
    val modelLookup: Flow<String?> = store.data.map { it[Keys.MODEL_LOOKUP] }
    val modelAsk: Flow<String?> = store.data.map { it[Keys.MODEL_ASK] }
    val modelSpeech: Flow<String?> = store.data.map { it[Keys.MODEL_SPEECH] }

    suspend fun setModel(job: AiJob, model: String?) {
        val k = when (job) { AiJob.LOOKUP -> Keys.MODEL_LOOKUP; AiJob.ASK -> Keys.MODEL_ASK; AiJob.SPEECH -> Keys.MODEL_SPEECH }
        store.edit { if (model == null) it.remove(k) else it[k] = model }
    }

    /** Half-logged days the person chose to count in History's averages, as epoch days. */
    val historyCounted: Flow<Set<Long>> = store.data.map { p -> p[Keys.HISTORY_COUNTED].orEmpty().mapNotNull { it.toLongOrNull() }.toSet() }

    suspend fun setHistoryCounted(day: Long, counted: Boolean) {
        store.edit {
            val now = it[Keys.HISTORY_COUNTED].orEmpty()
            it[Keys.HISTORY_COUNTED] = if (counted) now + day.toString() else now - day.toString()
        }
    }

    /** Whether the person has seen and accepted what Ask sends, the first time they use it. */
    val askConsent: Flow<Boolean> = store.data.map { it[Keys.ASK_CONSENT] ?: false }

    suspend fun setAskConsent(on: Boolean) {
        store.edit { it[Keys.ASK_CONSENT] = on }
    }
}

/** The jobs the AI does, each with its own model in Advanced › AI models. */
enum class AiJob(val label: String) { LOOKUP("Food lookups"), ASK("Ask chat"), SPEECH("Speech") }

/** One thing a tester told us: "Wrong numbers · the butter at lunch seemed like a lot". */
@kotlinx.serialization.Serializable
data class BetaNote(
    val at: Long,
    /** "problem", "idea" or "liked". */
    val kind: String,
    val tags: List<String> = emptyList(),
    val text: String = "",
    /** The tester's name when it was written, so "From Sam" stays right after a rename. */
    val who: String = "",
)
