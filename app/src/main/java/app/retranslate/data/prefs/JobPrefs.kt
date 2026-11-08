package app.retranslate.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * Job-behaviour settings (§8.3's "inter-chapter delay"): the single
 * owner of the pacing value the shared [app.retranslate.domain.translate.RequestPacer]
 * reads before every provider request in bulk work.
 */
class JobPrefs(private val store: DataStore<Preferences>) {

    suspend fun interCallDelayMs(): Long =
        store.data.first()[KEY_INTER_CALL_DELAY_MS] ?: DEFAULT_INTER_CALL_DELAY_MS

    suspend fun setInterCallDelayMs(value: Long) {
        store.edit { it[KEY_INTER_CALL_DELAY_MS] = value.coerceAtLeast(0L) }
    }

    companion object {
        /** §5.5/§7.4 default pacing: 600 ms between bulk requests. */
        const val DEFAULT_INTER_CALL_DELAY_MS = 600L
        private val KEY_INTER_CALL_DELAY_MS = longPreferencesKey("inter_call_delay_ms")
    }
}
