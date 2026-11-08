package app.retranslate.providers.free

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.core.network.HttpResponse
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
 * Lingva parsing + rotation. Fixtures mirror the live API: success
 * `{"translation":…}`, the 500 `{"error":…}` body both bundled instances
 * returned from the sandbox, and the parked-domain HTML page.
 */
class LingvaProviderTest {

    private val clock = FakeClock(now = 0L)
    private val health = ProviderHealth(FakeHealthStore(), clock.lambda)
    private val instances = FakeInstanceStore(
        mapOf(ProviderId.LINGVA to listOf("https://l1.example", "https://l2.example"))
    )

    private fun provider(transport: FakeTransport) = LingvaProvider(
        transport = transport,
        health = health,
        instanceStore = instances,
        nowMs = clock.lambda,
        delayFn = {}
    )

    private val req = TranslateRequest(text = "안녕하세요", source = "ko", target = "en")

    @Test
    fun `parses a translation response`() = runTest {
        val transport = FakeTransport()
            .onUrlContaining("l1.example", 200, """{"translation":"hello"}""")
        val result = provider(transport).translate(req)
        assertEquals("hello", (result as AppResult.Ok).value.text)
        assertTrue(
            transport.requestedUrls.single().contains("/api/v1/ko/en/")
        )
    }

    @Test
    fun `the live 500 error JSON is retryable and rotates`() = runTest {
        val transport = FakeTransport()
            .onUrlContaining("l1.example", 500, """{"error":"An error occurred while retrieving the translation"}""")
            .onUrlContaining("l2.example", 200, """{"translation":"hello"}""")
        val result = provider(transport).translate(req)
        assertTrue(result is AppResult.Ok)
        assertEquals(2, transport.requestedUrls.size)
    }

    @Test
    fun `an HTML page instead of JSON is a non-retryable fault`() = runTest {
        val transport = FakeTransport()
            .on({ true }, AppResult.Ok(HttpResponse(200, "<!DOCTYPE html><html>parked</html>")))
        val result = provider(transport).translate(req)
        val error = (result as AppResult.Err).error
        assertTrue(error is AppError.ProviderDown)
        assertFalse((error as AppError.ProviderDown).retryable)
        assertEquals(1, transport.requestedUrls.size)
    }

    @Test
    fun `language list parses and maps to app codes, with caching`() = runTest {
        val transport = FakeTransport().onUrlContaining(
            "/api/v1/languages/source",
            200,
            """{"languages":[{"code":"auto","name":"Detect"},{"code":"en","name":"English"},{"code":"ko","name":"Korean"}]}"""
        )
        val provider = provider(transport)
        val languages = (provider.supportedLanguages() as AppResult.Ok).value
        // "auto" is not a target language; names come from the canonical list.
        assertEquals(listOf("en", "ko"), languages.map { it.code })
        val callsAfterFirst = transport.requestedUrls.size
        provider.supportedLanguages()
        assertEquals("second call must hit the 7-day cache", callsAfterFirst, transport.requestedUrls.size)
    }
}
