package app.retranslate.domain.export

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.data.db.BookEntity
import app.retranslate.data.db.ChapterEntity
import app.retranslate.data.files.TempFiles
import app.retranslate.domain.books.FakeBookDao
import app.retranslate.domain.books.FakeChapterDao
import app.retranslate.domain.books.FakeJobRunDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** PLAN.md §7.5: export scope, gaps, failure shapes and bookkeeping. */
class BookExporterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val bookDao = FakeBookDao()
    private val chapterDao = FakeChapterDao()
    private val jobRunDao = FakeJobRunDao()

    private fun exporter() = BookExporter(
        bookDao, chapterDao, jobRunDao, TempFiles(File(temp.root, "tmp"))
    )

    private suspend fun seed(translated: List<Int>, total: Int): Long {
        val bookId = bookDao.insert(
            BookEntity(
                titleOriginal = "원본", titleTranslated = "Road Home",
                sourceLang = "ko", targetLang = "en", createdAt = 1, updatedAt = 1
            )
        )
        for (n in 1..total) {
            chapterDao.insert(
                ChapterEntity(
                    bookId = bookId, number = n, title = "Chapter $n",
                    sourceText = "본문 $n",
                    translatedText = if (n in translated) "Text $n one.\n\nText $n two." else null,
                    status = if (n in translated) "TRANSLATED" else "PENDING",
                    updatedAt = 1
                )
            )
        }
        return bookId
    }

    @Test
    fun `preview counts only real translations`() = runTest {
        val bookId = seed(listOf(1, 3), total = 4)
        val preview = exporter().preview(bookId)
        assertEquals(4, preview.totalChapters)
        assertEquals(2, preview.translatedChapters)
    }

    @Test
    fun `txt export is one headed section per chapter`() = runTest {
        val bookId = seed(listOf(1, 2), total = 2)
        val outcome = when (val r = exporter().exportBook(bookId, ExportFormat.TXT, false)) {
            is AppResult.Ok -> r.value
            is AppResult.Err -> fail("export failed: ${r.error}").let { error("x") }
        }
        assertEquals("Road Home.txt", outcome.fileName)
        assertEquals(
            "Chapter 1\n\nText 1 one.\n\nText 1 two.\n\n" +
                "Chapter 2\n\nText 2 one.\n\nText 2 two.\n",
            outcome.file.readText()
        )
    }

    @Test
    fun `nothing translated is an honest ExportFailed, never an empty file`() = runTest {
        val bookId = seed(emptyList(), total = 3)
        val result = exporter().exportBook(bookId, ExportFormat.EPUB, false)
        val err = (result as AppResult.Err).error
        assertTrue(err is AppError.ExportFailed)
        assertTrue((err as AppError.ExportFailed).reason.contains("no chapters are translated"))
        // The temp area holds no partial export afterwards.
        val tmpRoot = File(temp.root, "tmp")
        val leftovers = tmpRoot.walkTopDown().filter { it.isFile }.toList()
        assertTrue("no partial files: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun `unknown book is ExportFailed`() = runTest {
        val result = exporter().exportBook(999L, ExportFormat.EPUB, false)
        assertTrue((result as AppResult.Err).error is AppError.ExportFailed)
    }

    @Test
    fun `export records a finished EXPORT job row`() = runTest {
        val bookId = seed(listOf(1, 3), total = 3)
        exporter().exportBook(bookId, ExportFormat.EPUB, false)
        val row = jobRunDao.runs.value.single()
        assertEquals("EXPORT", row.kind)
        assertEquals("FINISHED", row.state)
        assertEquals(3, row.totalChapters)
        assertEquals(2, row.doneChapters)
    }

    @Test
    fun `file names are sanitised`() {
        assertEquals("My Novel.txt", BookExporter.fileNameFor("My Novel", 3L, ExportFormat.TXT))
        assertEquals("A B C.epub", BookExporter.fileNameFor("A/B: C", 3L, ExportFormat.EPUB))
        assertEquals("book-7.epub", BookExporter.fileNameFor("///", 7L, ExportFormat.EPUB))
    }

    @Test
    fun `exported file lands in the export temp area and survives`() = runTest {
        val bookId = seed(listOf(1), total = 1)
        val outcome = when (val r = exporter().exportBook(bookId, ExportFormat.EPUB, true)) {
            is AppResult.Ok -> r.value
            is AppResult.Err -> fail("export failed: ${r.error}").let { error("x") }
        }
        assertTrue(outcome.file.exists())
        assertTrue(outcome.file.length() > 0)
        assertFalse(outcome.fileName.contains('/'))
    }
}
