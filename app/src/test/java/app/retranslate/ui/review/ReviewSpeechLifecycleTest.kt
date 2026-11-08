package app.retranslate.ui.review

import androidx.lifecycle.ViewModelStore
import app.retranslate.core.error.AppResult
import app.retranslate.data.db.BookEntity
import app.retranslate.data.db.ChapterEntity
import app.retranslate.data.files.BookFiles
import app.retranslate.data.prefs.AppearancePrefs
import app.retranslate.domain.books.BookRepository
import app.retranslate.domain.books.DirectTxRunner
import app.retranslate.domain.books.FakeBookDao
import app.retranslate.domain.books.FakeCachedTranslationDao
import app.retranslate.domain.books.FakeChapterDao
import app.retranslate.domain.books.FakeKnowledgeBaseDao
import app.retranslate.domain.export.ChapterFlags
import app.retranslate.domain.kb.KbRepository
import app.retranslate.domain.speech.SpeakOutcome
import app.retranslate.domain.speech.SpeechController
import app.retranslate.domain.translate.ChapterPolisher
import app.retranslate.domain.translate.ChapterSnapshots
import app.retranslate.domain.translate.ChapterTranslator
import app.retranslate.domain.translate.PolishAi
import app.retranslate.domain.translate.RequestPacer
import app.retranslate.domain.translate.TranslationCache
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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private class FakeSpeechController : SpeechController {
    private val _speaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _speaking.asStateFlow()
    val spoken = mutableListOf<Pair<String, String>>()
    var stopCount = 0
    var shutdownCount = 0

    override suspend fun speak(text: String, languageTag: String): SpeakOutcome {
        spoken += text to languageTag
        _speaking.value = true
        return SpeakOutcome.STARTED
    }

    override fun stop() {
        stopCount++
        _speaking.value = false
    }

    override fun shutdown() {
        shutdownCount++
    }
}

private class NoOpPolishAi : PolishAi {
    override fun isConfigured(): Boolean = true
    override suspend fun complete(
        systemPrompt: String,
        userPrompt: String,
        targetLang: String,
        quality: QualityMode
    ): AppResult<String> = AppResult.Ok(userPrompt)
}

private class SpeechEchoProvider : Provider {
    override val id = ProviderId.GEMINI
    override val displayName = "Echo"
    override val kind = Provider.Kind.KEY_AI
    override fun supportsPair(source: String, target: String): Boolean = true
    override suspend fun supportedLanguages(): AppResult<List<Language>> =
        AppResult.Ok(emptyList())

    override suspend fun translate(req: TranslateRequest): AppResult<Translation> =
        AppResult.Ok(Translation("Fresh English rendering.", ProviderId.GEMINI))

    override suspend fun testConnection(): AppResult<Unit> = AppResult.Ok(Unit)
}

