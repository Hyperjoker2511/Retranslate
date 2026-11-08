package app.retranslate.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * AI Compare & Merge settings (PLAN.md §5.6, P7): the Quick Translate
 * toggle's persisted state and the daily soft cap. The per-day count
 * itself lives in [ProviderConfigStore] (it is usage bookkeeping, like
 * MyMemory's budget); this class owns only the user's two choices.
 */
class MergePrefs(private val store: DataStore<Preferences>) {

    /** The toggle as the Translate screen shows it (default ON, §5.6). */
    val enabled: Flow<Boolean> = store.data.map { it[KEY_ENABLED] ?: true }

    suspend fun isEnabled(): Boolean = enabled.first()

    suspend fun setEnabled(value: Boolean) {
        store.edit { it[KEY_ENABLED] = value }
    }

    /** The daily merge cap (default 100 on the free Gemini tier, §5.6). */
    val dailyCap: Flow<Int> = store.data.map { it[KEY_CAP] ?: DEFAULT_DAILY_CAP }

    suspend fun dailyCap(): Int = dailyCap.first()

    suspend fun setDailyCap(value: Int) {
        store.edit { it[KEY_CAP] = value.coerceIn(MIN_DAILY_CAP, MAX_DAILY_CAP) }
    }

    companion object {
        const val DEFAULT_DAILY_CAP = 100
        const val MIN_DAILY_CAP = 1
        const val MAX_DAILY_CAP = 10_000
        private val KEY_ENABLED = booleanPreferencesKey("merge_enabled")
        private val KEY_CAP = intPreferencesKey("merge_daily_cap")
    }
}
