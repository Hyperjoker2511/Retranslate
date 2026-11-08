package app.retranslate.providers.gateway

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.providers.FakeTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.1.3 (§K): the Add-a-provider test-drive — protocol-based, no
 * site allow-list. A site is added only when it answers like a
 * standard gateway; a dead or foreign-shaped site adds NOTHING.
 */
class GatewayProbeTest {

    private val modelsJson = """{"data":[{"id":"free/model-a"},{"id":"paid/model-b"}]}"""

    @Test
    fun `openai-shaped site is found at its v1 root`() = runTest {
        val transport = FakeTransport().onUrlContaining(
            "example.ai/v1/models", 200, modelsJson
        )
        val result = GatewayProbe(transport).probe("example.ai")
        assertTrue(result is GatewayProbeResult.Found)
        val found = result as GatewayProbeResult.Found
        assertEquals("https://example.ai/v1", found.baseUrl)
        assertEquals(GatewayProtocol.OPENAI, found.protocol)
        assertEquals(listOf("free/model-a", "paid/model-b"), found.models)
    }

    @Test
    fun `a key-gated site is still found - models come with the key step`() = runTest {
        val transport = FakeTransport().onUrlContaining("/models", 401, "")
        val result = GatewayProbe(transport).probe("https://gated.example.com/v1")
        assertTrue(result is GatewayProbeResult.Found)
        assertEquals(
            emptyList<String>(),
            (result as GatewayProbeResult.Found).models
        )
    }

    @Test
    fun `a dead or foreign site adds nothing`() = runTest {
        val transport = FakeTransport() // fallback 404 for everything
        val result = GatewayProbe(transport).probe("https://not-a-gateway.example")
        assertTrue(result is GatewayProbeResult.NotFound)
    }

    @Test
    fun `a rejected key is refused at the key step`() = runTest {
        val transport = FakeTransport().onUrlContaining("/models", 401, "")
        val result = GatewayProbe(transport).fetchModelsWithKey(
            "https://gated.example.com/v1", GatewayProtocol.OPENAI, "sk-bad"
        )
        assertTrue(result is AppResult.Err)
        assertTrue((result as AppResult.Err).error is AppError.AuthFailed)
    }

    @Test
    fun `a good key fetches the real model list`() = runTest {
        val transport = FakeTransport().onUrlContaining("/models", 200, modelsJson)
        val result = GatewayProbe(transport).fetchModelsWithKey(
            "https://ok.example.com/v1", GatewayProtocol.OPENAI, "sk-good"
        )
        assertTrue(result is AppResult.Ok)
        assertEquals(
            listOf("free/model-a", "paid/model-b"),
            (result as AppResult.Ok).value
        )
    }

    @Test
    fun `free badges read the provider's own model names`() {
        assertTrue(GatewayConfig.isListedFree("free/model-a"))
        assertTrue(GatewayConfig.isListedFree("model-x:free"))
        assertTrue(GatewayConfig.isListedFree("model-free-tier"))
        assertTrue(!GatewayConfig.isListedFree("gpt-4o"))
    }
}
