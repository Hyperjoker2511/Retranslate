package app.retranslate.domain.translate

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.providers.FakeClock
import app.retranslate.providers.FakeHealthStore
import app.retranslate.providers.Language
import app.retranslate.providers.Provider
import app.retranslate.providers.ProviderId
import app.retranslate.providers.TranslateRequest
import app.retranslate.providers.Translation
import app.retranslate.providers.hub.ProviderHealth
import app.retranslate.providers.hub.ProviderRouter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class LargeTextFakeProvider(
    private val failOnCall: Int = -1
) : Provider {
    override val id: ProviderId = ProviderId.MOZHI
    override val displayName: String = "Mozhi"
    override val kind: Provider.Kind = Provider.Kind.FREE_WEB
    val calls = mutableListOf<TranslateRequest>()

    override fun supportsPair(source: String, target: String): Boolean = true

    override suspend fun supportedLanguages(): AppResult<List<Language>> =
        AppResult.Ok(emptyList())

    override suspend fun translate(req: TranslateRequest): AppResult<Translation> {
        calls += req
        if (calls.size == failOnCall) {
            return AppResult.Err(AppError.ProviderDown(id.name, 500))
        }
        // Replace Hangul with Latin letters while preserving every
        // paragraph break, so the verifier sees a plausible translation.
        val rendered = req.text.map { c ->
            if (c in '\uAC00'..'\uD7A3') 'a' else c
        }.joinToString("")
        return AppResult.Ok(Translation(rendered, id))
    }

    override suspend fun testConnection(): AppResult<Unit> = AppResult.Ok(Unit)
}

/** P8 hardening: the Translate tab's Large text mode is a real flow. */
class LargeTextTranslatorTest {

    private val clock = FakeClock(now = 10_000L)

    private fun translator(provider: LargeTextFakeProvider): LargeTextTranslator {
        val router = ProviderRouter(
            providers = listOf(provider),
            health = ProviderHealth(FakeHealthStore(), clock.lambda),
            nowMs = clock.lambda
        )
        return LargeTextTranslator(
            router = router,
            pacer = RequestPacer({ 0L }, {}, clock.lambda)
        )
    }

    @Test
    fun `multi-chunk paste is translated in order with progress`() = runTest {
        val provider = LargeTextFakeProvider()
        val paragraph = "가".repeat(4_000)
        val input = "$paragraph\n\n$paragraph"
        val progress = mutableListOf<Pair<Int, Int>>()

        val result = translator(provider).translate(
            input, "ko", "en",
            onProgress = { done, total -> progress += done to total }
        )

        val outcome = (result as AppResult.Ok).value
        assertEquals(2, provider.calls.size)
        assertEquals(2, outcome.chunkCount)
        assertEquals(listOf(1 to 2, 2 to 2), progress)
        assertEquals(input.length, outcome.text.length)
        assertTrue(outcome.text.none { it in '\uAC00'..'\uD7A3' })
        assertEquals(listOf("Mozhi"), outcome.viaNames)
    }

    @Test
    fun `a failed chunk fails the whole paste and exposes no partial result`() = runTest {
        val provider = LargeTextFakeProvider(failOnCall = 2)
        val paragraph = "가".repeat(4_000)
        val result = translator(provider).translate(
            "$paragraph\n\n$paragraph", "ko", "en"
        )
        assertTrue(result is AppResult.Err)
        assertEquals(2, provider.calls.size)
    }

    @Test
    fun `an oversized paste is refused before any provider call`() = runTest {
        val provider = LargeTextFakeProvider()
        val result = translator(provider).translate(
            "a".repeat(LargeTextTranslator.MAX_INPUT_CHARS + 1), "ko", "en"
        )
        assertTrue((result as AppResult.Err).error is AppError.VerificationFailed)
        assertEquals(0, provider.calls.size)
    }
}
