package app.retranslate.data.prefs

import app.retranslate.providers.hub.HealthStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ProviderConfigStore's persisted health stamps: round-trip + defensive decode. */
class ProviderConfigStoreStampsTest {

    @Test
    fun `stamps round-trip through their JSON shape`() {
        val stamps = mapOf(
            "MOZHI" to HealthStamp(lastSuccessMs = 111L, cooldownUntilMs = 222L),
            "LINGVA@https://lingva.lunar.icu" to HealthStamp(lastSuccessMs = 0L, cooldownUntilMs = 999L)
        )
        val decoded = ProviderConfigStore.decodeStamps(ProviderConfigStore.encodeStamps(stamps))
        assertEquals(stamps, decoded)
    }

    @Test
    fun `corrupt stamp blobs degrade to empty, never a crash`() {
        assertTrue(ProviderConfigStore.decodeStamps("").isEmpty())
        assertTrue(ProviderConfigStore.decodeStamps("{broken").isEmpty())
        assertTrue(ProviderConfigStore.decodeStamps("""{"X": 42}""").isEmpty())
        assertTrue(ProviderConfigStore.decodeStamps("""[]""").isEmpty())
    }
}
