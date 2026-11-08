package app.retranslate.ui.translate

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.data.prefs.MergePrefs
import app.retranslate.data.prefs.SavedLanguages
import app.retranslate.domain.speech.SpeakOutcome
import app.retranslate.domain.speech.SpeechController
import app.retranslate.domain.translate.LargeTextTranslator
import app.retranslate.domain.translate.MergeAi
import app.retranslate.domain.translate.MergeOrchestrator
import app.retranslate.providers.FakeClock
import app.retranslate.providers.FakeHealthStore
import app.retranslate.providers.FakePreferencesDataStore
import app.retranslate.providers.Language
import app.retranslate.providers.Provider
import app.retranslate.providers.ProviderId
import app.retranslate.providers.QualityMode
import app.retranslate.providers.TranslateRequest
import app.retranslate.providers.Translation
import app.retranslate.providers.hub.ProviderHealth
import app.retranslate.providers.hub.ProviderRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class ScriptedProvider(
    override val id: ProviderId,
    override val displayName: String,
    var result: AppResult<Translation>
) : Provider {
    override val kind: Provider.Kind = Provider.Kind.KEY_AI
    val calls = mutableListOf<TranslateRequest>()

    override fun supportsPair(source: String, target: String): Boolean = true
    override suspend fun supportedLanguages(): AppResult<List<Language>> =
        AppResult.Ok(emptyList())

    override suspend fun translate(req: TranslateRequest): AppResult<Translation> {
        calls += req
        return result
    }

    override suspend fun testConnection(): AppResult<Unit> = AppResult.Ok(Unit)
}

private class FakeJudge(var answer: AppResult<String>) : MergeAi {
    var calls = 0
    override fun judgeName(): String? = "Gemini"
    override suspend fun complete(
        systemPrompt: String,
        userPrompt: String,
        targetLang: String,
        quality: QualityMode
    ): AppResult<String> {
        calls++
        return answer
    }
}

private class FakeSpeechController(
    var outcome: SpeakOutcome = SpeakOutcome.STARTED
) : SpeechController {
    private val _speaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _speaking.asStateFlow()
    val spoken = mutableListOf<Pair<String, String>>()
    var stopCount = 0
    var shutdownCount = 0

    override suspend fun speak(text: String, languageTag: String): SpeakOutcome {
        spoken += text to languageTag
        if (outcome == SpeakOutcome.STARTED) _speaking.value = true
        return outcome
    }

    override fun stop() {
        stopCount++
        _speaking.value = false
    }

    override fun shutdown() {
        shutdownCount++
    }
}

