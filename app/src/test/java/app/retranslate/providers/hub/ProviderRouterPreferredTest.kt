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

/** A scripted provider (same shape as ProviderRouterTest's). */
private class PreferredFakeProvider(
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

private fun okPref(id: ProviderId, text: String): AppResult<Translation> =
    AppResult.Ok(Translation(text = text, providerId = id))

private fun downPref(id: ProviderId): AppResult<Translation> =
    AppResult.Err(AppError.ProviderDown(id.name, 500))

/**
 * v1.1 (workstream C): the Translate tab's provider picker. A manually
 * chosen provider leads the QUICK_TEXT chain; everything else about
 * the chain — order, failover, switch reporting — is unchanged.
 */
class ProviderRouterPreferredTest {

    private val clock = FakeClock(now = 100_000L)
    private val store = FakeHealthStore()
    private val health = ProviderHealth(store, clock.lambda)

    private fun router(vararg providers: Provider) =
        ProviderRouter(providers.toList(), health, nowMs = clock.lambda)

    @Test
    fun `the chosen provider is tried first and answers`() = runTest {
        val mozhi = PreferredFakeProvider(ProviderId.MOZHI, "Mozhi") {
            okPref(ProviderId.MOZHI, "from mozhi")
        }
        val lingva = PreferredFakeProvider(ProviderId.LINGVA, "Lingva") {
            okPref(ProviderId.LINGVA, "from lingva")
        }
        val result = router(mozhi, lingva).translate(
            "안녕", "ko", "en", preferredProvider = ProviderId.LINGVA
        )
        val routed = (result as AppResult.Ok).value
        assertEquals("from lingva", routed.text)
        assertEquals(ProviderId.LINGVA, routed.via)
        // The chosen provider answered: no switch to report, and the
        // chain's usual first provider was never called.
        assertNull(routed.switchedFromName)
        assertEquals(0, mozhi.calls.size)
        assertEquals(1, lingva.calls.size)
    }

    @Test
    fun `when the chosen provider fails the chain still answers and names it`() = runTest {
        val mozhi = PreferredFakeProvider(ProviderId.MOZHI, "Mozhi") {
            okPref(ProviderId.MOZHI, "from mozhi")
        }
        val lingva = PreferredFakeProvider(ProviderId.LINGVA, "Lingva") {
            downPref(ProviderId.LINGVA)
        }
        val result = router(mozhi, lingva).translate(
            "안녕", "ko", "en", preferredProvider = ProviderId.LINGVA
        )
        val routed = (result as AppResult.Ok).value
        // The request succeeds through the normal chain, the via names
        // who ACTUALLY answered, and the switch names the CHOSEN
        // provider that was busy — the snackbar stays truthful.
        assertEquals("from mozhi", routed.text)
        assertEquals(ProviderId.MOZHI, routed.via)
        assertEquals("Lingva", routed.switchedFromName)
        assertEquals(
            listOf(ProviderId.LINGVA, ProviderId.MOZHI), routed.attempted
        )
    }

    @Test
    fun `a chosen provider that cannot do the pair is skipped, request still served`() =
        runTest {
            val mozhi = PreferredFakeProvider(ProviderId.MOZHI, "Mozhi") {
                okPref(ProviderId.MOZHI, "from mozhi")
            }
            val noPair = PreferredFakeProvider(
                ProviderId.LINGVA, "Lingva", supported = false
            ) { okPref(ProviderId.LINGVA, "never") }
            val result = router(mozhi, noPair).translate(
                "안녕", "ko", "en", preferredProvider = ProviderId.LINGVA
            )
            val routed = (result as AppResult.Ok).value
            assertEquals("from mozhi", routed.text)
            assertEquals(0, noPair.calls.size)
        }

    @Test
    fun `the preference never affects chapter jobs`() = runTest {
        val mozhi = PreferredFakeProvider(ProviderId.MOZHI, "Mozhi") {
            okPref(ProviderId.MOZHI, "from mozhi")
        }
        val lingva = PreferredFakeProvider(ProviderId.LINGVA, "Lingva") {
            okPref(ProviderId.LINGVA, "from lingva")
        }
        val result = router(mozhi, lingva).translate(
            "안녕", "ko", "en",
            purpose = TranslatePurpose.CHAPTER,
            preferredProvider = ProviderId.LINGVA
        )
        val routed = (result as AppResult.Ok).value
        assertEquals(ProviderId.MOZHI, routed.via)
        assertEquals(0, lingva.calls.size)
    }

    @Test
    fun `an unknown or absent preference keeps the plain chain`() = runTest {
        val mozhi = PreferredFakeProvider(ProviderId.MOZHI, "Mozhi") {
            okPref(ProviderId.MOZHI, "from mozhi")
        }
        val result = router(mozhi).translate(
            "안녕", "ko", "en", preferredProvider = ProviderId.GEMINI
        )
        assertTrue(result is AppResult.Ok)
        assertEquals(ProviderId.MOZHI, (result as AppResult.Ok).value.via)
    }
}