/**
 * PLAN.md §9.3/§9.5 (P7) at the review ViewModel: Listen reads the
 * SHOWN side in ITS language; switching sides, changing chapter, or
 * leaving the screen stops speech immediately; the engine is shut
 * down when the ViewModel is cleared — speech never survives its
 * screen. The Aa state (review theme + text size) round-trips
 * through AppearancePrefs with clamping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewSpeechLifecycleTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private inner class Env {
        val bookDao = FakeBookDao()
        val chapterDao = FakeChapterDao()
        val kbDao = FakeKnowledgeBaseDao()
        val repository = BookRepository(bookDao, chapterDao, kbDao, DirectTxRunner) { 5_000L }
        val bookFiles = BookFiles(File(temp.root, "books"))
        val flags = ChapterFlags(bookFiles, dispatcher)
        val snapshots = ChapterSnapshots(chapterDao, bookFiles)
        val kbRepository = KbRepository(kbDao, chapterDao, DirectTxRunner)
        val polisher = ChapterPolisher(
            chapterDao, kbRepository, snapshots, NoOpPolishAi(), DirectTxRunner
        )
        private val clock = FakeClock(now = 1_000L)
        val translator = ChapterTranslator(
            router = ProviderRouter(
                listOf(SpeechEchoProvider()),
                ProviderHealth(FakeHealthStore(), clock.lambda),
                nowMs = clock.lambda
            ),
            chapterDao = chapterDao,
            cache = TranslationCache(FakeCachedTranslationDao()) { clock.now },
            tx = DirectTxRunner,
            pacer = RequestPacer({ 0L }, {}, clock.lambda),
            clock = clock.lambda
        )
        val speech = FakeSpeechController()
        val appearancePrefs = AppearancePrefs(FakePreferencesDataStore())

        suspend fun seed(): Pair<Long, Long> {
            val bookId = bookDao.insert(
                BookEntity(
                    titleOriginal = "원본", sourceLang = "ko", targetLang = "en",
                    createdAt = 1, updatedAt = 1
                )
            )
            val chapterId = chapterDao.insert(
                ChapterEntity(
                    bookId = bookId, number = 1, title = "Chapter 1",
                    sourceText = "첫 문단입니다.\n\n둘째 문단입니다.",
                    translatedText = "First paragraph.\n\nSecond paragraph.",
                    status = "TRANSLATED", updatedAt = 1
                )
            )
            chapterDao.insert(
                ChapterEntity(
                    bookId = bookId, number = 2, title = "Chapter 2",
                    sourceText = "다음 장입니다.",
                    translatedText = "The next chapter.",
                    status = "TRANSLATED", updatedAt = 1
                )
            )
            return bookId to chapterId
        }

        fun viewModel(bookId: Long, chapterId: Long) = ChapterReviewViewModel(
            repository = repository,
            chapterDao = chapterDao,
            kbRepository = kbRepository,
            chapterFlags = flags,
            polisher = polisher,
            chapterTranslator = translator,
            snapshots = snapshots,
            geminiKeyPresent = { true },
            bookId = bookId,
            chapterId = chapterId,
            speech = speech,
            appearancePrefs = appearancePrefs
        )
    }

    @Test
    fun `listen reads the translation in the target language`() = runTest(dispatcher) {
        val env = Env()
        val (bookId, chapterId) = env.seed()
        val vm = env.viewModel(bookId, chapterId)
        advanceUntilIdle()

        vm.listen()
        advanceUntilIdle()
        assertEquals(1, env.speech.spoken.size)
        assertEquals("en", env.speech.spoken.single().second)
        assertTrue(env.speech.spoken.single().first.contains("First paragraph."))
        assertTrue(vm.state.value.isSpeaking)
    }

    @Test
    fun `switching to the original stops speech, and listen then uses the source language`() =
        runTest(dispatcher) {
            val env = Env()
            val (bookId, chapterId) = env.seed()
            val vm = env.viewModel(bookId, chapterId)
            advanceUntilIdle()

            vm.listen()
            advanceUntilIdle()
            assertTrue(vm.state.value.isSpeaking)

            vm.showOriginal()
            assertEquals(1, env.speech.stopCount)
            advanceUntilIdle()
            assertFalse(vm.state.value.isSpeaking)

            vm.listen()
            advanceUntilIdle()
            assertEquals("ko", env.speech.spoken.last().second)
            assertTrue(env.speech.spoken.last().first.contains("첫 문단입니다"))
        }

    @Test
    fun `changing chapter and leaving the screen stop speech immediately`() =
        runTest(dispatcher) {
            val env = Env()
            val (bookId, chapterId) = env.seed()
            val vm = env.viewModel(bookId, chapterId)
            advanceUntilIdle()

            vm.listen()
            advanceUntilIdle()
            vm.openNext()
            assertEquals(1, env.speech.stopCount)
            advanceUntilIdle()

            vm.listen()
            advanceUntilIdle()
            vm.onScreenClosed()
            assertEquals(2, env.speech.stopCount)
        }

    @Test
    fun `clearing the view model shuts the speech engine down`() = runTest(dispatcher) {
        val env = Env()
        val (bookId, chapterId) = env.seed()
        val vm = env.viewModel(bookId, chapterId)
        advanceUntilIdle()
        vm.listen()
        advanceUntilIdle()

        val store = ViewModelStore()
        store.put("review", vm)
        store.clear()
        assertEquals(1, env.speech.shutdownCount)
        assertTrue(env.speech.stopCount >= 1)
    }

    @Test
    fun `aa state round-trips through the appearance prefs with clamping`() =
        runTest(dispatcher) {
            val env = Env()
            val (bookId, chapterId) = env.seed()
            val vm = env.viewModel(bookId, chapterId)
            advanceUntilIdle()
            assertEquals("LIGHT", vm.appearance.value.reviewThemeId)
            assertEquals(1.0f, vm.appearance.value.reviewTextScale, 0.001f)

            vm.setReviewTheme("NIGHT")
            vm.setReviewTextScale(2.0f)
            advanceUntilIdle()
            assertEquals("NIGHT", vm.appearance.value.reviewThemeId)
            // AppearancePrefs clamps to the 0.85–1.4 reading range (§9.5).
            assertEquals(1.4f, vm.appearance.value.reviewTextScale, 0.001f)
        }
}
