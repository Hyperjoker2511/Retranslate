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
 * Mozhi parsing + rotation against fixtures recorded from the LIVE API
 * during the P1 smoke tests (build-logs/P1-REPORT.md).
 */
class MozhiProviderTest {

    private val clock = FakeClock(now = 0L)
    private val health = ProviderHealth(FakeHealthStore(), clock.lambda)
    private val instances = FakeInstanceStore(
        mapOf(ProviderId.MOZHI to listOf("https://i1.example", "https://i2.example"))
    )

    private fun provider(transport: FakeTransport) = MozhiProvider(
        transport = transport,
        health = health,
        instances = instances,
        nowMs = clock.lambda,
        delayFn = {}
    )

    private val req = TranslateRequest(text = "안녕하세요", source = "ko", target = "en")

    /** The exact shape mozhi.adminforge.de returned live for 안녕하세요 ko→en. */
    private val liveHelloBody = """
        {"engine":"google","detected":"ko","translated-text":"hello",
         "source_language":"ko","target_language":"en","word_choices":null,
         "source_transliteration":"","target_transliteration":"",
         "source_synonyms":null,"target_synonyms":null}
    """.trimIndent()

    @Test
    fun `parses the live response shape including detection`() = runTest {
        val transport = FakeTransport().onUrlContaining("i1.example", 200, liveHelloBody)
        val result = provider(transport).translate(req)
        val translation = (result as AppResult.Ok).value
        assertEquals("hello", translation.text)
        assertEquals("ko", translation.detectedSource)
        assertEquals("https://i1.example", translation.instance)
        assertTrue(transport.requestedUrls.single().contains("/api/translate/"))
        assertTrue(transport.requestedUrls.single().contains("engine=google"))
    }

    @Test
    fun `rate-limited instance rotates to the next instance`() = runTest {
        val transport = FakeTransport()
            .onUrlContaining("i1.example", 500, "instance has been rate limited")
            .onUrlContaining("i2.example", 200, liveHelloBody)
        val result = provider(transport).translate(req)
        assertTrue(result is AppResult.Ok)
        assertEquals("https://i2.example", (result as AppResult.Ok).value.instance)
        assertEquals(2, transport.requestedUrls.size)
    }

    @Test
    fun `rate limit body maps to RateLimited when every instance is limited`() = runTest {
        val transport = FakeTransport()
            .on({ true }, AppResult.Ok(app.retranslate.core.network.HttpResponse(500, "instance has been rate limited")))
        val result = provider(transport).translate(req)
        val error = (result as AppResult.Err).error
        assertTrue(error is AppError.RateLimited)
    }

    @Test
    fun `invalid language body maps to UnsupportedLanguagePair and stops rotation`() = runTest {
        val transport = FakeTransport()
            .on({ true }, AppResult.Ok(app.retranslate.core.network.HttpResponse(500, "Target language code invalid")))
        val result = provider(transport).translate(req)
        val error = (result as AppResult.Err).error
        assertTrue(error is AppError.UnsupportedLanguagePair)
        assertEquals(1, transport.requestedUrls.size)
    }

    @Test
    fun `a 200 HTML bot-check page is a non-retryable provider fault`() = runTest {
        val transport = FakeTransport()
            .on({ true }, AppResult.Ok(app.retranslate.core.network.HttpResponse(200, "<!doctype html><title>Checking if wolf...</title>")))
        val result = provider(transport).translate(req)
        val error = (result as AppResult.Err).error
        assertTrue(error is AppError.ProviderDown)
        assertFalse((error as AppError.ProviderDown).retryable)
        assertEquals(1, transport.requestedUrls.size)
    }

    @Test
    fun `blank translated-text is a fault, never an empty success`() = runTest {
        val transport = FakeTransport()
            .on({ true }, AppResult.Ok(app.retranslate.core.network.HttpResponse(200, """{"translated-text":"","detected":""}""")))
        val result = provider(transport).translate(req)
        assertTrue(result is AppResult.Err)
    }
}
