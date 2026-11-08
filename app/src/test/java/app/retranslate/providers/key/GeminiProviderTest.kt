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
 * Gemini fixtures: the 400 auth body is VERBATIM from the live endpoint
 * (P2 probe, 2026-10-27, fake key). The success and 429 bodies follow
 * Google's documented generateContent envelope — the success path stays
 * UNVERIFIED live until a real key exists (P2 report).
 */
class GeminiProviderTest {

    private val clock = FakeClock(now = 1_000L)
    private val health = ProviderHealth(FakeHealthStore(), clock.lambda)
    private var storedKey: String? = "probe-key"
    private var chargedChars = 0

    private fun provider(transport: FakeTransport) = GeminiProvider(
        transport = transport,
        health = health,
        keyProvider = { storedKey },
        isConfigured = { storedKey != null },
        nowMs = clock.lambda,
        delayFn = {},
        usageRecorder = { chargedChars += it }
    )

    private fun provider(
        transport: FakeTransport,
        session: GeminiModelSession,
        manual: String? = null
    ) = GeminiProvider(
        transport = transport,
        health = health,
        keyProvider = { storedKey },
        isConfigured = { storedKey != null },
        nowMs = clock.lambda,
        delayFn = {},
        usageRecorder = { chargedChars += it },
        modelPreference = { manual },
        session = session
    )

    private fun request(
        text: String = "안녕하세요",
        quality: QualityMode = QualityMode.BALANCED
    ) = TranslateRequest(text = text, source = "ko", target = "en", quality = quality)

    @Test
    fun `success parses the candidate text and charges usage`() = runTest {
        val transport = FakeTransport().onUrlContaining(":generateContent", 200, SUCCESS_BODY)
        val result = provider(transport).translate(request())
        val translation = (result as AppResult.Ok).value
        assertEquals("Hello.", translation.text)
        assertEquals("안녕하세요".length, chargedChars)
    }

    @Test
    fun `request shape - model per quality, key header, system instruction`() = runTest {
        val transport = FakeTransport().onUrlContaining(":generateContent", 200, SUCCESS_BODY)
        provider(transport).translate(request(quality = QualityMode.FAST))
        val fast = transport.requests.single()
        assertTrue(fast.url.contains("gemini-3.5-flash-lite:generateContent"))
        assertEquals("probe-key", fast.headers["x-goog-api-key"])
        assertTrue(fast.body!!.contains("systemInstruction"))
        assertTrue(fast.body.contains("Korean"))
        assertTrue(fast.body.contains("English"))
        assertTrue(fast.body.contains("\"temperature\":0.3"))

        transport.requests.clear()
        provider(transport).translate(request(quality = QualityMode.BALANCED))
        assertTrue(transport.requests.single().url.contains("gemini-3.5-flash-lite:generateContent"))
    }

    @Test
    fun `a KB system prompt from the request context replaces the default instruction`() = runTest {
        val transport = FakeTransport().onUrlContaining(":generateContent", 200, SUCCESS_BODY)
        val req = request().copy(
            context = app.retranslate.providers.AiContext("KNOWLEDGE BASE: Lin Yun is male.")
        )
        provider(transport).translate(req)
        val body = transport.requests.single().body!!
        assertTrue(body.contains("KNOWLEDGE BASE: Lin Yun is male."))
        assertFalse(body.contains("You are a translation engine"))
    }

