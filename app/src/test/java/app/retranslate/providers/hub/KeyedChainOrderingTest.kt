package app.retranslate.providers.hub

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.providers.FakeClock
import app.retranslate.providers.FakeHealthStore
import app.retranslate.providers.FakeInstanceStore
import app.retranslate.providers.FakeTransport
import app.retranslate.providers.Language
import app.retranslate.providers.Provider
import app.retranslate.providers.ProviderId
import app.retranslate.providers.TranslateRequest
import app.retranslate.providers.Translation
import app.retranslate.providers.free.MozhiProvider
import app.retranslate.providers.key.GeminiProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A scripted provider for ordering tests (mirrors ProviderRouterTest's). */
private class ScriptedProvider(
    override val id: ProviderId,
    override val displayName: String,
    private val configured: Boolean = true,
    private val behaviour: suspend (TranslateRequest) -> AppResult<Translation>
) : Provider {
    override val kind: Provider.Kind = Provider.Kind.FREE_WEB
    val calls = mutableListOf<TranslateRequest>()

    override fun supportsPair(source: String, target: String): Boolean = configured
    override suspend fun supportedLanguages(): AppResult<List<Language>> =
        AppResult.Ok(emptyList())

    override suspend fun translate(req: TranslateRequest): AppResult<Translation> {
        calls += req
        return behaviour(req)
    }

    override suspend fun testConnection(): AppResult<Unit> = AppResult.Ok(Unit)
}

private fun ok(id: ProviderId, text: String = "translated"): AppResult<Translation> =
    AppResult.Ok(Translation(text = text, providerId = id))

private fun down(id: ProviderId): AppResult<Translation> =
    AppResult.Err(AppError.ProviderDown(id.name, 500))

/**
 * PLAN.md §4.4.1 (decision 2026-10-27) + §11 P2 milestone: with a Gemini
 * key, Gemini is FIRST and the free chain is the safety net; a Gemini
 * 429 or a bad key continues the chain instead of failing the user.
 */
class KeyedChainOrderingTest {

    private val clock = FakeClock(now = 100_000L)
    private val health = ProviderHealth(FakeHealthStore(), clock.lambda)
    private var geminiKeyPresent = true

    private fun router(vararg providers: Provider) = ProviderRouter(
        providers = providers.toList(),
        health = health,
        tierOf = { ChainOrdering.tierOf(it, geminiKeyPresent) },
        nowMs = clock.lambda
    )

    private fun gemini(behaviour: suspend (TranslateRequest) -> AppResult<Translation>) =
        ScriptedProvider(ProviderId.GEMINI, "Gemini AI", configured = geminiKeyPresent, behaviour)

    private fun deepl(behaviour: suspend (TranslateRequest) -> AppResult<Translation>) =
        ScriptedProvider(ProviderId.DEEPL, "DeepL", behaviour = behaviour)

    private fun mozhi(behaviour: suspend (TranslateRequest) -> AppResult<Translation>) =
        ScriptedProvider(ProviderId.MOZHI, "Mozhi", behaviour = behaviour)

    @Test
    fun `tier assignment matches the plan in both worlds`() {
        val g = ScriptedProvider(ProviderId.GEMINI, "Gemini AI") { ok(ProviderId.GEMINI) }
        val d = ScriptedProvider(ProviderId.DEEPL, "DeepL") { ok(ProviderId.DEEPL) }
        val m = ScriptedProvider(ProviderId.MOZHI, "Mozhi") { ok(ProviderId.MOZHI) }
        // With a Gemini key: AI first, keyed classics next, free net last.
        assertTrue(ChainOrdering.tierOf(g, true) < ChainOrdering.tierOf(d, true))
        assertTrue(ChainOrdering.tierOf(d, true) < ChainOrdering.tierOf(m, true))
        // Without: free net first, keyed classics after.
        assertTrue(ChainOrdering.tierOf(m, false) < ChainOrdering.tierOf(d, false))
        assertEquals(0, ChainOrdering.tierOf(g, false))
    }

    @Test
    fun `with a key, Gemini answers first and nothing else is called`() = runTest {
        val g = gemini { ok(ProviderId.GEMINI, "from gemini") }
        val d = deepl { ok(ProviderId.DEEPL) }
        val m = mozhi { ok(ProviderId.MOZHI) }
        // Static list order deliberately puts the free provider first:
        // tiers, not list order, must decide.
        val routed = (router(m, d, g).translate("안녕", "ko", "en") as AppResult.Ok).value
        assertEquals(ProviderId.GEMINI, routed.via)
        assertEquals("from gemini", routed.text)
        assertEquals(0, d.calls.size)
        assertEquals(0, m.calls.size)
    }

    @Test
    fun `Gemini 429 falls through to DeepL, then the free net`() = runTest {
        val g = gemini { AppResult.Err(AppError.RateLimited("GEMINI", 30)) }
        val d = deepl { down(ProviderId.DEEPL) }
        val m = mozhi { ok(ProviderId.MOZHI, "from mozhi") }
        val routed = (router(g, d, m).translate("안녕", "ko", "en") as AppResult.Ok).value
        assertEquals(ProviderId.MOZHI, routed.via)
        assertEquals("Gemini AI", routed.switchedFromName)
        assertEquals(
            listOf(ProviderId.GEMINI, ProviderId.DEEPL, ProviderId.MOZHI),
            routed.attempted
        )
    }

    @Test
    fun `Gemini 429 answered by DeepL names DeepL as the answer`() = runTest {
        val g = gemini { AppResult.Err(AppError.RateLimited("GEMINI", null)) }
        val d = deepl { ok(ProviderId.DEEPL, "from deepl") }
        val routed = (router(g, d).translate("안녕", "ko", "en") as AppResult.Ok).value
        assertEquals(ProviderId.DEEPL, routed.via)
        assertEquals("Gemini AI", routed.switchedFromName)
    }

    @Test
    fun `an invalid Gemini key never reaches the user - the chain answers`() = runTest {
        val g = gemini { AppResult.Err(AppError.AuthFailed("GEMINI")) }
        val m = mozhi { ok(ProviderId.MOZHI, "still translated") }
        val routed = (router(g, m).translate("안녕", "ko", "en") as AppResult.Ok).value
        assertEquals("still translated", routed.text)
        assertEquals(ProviderId.MOZHI, routed.via)
        assertEquals("Gemini AI", routed.switchedFromName)
    }

    @Test
    fun `without a key the free net leads and keyed classics follow`() = runTest {
        geminiKeyPresent = false
        val g = gemini { ok(ProviderId.GEMINI) }
        val d = deepl { ok(ProviderId.DEEPL, "from deepl") }
        val m = mozhi { down(ProviderId.MOZHI) }
        val routed = (router(g, d, m).translate("안녕", "ko", "en") as AppResult.Ok).value
        assertEquals(ProviderId.DEEPL, routed.via)
        assertEquals(listOf(ProviderId.MOZHI, ProviderId.DEEPL), routed.attempted)
        assertEquals(0, g.calls.size)
    }

    @Test
    fun `end to end - real Gemini 429 fixture falls through to a real Mozhi`() = runTest {
        val quotaBody = """
            {"error":{"code":429,"message":"quota","status":"RESOURCE_EXHAUSTED",
            "details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"1s"}]}}
        """.trimIndent()
        val geminiTransport = FakeTransport().onUrlContaining(":generateContent", 429, quotaBody)
        val realGemini = GeminiProvider(
            transport = geminiTransport,
            health = health,
            keyProvider = { "probe-key" },
            isConfigured = { true },
            nowMs = clock.lambda,
            delayFn = {}
        )
        val mozhiTransport = FakeTransport().onUrlContaining(
            "/api/translate/",
            200,
            """{"engine":"google","detected":"ko","translated-text":"Hello."}"""
        )
        val realMozhi = MozhiProvider(
            transport = mozhiTransport,
            health = health,
            instances = FakeInstanceStore(
                mapOf(ProviderId.MOZHI to listOf("https://mozhi.example.org"))
            ),
            nowMs = clock.lambda,
            delayFn = {}
        )
        val routed = (router(realGemini, realMozhi)
            .translate("안녕하세요", "ko", "en") as AppResult.Ok).value
        assertEquals(ProviderId.MOZHI, routed.via)
        assertEquals("Hello.", routed.text)
        assertEquals("Gemini AI", routed.switchedFromName)
    }
}
