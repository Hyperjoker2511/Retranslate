package app.retranslate.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLAN.md §4.6/§12.1: per-provider language-code mapping, unit-tested. */
class ProviderLanguageMapsTest {

    @Test
    fun `mozhi uses google codes verbatim - zh-CN never collapses to zh`() {
        val map = ProviderLanguageMaps.MOZHI
        assertEquals("zh-CN", map.toProvider("zh-CN", isSource = true))
        assertEquals("zh-TW", map.toProvider("zh-TW", isSource = false))
        assertEquals("ko", map.toProvider("ko", isSource = true))
    }

    @Test
    fun `auto is a source-only code where supported`() {
        assertEquals("auto", ProviderLanguageMaps.MOZHI.toProvider("auto", isSource = true))
        assertEquals("auto", ProviderLanguageMaps.LINGVA.toProvider("auto", isSource = true))
        assertNull(ProviderLanguageMaps.MOZHI.toProvider("auto", isSource = false))
        assertNull(ProviderLanguageMaps.LINGVA.toProvider("auto", isSource = false))
    }

    @Test
    fun `mymemory cannot auto-detect - pair unsupported for auto source`() {
        val map = ProviderLanguageMaps.MYMEMORY
        assertNull(map.toProvider("auto", isSource = true))
        assertFalse(map.supportsPair("auto", "en"))
        assertTrue(map.supportsPair("ko", "en"))
    }

    @Test
    fun `unknown codes map to null so the router can pre-filter`() {
        assertNull(ProviderLanguageMaps.MOZHI.toProvider("xx", isSource = true))
        assertNull(ProviderLanguageMaps.LINGVA.toProvider("", isSource = true))
        assertFalse(ProviderLanguageMaps.SIMPLYTRANSLATE.supportsPair("ko", "xx"))
        assertTrue(ProviderLanguageMaps.SIMPLYTRANSLATE.supportsPair("ko", "en"))
    }

    @Test
    fun `provider codes map back to app codes for detection results`() {
        assertEquals("ko", ProviderLanguageMaps.MOZHI.fromProvider("ko"))
        assertEquals("zh-CN", ProviderLanguageMaps.MOZHI.fromProvider("zh-CN"))
        assertNull(ProviderLanguageMaps.MOZHI.fromProvider(""))
        assertNull(ProviderLanguageMaps.MOZHI.fromProvider("not-a-language"))
    }
}
