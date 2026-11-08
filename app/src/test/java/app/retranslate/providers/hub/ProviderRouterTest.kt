package app.retranslate.providers.hub

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.providers.FakeClock
import app.retranslate.providers.FakeHealthStore
import app.retranslate.providers.Language
import app.retranslate.providers.Provider
import app.retranslate.providers.ProviderId
import app.retranslate.providers.TranslateRequest
import app.retranslate.providers.Translation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A scripted provider for router tests. */
private class FakeProvider(
    override val id: ProviderId,
    override val displayName: String,
    private val supported: Boolean = true,
    private val behaviour: suspend (TranslateRequest) -> AppResult<Translation>
) : Provider {
    override val kind: Provider.Kind = Provider.Kind.FREE_WEB
    val calls = mutableListOf<TranslateRequest>()

    override fun supportsPair(source: String, target: String): Boolean = supported

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

/** PLAN.md §4.4/§12.1: chain construction, failover order, switch contract. */
class ProviderRouterTest {

    private val clock = FakeClock(now = 100_000L)
    private val store = FakeHealthStore()
    private val health = ProviderHealth(store, clock.lambda)

    private fun router(vararg providers: Provider) =
        ProviderRouter(providers.toList(), health, nowMs = clock.lambda)

    @Test
    fun `first provider answers - no switch reported`() = runTest {
        val mozhi = FakeProvider(ProviderId.MOZHI, "Mozhi") { ok(ProviderId.MOZHI, "hello") }
        val lingva = FakeProvider(ProviderId.LINGVA, "Lingva") { ok(ProviderId.LINGVA) }
        val result = router(mozhi, lingva).translate("안녕", "ko", "en")
        val routed = (result as AppResult.Ok).value
        assertEquals("hello", routed.text)
        assertEquals(ProviderId.MOZHI, routed.via)
        assertNull(routed.switchedFromName)
        assertEquals(0, lingva.calls.size)
    }

    @Test
    fun `fallback cascades in order and names the switch`() = runTest {
        val mozhi = FakeProvider(ProviderId.MOZHI, "Mozhi") { down(ProviderId.MOZHI) }
        val lingva = FakeProvider(ProviderId.LINGVA, "Lingva") { ok(ProviderId.LINGVA, "via lingva") }
        val simply = FakeProvider(ProviderId.SIMPLYTRANSLATE, "SimplyTranslate") { ok(ProviderId.SIMPLYTRANSLATE) }
        val result = router(mozhi, lingva, simply).translate("안녕", "ko", "en")
        val routed = (result as AppResult.Ok).value
        assertEquals(ProviderId.LINGVA, routed.via)
        assertEquals("Mozhi", routed.switchedFromName)
        assertEquals(listOf(ProviderId.MOZHI, ProviderId.LINGVA), routed.attempted)
        assertEquals(0, simply.calls.size)
    }

    @Test
    fun `language pre-filter never calls a provider that cannot do the pair`() = runTest {
        val noPair = FakeProvider(ProviderId.MOZHI, "Mozhi", supported = false) {
            ok(ProviderId.MOZHI)
        }
        val lingva = FakeProvider(ProviderId.LINGVA, "Lingva") { ok(ProviderId.LINGVA, "done") }
        val result = router(noPair, lingva).translate("안녕", "ko", "en")
        assertTrue(result is AppResult.Ok)
        assertEquals(0, noPair.calls.size)
    }

    @Test
    fun `no provider for the pair is a typed error before any call`() = runTest {
        val noPair = FakeProvider(ProviderId.MOZHI, "Mozhi", supported = false) {
            ok(ProviderId.MOZHI)
        }
        val result = router(noPair).translate("안녕", "ko", "en")
        val error = (result as AppResult.Err).error
        assertTrue(error is AppError.UnsupportedLanguagePair)
        assertEquals(0, noPair.calls.size)
    }

    @Test
    fun `same language is refused before any call`() = runTest {
        val mozhi = FakeProvider(ProviderId.MOZHI, "Mozhi") { ok(ProviderId.MOZHI) }
        val result = router(mozhi).translate("hello", "en", "en")
        val error = (result as AppResult.Err).error
        assertTrue(error is AppError.SameLanguage)
        assertEquals(0, mozhi.calls.size)
    }

    @Test
    fun `whole chain failing yields AllProvidersFailed with the attempted order`() = runTest {
        val mozhi = FakeProvider(ProviderId.MOZHI, "Mozhi") { down(ProviderId.MOZHI) }
        val lingva = FakeProvider(ProviderId.LINGVA, "Lingva") { down(ProviderId.LINGVA) }
        val result = router(mozhi, lingva).translate("안녕", "ko", "en")
        val error = (result as AppResult.Err).error
        assertEquals(
            AppError.AllProvidersFailed(listOf("MOZHI", "LINGVA")),
            error
        )
    }

    @Test
    fun `when every failure is no-network the error is NoNetwork`() = runTest {
        val mozhi = FakeProvider(ProviderId.MOZHI, "Mozhi") { AppResult.Err(AppError.NoNetwork) }
        val lingva = FakeProvider(ProviderId.LINGVA, "Lingva") { AppResult.Err(AppError.NoNetwork) }
        val result = router(mozhi, lingva).translate("안녕", "ko", "en")
        assertEquals(AppError.NoNetwork, (result as AppResult.Err).error)
    }

    @Test
    fun `a provider in cooldown is skipped entirely`() = runTest {
        health.ensureLoaded()
        repeat(3) { health.recordFailure(ProviderHealth.providerKey(ProviderId.LINGVA)) }
        val mozhi = FakeProvider(ProviderId.MOZHI, "Mozhi") { down(ProviderId.MOZHI) }
        val lingva = FakeProvider(ProviderId.LINGVA, "Lingva") { ok(ProviderId.LINGVA) }
        val simply = FakeProvider(ProviderId.SIMPLYTRANSLATE, "SimplyTranslate") {
            ok(ProviderId.SIMPLYTRANSLATE, "from simply")
        }
        val result = router(mozhi, lingva, simply).translate("안녕", "ko", "en")
        val routed = (result as AppResult.Ok).value
        assertEquals(ProviderId.SIMPLYTRANSLATE, routed.via)
        assertEquals(0, lingva.calls.size)
        assertEquals(
            listOf(ProviderId.MOZHI, ProviderId.SIMPLYTRANSLATE),
            routed.attempted
        )
    }

    @Test
    fun `the chain deadline stops later providers from being called`() = runTest {
        val slow = FakeProvider(ProviderId.MOZHI, "Mozhi") {
            clock.now += ProviderRouter.QUICK_CHAIN_BUDGET_MS + 1
            down(ProviderId.MOZHI)
        }
        val lingva = FakeProvider(ProviderId.LINGVA, "Lingva") { ok(ProviderId.LINGVA) }
        val result = router(slow, lingva).translate("안녕", "ko", "en")
        assertTrue(result is AppResult.Err)
        assertEquals(0, lingva.calls.size)
    }

    @Test
    fun `recent success reorders the chain - last working provider goes first`() = runTest {
        health.ensureLoaded()
        clock.now = 50_000L
        health.recordSuccess(ProviderHealth.providerKey(ProviderId.LINGVA))
        clock.now = 100_000L
        val mozhi = FakeProvider(ProviderId.MOZHI, "Mozhi") { ok(ProviderId.MOZHI, "mozhi") }
        val lingva = FakeProvider(ProviderId.LINGVA, "Lingva") { ok(ProviderId.LINGVA, "lingva") }
        val result = router(mozhi, lingva).translate("안녕", "ko", "en")
        val routed = (result as AppResult.Ok).value
        assertEquals(ProviderId.LINGVA, routed.via)
        assertEquals(0, mozhi.calls.size)
    }
}
