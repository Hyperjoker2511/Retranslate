package app.retranslate.providers.key

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.providers.FakeClock
import app.retranslate.providers.FakeHealthStore
import app.retranslate.providers.FakeTransport
import app.retranslate.providers.QualityMode
import app.retranslate.providers.TranslateRequest
import app.retranslate.providers.hub.ProviderHealth
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenAI fixtures (PLAN.md §4.3, P7): request/response shapes follow
 * OpenAI's documented chat-completions envelope; the 401 body follows
 * the documented error envelope. The SUCCESS path is fixture-verified
 * only — no real key exists in this sandbox (same honesty rule as P2);
 * the negative paths are live-probed separately (P7 report).
 */
class OpenAiProviderTest {

    private val clock = FakeClock(now = 1_000L)
    private val health = ProviderHealth(FakeHealthStore(), clock.lambda)
    private var storedKey: String? = "probe-key"
    private var chargedChars = 0

    private fun provider(transport: FakeTransport) = OpenAiProvider(
        transport = transport,
        health = health,
        keyProvider = { storedKey },
        isConfigured = { storedKey != null },
        nowMs = clock.lambda,
        delayFn = {},
        usageRecorder = { chargedChars += it }
    )

    private fun request(
        text: String = "안녕하세요",
        quality: QualityMode = QualityMode.BALANCED
    ) = TranslateRequest(text = text, source = "ko", target = "en", quality = quality)

    @Test
    fun `success parses the completion and charges usage`() = runTest {
        val transport = FakeTransport().onUrlContaining("/v1/chat/completions", 200, SUCCESS_BODY)
        val result = provider(transport).translate(request())
        val translation = (result as AppResult.Ok).value
        assertEquals("Hello.", translation.text)
        assertEquals("안녕하세요".length, chargedChars)
    }

    @Test
    fun `request shape - bearer header, model per quality, system plus user messages`() = runTest {
        val transport = FakeTransport().onUrlContaining("/v1/chat/completions", 200, SUCCESS_BODY)
        provider(transport).translate(request(quality = QualityMode.FAST))
        val fast = transport.requests.single()
        assertTrue(fast.url.endsWith("/v1/chat/completions"))
        assertEquals("Bearer probe-key", fast.headers["Authorization"])
        assertTrue(fast.body!!.contains("\"model\":\"gpt-4o-mini\""))
        assertTrue(fast.body.contains("\"role\":\"system\""))
        assertTrue(fast.body.contains("\"role\":\"user\""))
        assertTrue(fast.body.contains("Korean"))
        assertTrue(fast.body.contains("English"))
        assertTrue(fast.body.contains("\"temperature\":0.3"))

        transport.requests.clear()
        provider(transport).translate(request(quality = QualityMode.BEST))
        assertTrue(transport.requests.single().body!!.contains("\"model\":\"gpt-4o\""))
    }

    @Test
    fun `a KB system prompt from context replaces the default instruction`() = runTest {
        val transport = FakeTransport().onUrlContaining("/v1/chat/completions", 200, SUCCESS_BODY)
        val req = request().copy(
            context = app.retranslate.providers.AiContext("KNOWLEDGE BASE: Lin Yun is male.")
        )
        provider(transport).translate(req)
        val body = transport.requests.single().body!!
        assertTrue(body.contains("KNOWLEDGE BASE: Lin Yun is male."))
    }

    @Test
    fun `401 maps to AuthFailed`() = runTest {
        val transport = FakeTransport().onUrlContaining("/v1/chat/completions", 401, ERROR_401_BODY)
        val result = provider(transport).translate(request())
        assertTrue((result as AppResult.Err).error is AppError.AuthFailed)
    }

    @Test
    fun `429 maps to RateLimited`() = runTest {
        val transport = FakeTransport().onUrlContaining("/v1/chat/completions", 429, ERROR_429_BODY)
        val result = provider(transport).translate(request())
        assertTrue((result as AppResult.Err).error is AppError.RateLimited)
    }

    @Test
    fun `an explicit refusal is AiBadOutput, never a translation`() = runTest {
        val transport = FakeTransport().onUrlContaining("/v1/chat/completions", 200, REFUSAL_BODY)
        val result = provider(transport).translate(request())
        assertTrue((result as AppResult.Err).error is AppError.AiBadOutput)
    }

    @Test
    fun `an unparseable 200 is AiBadOutput`() = runTest {
        val transport = FakeTransport().onUrlContaining("/v1/chat/completions", 200, "<<not json>>")
        val result = provider(transport).translate(request())
        assertTrue((result as AppResult.Err).error is AppError.AiBadOutput)
    }

    @Test
    fun `no key fails before any request`() = runTest {
        storedKey = null
        val transport = FakeTransport()
        val result = provider(transport).translate(request())
        assertTrue((result as AppResult.Err).error is AppError.AuthFailed)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `testWithSecret sends the candidate key, not the stored one`() = runTest {
        val transport = FakeTransport().onUrlContaining("/v1/chat/completions", 200, SUCCESS_BODY)
        val result = provider(transport).testWithSecret("candidate-key")
        assertTrue(result is AppResult.Ok)
        assertEquals("Bearer candidate-key", transport.requests.single().headers["Authorization"])
    }

    private companion object {
        val SUCCESS_BODY = """
            {"id":"chatcmpl-123","object":"chat.completion","created":1700000000,
             "model":"gpt-4o-mini",
             "choices":[{"index":0,"message":{"role":"assistant","content":"Hello."},
                          "finish_reason":"stop"}],
             "usage":{"prompt_tokens":20,"completion_tokens":2,"total_tokens":22}}
        """.trimIndent()

        val REFUSAL_BODY = """
            {"id":"chatcmpl-124","object":"chat.completion","created":1700000000,
             "model":"gpt-4o-mini",
             "choices":[{"index":0,"message":{"role":"assistant","content":null,
                          "refusal":"I can't help with that."},
                          "finish_reason":"stop"}]}
        """.trimIndent()

        val ERROR_401_BODY = """
            {"error":{"message":"Incorrect API key provided: probe-key.",
                      "type":"invalid_request_error","param":null,
                      "code":"invalid_api_key"}}
        """.trimIndent()

        val ERROR_429_BODY = """
            {"error":{"message":"Rate limit reached for gpt-4o-mini.",
                      "type":"rate_limit_error","param":null,
                      "code":"rate_limit_exceeded"}}
        """.trimIndent()
    }
}
