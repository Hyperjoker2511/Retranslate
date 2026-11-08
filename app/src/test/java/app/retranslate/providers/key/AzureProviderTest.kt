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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Azure fixtures follow Microsoft's documented v3 envelopes. The
 * endpoint did not answer from this sandbox at all (P2 probes timed
 * out), so every path here is fixture-verified only — UNVERIFIED live.
 */
class AzureProviderTest {

    private val clock = FakeClock(now = 1_000L)
    private val health = ProviderHealth(FakeHealthStore(), clock.lambda)
    private var storedSecret: String? = "probe-key|eastus"
    private var chargedChars = 0

    private fun provider(transport: FakeTransport) = AzureProvider(
        transport = transport,
        health = health,
        secretProvider = { storedSecret },
        isConfigured = { storedSecret != null },
        nowMs = clock.lambda,
        delayFn = {},
        usageRecorder = { chargedChars += it }
    )

    private fun request(text: String = "안녕하세요") =
        TranslateRequest(text = text, source = "ko", target = "en")

    @Test
    fun `secret shape - key and region both required`() {
        assertEquals(
            AzureProvider.Secret("k", "eastus"),
            AzureProvider.parseSecret("k|eastus")
        )
        assertEquals("k|eastus", AzureProvider.assembleSecret(" k ", " eastus "))
        assertNull(AzureProvider.parseSecret("key-without-region"))
        assertNull(AzureProvider.parseSecret("|eastus"))
        assertNull(AzureProvider.parseSecret("key|"))
        assertNull(AzureProvider.parseSecret(null))
        assertNull(AzureProvider.parseSecret(""))
    }

    @Test
    fun `success parses text and detected language`() = runTest {
        val transport = FakeTransport().onUrlContaining("/translate", 200, SUCCESS_BODY)
        val result = provider(transport).translate(request())
        val translation = (result as AppResult.Ok).value
        assertEquals("Hello.", translation.text)
        assertEquals("ko", translation.detectedSource)
        assertEquals("안녕하세요".length, chargedChars)
    }

    @Test
    fun `request shape - key and region headers, zh-Hans rename in the URL`() = runTest {
        val transport = FakeTransport().onUrlContaining("/translate", 200, SUCCESS_BODY)
        provider(transport).translate(request().copy(source = "zh-CN"))
        val sent = transport.requests.single()
        assertEquals("probe-key", sent.headers["Ocp-Apim-Subscription-Key"])
        assertEquals("eastus", sent.headers["Ocp-Apim-Subscription-Region"])
        assertTrue(sent.url.contains("from=zh-Hans"))
        assertTrue(sent.url.contains("api-version=3.0"))
        assertTrue(sent.body!!.contains("\"Text\":\"안녕하세요\""))
    }

    @Test
    fun `auto source omits the from parameter`() = runTest {
        val transport = FakeTransport().onUrlContaining("/translate", 200, SUCCESS_BODY)
        provider(transport).translate(request().copy(source = "auto"))
        assertFalse(transport.requests.single().url.contains("from="))
    }

    @Test
    fun `401 maps to AuthFailed and is not retried`() = runTest {
        val body = """{"error":{"code":401001,"message":"The request is not authorized."}}"""
        val transport = FakeTransport().onUrlContaining("/translate", 401, body)
        val result = provider(transport).translate(request())
        assertEquals(AppError.AuthFailed("AZURE"), (result as AppResult.Err).error)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `a quota-worded 403 maps to RateLimited (Microsoft's own semantics)`() = runTest {
        val body = """{"error":{"code":403001,"message":"You have used all available free translations for this month. Quota exceeded."}}"""
        val transport = FakeTransport().onUrlContaining("/translate", 403, body)
        val result = provider(transport).translate(request())
        assertTrue((result as AppResult.Err).error is AppError.RateLimited)
    }

    @Test
    fun `429 maps to RateLimited`() = runTest {
        val body = """{"error":{"code":429001,"message":"Too many requests."}}"""
        val transport = FakeTransport().onUrlContaining("/translate", 429, body)
        val result = provider(transport).translate(request())
        assertTrue((result as AppResult.Err).error is AppError.RateLimited)
    }

    @Test
    fun `a stored secret without a region is an auth failure, never a call`() = runTest {
        storedSecret = "key-without-region"
        val transport = FakeTransport()
        val result = provider(transport).translate(request())
        assertEquals(AppError.AuthFailed("AZURE"), (result as AppResult.Err).error)
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun `pair support follows the conservative Azure set`() = runTest {
        val azure = provider(FakeTransport())
        assertTrue(azure.supportsPair("ko", "en"))
        assertTrue(azure.supportsPair("zh-TW", "en"))
        assertFalse(azure.supportsPair("ceb", "en"))
        storedSecret = null
        assertFalse(azure.supportsPair("ko", "en"))
    }

    companion object {
        private val SUCCESS_BODY = """
            [{"detectedLanguage":{"language":"ko","score":1.0},
              "translations":[{"text":"Hello.","to":"en"}]}]
        """.trimIndent()
    }
}
