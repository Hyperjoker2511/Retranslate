package app.retranslate.ui.settings

import app.retranslate.data.prefs.ProviderConfigStore
import app.retranslate.providers.FakeClock
import app.retranslate.providers.FakeHealthStore
import app.retranslate.providers.FakePreferencesDataStore
import app.retranslate.providers.FakeTransport
import app.retranslate.providers.hub.ProviderHealth
import app.retranslate.providers.local.OllamaProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PLAN.md §4.3 (P7): the Ollama set-up screen's rules — a bad address
 * saves nothing and says why in plain words; a good one is stored
 * normalised and wakes the provider; Remove puts it back to dormant.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OllamaSetupViewModelTest {

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
    private val ollama = OllamaProvider(
        transport = FakeTransport(),
        health = ProviderHealth(FakeHealthStore(), clock.lambda),
        configSource = configStore,
        nowMs = clock.lambda,
        delayFn = {}
    )

    private fun viewModel() = OllamaSetupViewModel(configStore, ollama)

    @Test
    fun `a bad address saves nothing and explains itself`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onBaseUrlChanged("192.168.1.20")
        vm.onModelChanged("llama3.1")
        vm.onSave()
        advanceUntilIdle()
        assertFalse(vm.state.value.isConfigured)
        assertTrue(vm.state.value.errorText!!.contains("doesn't look right"))
        assertNull(configStore.ollamaConfig())
    }

    @Test
    fun `a good setup saves normalised and wakes the provider`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onBaseUrlChanged("http://192.168.1.20:11434/")
        vm.onModelChanged("llama3.1")
        vm.onSave()
        advanceUntilIdle()

        assertTrue(vm.state.value.isConfigured)
        val saved = configStore.ollamaConfig()!!
        assertEquals("http://192.168.1.20:11434", saved.baseUrl)
        assertEquals("llama3.1", saved.model)
        assertTrue(ollama.supportsPair("ko", "en"))
    }

    @Test
    fun `remove returns the row to dormant`() = runTest(dispatcher) {
        configStore.saveOllamaConfig("http://192.168.1.20:11434", "llama3.1")
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue(vm.state.value.isConfigured)

        vm.onRemove()
        advanceUntilIdle()
        assertFalse(vm.state.value.isConfigured)
        assertNull(configStore.ollamaConfig())
        assertFalse(ollama.supportsPair("ko", "en"))
    }
}
