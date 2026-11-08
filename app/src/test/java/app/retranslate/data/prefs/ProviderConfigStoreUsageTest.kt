package app.retranslate.data.prefs

import app.retranslate.providers.FakePreferencesDataStore
import app.retranslate.providers.ProviderId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** PLAN.md §5.5: per-provider monthly character counters. */
class ProviderConfigStoreUsageTest {

    private fun store() = ProviderConfigStore(FakePreferencesDataStore())

    @Test
    fun `usage accumulates per provider and per month`() = runTest {
        val config = store()
        config.addUsageChars(ProviderId.GEMINI, 100, month = "2026-10")
        config.addUsageChars(ProviderId.GEMINI, 250, month = "2026-10")
        config.addUsageChars(ProviderId.DEEPL, 40, month = "2026-10")
        config.addUsageChars(ProviderId.GEMINI, 999, month = "2026-09")
        assertEquals(350, config.usageChars(ProviderId.GEMINI, month = "2026-10"))
        assertEquals(40, config.usageChars(ProviderId.DEEPL, month = "2026-10"))
        assertEquals(999, config.usageChars(ProviderId.GEMINI, month = "2026-09"))
        assertEquals(0, config.usageChars(ProviderId.AZURE, month = "2026-10"))
    }

    @Test
    fun `zero and negative charges are ignored`() = runTest {
        val config = store()
        config.addUsageChars(ProviderId.GEMINI, 0, month = "2026-10")
        config.addUsageChars(ProviderId.GEMINI, -5, month = "2026-10")
        assertEquals(0, config.usageChars(ProviderId.GEMINI, month = "2026-10"))
    }
}
