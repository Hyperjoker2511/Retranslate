package app.retranslate.domain.books

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * PDF pipeline (PLAN.md §7.2) with a fake extractor: cleaning rules
 * (running heads, page numbers, hyphenation), chapter splitting, and
 * the scanned-PDF honest refusal. The real PDFBox extractor has its
 * own test (PdfBoxTextExtractorTest).
 */
class PdfBookParserTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** Writes preset page text (form-feed separated, like the real extractor). */
    private class FakeExtractor(
        private val pagesText: String,
        private val pages: Int,
        private val title: String? = null,
        private val failure: AppError? = null
    ) : PdfTextExtractor {
        override fun extract(
            pdf: File,
            outText: File,
            onProgress: (Int, Int) -> Unit
        ): AppResult<PdfExtraction> {
            if (failure != null) return AppResult.Err(failure)
            outText.writeText(pagesText, Charsets.UTF_8)
            onProgress(pages, pages)
            return AppResult.Ok(PdfExtraction(pages = pages, title = title))
        }
    }

    private fun pageText(vararg pages: List<String>): String =
        pages.joinToString("\u000C") { it.joinToString("\n") } + "\u000C"

    @Test
    fun `running heads and page numbers are stripped, chapters split`() {
        val head = "The Fixture Saga"
        val text = pageText(
            listOf(head, "Chapter 1", "He woke up early.", "The sun was bright.", "1"),
            listOf(head, "He ate breakfast.", "Then he left the house.", "2"),
            listOf(head, "Chapter 2", "The road was long.", "Dust rose behind him.", "3"),
            listOf(head, "He kept walking.", "Night fell at last.", "4")
        )
        val parser = PdfBookParser(FakeExtractor(text, pages = 4, title = "The Fixture Saga"))
        val workDir = temp.newFolder()
        val book = unwrap(parser.parse(File(temp.root, "x.pdf"), workDir) {})
        assertEquals("The Fixture Saga", book.title)
        assertEquals(2, book.chapters.size)
        val allText = book.chapters.joinToString("") { it.file.readText(Charsets.UTF_8) }
        assertTrue(allText.contains("He woke up early."))
        assertTrue(allText.contains("Night fell at last."))
        assertTrue("running head leaked", !allText.contains("The Fixture Saga\n"))
    }

    @Test
    fun `hyphenated line breaks are joined`() {
        val text = pageText(
            listOf("Chapter 1", "The transla-", "tion was finally complete.", "He smiled.")
        )
        val parser = PdfBookParser(FakeExtractor(text, pages = 1))
        val book = unwrap(parser.parse(File(temp.root, "x.pdf"), temp.newFolder()) {})
        val allText = book.chapters.joinToString("") { it.file.readText(Charsets.UTF_8) }
        assertTrue(allText.contains("translation was finally complete."))
    }

    @Test
    fun `no text at all is a scanned PDF refusal`() {
        val parser = PdfBookParser(FakeExtractor("\u000C\u000C", pages = 2))
        when (val result = parser.parse(File(temp.root, "x.pdf"), temp.newFolder()) {}) {
            is AppResult.Ok -> fail("scanned PDF must not produce a book")
            is AppResult.Err -> assertEquals(AppError.ScannedPdf, result.error)
        }
    }

    @Test
    fun `extractor failure passes through untouched`() {
        val parser = PdfBookParser(
            FakeExtractor("", pages = 0, failure = AppError.BadFile("this PDF is password-protected"))
        )
        when (val result = parser.parse(File(temp.root, "x.pdf"), temp.newFolder()) {}) {
            is AppResult.Ok -> fail("must fail")
            is AppResult.Err -> assertTrue(result.error is AppError.BadFile)
        }
    }

    @Test
    fun `zero pages is a BadFile`() {
        val parser = PdfBookParser(FakeExtractor("", pages = 0))
        when (val result = parser.parse(File(temp.root, "x.pdf"), temp.newFolder()) {}) {
            is AppResult.Ok -> fail("must fail")
            is AppResult.Err -> assertTrue(result.error is AppError.BadFile)
        }
    }
}
