package app.retranslate.providers.free

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.providers.FakeClock
import app.retranslate.providers.FakeHealthStore
import app.retranslate.providers.FakeInstanceStore
import app.retranslate.providers.FakeTransport
import app.retranslate.providers.ProviderId
import app.retranslate.providers.TranslateRequest
import app.retranslate.providers.hub.ProviderHealth
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LibreTranslate fixtures: the 400 "get an API key" body is VERBATIM
 * from the live public host (P2 probe, 2026-10-27). The success body
 * follows the documented self-hosted envelope.
 */
class LibreTranslateProviderTest {

    private val clock = FakeClock(now = 1_000L)
    private val health = ProviderHealth(FakeHealthStore(), clock.lambda)
    private var storedKey: String? = null

    private fun provider(
        transport: FakeTransport,
        instances: List<String> = listOf("https://libre.example.org")
    ) = LibreTranslateProvider(
        transport = transport,
        health = health,
        instances = FakeInstanceStore(mapOf(ProviderId.LIBRETRANSLATE to instances)),
        nowMs = clock.lambda,
        delayFn = {},
        keyProvider = { storedKey }
    )

    private fun request(text: String = "안녕하세요") =
        TranslateRequest(text = text, source = "ko", target = "en")

    @Test
    fun `success parses translatedText`() = runTest {
        val transport = FakeTransport()
            .onUrlContaining("/translate", 200, """{"translatedText":"Hello."}""")
        val result = provider(transport).translate(request())
        assertEquals("Hello.", (result as AppResult.Ok).value.text)
        val sent = transport.requests.single()
        assertTrue(sent.url.startsWith("https://libre.example.org/translate"))
        assertTrue(sent.body!!.contains("\"source\":\"ko\""))
        assertFalse(sent.body.contains("api_key"))
    }

    @Test
    fun `a stored key rides along as api_key`() = runTest {
        storedKey = "user-key"
        val transport = FakeTransport()
            .onUrlContaining("/translate", 200, """{"translatedText":"Hello."}""")
        provider(transport).translate(request())
        assertTrue(transport.requests.single().body!!.contains("\"api_key\":\"user-key\""))
    }

    @Test
    fun `the public host's no-key answer maps to AuthFailed`() = runTest {
        val transport = FakeTransport().onUrlContaining("/translate", 400, NO_KEY_BODY)
        val result = provider(
            transport,
            instances = listOf("https://libretranslate.com")
        ).translate(request())
        assertEquals(AppError.AuthFailed("LIBRETRANSLATE"), (result as AppResult.Err).error)
    }

    @Test
    fun `429 maps to RateLimited`() = runTest {
        val transport = FakeTransport()
            .onUrlContaining("/translate", 429, """{"error":"Too many requests"}""")
        val result = provider(transport).translate(request())
        assertTrue((result as AppResult.Err).error is AppError.RateLimited)
    }

    @Test
    fun `no instances configured - fails fast without any HTTP call`() = runTest {
        val transport = FakeTransport()
        val result = provider(transport, instances = emptyList()).translate(request())
        assertTrue(result is AppResult.Err)
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun `zh-TW maps to the traditional code zt`() = runTest {
        val transport = FakeTransport()
            .onUrlContaining("/translate", 200, """{"translatedText":"Hello."}""")
        provider(transport).translate(request().copy(source = "zh-TW"))
        assertTrue(transport.requests.single().body!!.contains("\"source\":\"zt\""))
    }

    @Test
    fun `testWithSecret proves a candidate key against the public host`() = runTest {
        val transport = FakeTransport()
            .onUrlContaining("libretranslate.com/translate", 200, """{"translatedText":"Hola."}""")
        val result = provider(transport).testWithSecret("candidate")
        assertTrue(result is AppResult.Ok)
        assertTrue(transport.requests.single().body!!.contains("\"api_key\":\"candidate\""))
    }

    companion object {
        /** Verbatim live response from libretranslate.com without a key (P2 probe). */
        private const val NO_KEY_BODY =
            """{"error":"Visit https://portal.libretranslate.com to get an API key"}"""
    }
}
