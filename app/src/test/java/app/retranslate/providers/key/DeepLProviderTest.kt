package app.retranslate.providers.key

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.providers.FakeClock
import app.retranslate.providers.FakeHealthStore
import app.retranslate.providers.FakeTransport
import app.retranslate.providers.TranslateRequest
import app.retranslate.providers.hub.ProviderHealth
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeepL fixtures: the 403 body is VERBATIM from the live api-free
 * endpoint (P2 probe, 2026-10-27). Success/usage bodies follow DeepL's
 * documented v2 envelope — UNVERIFIED live until a real key exists.
 */
class DeepLProviderTest {

    private val clock = FakeClock(now = 1_000L)
    private val health = ProviderHealth(FakeHealthStore(), clock.lambda)
    private var storedKey: String? = "probe-key:fx"
    private var chargedChars = 0

    private fun provider(transport: FakeTransport) = DeepLProvider(
        transport = transport,
        health = health,
        keyProvider = { storedKey },
        isConfigured = { storedKey != null },
        nowMs = clock.lambda,
        delayFn = {},
        usageRecorder = { chargedChars += it }
    )

    private fun request(text: String = "안녕하세요") =
        TranslateRequest(text = text, source = "ko", target = "en")

    @Test
    fun `free keys talk to api-free, pro keys to api`() {
        assertEquals(DeepLProvider.HOST_FREE, DeepLProvider.hostFor("abc:fx"))
        assertEquals(DeepLProvider.HOST_PRO, DeepLProvider.hostFor("abc"))
    }

    @Test
    fun `success parses text and the detected language`() = runTest {
        val transport = FakeTransport().onUrlContaining("/v2/translate", 200, SUCCESS_BODY)
        val result = provider(transport).translate(request())
        val translation = (result as AppResult.Ok).value
        assertEquals("Hello.", translation.text)
        assertEquals("ko", translation.detectedSource)
        assertEquals("안녕하세요".length, chargedChars)
    }

    @Test
    fun `request shape - auth header, EN-US target, upper-case codes`() = runTest {
        val transport = FakeTransport().onUrlContaining("/v2/translate", 200, SUCCESS_BODY)
        provider(transport).translate(request())
        val sent = transport.requests.single()
        assertTrue(sent.url.startsWith("https://api-free.deepl.com"))
        assertEquals("DeepL-Auth-Key probe-key:fx", sent.headers["Authorization"])
        assertTrue(sent.body!!.contains("\"source_lang\":\"KO\""))
        assertTrue(sent.body.contains("\"target_lang\":\"EN-US\""))
    }

    @Test
    fun `auto source omits source_lang`() = runTest {
        val transport = FakeTransport().onUrlContaining("/v2/translate", 200, SUCCESS_BODY)
        provider(transport).translate(request().copy(source = "auto"))
        assertFalse(transport.requests.single().body!!.contains("source_lang"))
    }

    @Test
    fun `bad key - the verbatim live 403 maps to AuthFailed, one attempt only`() = runTest {
        val transport = FakeTransport().onUrlContaining("/v2/translate", 403, BAD_KEY_BODY)
        val result = provider(transport).translate(request())
        assertEquals(AppError.AuthFailed("DEEPL"), (result as AppResult.Err).error)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `quota status 456 maps to RateLimited`() = runTest {
        val transport = FakeTransport()
            .onUrlContaining("/v2/translate", 456, """{"message":"Quota exceeded."}""")
        val result = provider(transport).translate(request())
        val error = (result as AppResult.Err).error
        assertTrue(error is AppError.RateLimited)
    }

    @Test
    fun `test button probes the usage endpoint on the right host`() = runTest {
        val transport = FakeTransport()
            .onUrlContaining("/v2/usage", 200, """{"character_count":42,"character_limit":500000}""")
        val result = provider(transport).testWithSecret("candidate:fx")
        assertTrue(result is AppResult.Ok)
        val sent = transport.requests.single()
        assertTrue(sent.url.startsWith("https://api-free.deepl.com/v2/usage"))
        assertEquals("DeepL-Auth-Key candidate:fx", sent.headers["Authorization"])
    }

    @Test
    fun `test button with a bad key fails as AuthFailed and saves nothing`() = runTest {
        val transport = FakeTransport().onUrlContaining("/v2/usage", 403, BAD_KEY_BODY)
        val result = provider(transport).testWithSecret("wrong:fx")
        assertEquals(AppError.AuthFailed("DEEPL"), (result as AppResult.Err).error)
    }

    @Test
    fun `unsupported canonical languages are pre-filtered`() = runTest {
        val deepl = provider(FakeTransport())
        assertTrue(deepl.supportsPair("ko", "en"))
        assertTrue(deepl.supportsPair("zh-CN", "en"))
        assertFalse(deepl.supportsPair("ceb", "en"))
        assertFalse(deepl.supportsPair("ko", "hmn"))
        storedKey = null
        assertFalse(deepl.supportsPair("ko", "en"))
    }

    companion object {
        private val SUCCESS_BODY = """
            {"translations":[{"detected_source_language":"KO","text":"Hello.","billed_characters":5}]}
        """.trimIndent()

        /** Verbatim live response to a fake key (P2 probe, 2026-10-27). */
        private const val BAD_KEY_BODY =
            """{"message":"Authentication failed, provided API key is invalid. """ +
                """You can find more info in our docs: https://developers.deepl.com/docs/getting-started/auth"}"""
    }
}
