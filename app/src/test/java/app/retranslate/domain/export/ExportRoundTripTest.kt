package app.retranslate.domain.export

import app.retranslate.core.error.AppResult
import app.retranslate.data.db.BookEntity
import app.retranslate.data.db.ChapterEntity
import app.retranslate.data.files.TempFiles
import app.retranslate.domain.books.EpubParser
import app.retranslate.domain.books.FakeBookDao
import app.retranslate.domain.books.FakeChapterDao
import app.retranslate.domain.books.FakeJobRunDao
import app.retranslate.domain.books.ImportFormat
import app.retranslate.domain.books.ImportPlanner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private fun <T> AppResult<T>.unwrap(): T = when (this) {
    is AppResult.Ok -> value
    is AppResult.Err -> fail("unexpected error: $error").let { error("unreachable") }
}

/**
 * PLAN.md §7.5/§12 gate item: an exported EPUB must re-import through
 * the P3 parser into THE SAME chapter texts (and survive the import
 * planner unchanged when chapters are full-size). Parsed chapters are
 * file-backed (§7.1 streaming), so texts are read from their files.
 */
class ExportRoundTripTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val bookDao = FakeBookDao()
    private val chapterDao = FakeChapterDao()
    private val jobRunDao = FakeJobRunDao()

    private fun exporter() = BookExporter(
        bookDao, chapterDao, jobRunDao, TempFiles(File(temp.root, "tmp"))
    )

    /** Canonical stored text: paragraphs joined by blank lines. */
    private fun chapterText(chapter: Int): String =
        (1..3).joinToString("\n\n") { p ->
            "Chapter $chapter paragraph $p. " +
                "The swordmaster walked the long road home through rain and " +
                "lantern light, thinking of the promise he had made to the " +
                "village elder many winters ago, before the war came north " +
                "and changed every road he knew into a stranger's path."
        }

    private suspend fun seedBook(
        translatedNumbers: List<Int>,
        totalChapters: Int = 3
    ): Long {
        val bookId = bookDao.insert(
            BookEntity(
                titleOriginal = "원본 제목", titleTranslated = "The Long Road",
                author = "Kim Author", sourceLang = "ko", targetLang = "en",
                createdAt = 1, updatedAt = 1
            )
        )
        for (n in 1..totalChapters) {
            chapterDao.insert(
                ChapterEntity(
                    bookId = bookId, number = n,
                    title = "Chapter $n",
                    sourceText = "원본 본문 $n",
                    translatedText = if (n in translatedNumbers) chapterText(n) else null,
                    status = if (n in translatedNumbers) "TRANSLATED" else "PENDING",
                    updatedAt = 1
                )
            )
        }
        return bookId
    }

    @Test
    fun `exported epub re-imports to the same chapters`() = runTest {
        val bookId = seedBook(listOf(1, 2, 3))
        val file = exporter()
            .exportBook(bookId, ExportFormat.EPUB, includeTranslatorNote = false)
            .unwrap().file

        val workDir = temp.newFolder()
        val parsed = EpubParser().parse(file, workDir) {}.unwrap()
        assertEquals("The Long Road", parsed.title)
        assertEquals(3, parsed.chapters.size)
        assertEquals(
            listOf(chapterText(1), chapterText(2), chapterText(3)),
            parsed.chapters.map { it.file.readText() }
        )
        assertEquals(
            listOf("Chapter 1", "Chapter 2", "Chapter 3"),
            parsed.chapters.map { it.title }
        )

        // …and through the planner the full-size chapters stay as-is.
        val planned = ImportPlanner.plan(parsed, ImportFormat.EPUB, workDir).unwrap()
        assertEquals(3, planned.chapters.size)
        assertEquals(
            listOf(chapterText(1), chapterText(2), chapterText(3)),
            planned.chapters.map { it.file.readText() }
        )
        assertEquals(
            listOf("Chapter 1", "Chapter 2", "Chapter 3"),
            planned.chapters.map { it.title }
        )
    }

    @Test
    fun `export with gaps re-imports exactly the translated chapters`() = runTest {
        val bookId = seedBook(listOf(1, 3), totalChapters = 4)
        val outcome = exporter()
            .exportBook(bookId, ExportFormat.EPUB, includeTranslatorNote = true)
            .unwrap()
        assertEquals(2, outcome.includedChapters)
        assertEquals(2, outcome.skippedChapters)

        val parsed = EpubParser().parse(outcome.file, temp.newFolder()) {}.unwrap()
        assertEquals(
            listOf(chapterText(1), chapterText(3)),
            parsed.chapters.map { it.file.readText() }
        )
    }
}
