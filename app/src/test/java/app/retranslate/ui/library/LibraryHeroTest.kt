package app.retranslate.ui.library

import app.retranslate.data.db.BookEntity
import app.retranslate.data.db.ChapterEntity
import app.retranslate.domain.books.BookImporter
import app.retranslate.domain.books.BookRepository
import app.retranslate.domain.books.DirectTxRunner
import app.retranslate.domain.books.FakeBookDao
import app.retranslate.domain.books.FakeChapterDao
import app.retranslate.domain.books.FakeKnowledgeBaseDao
import app.retranslate.domain.books.ImportSessionStore
import app.retranslate.data.files.PickedFile
import app.retranslate.data.files.TempFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * PLAN.md §9.3 (P6): the Library hero resumes the last CHECKED
 * chapter when a position was saved, and falls back to the book page
 * when none was.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryHeroTest {

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

    @Test
    fun `hero resumes the last checked chapter`() = runTest(dispatcher) {
        val bookDao = FakeBookDao()
        val chapterDao = FakeChapterDao()
        val bookId = bookDao.insert(
            BookEntity(
                titleOriginal = "원본", sourceLang = "ko", targetLang = "en",
                createdAt = 1, updatedAt = 100
            )
        )
        bookDao.shelfCounts = { 10 to 4 }
        repeat(10) { i ->
            chapterDao.insert(
                ChapterEntity(
                    bookId = bookId, number = i + 1, sourceText = "본문",
                    updatedAt = 1
                )
            )
        }
        val checked = chapterDao.chapters.first { it.number == 4 }
        val checkedId = checked.id
        chapterDao.update(checked.copy(readProgress = 0.62f, lastReadParagraph = 12))

        val repository = BookRepository(
            bookDao, chapterDao, FakeKnowledgeBaseDao(), DirectTxRunner
        ) { 5_000L }
        val importer = BookImporter(
            TempFiles(File(temp.root, "tmp")), repository,
            app.retranslate.data.files.BookFiles(File(temp.root, "books")),
            ioDispatcher = dispatcher
        )
        val vm = LibraryViewModel(
            repository, importer, ImportSessionStore(),
            chapterDao = chapterDao
        ) { _: android.net.Uri -> error("no picking") }
        advanceUntilIdle()

        val hero = vm.state.value.heroChapter
        assertTrue(hero != null)
        assertEquals(4, hero!!.chapterNumber)
        assertEquals(62, hero.percentChecked)

        val events = mutableListOf<LibraryUiEvent>()
        val job = launch { vm.events.collect { events += it } }
        advanceUntilIdle() // let the collector subscribe before the tap
        vm.onHeroClicked()
        advanceUntilIdle()
        val open = events.filterIsInstance<LibraryUiEvent.OpenReview>().first()
        assertEquals(bookId, open.bookId)
        assertEquals(checkedId, open.chapterId)
        job.cancel()
    }

    @Test
    fun `hero without a saved position opens the book page`() = runTest(dispatcher) {
        val bookDao = FakeBookDao()
        val chapterDao = FakeChapterDao()
        val bookId = bookDao.insert(
            BookEntity(
                titleOriginal = "원본", sourceLang = "ko", targetLang = "en",
                createdAt = 1, updatedAt = 100
            )
        )
        bookDao.shelfCounts = { 10 to 4 }
        val repository = BookRepository(
            bookDao, chapterDao, FakeKnowledgeBaseDao(), DirectTxRunner
        ) { 5_000L }
        val importer = BookImporter(
            TempFiles(File(temp.root, "tmp")), repository,
            app.retranslate.data.files.BookFiles(File(temp.root, "books")),
            ioDispatcher = dispatcher
        )
        val vm = LibraryViewModel(
            repository, importer, ImportSessionStore(),
            chapterDao = chapterDao
        ) { _: android.net.Uri -> error("no picking") }
        advanceUntilIdle()
        assertNull(vm.state.value.heroChapter)

        val events = mutableListOf<LibraryUiEvent>()
        val job = launch { vm.events.collect { events += it } }
        advanceUntilIdle() // let the collector subscribe before the tap
        vm.onHeroClicked()
        advanceUntilIdle()
        assertEquals(
            listOf(bookId),
            events.filterIsInstance<LibraryUiEvent.OpenBook>().map { it.bookId }
        )
        job.cancel()
    }
}
