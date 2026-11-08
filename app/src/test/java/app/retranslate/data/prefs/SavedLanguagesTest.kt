package app.retranslate.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Minimal in-memory DataStore for JVM tests. */
private class FakeDataStore : DataStore<Preferences> {
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
 * PLAN.md §3.5/§12.1: SavedLanguages round-trips in EVERY stored shape.
 * This is the old app's corruption bug, kept as a test forever: the old
 * app wrote both `{"code":"en","name":"English"}` JSON and bare codes
 * into these same slots; every shape must decode, junk must degrade.
 */
class SavedLanguagesTest {

    @Test
    fun `missing and blank values decode to the defaults`() {
        assertEquals("auto", SavedLanguages.decode(null, isSource = true))
        assertEquals("en", SavedLanguages.decode(null, isSource = false))
        assertEquals("auto", SavedLanguages.decode("", isSource = true))
        assertEquals("en", SavedLanguages.decode("   ", isSource = false))
    }

    @Test
    fun `bare codes decode - the shape other writers used`() {
        assertEquals("ko", SavedLanguages.decode("ko", isSource = true))
        assertEquals("zh-CN", SavedLanguages.decode("zh-CN", isSource = false))
        assertEquals("ja", SavedLanguages.decode(" ja ", isSource = true))
    }

    @Test
    fun `canonical JSON objects decode by their code field`() {
        assertEquals(
            "ko",
            SavedLanguages.decode("""{"code":"ko","name":"Korean"}""", isSource = true)
        )
        assertEquals(
            "en",
            SavedLanguages.decode("""{"code":"en","name":"English"}""", isSource = false)
        )
        // Extra/unknown fields are ignored.
        assertEquals(
            "fr",
            SavedLanguages.decode("""{"code":"fr","name":"French","x":1}""", isSource = false)
        )
    }

    @Test
    fun `quoted strings and junk degrade safely`() {
        assertEquals("ja", SavedLanguages.decode("\"ja\"", isSource = true))
        assertEquals("auto", SavedLanguages.decode("{not json", isSource = true))
        assertEquals("en", SavedLanguages.decode("""{"name":"English"}""", isSource = false))
        assertEquals("en", SavedLanguages.decode("not-a-language", isSource = false))
        assertEquals("auto", SavedLanguages.decode("klingon", isSource = true))
    }

    @Test
    fun `auto is source-only - an auto target falls back to English`() {
        assertEquals("auto", SavedLanguages.decode("auto", isSource = true))
        assertEquals("en", SavedLanguages.decode("auto", isSource = false))
        assertEquals(
            "en",
            SavedLanguages.decode("""{"code":"auto","name":"Auto detect"}""", isSource = false)
        )
    }

    @Test
    fun `encode writes the canonical shape and decode round-trips it`() {
        for (code in listOf("ko", "zh-CN", "en", "ja", "auto")) {
            val encoded = SavedLanguages.encode(code, isSource = true)
            assertEquals(code, SavedLanguages.decode(encoded, isSource = true))
        }
        // The stored shape really is the JSON object (one writer, one shape).
        val encoded = SavedLanguages.encode("ko", isSource = true)
        assertEquals("""{"code":"ko","name":"Korean"}""", encoded)
    }

    @Test
    fun `save then read returns the pair - and heals a corrupted slot`() = runTest {
        val store = FakeDataStore()
        val saved = SavedLanguages(store)
        assertEquals(LanguagePair("auto", "en"), saved.current())

        saved.save(LanguagePair(source = "ko", target = "en"))
        assertEquals(LanguagePair("ko", "en"), saved.pair.first())

        // Simulate the old corruption: a bare code in one slot, junk in the other.
        store.updateData { prefs ->
            val mutable = prefs.toMutablePreferences()
            mutable[androidx.datastore.preferences.core.stringPreferencesKey("sourceLanguage")] =
                "zh-CN"
            mutable[androidx.datastore.preferences.core.stringPreferencesKey("targetLanguage")] =
                """{"code":"en","name":"English"}"""
            mutable.toPreferences()
        }
        assertEquals(LanguagePair("zh-CN", "en"), saved.current())
    }
}
