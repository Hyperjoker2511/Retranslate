package app.retranslate.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * v1.1.3 (F24): the one-time "What's new" card's memory — shown once
 * on the Library after the update, dismissed forever by one tap.
 */
class WhatsNewPrefs(private val store: DataStore<Preferences>) {

    val seen113: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_SEEN_113] ?: false
    }

    suspend fun markSeen113() {
        store.edit { prefs -> prefs[KEY_SEEN_113] = true }
    }

    companion object {
        private val KEY_SEEN_113 = booleanPreferencesKey("whats_new_seen_1_1_3")
    }
}
