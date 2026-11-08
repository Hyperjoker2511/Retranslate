package app.retranslate.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.retranslate.providers.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** In-memory DataStore (same pattern as SavedLanguagesTest's fake). */
private class ChoiceFakeDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow<Preferences>(emptyPreferences())
    override val data: Flow<Preferences> = state
    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences
    ): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}

/**
 * v1.1 (workstream C): the Translate tab's provider choice persists
 * across restarts (it IS a DataStore value, read back cold), junk
 * degrades to Automatic, and clearing returns to Automatic.
 */
class ProviderChoicePrefsTest {

    @Test
    fun `default is Automatic - nothing stored`() = runTest {
        val prefs = ProviderChoicePrefs(ChoiceFakeDataStore())
        assertNull(prefs.choice.first())
    }

    @Test
    fun `a chosen provider round-trips through a fresh reader`() = runTest {
        val store = ChoiceFakeDataStore()
        ProviderChoicePrefs(store).setChoice(ProviderId.GEMINI)
        // A cold reader over the same store = an app restart.
        assertEquals(ProviderId.GEMINI, ProviderChoicePrefs(store).choice.first())
    }

    @Test
    fun `choosing Automatic clears the stored value`() = runTest {
        val store = ChoiceFakeDataStore()
        val prefs = ProviderChoicePrefs(store)
        prefs.setChoice(ProviderId.MOZHI)
        prefs.setChoice(null)
        assertNull(ProviderChoicePrefs(store).choice.first())
    }

    @Test
    fun `junk in the store degrades to Automatic`() = runTest {
        assertNull(ProviderChoicePrefs.decode("NOT_A_PROVIDER"))
        assertNull(ProviderChoicePrefs.decode(""))
        assertNull(ProviderChoicePrefs.decode(null))
        assertEquals(ProviderId.DEEPL, ProviderChoicePrefs.decode("DEEPL"))
    }
}
