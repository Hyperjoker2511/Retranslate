package app.retranslate.providers.local

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

private class FakeOllamaConfigSource(var config: OllamaConfig?) : OllamaConfigSource {
    override suspend fun ollamaConfig(): OllamaConfig? = config
}

/**
 * Ollama (PLAN.md §4.3, P7): the dormant-entry contract. Unconfigured,
 * the provider is inert — no pair support, no calls. Configured, it
 * speaks the documented /api/chat shape. All fixture-level: whether
 * Android's cleartext policy lets the phone reach a LAN PC is a
 * device fact, labelled UNVERIFIED in the P7 report.
 */
class OllamaProviderTest {

    private val clock = FakeClock(now = 1_000L)
    private val health = ProviderHealth(FakeHealthStore(), clock.lambda)
    private val configSource = FakeOllamaConfigSource(null)

    private fun provider(transport: FakeTransport) = OllamaProvider(
        transport = transport,
        health = health,
        configSource = configSource,
        nowMs = clock.lambda,
        delayFn = {}
    )

    private fun request() = TranslateRequest(text = "안녕하세요", source = "ko", target = "en")

    @Test
    fun `dormant - no pair support and no calls while unconfigured`() = runTest {
        val transport = FakeTransport()
        val provider = provider(transport)
        provider.refreshConfig()
        assertFalse(provider.supportsPair("ko", "en"))
        val result = provider.translate(request())
        assertTrue((result as AppResult.Err).error is AppError.ProviderDown)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `configured - chat request shape and response parse`() = runTest {
        configSource.config = OllamaConfig("http://192.168.1.20:11434", "llama3.1")
        val transport = FakeTransport().onUrlContaining("/api/chat", 200, CHAT_BODY)
        val provider = provider(transport)
        provider.refreshConfig()
        assertTrue(provider.supportsPair("ko", "en"))

        val result = provider.translate(request())
        assertEquals("Hello.", (result as AppResult.Ok).value.text)

        val sent = transport.requests.single()
        assertTrue(sent.url == "http://192.168.1.20:11434/api/chat")
        assertTrue(sent.body!!.contains("\"model\":\"llama3.1\""))
        assertTrue(sent.body.contains("\"stream\":false"))
        assertTrue(sent.body.contains("\"role\":\"system\""))
        assertTrue(sent.body.contains("\"role\":\"user\""))
        assertTrue(sent.body.contains("안녕하세요"))
    }

    @Test
    fun `test connection reads the model list endpoint`() = runTest {
        configSource.config = OllamaConfig("http://192.168.1.20:11434", "llama3.1")
        val transport = FakeTransport().onUrlContaining("/api/tags", 200, TAGS_BODY)
        val result = provider(transport).testConnection()
        assertTrue(result is AppResult.Ok)
        assertEquals("GET", transport.requests.single().method)
    }

    @Test
    fun `unknown model on the PC surfaces as an error, not a translation`() = runTest {
        configSource.config = OllamaConfig("http://192.168.1.20:11434", "nosuchmodel")
        val transport = FakeTransport().onUrlContaining("/api/chat", 404, ERROR_BODY)
        val result = provider(transport).translate(request())
        assertTrue(result is AppResult.Err)
    }

    @Test
    fun `base url shape checks`() {
        assertTrue(OllamaProvider.isPlausibleBaseUrl("http://192.168.1.20:11434"))
        assertTrue(OllamaProvider.isPlausibleBaseUrl("https://pc.local:11434/"))
        assertFalse(OllamaProvider.isPlausibleBaseUrl("192.168.1.20:11434"))
        assertFalse(OllamaProvider.isPlausibleBaseUrl("http://"))
        assertFalse(OllamaProvider.isPlausibleBaseUrl(""))
        assertEquals(
            "http://192.168.1.20:11434",
            OllamaProvider.normalizeBaseUrl("http://192.168.1.20:11434/")
        )
    }

    private companion object {
        val CHAT_BODY = """
            {"model":"llama3.1","created_at":"2026-10-27T00:00:00Z",
             "message":{"role":"assistant","content":"Hello."},"done":true,
             "total_duration":12345}
        """.trimIndent()

        val TAGS_BODY = """
            {"models":[{"name":"llama3.1","model":"llama3.1"}]}
        """.trimIndent()

        val ERROR_BODY = """
            {"error":"model 'nosuchmodel' not found, try pulling it first"}
        """.trimIndent()
    }
}
