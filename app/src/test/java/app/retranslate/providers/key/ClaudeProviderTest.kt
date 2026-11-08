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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude fixtures (PLAN.md §4.3, P7): request/response shapes follow
 * Anthropic's documented Messages envelope; error bodies follow the
 * documented error envelope. The SUCCESS path is fixture-verified
 * only — no real key exists in this sandbox (same honesty rule as
 * P2); the negative paths are live-probed separately (P7 report).
 */
class ClaudeProviderTest {

    private val clock = FakeClock(now = 1_000L)
    private val health = ProviderHealth(FakeHealthStore(), clock.lambda)
    private var storedKey: String? = "probe-key"
    private var chargedChars = 0

    private fun provider(transport: FakeTransport) = ClaudeProvider(
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
    fun `success joins text blocks and charges usage`() = runTest {
        val transport = FakeTransport().onUrlContaining("/v1/messages", 200, SUCCESS_BODY)
        val result = provider(transport).translate(request())
        val translation = (result as AppResult.Ok).value
        assertEquals("Hello there.", translation.text)
        assertEquals("안녕하세요".length, chargedChars)
    }

    @Test
    fun `request shape - key headers, top-level system, model per quality`() = runTest {
        val transport = FakeTransport().onUrlContaining("/v1/messages", 200, SUCCESS_BODY)
        provider(transport).translate(request(quality = QualityMode.FAST))
        val fast = transport.requests.single()
        assertTrue(fast.url.endsWith("/v1/messages"))
        assertEquals("probe-key", fast.headers["x-api-key"])
        assertEquals("2023-06-01", fast.headers["anthropic-version"])
        assertTrue(fast.body!!.contains("\"model\":\"claude-haiku-4-5\""))
        assertTrue(fast.body.contains("\"max_tokens\":8192"))
        assertTrue(fast.body.contains("\"system\":\"You are a translation engine."))
        // Messages carry only the user turn for Claude (no system role inside).
        assertFalse(fast.body.contains("\"role\":\"system\""))

        transport.requests.clear()
        provider(transport).translate(request(quality = QualityMode.BALANCED))
        assertTrue(
            transport.requests.single().body!!.contains("\"model\":\"claude-sonnet-4-5\"")
        )
    }

    @Test
    fun `401 maps to AuthFailed`() = runTest {
        val transport = FakeTransport().onUrlContaining("/v1/messages", 401, ERROR_401_BODY)
        val result = provider(transport).translate(request())
        assertTrue((result as AppResult.Err).error is AppError.AuthFailed)
    }

    @Test
    fun `429 maps to RateLimited`() = runTest {
        val transport = FakeTransport().onUrlContaining("/v1/messages", 429, ERROR_429_BODY)
        val result = provider(transport).translate(request())
        assertTrue((result as AppResult.Err).error is AppError.RateLimited)
    }

    @Test
    fun `a refusal stop reason is AiBadOutput`() = runTest {
        val transport = FakeTransport().onUrlContaining("/v1/messages", 200, REFUSAL_BODY)
        val result = provider(transport).translate(request())
        assertTrue((result as AppResult.Err).error is AppError.AiBadOutput)
    }

    @Test
    fun `a max_tokens stop is AiBadOutput (truncated, unusable)`() = runTest {
        val transport = FakeTransport().onUrlContaining("/v1/messages", 200, TRUNCATED_BODY)
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
    fun `testWithSecret sends the candidate key`() = runTest {
        val transport = FakeTransport().onUrlContaining("/v1/messages", 200, SUCCESS_BODY)
        val result = provider(transport).testWithSecret("candidate-key")
        assertTrue(result is AppResult.Ok)
        assertEquals("candidate-key", transport.requests.single().headers["x-api-key"])
    }

    private companion object {
        val SUCCESS_BODY = """
            {"id":"msg_123","type":"message","role":"assistant",
             "model":"claude-sonnet-4-5",
             "content":[{"type":"thinking","thinking":"The user greets me."},
                         {"type":"text","text":"Hello "},
                         {"type":"text","text":"there."}],
             "stop_reason":"end_turn",
             "usage":{"input_tokens":20,"output_tokens":5}}
        """.trimIndent()

        val REFUSAL_BODY = """
            {"id":"msg_124","type":"message","role":"assistant",
             "model":"claude-sonnet-4-5",
             "content":[{"type":"text","text":"I can't help with that."}],
             "stop_reason":"refusal",
             "usage":{"input_tokens":20,"output_tokens":5}}
        """.trimIndent()

        val TRUNCATED_BODY = """
            {"id":"msg_125","type":"message","role":"assistant",
             "model":"claude-sonnet-4-5",
             "content":[{"type":"text","text":"Hello th"}],
             "stop_reason":"max_tokens",
             "usage":{"input_tokens":20,"output_tokens":8192}}
        """.trimIndent()

        val ERROR_401_BODY = """
            {"type":"error","error":{"type":"authentication_error",
             "message":"x-api-key header is invalid"}}
        """.trimIndent()

        val ERROR_429_BODY = """
            {"type":"error","error":{"type":"rate_limit_error",
             "message":"Rate limit exceeded."}}
        """.trimIndent()
    }
}