    @Test
    fun `bad key - the verbatim live 400 maps to AuthFailed and is not retried`() = runTest {
        val transport = FakeTransport().onUrlContaining(":generateContent", 400, BAD_KEY_BODY)
        val result = provider(transport).translate(request())
        assertEquals(
            AppError.AuthFailed(
                "GEMINI",
                "HTTP 400: API key not valid. Please pass a valid API key."
            ),
            (result as AppResult.Err).error
        )
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `quota - 429 RESOURCE_EXHAUSTED maps to RateLimited with the RetryInfo delay`() = runTest {
        val transport = FakeTransport().onUrlContaining(":generateContent", 429, QUOTA_BODY)
        val result = provider(transport).callForTest(request())
        assertEquals(AppError.RateLimited("GEMINI", 27L), result)
    }

    @Test
    fun `server error maps to a retryable ProviderDown`() = runTest {
        val transport = FakeTransport().onUrlContaining(":generateContent", 503, "{}")
        val result = provider(transport).callForTest(request())
        assertEquals(
            AppError.ProviderDown("GEMINI", 503, retryable = true, detail = "HTTP 503"),
            result
        )
    }

    @Test
    fun `a blocked prompt is AiBadOutput, never an empty success`() = runTest {
        val transport = FakeTransport().onUrlContaining(":generateContent", 200, BLOCKED_BODY)
        val result = provider(transport).callForTest(request())
        assertEquals(AppError.AiBadOutput("GEMINI"), result)
    }

    @Test
    fun `a malformed 200 body is AiBadOutput`() = runTest {
        val transport = FakeTransport().onUrlContaining(":generateContent", 200, "<html>nope</html>")
        val result = provider(transport).callForTest(request())
        assertEquals(AppError.AiBadOutput("GEMINI"), result)
    }

    @Test
    fun `no key - pair unsupported so the router never calls it`() = runTest {
        storedKey = null
        val transport = FakeTransport()
        val gemini = provider(transport)
        assertFalse(gemini.supportsPair("ko", "en"))
        val result = gemini.translate(request())
        assertEquals(AppError.AuthFailed("GEMINI"), (result as AppResult.Err).error)
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun `testWithSecret proves a candidate key with one cheap call`() = runTest {
        val transport = FakeTransport().onUrlContaining(":generateContent", 200, SUCCESS_BODY)
        val result = provider(transport).testWithSecret("candidate-key")
        assertTrue(result is AppResult.Ok)
        val sent = transport.requests.single()
        assertEquals("candidate-key", sent.headers["x-goog-api-key"])
        assertTrue(sent.url.contains("gemini-3.5-flash-lite"))
    }

    // ---- v1.1.2: the model cascade -----------------------------------
    // Google retired every 2.5 model for new keys (Sept 2026): the 404
    // below is the shape the user's own phone showed. One retired model
    // must never block a valid key or a real translation again.

    @Test
    fun `key test - retired lite steps to the next lite and the key passes`() = runTest {
        val transport = FakeTransport()
            .onUrlContaining("gemini-3.5-flash-lite", 404, RETIRED_BODY)
            .onUrlContaining("gemini-3.1-flash-lite", 200, SUCCESS_BODY)
        val result = provider(transport).testWithSecret("candidate-key")
        assertTrue(result is AppResult.Ok)
        assertEquals(2, transport.requests.size)
        assertTrue(transport.requests[0].url.contains("gemini-3.5-flash-lite"))
        assertTrue(transport.requests[1].url.contains("gemini-3.1-flash-lite"))
    }

    @Test
    fun `cascade - both lites retired, the first Flash rescues a real translation`() = runTest {
        val transport = FakeTransport()
            .onUrlContaining("gemini-3.5-flash-lite", 404, RETIRED_BODY)
            .onUrlContaining("gemini-3.1-flash-lite", 404, RETIRED_BODY)
            .onUrlContaining("gemini-3.8-flash", 200, SUCCESS_BODY)
        val result = provider(transport).translate(request())
        assertTrue(result is AppResult.Ok)
        assertEquals(3, transport.requests.size)
        assertTrue(transport.requests[2].url.contains("gemini-3.8-flash"))
    }

    @Test
    fun `cascade order is lite-first for every quality`() = runTest {
        val gemini = provider(FakeTransport())
        assertEquals(
            listOf(
                "gemini-3.5-flash-lite", "gemini-3.1-flash-lite",
                "gemini-3.8-flash", "gemini-3.6-flash"
            ),
            gemini.cascadeOrder(null)
        )
        assertEquals("gemini-3.5-flash-lite", GeminiProvider.modelFor(QualityMode.FAST))
        assertEquals("gemini-3.5-flash-lite", GeminiProvider.modelFor(QualityMode.BALANCED))
        assertEquals("gemini-3.5-flash-lite", GeminiProvider.modelFor(QualityMode.BEST))
    }

    @Test
    fun `cascade - every model retired surfaces the last 404, after exactly four calls`() =
        runTest {
            val transport = FakeTransport()
                .onUrlContaining("gemini-3.5-flash-lite", 404, RETIRED_BODY)
                .onUrlContaining("gemini-3.1-flash-lite", 404, RETIRED_BODY)
                .onUrlContaining("gemini-3.8-flash", 404, RETIRED_BODY)
                .onUrlContaining("gemini-3.6-flash", 404, RETIRED_BODY)
            val result = provider(transport).testWithSecret("candidate-key")
            assertTrue(result is AppResult.Err)
            assertEquals(4, transport.requests.size)
        }

    @Test
    fun `a 404 that is not about a model does not trigger the cascade`() = runTest {
        val transport = FakeTransport()
            .onUrlContaining("gemini-3.5-flash-lite", 404, PLAIN_404_BODY)
        val result = provider(transport).testWithSecret("candidate-key")
        assertTrue(result is AppResult.Err)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `a bad key does not trigger the cascade`() = runTest {
        val transport = FakeTransport()
            .onUrlContaining(":generateContent", 400, BAD_KEY_BODY)
        val result = provider(transport).testWithSecret("candidate-key")
        assertTrue((result as AppResult.Err).error is AppError.AuthFailed)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `session memory - the working model is tried first on the next call`() = runTest {
        val session = GeminiModelSession()
        val transport = FakeTransport()
            .onUrlContaining("gemini-3.5-flash-lite", 404, RETIRED_BODY)
            .onUrlContaining("gemini-3.1-flash-lite", 200, SUCCESS_BODY)
        val gemini = provider(transport, session)
        assertTrue(gemini.testWithSecret("candidate-key") is AppResult.Ok)
        assertEquals("gemini-3.1-flash-lite", session.workingModel)
        transport.requests.clear()
        assertTrue(gemini.testWithSecret("candidate-key") is AppResult.Ok)
        // Straight to the remembered model — no 404 tax the second time.
        assertEquals(1, transport.requests.size)
        assertTrue(transport.requests.single().url.contains("gemini-3.1-flash-lite"))
    }

    @Test
    fun `manual pin - the pinned Flash model is tried first`() = runTest {
        val transport = FakeTransport()
            .onUrlContaining("gemini-3.8-flash", 200, SUCCESS_BODY)
        val gemini = provider(transport, GeminiModelSession(), manual = "gemini-3.8-flash")
        assertTrue(gemini.testWithSecret("candidate-key") is AppResult.Ok)
        assertEquals(1, transport.requests.size)
        assertTrue(transport.requests.single().url.contains("gemini-3.8-flash"))
    }

    @Test
    fun `manual pin - a retired pinned model is rescued by the lite-first cascade`() = runTest {
        val transport = FakeTransport()
            .onUrlContaining("gemini-3.8-flash", 404, RETIRED_BODY)
            .onUrlContaining("gemini-3.5-flash-lite", 200, SUCCESS_BODY)
        val gemini = provider(transport, GeminiModelSession(), manual = "gemini-3.8-flash")
        assertTrue(gemini.testWithSecret("candidate-key") is AppResult.Ok)
        assertEquals(2, transport.requests.size)
        assertTrue(transport.requests[0].url.contains("gemini-3.8-flash"))
        assertTrue(transport.requests[1].url.contains("gemini-3.5-flash-lite"))
    }

    /**
     * Error-TYPE assertion lens: runs the full translate() (retry loop
     * included — the fake answers instantly) and returns the final error.
     */
    private suspend fun GeminiProvider.callForTest(req: TranslateRequest): AppError =
        (translate(req) as AppResult.Err).error

    companion object {
        private val SUCCESS_BODY = """
            {"candidates":[{"content":{"parts":[{"text":"Hello."}],"role":"model"},
            "finishReason":"STOP","index":0}],
            "usageMetadata":{"promptTokenCount":9,"candidatesTokenCount":2,"totalTokenCount":11},
            "modelVersion":"gemini-3.5-flash-lite","responseId":"probe"}
        """.trimIndent()

        /** Verbatim live response to a fake key (P2 probe, 2026-10-27). */
        private val BAD_KEY_BODY = """
            {"error":{"code":400,
            "message":"API key not valid. Please pass a valid API key.",
            "status":"INVALID_ARGUMENT",
            "details":[
              {"@type":"type.googleapis.com/google.rpc.ErrorInfo",
               "reason":"API_KEY_INVALID","domain":"googleapis.com",
               "metadata":{"service":"generativelanguage.googleapis.com"}},
              {"@type":"type.googleapis.com/google.rpc.LocalizedMessage",
               "locale":"en-US",
               "message":"API key not valid. Please pass a valid API key."}]}}
        """.trimIndent()

        /** Documented RESOURCE_EXHAUSTED envelope with a RetryInfo detail. */
        private val QUOTA_BODY = """
            {"error":{"code":429,
            "message":"You exceeded your current quota, please check your plan and billing details.",
            "status":"RESOURCE_EXHAUSTED",
            "details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"27s"}]}}
        """.trimIndent()

        private val BLOCKED_BODY = """
            {"promptFeedback":{"blockReason":"SAFETY"}}
        """.trimIndent()

        /**
         * The model-retirement 404 — the message text is the one the
         * user's phone showed for gemini-2.5-flash-lite (2026-10-27).
         */
        private val RETIRED_BODY = """
            {"error":{"code":404,
            "message":"This model models/gemini-2.5-flash-lite is no longer available to new users. Please update your code to use models/gemini-3.5-flash-lite for the latest features and improvements.",
            "status":"NOT_FOUND"}}
        """.trimIndent()

        /** A 404 about something else entirely — never a cascade trigger. */
        private val PLAIN_404_BODY = """
            {"error":{"code":404,
            "message":"The requested resource was not found.",
            "status":"NOT_FOUND"}}
        """.trimIndent()
    }
}
