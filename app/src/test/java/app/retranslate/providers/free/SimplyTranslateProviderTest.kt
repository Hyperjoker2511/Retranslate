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
 * SimplyTranslate parsing. The critical fixture is the one recorded
 * LIVE in P1: HTTP 200, correct shape, EMPTY translated_text — that
 * must surface as a provider fault, never as a successful empty
 * translation (the old app's worst bug).
 */
class SimplyTranslateProviderTest {

    private val clock = FakeClock(now = 0L)
    private val health = ProviderHealth(FakeHealthStore(), clock.lambda)
    private val instances = FakeInstanceStore(
        mapOf(ProviderId.SIMPLYTRANSLATE to listOf("https://s1.example"))
    )

    private fun provider(transport: FakeTransport) = SimplyTranslateProvider(
        transport = transport,
        health = health,
        instances = instances,
        nowMs = clock.lambda,
        delayFn = {}
    )

    private val req = TranslateRequest(text = "hello", source = "en", target = "es")

    @Test
    fun `parses a translation response`() = runTest {
        val transport = FakeTransport().on(
            { true },
            AppResult.Ok(HttpResponse(200, """{"source_language":"en","translated_text":"hola","pronunciation":""}"""))
        )
        val result = provider(transport).translate(req)
        assertEquals("hola", (result as AppResult.Ok).value.text)
    }

    @Test
    fun `the live empty-translation shape is a fault, not a result`() = runTest {
        // Verbatim shape simplytranslate.org returned from the sandbox.
        val transport = FakeTransport().on(
            { true },
            AppResult.Ok(
                HttpResponse(
                    200,
                    """{"source_language":"ko","definitions":null,"translations":null,"translated_text":"","pronunciation":""}"""
                )
            )
        )
        val result = provider(transport)
            .translate(TranslateRequest(text = "안녕하세요", source = "ko", target = "en"))
        val error = (result as AppResult.Err).error
        assertTrue(error is AppError.ProviderDown)
        assertFalse((error as AppError.ProviderDown).retryable)
        assertEquals(1, transport.requestedUrls.size)
    }

    @Test
    fun `a 500 HTML error page is retryable server trouble`() = runTest {
        // Verbatim first line st.privacydev.net returned from the sandbox.
        val transport = FakeTransport().on(
            { true },
            AppResult.Ok(HttpResponse(500, "<!doctype html><title>500 Internal Server Error</title>"))
        )
        val result = provider(transport).translate(req)
        val error = (result as AppResult.Err).error
        assertTrue(error is AppError.ProviderDown)
        assertTrue((error as AppError.ProviderDown).retryable)
    }
}
