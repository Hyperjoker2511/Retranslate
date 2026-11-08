package app.retranslate.ui.review

import app.retranslate.core.error.AppResult
import app.retranslate.data.db.BookEntity
import app.retranslate.data.db.ChapterEntity
import app.retranslate.data.db.KbEntryEntity
import app.retranslate.data.db.KnowledgeBaseEntity
import app.retranslate.data.files.BookFiles
import app.retranslate.domain.books.BookRepository
import app.retranslate.domain.books.DirectTxRunner
import app.retranslate.domain.books.FakeBookDao
import app.retranslate.domain.books.FakeCachedTranslationDao
import app.retranslate.domain.books.FakeChapterDao
import app.retranslate.domain.books.FakeKnowledgeBaseDao
import app.retranslate.domain.export.ChapterFlags
import app.retranslate.domain.kb.KbRepository
import app.retranslate.domain.translate.ChapterPolisher
import app.retranslate.domain.translate.ChapterSnapshots
import app.retranslate.domain.translate.ChapterTranslator
import app.retranslate.domain.translate.PolishAi
import app.retranslate.domain.translate.RequestPacer
import app.retranslate.domain.translate.TranslationCache
import app.retranslate.providers.FakeClock
import app.retranslate.providers.FakeHealthStore
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** PLAN.md §9.3: the chapter review screen's ViewModel behaviour. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChapterReviewViewModelTest {

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

    private class FakePolishAi(var output: String = "") : PolishAi {
        override fun isConfigured(): Boolean = true
        override suspend fun complete(
            systemPrompt: String,
            userPrompt: String,
            targetLang: String,
            quality: QualityMode
        ): AppResult<String> = AppResult.Ok(output)
    }

    private class EchoProvider : Provider {
        override val id = ProviderId.GEMINI
        override val displayName = "Echo"
        override val kind = Provider.Kind.KEY_AI
        override fun supportsPair(source: String, target: String): Boolean = true
        override suspend fun supportedLanguages(): AppResult<List<Language>> =
            AppResult.Ok(emptyList())

        override suspend fun translate(req: TranslateRequest): AppResult<Translation> =
            AppResult.Ok(
                Translation(
                    req.text.split("\n\n").joinToString("\n\n") {
                        "Fresh English rendering of the passage."
                    },
                    ProviderId.GEMINI
                )
            )

        override suspend fun testConnection(): AppResult<Unit> = AppResult.Ok(Unit)
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
        val polishAi = FakePolishAi()
        val polisher = ChapterPolisher(chapterDao, kbRepository, snapshots, polishAi, DirectTxRunner)
        private val clock = FakeClock(now = 1_000L)
        val translator = ChapterTranslator(
            router = ProviderRouter(
                listOf(EchoProvider()),
                ProviderHealth(FakeHealthStore(), clock.lambda),
                nowMs = clock.lambda
            ),
            chapterDao = chapterDao,
            cache = TranslationCache(FakeCachedTranslationDao()) { clock.now },
            tx = DirectTxRunner,
            pacer = RequestPacer({ 0L }, {}, clock.lambda),
            clock = clock.lambda
        )

        suspend fun seedBook(): Long = bookDao.insert(
            BookEntity(
                titleOriginal = "원본", titleTranslated = "Road Home",
                sourceLang = "ko", targetLang = "en", createdAt = 1, updatedAt = 1
            )
        )

        suspend fun seedChapter(
            bookId: Long, number: Int, translation: String?,
            lastReadParagraph: Int = 0, qualityScore: Float? = null
        ): Long = chapterDao.insert(
            ChapterEntity(
                bookId = bookId, number = number, title = "Chapter $number",
                sourceText = "첫 문단입니다.\n\n둘째 문단입니다.\n\n셋째 문단입니다.",
                translatedText = translation,
                status = if (translation != null) "TRANSLATED" else "PENDING",
                qualityScore = qualityScore,
                lastReadParagraph = lastReadParagraph,
                updatedAt = 1
            )
        )

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
            chapterId = chapterId
        )
    }

    private val translation = "Jinwoo drew his blade.\n\n" +
        "The hall fell silent as he stepped forward.\n\n" +
        "Outside, the rain kept falling over the village."

    @Test
    fun `loads both sides, status and the saved resume point`() = runTest(dispatcher) {
        val env = Env()
        val bookId = env.seedBook()
        val chapterId = env.seedChapter(bookId, 2, translation, lastReadParagraph = 2, qualityScore = 82f)
        env.seedChapter(bookId, 1, translation)
        env.seedChapter(bookId, 3, null)

        val vm = env.viewModel(bookId, chapterId)
        advanceUntilIdle()
        val s = vm.state.value
        assertFalse(s.isLoading)
        assertTrue(s.hasTranslation)
        assertEquals("Chapter 2", s.chapterTitle)
        assertEquals("Done", s.statusText)
        assertEquals(82f, s.qualityScore!!, 0.001f)
        assertEquals(3, s.originalParagraphs.size)
        assertEquals(3, s.translatedParagraphs.size)
        assertEquals(2, s.initialParagraph)
        assertTrue(s.prevChapterId != null)
        assertTrue(s.nextChapterId != null)
    }

    @Test
    fun `KB warnings surface when the text breaks a locked name`() = runTest(dispatcher) {
        val env = Env()
        val bookId = env.seedBook()
        val kbId = env.kbDao.insertKb(KnowledgeBaseEntity(bookId = bookId, version = 1, createdAt = 1))
        env.kbDao.insertEntries(
            listOf(
                KbEntryEntity(
                    kbId = kbId, category = "NAME", sourceForm = "진우",
                    canonicalForm = "Jin Woo", locked = true, frequency = 30
                )
            )
        )
        val chapterId = env.seedChapter(bookId, 1, translation)
        val vm = env.viewModel(bookId, chapterId)
        advanceUntilIdle()
        assertEquals(1, vm.state.value.kbWarnings.size)
        assertTrue(vm.state.value.kbWarnings[0].contains("Jin Woo"))
    }

    /** ChapterFlags runs on the shared test dispatcher in this suite,
     * so advanceUntilIdle drains its file work deterministically. */
    private fun kotlinx.coroutines.test.TestScope.awaitCondition(
        condition: () -> Boolean
    ) {
        repeat(100) {
            advanceUntilIdle()
            if (condition()) return
            Thread.sleep(20)
        }
        advanceUntilIdle()
    }

    @Test
    fun `flag taps in flag mode persist through the flags store`() = runTest(dispatcher) {
        val env = Env()
        val bookId = env.seedBook()
        val chapterId = env.seedChapter(bookId, 1, translation)
        val vm = env.viewModel(bookId, chapterId)
        advanceUntilIdle()
        // A tap outside flag mode does nothing.
        vm.onParagraphTapped(1)
        advanceUntilIdle()
        assertTrue(vm.state.value.flagged.isEmpty())
        vm.toggleFlagMode()
        vm.onParagraphTapped(1)
        awaitCondition { vm.state.value.flagged == setOf(1) }
        assertEquals(setOf(1), vm.state.value.flagged)
        assertEquals(setOf(1), env.flags.flaggedParagraphs(bookId, chapterId))
        vm.onParagraphTapped(1)
        awaitCondition { vm.state.value.flagged.isEmpty() }
        assertTrue(vm.state.value.flagged.isEmpty())
    }

    @Test
    fun `scrolling saves the checking position on the chapter`() = runTest(dispatcher) {
        val env = Env()
        val bookId = env.seedBook()
        val chapterId = env.seedChapter(bookId, 1, translation)
        val vm = env.viewModel(bookId, chapterId)
        advanceUntilIdle()
        vm.onPositionChanged(2)
        advanceUntilIdle()
        val row = env.chapterDao.getChapter(chapterId)!!
        assertEquals(2, row.lastReadParagraph)
        assertEquals(1f, row.readProgress, 0.001f)
    }

    @Test
    fun `polish applies through the polisher and undo restores`() = runTest(dispatcher) {
        val env = Env()
        val bookId = env.seedBook()
        val chapterId = env.seedChapter(bookId, 1, translation)
        env.polishAi.output = translation.replace("fell silent", "went quiet")
        val vm = env.viewModel(bookId, chapterId)
        advanceUntilIdle()
        vm.polish()
        advanceUntilIdle()
        // The applied text lands in the DAO (the fake chapter flow is
        // one-shot, so the VM's rendered copy refresh is Room's job).
        assertTrue(
            env.chapterDao.getChapter(chapterId)!!.translatedText!!.contains("went quiet")
        )
        assertTrue(vm.state.value.undoAvailable)
        vm.undoChapterChange()
        advanceUntilIdle()
        assertEquals(
            translation,
            env.chapterDao.getChapter(chapterId)!!.translatedText
        )
        assertFalse(vm.state.value.undoAvailable)
    }

    @Test
    fun `re-translate replaces the text with a fresh translation`() = runTest(dispatcher) {
        val env = Env()
        val bookId = env.seedBook()
        val chapterId = env.seedChapter(bookId, 1, translation)
        val vm = env.viewModel(bookId, chapterId)
        advanceUntilIdle()
        val messages = mutableListOf<String>()
        val job = launch {
            vm.events.collect {
                if (it is ReviewUiEvent.ShowMessage) messages += it.text
            }
        }
        vm.askRetranslate()
        assertTrue(vm.state.value.showRetranslateConfirm)
        vm.confirmRetranslate()
        advanceUntilIdle()
        assertTrue(
            env.chapterDao.getChapter(chapterId)!!.translatedText!!
                .startsWith("Fresh English rendering")
        )
        assertTrue(messages.any { it.contains("re-translated") })
        job.cancel()
    }

    @Test
    fun `paragraph kinds classify system windows and thoughts`() {
        assertEquals(
            ParagraphKind.SYSTEM_WINDOW,
            paragraphKind("[Level up! You gained a skill.]")
        )
        assertEquals(ParagraphKind.THOUGHT, paragraphKind("*I must run.*"))
        assertEquals(ParagraphKind.THOUGHT, paragraphKind("_Why is he here?_"))
        assertEquals(ParagraphKind.NORMAL, paragraphKind("A normal sentence."))
        assertEquals(ParagraphKind.NORMAL, paragraphKind("*not closed"))
    }
}
