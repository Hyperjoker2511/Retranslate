package app.retranslate.domain.books

import app.retranslate.core.error.AppResult
import app.retranslate.data.db.BookEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** P8 (§9.3): "Add chapters" and "Paste a chapter" on an existing book. */
class BookRepositoryAppendTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class Fixture {
        val bookDao = FakeBookDao()
        val chapterDao = FakeChapterDao()
        val repository = BookRepository(
            bookDao, chapterDao, FakeKnowledgeBaseDao(),
            DirectTxRunner,
            clock = { 7_000L }
        )
    }

    private suspend fun seedBook(
        f: Fixture,
        sourceLang: String = "ko",
        targetLang: String = "en"
    ): Long = f.bookDao.insert(
        BookEntity(
            titleOriginal = "Seeded",
            sourceLang = sourceLang,
            targetLang = targetLang,
            createdAt = 1_000L,
            updatedAt = 1_000L
        )
    )

    private fun planned(vararg texts: String): PlannedBook {
        val dir = temp.newFolder()
        val chapters = texts.mapIndexed { index, text ->
            val file = File(dir, "chapter-${index + 1}.txt")
            file.writeText(text, Charsets.UTF_8)
            ParsedChapter(title = "Parsed ${index + 1}", file = file, charCount = text.length)
        }
        return PlannedBook(
            title = "Incoming",
            author = null,
            declaredLang = null,
            coverFile = null,
            chapters = chapters
        )
    }

    @Test
    fun `paste adds the next chapter as a pending source`() = runTest {
        val f = Fixture()
        val bookId = seedBook(f)

        val first = f.repository.addChapter(bookId, "  Opening  ", "  본문 내용  ")
        assertEquals(1, (first as AppResult.Ok).value)
        val second = f.repository.addChapter(bookId, null, "다음 장 내용")
        assertEquals(2, (second as AppResult.Ok).value)

        val chapters = f.chapterDao.getChapters(bookId)
        assertEquals(listOf(1, 2), chapters.map { it.number })
        assertEquals("Opening", chapters[0].title)
        assertEquals("본문 내용", chapters[0].sourceText)
        assertNull(chapters[0].translatedText)
        assertEquals("PENDING", chapters[0].status)
        assertEquals("Chapter 2", chapters[1].title)

        val book = f.bookDao.getById(bookId)!!
        assertEquals(2, book.chapterCountCache)
        assertTrue(book.sizeBytesCache > 0)
    }

    @Test
    fun `paste into an already-translated book stores a translation`() = runTest {
        val f = Fixture()
        val bookId = seedBook(f, sourceLang = "en", targetLang = "en")

        val added = f.repository.addChapter(bookId, "Done", "Already in English.")
        assertEquals(1, (added as AppResult.Ok).value)

        val chapter = f.chapterDao.getChapters(bookId).single()
        assertNull(chapter.sourceText)
        assertEquals("Already in English.", chapter.translatedText)
        assertEquals("TRANSLATED", chapter.status)
        assertEquals("IMPORTED_TRANSLATION", chapter.origin)
    }

    @Test
    fun `append continues numbering after existing chapters`() = runTest {
        val f = Fixture()
        val bookId = seedBook(f)
        f.repository.addChapter(bookId, "Existing", "기존 장")

        val result = f.repository.appendImport(
            bookId,
            planned("새 장 하나", "새 장 둘")
        )
        assertEquals(2, (result as AppResult.Ok).value)

        val chapters = f.chapterDao.getChapters(bookId)
        assertEquals(listOf(1, 2, 3), chapters.map { it.number })
        assertEquals("새 장 하나", chapters[1].sourceText)
        assertEquals("새 장 둘", chapters[2].sourceText)
        assertEquals(3, f.bookDao.getById(bookId)!!.chapterCountCache)
    }

    @Test
    fun `blank paste and missing book are honest errors`() = runTest {
        val f = Fixture()
        val bookId = seedBook(f)
        assertTrue(f.repository.addChapter(bookId, "T", "   ") is AppResult.Err)
        assertTrue(f.repository.addChapter(999L, "T", "text") is AppResult.Err)
        assertTrue(f.repository.appendImport(999L, planned("x")) is AppResult.Err)
        assertEquals(0, f.chapterDao.countForBook(bookId))
    }
}
