package app.retranslate.ui.settings

import app.retranslate.core.error.AppResult
import app.retranslate.core.security.KeyVault
import app.retranslate.core.security.SecretCipher
import app.retranslate.data.prefs.MergePrefs
import app.retranslate.data.prefs.ProviderConfigStore
import app.retranslate.providers.FakeClock
import app.retranslate.providers.FakeHealthStore
import app.retranslate.providers.FakePreferencesDataStore
import app.retranslate.providers.Language
import app.retranslate.providers.Provider
import app.retranslate.providers.ProviderId
import app.retranslate.providers.TranslateRequest
import app.retranslate.providers.Translation
import app.retranslate.providers.hub.ProviderHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class IdentityCipher : SecretCipher {
    override fun encrypt(plain: ByteArray): ByteArray = byteArrayOf(0x2A) + plain
    override fun decrypt(data: ByteArray): ByteArray = data.drop(1).toByteArray()
}

private class StubProvider(
    override val id: ProviderId,
    override val displayName: String
) : Provider {
    override val kind: Provider.Kind = Provider.Kind.FREE_WEB
    override fun supportsPair(source: String, target: String): Boolean = true
    override suspend fun supportedLanguages(): AppResult<List<Language>> =
        AppResult.Ok(emptyList())

    override suspend fun translate(req: TranslateRequest): AppResult<Translation> =
        AppResult.Ok(Translation("x", id))

    override suspend fun testConnection(): AppResult<Unit> = AppResult.Ok(Unit)
}

/**
 * PLAN.md §5.6/§4.3 at the Providers screen (P7): OpenAI + Claude
 * rows exist with honest "Needs a key" status, the Ollama row is
 * dormant-explained until configured (then shows the PC + model),
 * and the merge daily cap is readable/writable here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProvidersViewModelP7Test {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val clock = FakeClock(now = 1_000L)
    private val configStore = ProviderConfigStore(FakePreferencesDataStore())
    private val mergePrefs = MergePrefs(FakePreferencesDataStore())
    private val keyVault = KeyVault(
        FakePreferencesDataStore(), IdentityCipher(),
        CoroutineScope(Dispatchers.Unconfined)
    )

    private fun viewModel(): ProvidersViewModel {
        val providers = ProvidersViewModel.DISPLAY_ORDER.associateWith { id ->
            StubProvider(id, id.name)
        }
        return ProvidersViewModel(
            keyVault = keyVault,
            configStore = configStore,
            health = ProviderHealth(FakeHealthStore(), clock.lambda),
            providers = providers,
            mergePrefs = mergePrefs
        )
    }

    @Test
    fun `rows include OpenAI and Claude needing keys, Ollama dormant, Offline last`() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            val rows = vm.state.value.rows
            assertEquals(ProvidersViewModel.DISPLAY_ORDER, rows.map { it.id })

            val openAi = rows.first { it.id == ProviderId.OPENAI }
            assertEquals("Needs a key", openAi.statusText)
            val claude = rows.first { it.id == ProviderId.CLAUDE }
            assertEquals("Needs a key", claude.statusText)

            // v1.1.3 (§H): the Offline row joined the end of the list —
            // Ollama is no longer last; it stays dormant and explained.
            val ollama = rows.first { it.id == ProviderId.OLLAMA }
            assertEquals("Not set up", ollama.statusText)
            assertTrue(ollama.detailText!!.contains("your own computer"))
            assertEquals(ProviderId.OFFLINE, rows.last().id)
        }

    @Test
    fun `a configured Ollama row shows the computer and model`() = runTest(dispatcher) {
        configStore.saveOllamaConfig("http://192.168.1.20:11434", "llama3.1")
        val vm = viewModel()
        advanceUntilIdle()
        vm.refresh()
        advanceUntilIdle()
        val ollama = vm.state.value.rows.first { it.id == ProviderId.OLLAMA }
        assertTrue(ollama.statusText != "Not set up")
        assertTrue(ollama.detailText!!.contains("llama3.1"))
        assertTrue(ollama.detailText.contains("192.168.1.20"))
    }

    @Test
    fun `the merge cap defaults to 100 and the stepper writes through`() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            assertEquals(MergePrefs.DEFAULT_DAILY_CAP, vm.state.value.mergeCap)
            assertEquals(0, vm.state.value.mergesUsedToday)

            vm.setMergeCap(250)
            advanceUntilIdle()
            assertEquals(250, vm.state.value.mergeCap)
            assertEquals(250, mergePrefs.dailyCap())
        }
}
