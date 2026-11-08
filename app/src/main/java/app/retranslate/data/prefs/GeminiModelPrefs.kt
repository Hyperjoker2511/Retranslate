package app.retranslate.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.retranslate.providers.key.GeminiProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The Gemini model choice from the Gemini key screen (v1.1.2): which
 * model the provider tries FIRST. Null = "Automatic (recommended)" —
 * the lite-first cascade in [GeminiProvider]. A manual choice never
 * disables the cascade: if the pinned model answers a
 * model-not-available 404, the cascade still rescues the call.
 *
 * Lives in the `settings` DataStore next to [ProviderChoicePrefs];
 * deliberately NOT a Room column — it is a UI preference, not book data.
 */
class GeminiModelPrefs(private val store: DataStore<Preferences>) {

    /** The persisted choice; a stored id that is no longer a known model is Automatic. */
    val choice: Flow<String?> = store.data.map { prefs -> decode(prefs[KEY_MODEL]) }

    suspend fun choice(): String? = choice.first()

    suspend fun setChoice(modelId: String?) {
        store.edit { prefs ->
            if (modelId == null) prefs.remove(KEY_MODEL) else prefs[KEY_MODEL] = modelId
        }
    }

    companion object {
        private val KEY_MODEL = stringPreferencesKey("gemini_model_choice")

        /** Stored id → known model id; junk or absence degrades to Automatic (null). */
        fun decode(raw: String?): String? =
            raw?.takeIf { it in GeminiProvider.CASCADE_ORDER }
    }
}
