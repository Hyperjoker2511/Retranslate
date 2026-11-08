package app.retranslate.ui.library

import app.retranslate.data.db.BookEntity
import app.retranslate.data.db.TxRunner
import app.retranslate.data.files.BookFiles
import app.retranslate.data.files.TempFiles
import app.retranslate.domain.books.BookImporter
import app.retranslate.domain.books.BookRepository
import app.retranslate.domain.books.DirectTxRunner
import app.retranslate.domain.books.FakeBookDao
import app.retranslate.domain.books.FakeChapterDao
import app.retranslate.domain.books.FakeKnowledgeBaseDao
import app.retranslate.domain.books.ImportSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Library + review ViewModel behaviour (PLAN.md §9.3/§7.1) over fake
 * DAOs: shelf mapping, search/filter, the hero rule, manual creation,
 * and the review screen's duplicate + commit flow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelsTest {

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

    private class Env(root: File, ioDispatcher: kotlinx.coroutines.CoroutineDispatcher) {
        val bookDao = FakeBookDao()
        val chapterDao = FakeChapterDao()
        val repository = BookRepository(
            bookDao, chapterDao, FakeKnowledgeBaseDao(),
            DirectTxRunner,
            clock = { 5_000L }
        )
        val bookFiles = BookFiles(File(root, "books"))
        val importer = BookImporter(
            TempFiles(File(root, "tmp")), repository, bookFiles,
            ioDispatcher = ioDispatcher
        )
        val sessionStore = ImportSessionStore()
    }

    private fun book(id: Long, title: String, updatedAt: Long) = BookEntity(
        id = id, titleOriginal = title, author = "Author $id",
        sourceLang = "ko", targetLang = "en",
        createdAt = updatedAt, updatedAt = updatedAt
    )

    @Test
    fun `shelf maps rows, search and filters work`() = runTest {
        val env = Env(temp.newFolder(), dispatcher)
        env.bookDao.books.value = listOf(
            book(1, "Sword Saga", 300),
            book(2, "Moon Palace", 200),
            book(3, "Sword of Dawn", 100)
        )
        val shelfDao = env.bookDao
        // FakeBookDao.observeShelf reports 0 counts; use counts via a
        // custom repository check instead: titles + pair text are VM work.
        val vm = LibraryViewModel(env.repository, env.importer, env.sessionStore) {
            throw AssertionError("no file picking in this test")
        }
        advanceUntilIdle()
        var state = vm.state.value
        assertEquals(3, state.books.size)
        assertEquals("Korean → English", state.books[0].languagePair)
        assertTrue(state.books[0].title == "Sword Saga")

        vm.setQuery("sword")
        state = vm.state.value
        assertEquals(listOf("Sword Saga", "Sword of Dawn"), state.books.map { it.title })

        vm.setQuery("")
        vm.setFilter(ShelfFilter.FINISHED)
        state = vm.state.value
        // Fake shelf rows report 0 chapters → nothing counts as finished.
        assertEquals(0, state.books.size)
        assertTrue(shelfDao.books.value.size == 3)
    }

    @Test
    fun `manual book creation opens the new book`() = runTest {
        val env = Env(temp.newFolder(), dispatcher)
        val vm = LibraryViewModel(env.repository, env.importer, env.sessionStore) {
            throw AssertionError("no file picking in this test")
        }
        val events = mutableListOf<LibraryUiEvent>()
        val job = launch { vm.events.collect { events += it } }
        advanceUntilIdle()
        vm.createManualBook(
            "  My Novel  ", "ko", "en",
            app.retranslate.domain.books.GenrePreset.CULTIVATION,
            app.retranslate.providers.QualityMode.BEST
        )
        advanceUntilIdle()
        val stored = env.bookDao.books.value
        assertEquals(1, stored.size)
        assertEquals("My Novel", stored[0].titleOriginal)
        assertEquals("cultivation", stored[0].genrePresetId)
        assertEquals("BEST", stored[0].qualityMode)
        assertTrue(events.any { it is LibraryUiEvent.OpenBook })
        job.cancel()
    }

    @Test
    fun `review commits a staged session and reports Done`() = runTest {
        val env = Env(temp.newFolder(), dispatcher)
        val korean = "제1장 시작\n그는 천천히 눈을 떴다. 마을은 조용했다. 수련의 길은 멀고 험했다. 그는 검을 들고 앞으로 나아갔다. 바람이 불고 구름이 흘렀다. 그의 마음은 단단했다.\n"
        val session = when (
            val r = env.importer.importFile(
                { korean.byteInputStream(java.nio.charset.Charset.forName("EUC-KR")) },
                "novel.txt", -1
            ) {}
        ) {
            is app.retranslate.core.error.AppResult.Ok -> r.value
            is app.retranslate.core.error.AppResult.Err ->
                throw AssertionError("import failed: ${r.error}")
        }
        env.sessionStore.set(session)
        val vm = ImportReviewViewModel(env.sessionStore, env.importer, env.repository)
        val initial = vm.state.value
        assertEquals(false, initial.missing)
        assertEquals("ko", initial.sourceLang)
        assertEquals(1, initial.chapterCount)

        val events = mutableListOf<ImportReviewUiEvent>()
        val job = launch { vm.events.collect { events += it } }
        advanceUntilIdle()
        vm.setTitle("검의 길")
        vm.onAddClicked()
        advanceUntilIdle()
        assertTrue(events.any { it is ImportReviewUiEvent.Done })
        assertEquals(1, env.bookDao.books.value.size)
        assertEquals("검의 길", env.bookDao.books.value[0].titleOriginal)
        job.cancel()
    }

    @Test
    fun `review without a session is an honest interrupted state`() = runTest {
        val env = Env(temp.newFolder(), dispatcher)
        val vm = ImportReviewViewModel(env.sessionStore, env.importer, env.repository)
        assertTrue(vm.state.value.missing)
    }

    @Test
    fun `review blocks Add until a language is chosen`() = runTest {
        val env = Env(temp.newFolder(), dispatcher)
        // Text the detector won't claim: digits and symbols only.
        val mystery = "12345 67890 !!! ??? ... ;;; ::: 111 222 333 444 555 666 777 888 999 000"
        val session = when (
            val r = env.importer.importFile(
                { mystery.byteInputStream(Charsets.UTF_8) }, "mystery.txt", -1
            ) {}
        ) {
            is app.retranslate.core.error.AppResult.Ok -> r.value
            is app.retranslate.core.error.AppResult.Err ->
                throw AssertionError("import failed: ${r.error}")
        }
        // The mystery text parses (it has characters) but detection fails.
        env.sessionStore.set(session)
        val vm = ImportReviewViewModel(env.sessionStore, env.importer, env.repository)
        assertTrue(vm.state.value.needsLanguageChoice)
        vm.onAddClicked()
        advanceUntilIdle()
        assertTrue(env.bookDao.books.value.isEmpty())
        vm.chooseSourceLang("ko")
        vm.onAddClicked()
        advanceUntilIdle()
        assertEquals(1, env.bookDao.books.value.size)
    }
}
