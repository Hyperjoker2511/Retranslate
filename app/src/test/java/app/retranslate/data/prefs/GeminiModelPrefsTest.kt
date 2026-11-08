package app.retranslate.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** In-memory DataStore (same pattern as ProviderChoicePrefsTest's fake). */
private class ModelFakeDataStore : DataStore<Preferences> {
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
 * v1.1.2: the Gemini key screen's model choice persists across
 * restarts, unknown/retired ids degrade to Automatic, and clearing
 * returns to Automatic.
 */
class GeminiModelPrefsTest {

    @Test
    fun `default is Automatic - nothing stored`() = runTest {
        val prefs = GeminiModelPrefs(ModelFakeDataStore())
        assertNull(prefs.choice.first())
    }

    @Test
    fun `a pinned model round-trips through a fresh reader`() = runTest {
        val store = ModelFakeDataStore()
        GeminiModelPrefs(store).setChoice("gemini-3.8-flash")
        assertEquals("gemini-3.8-flash", GeminiModelPrefs(store).choice.first())
    }

    @Test
    fun `choosing Automatic clears the stored value`() = runTest {
        val store = ModelFakeDataStore()
        val prefs = GeminiModelPrefs(store)
        prefs.setChoice("gemini-3.1-flash-lite")
        prefs.setChoice(null)
        assertNull(GeminiModelPrefs(store).choice.first())
    }

    @Test
    fun `retired or junk ids degrade to Automatic`() = runTest {
        assertNull(GeminiModelPrefs.decode("gemini-2.5-flash-lite"))
        assertNull(GeminiModelPrefs.decode("gemini-3.5-flash"))
        assertNull(GeminiModelPrefs.decode(""))
        assertNull(GeminiModelPrefs.decode(null))
        assertEquals(
            "gemini-3.5-flash-lite",
            GeminiModelPrefs.decode("gemini-3.5-flash-lite")
        )
    }
}
