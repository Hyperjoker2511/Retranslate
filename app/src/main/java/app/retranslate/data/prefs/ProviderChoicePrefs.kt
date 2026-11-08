package app.retranslate.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.retranslate.providers.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The Translate screen's provider choice (v1.1, the user's own feature):
 * which provider quick translate should try FIRST. Null = "Automatic" —
 * the router's normal chain order, unchanged since v1.0. Lives in the
 * `settings` DataStore next to [MergePrefs]; deliberately NOT a Room
 * column — it is a UI preference, not book data.
 */
class ProviderChoicePrefs(private val store: DataStore<Preferences>) {

    /** The persisted choice; a stored name that no longer parses is Automatic. */
    val choice: Flow<ProviderId?> = store.data.map { prefs -> decode(prefs[KEY_CHOICE]) }

    suspend fun choice(): ProviderId? = choice.first()

    suspend fun setChoice(id: ProviderId?) {
        store.edit { prefs ->
            if (id == null) prefs.remove(KEY_CHOICE) else prefs[KEY_CHOICE] = id.name
        }
    }

    /**
     * v1.1.3 (§I/§K): the pick as a STRING — a ProviderId name or a
     * gateway slot key ("gw1".."gw6") — so an added provider can be
     * the Translate tab's engine too. Null = Automatic.
     */
    val pickKey: Flow<String?> = store.data.map { prefs -> prefs[KEY_PICK] }

    suspend fun setPickKey(key: String?) {
        store.edit { prefs ->
            if (key == null) prefs.remove(KEY_PICK) else prefs[KEY_PICK] = key
        }
    }

    companion object {
        private val KEY_CHOICE = stringPreferencesKey("quick_translate_provider_choice")
        private val KEY_PICK = stringPreferencesKey("quick_translate_provider_pick")

        /** Stored name → id; junk or absence degrades to Automatic (null). */
        fun decode(raw: String?): ProviderId? =
            raw?.let { name -> ProviderId.entries.firstOrNull { it.name == name } }
    }
}
