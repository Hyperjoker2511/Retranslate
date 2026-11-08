package app.retranslate.providers.key

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.providers.FakeClock
import app.retranslate.providers.FakeHealthStore
import app.retranslate.providers.FakeTransport
import app.retranslate.providers.TranslateRequest
import app.retranslate.providers.hub.ProviderHealth
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.1.3 (§A1/§A4): the silent-merge bug's provider half — a reply
 * cut off by the model's output limit must surface as a typed
 * failure (the caller then rejects/retries loudly), never as a
 * "successful" truncated translation. In v1.1.2 the polish prompts
 * set no output limit and ignored the finish reason, so long
 * chapters came back truncated, failed verification, and the
 * original was silently kept.
 */
class ProviderTruncationTest {

    private val clock = FakeClock(now = 10_000L)
    private val health = ProviderHealth(FakeHealthStore(), clock.lambda)

    private fun request() = TranslateRequest(
        text = "안녕하세요", source = "ko", target = "en"
    )

    @Test
    fun `gemini MAX_TOKENS is a typed failure, not a partial text`() = runTest {
        val transport = FakeTransport().onUrlContaining(
            "generativelanguage", 200,
            """{"candidates":[{"content":{"parts":[{"text":"Partial ans"}],"role":"model"},
                "finishReason":"MAX_TOKENS","index":0}]}"""
        )
        val provider = GeminiProvider(
            transport = transport,
            health = health,
            keyProvider = { "key" },
            isConfigured = { true },
            nowMs = clock.lambda,
            delayFn = {}
        )
        val result = provider.translate(request())
        assertTrue(result is AppResult.Err)
        assertTrue((result as AppResult.Err).error is AppError.AiBadOutput)
    }

    @Test
    fun `openai finish_reason length is a typed failure`() = runTest {
        val transport = FakeTransport().onUrlContaining(
            "chat/completions", 200,
            """{"choices":[{"index":0,"message":{"role":"assistant",
                "content":"Partial ans"},"finish_reason":"length"}]}"""
        )
        val provider = OpenAiProvider(
            transport = transport,
            health = health,
            keyProvider = { "key" },
            isConfigured = { true },
            nowMs = clock.lambda,
            delayFn = {}
        )
        val result = provider.translate(request())
        assertTrue(result is AppResult.Err)
        assertTrue((result as AppResult.Err).error is AppError.AiBadOutput)
    }

    @Test
    fun `apinex instance posts to the gateway base with its own identity`() = runTest {
        val transport = FakeTransport().onUrlContaining(
            "api.apinex.bond/v1/chat/completions", 200,
            """{"choices":[{"index":0,"message":{"role":"assistant",
                "content":"Hello."},"finish_reason":"stop"}]}"""
        )
        val provider = OpenAiProvider(
            transport = transport,
            health = health,
            keyProvider = { "sk-apx-test" },
            isConfigured = { true },
            nowMs = clock.lambda,
            delayFn = {},
            apiBase = ApinexGateway.API_BASE,
            providerId = app.retranslate.providers.ProviderId.APINEX,
            providerDisplayName = ApinexGateway.DISPLAY_NAME,
            modelPreference = { ApinexGateway.DEFAULT_FREE_MODEL },
            testViaModelsList = true
        )
        val result = provider.translate(request())
        assertTrue(result is AppResult.Ok)
        assertTrue(transport.requestedUrls.any { it.contains("api.apinex.bond") })
    }
}