/**
 * PLAN.md §5.6/§9.3 at the Quick Translate ViewModel: the merge
 * switch's visibility/default/cap behaviour, the merged via-chip,
 * the versions viewer data, and Listen's lifecycle with a fake engine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranslateViewModelTest {

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

    private var mergesUsed = 0

    private inner class Env(aiKey: Boolean = true) {
        val mergeProviders = listOf(
            ScriptedProvider(
                ProviderId.GEMINI, "Gemini",
                AppResult.Ok(Translation("Hello, world.", ProviderId.GEMINI))
            ),
            ScriptedProvider(
                ProviderId.DEEPL, "DeepL",
                AppResult.Ok(Translation("Hello world!", ProviderId.DEEPL))
            ),
            ScriptedProvider(
                ProviderId.MOZHI, "Mozhi",
                AppResult.Ok(Translation("Hello, the world.", ProviderId.MOZHI))
            )
        )
        val judge = FakeJudge(AppResult.Ok("Hello, world — merged."))
        val plainProvider = ScriptedProvider(
            ProviderId.MOZHI, "Mozhi",
            AppResult.Ok(Translation("Plain chain result.", ProviderId.MOZHI))
        )
        val router = ProviderRouter(
            listOf(plainProvider),
            ProviderHealth(FakeHealthStore(), clock.lambda),
            nowMs = clock.lambda
        )
        val mergePrefs = MergePrefs(FakePreferencesDataStore())
        val orchestrator = MergeOrchestrator(
            providers = mergeProviders,
            health = ProviderHealth(FakeHealthStore(), clock.lambda),
            tierOf = { p ->
                when (p.id) {
                    ProviderId.GEMINI -> 0
                    ProviderId.DEEPL -> 1
                    else -> 2
                }
            },
            judge = judge,
            mergesUsedToday = { mergesUsed },
            dailyCap = { mergePrefs.dailyCap() },
            recordMergeUsed = { mergesUsed++ },
            nowMs = clock.lambda
        )
        val largeProvider = ScriptedProvider(
            ProviderId.MOZHI, "Mozhi",
            AppResult.Ok(Translation("Large result.", ProviderId.MOZHI))
        )
        val largeTextTranslator = LargeTextTranslator(
            ProviderRouter(
                listOf(largeProvider),
                ProviderHealth(FakeHealthStore(), clock.lambda),
                nowMs = clock.lambda
            )
        )
        val speech = FakeSpeechController()
        val viewModel = TranslateViewModel(
            router = router,
            savedLanguages = SavedLanguages(FakePreferencesDataStore()),
            largeTextTranslator = largeTextTranslator,
            mergeOrchestrator = orchestrator,
            mergePrefs = mergePrefs,
            mergesUsedToday = { mergesUsed },
            aiKeyPresent = { aiKey },
            speech = speech
        )
    }

    @Test
    fun `merge is available and on by default with an AI key`() = runTest(dispatcher) {
        val env = Env()
        advanceUntilIdle()
        assertTrue(env.viewModel.state.value.mergeAvailable)
        assertTrue(env.viewModel.state.value.mergeEnabled)

        env.viewModel.onInputChanged("안녕, 세상")
        env.viewModel.onTranslateClicked()
        advanceUntilIdle()

        val state = env.viewModel.state.value
        assertEquals("Hello, world — merged.", state.result)
        assertEquals("AI merge (3 versions checked)", state.viaProvider)
        assertEquals(3, state.mergeCandidates.size)
        assertEquals(1, state.mergesUsedToday)
        assertTrue(env.plainProvider.calls.isEmpty())

        env.viewModel.onShowVersions()
        assertTrue(env.viewModel.state.value.showVersions)
        env.viewModel.onDismissVersions()
        assertFalse(env.viewModel.state.value.showVersions)
    }

    @Test
    fun `large text mode uses the chunk translator, not merge or the quick chain`() =
        runTest(dispatcher) {
            val env = Env()
            advanceUntilIdle()
            env.viewModel.onLargeTextClicked()
            assertTrue(env.viewModel.state.value.largeTextMode)

            env.viewModel.onInputChanged("긴 문장")
            env.viewModel.onTranslateClicked()
            advanceUntilIdle()

            assertEquals("Large result.", env.viewModel.state.value.result)
            assertEquals(1, env.largeProvider.calls.size)
            assertTrue(env.plainProvider.calls.isEmpty())
            assertEquals(0, env.judge.calls)

            env.viewModel.onLargeTextClicked()
            assertFalse(env.viewModel.state.value.largeTextMode)
        }

    @Test
    fun `merge switched off uses the plain chain`() = runTest(dispatcher) {
        val env = Env()
        advanceUntilIdle()
        env.viewModel.onMergeToggleChanged(false)
        advanceUntilIdle()

        env.viewModel.onInputChanged("안녕, 세상")
        env.viewModel.onTranslateClicked()
        advanceUntilIdle()

        val state = env.viewModel.state.value
        assertEquals("Plain chain result.", state.result)
        assertEquals("Mozhi", state.viaProvider)
        assertEquals(1, env.plainProvider.calls.size)
        assertEquals(0, env.judge.calls)
    }

    @Test
    fun `cap reached disables merge and the plain chain answers`() = runTest(dispatcher) {
        mergesUsed = MergePrefs.DEFAULT_DAILY_CAP
        val env = Env()
        advanceUntilIdle()
        assertTrue(env.viewModel.state.value.mergeCapReached)

        env.viewModel.onInputChanged("안녕, 세상")
        env.viewModel.onTranslateClicked()
        advanceUntilIdle()

        assertEquals("Plain chain result.", env.viewModel.state.value.result)
        assertEquals(0, env.judge.calls)
    }

    @Test
    fun `no AI key hides the merge switch entirely`() = runTest(dispatcher) {
        val env = Env(aiKey = false)
        advanceUntilIdle()
        assertFalse(env.viewModel.state.value.mergeAvailable)

        env.viewModel.onInputChanged("안녕, 세상")
        env.viewModel.onTranslateClicked()
        advanceUntilIdle()
        assertEquals("Plain chain result.", env.viewModel.state.value.result)
        assertEquals(0, env.judge.calls)
    }

    @Test
    fun `listen speaks the result in the target language and stops on toggle and close`() =
        runTest(dispatcher) {
            val env = Env()
            advanceUntilIdle()
            env.viewModel.onMergeToggleChanged(false)
            env.viewModel.onInputChanged("안녕, 세상")
            env.viewModel.onTranslateClicked()
            advanceUntilIdle()
            // Translating itself retires any old speech (§9.3) — the
            // stop counts below are relative to that baseline.
            val baseline = env.speech.stopCount

            env.viewModel.onListenClicked()
            advanceUntilIdle()
            assertEquals(
                listOf("Plain chain result." to "en"), env.speech.spoken
            )
            assertTrue(env.viewModel.state.value.isSpeaking)

            // Same button = Stop.
            env.viewModel.onListenClicked()
            assertEquals(baseline + 1, env.speech.stopCount)
            advanceUntilIdle()
            assertFalse(env.viewModel.state.value.isSpeaking)

            // Leaving the screen stops speech immediately (§9.3).
            env.viewModel.onListenClicked()
            advanceUntilIdle()
            assertTrue(env.viewModel.state.value.isSpeaking)
            env.viewModel.onScreenClosed()
            assertEquals(baseline + 2, env.speech.stopCount)
            advanceUntilIdle()
            assertFalse(env.viewModel.state.value.isSpeaking)
        }

    @Test
    fun `a missing voice is explained in plain words, not silent`() = runTest(dispatcher) {
        val env = Env()
        env.speech.outcome = SpeakOutcome.NO_VOICE
        advanceUntilIdle()
        env.viewModel.onMergeToggleChanged(false)
        env.viewModel.onInputChanged("안녕, 세상")
        env.viewModel.onTranslateClicked()
        advanceUntilIdle()

        val messages = mutableListOf<String>()
        val job = launch {
            env.viewModel.events.collect { event ->
                if (event is TranslateUiEvent.ShowMessage) messages += event.text
            }
        }
        env.viewModel.onListenClicked()
        advanceUntilIdle()
        job.cancel()
        assertTrue(messages.any { it.contains("no voice for English") })
    }

    @Test
    fun `judge failure still returns the best single candidate`() = runTest(dispatcher) {
        val env = Env()
        env.judge.answer = AppResult.Err(AppError.RateLimited("GEMINI", null))
        advanceUntilIdle()
        env.viewModel.onInputChanged("안녕, 세상")
        env.viewModel.onTranslateClicked()
        advanceUntilIdle()

        val state = env.viewModel.state.value
        assertEquals("Hello, world.", state.result)
        assertEquals("Gemini", state.viaProvider)
        assertEquals(3, state.mergeCandidates.size)
    }
}
