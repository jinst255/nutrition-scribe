package com.proteinscribe.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.proteinscribe.shared.retention.RetentionUnit
import com.proteinscribe.shared.retention.RetentionWindow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "protein_scribe_settings")

enum class AiBackendKind { OPENAI, ANTHROPIC }
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Non-secret settings live in DataStore; the API key lives in EncryptedSharedPreferences
 * (Jetpack Security) so it is never written in plaintext (Section 3, Section 11).
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val MANUAL_MODE = booleanPreferencesKey("manual_mode")
        val DAILY_GOAL_GRAMS = doublePreferencesKey("daily_goal_grams")
        val DAILY_BASELINE_GRAMS = doublePreferencesKey("daily_baseline_grams")
        val BACKEND = stringPreferencesKey("ai_backend")
        val THEME = stringPreferencesKey("theme_mode")
        val RETAIN_TOTALS_AMOUNT = intPreferencesKey("retain_totals_amount")
        val RETAIN_TOTALS_UNIT = stringPreferencesKey("retain_totals_unit")
        val RETAIN_DETAILS_AMOUNT = intPreferencesKey("retain_details_amount")
        val RETAIN_DETAILS_UNIT = stringPreferencesKey("retain_details_unit")
    }

    private val encryptedPrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "protein_scribe_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun getApiKey(backend: AiBackendKind): String? =
        encryptedPrefs.getString("api_key_${backend.name}", null)

    fun setApiKey(backend: AiBackendKind, key: String) {
        encryptedPrefs.edit().putString("api_key_${backend.name}", key).apply()
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
    val manualMode: Flow<Boolean> = context.dataStore.data.map { it[Keys.MANUAL_MODE] ?: false }

    suspend fun setManualMode(value: Boolean) {
        context.dataStore.edit { it[Keys.MANUAL_MODE] = value }
    }

    val isOnboarded: Flow<Boolean> = context.dataStore.data.map { it[Keys.ONBOARDED] ?: false }

    suspend fun setOnboarded(value: Boolean) {
        context.dataStore.edit { it[Keys.ONBOARDED] = value }
    }

    val dailyGoalGrams: Flow<Double> = context.dataStore.data.map { it[Keys.DAILY_GOAL_GRAMS] ?: 160.0 }

    suspend fun setDailyGoalGrams(grams: Double) {
        context.dataStore.edit { it[Keys.DAILY_GOAL_GRAMS] = grams }
    }

    /** Second History-chart reference line (Section 12 extension): a below-baseline day reads red instead of blue. */
    val dailyBaselineGrams: Flow<Double> = context.dataStore.data.map { it[Keys.DAILY_BASELINE_GRAMS] ?: 112.0 }

    suspend fun setDailyBaselineGrams(grams: Double) {
        context.dataStore.edit { it[Keys.DAILY_BASELINE_GRAMS] = grams }
    }

    val backend: Flow<AiBackendKind> = context.dataStore.data.map {
        AiBackendKind.valueOf(it[Keys.BACKEND] ?: AiBackendKind.OPENAI.name)
    }

    suspend fun setBackend(backend: AiBackendKind) {
        context.dataStore.edit { it[Keys.BACKEND] = backend.name }
    }

    val theme: Flow<ThemeMode> = context.dataStore.data.map {
        ThemeMode.valueOf(it[Keys.THEME] ?: ThemeMode.SYSTEM.name)
    }

    suspend fun setTheme(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME] = mode.name }
    }

    val retainProteinTotals: Flow<RetentionWindow> = context.dataStore.data.map {
        RetentionWindow(
            amount = it[Keys.RETAIN_TOTALS_AMOUNT] ?: RetentionWindow.DEFAULT_PROTEIN_TOTALS.amount,
            unit = RetentionUnit.valueOf(it[Keys.RETAIN_TOTALS_UNIT] ?: RetentionWindow.DEFAULT_PROTEIN_TOTALS.unit.name),
        )
    }

    suspend fun setRetainProteinTotals(window: RetentionWindow) {
        context.dataStore.edit {
            it[Keys.RETAIN_TOTALS_AMOUNT] = window.amount
            it[Keys.RETAIN_TOTALS_UNIT] = window.unit.name
        }
    }

    val retainFoodDetails: Flow<RetentionWindow> = context.dataStore.data.map {
        RetentionWindow(
            amount = it[Keys.RETAIN_DETAILS_AMOUNT] ?: RetentionWindow.DEFAULT_FOOD_DETAILS.amount,
            unit = RetentionUnit.valueOf(it[Keys.RETAIN_DETAILS_UNIT] ?: RetentionWindow.DEFAULT_FOOD_DETAILS.unit.name),
        )
    }

    suspend fun setRetainFoodDetails(window: RetentionWindow) {
        context.dataStore.edit {
            it[Keys.RETAIN_DETAILS_AMOUNT] = window.amount
            it[Keys.RETAIN_DETAILS_UNIT] = window.unit.name
        }
    }
}
