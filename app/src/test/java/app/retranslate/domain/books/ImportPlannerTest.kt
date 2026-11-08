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
 * ImportPlanner (PLAN.md §7.1/§7.2): EPUB merge-forward, universal
 * oversized splitting, empty handling, title dedup suffixes, metadata
 * normalisation — the boundary validation before Room.
 */
class ImportPlannerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var seq = 0

    private fun chapter(title: String?, text: String): ParsedChapter {
        val file = File(temp.root, "src-${seq++}.txt")
        file.writeText(text, Charsets.UTF_8)
        return ParsedChapter(title = title, file = file, charCount = text.length)
    }

    private fun paragraphs(count: Int, wordsPerPara: Int = 20): String =
        (1..count).joinToString("\n\n") { i ->
            "Paragraph $i " + "with some story words ".repeat(wordsPerPara)
        }

    @Test
    fun `EPUB small items merge forward into the next chapter`() {
        val parsed = ParsedBook(
            title = "Book", author = null, declaredLang = null, coverFile = null,
            chapters = listOf(
                chapter("Title Page", "Just the title"),           // < 300 → merges
                chapter("Chapter 1", paragraphs(10)),               // real chapter
                chapter("Chapter 2", paragraphs(10))
            )
        )
        val planned = unwrap(ImportPlanner.plan(parsed, ImportFormat.EPUB, temp.newFolder()))
        assertEquals(2, planned.chapters.size)
        assertEquals("Chapter 1", planned.chapters[0].title)
        assertFileContains(planned.chapters[0].file, "Just the title")
        assertFileContains(planned.chapters[0].file, "Paragraph 1")
    }

    @Test
    fun `EPUB trailing small item appends to the last chapter`() {
        val parsed = ParsedBook(
            title = "Book", author = null, declaredLang = null, coverFile = null,
            chapters = listOf(
                chapter("Chapter 1", paragraphs(10)),
                chapter("Author Note", "A tiny note")
            )
        )
        val planned = unwrap(ImportPlanner.plan(parsed, ImportFormat.EPUB, temp.newFolder()))
        assertEquals(1, planned.chapters.size)
        assertFileContains(planned.chapters[0].file, "A tiny note")
    }

    @Test
    fun `TXT small chapters do NOT merge (real chapters stay chapters)`() {
        val parsed = ParsedBook(
            title = null, author = null, declaredLang = null, coverFile = null,
            chapters = listOf(
                chapter("Chapter 1", "Short but real."),
                chapter("Chapter 2", "Also short.")
            )
        )
        val planned = unwrap(ImportPlanner.plan(parsed, ImportFormat.TXT, temp.newFolder()))
        assertEquals(2, planned.chapters.size)
    }

    @Test
    fun `oversized chapter splits at paragraph boundaries`() {
        val bigText = paragraphs(600, wordsPerPara = 25) // ~350k chars
        assertTrue(bigText.length > ImportPlanner.SPLIT_THRESHOLD)
        val parsed = ParsedBook(
            title = null, author = null, declaredLang = null, coverFile = null,
            chapters = listOf(chapter("Big One", bigText))
        )
        val planned = unwrap(ImportPlanner.plan(parsed, ImportFormat.TXT, temp.newFolder()))
        assertTrue("pieces: ${planned.chapters.size}", planned.chapters.size >= 2)
        for (piece in planned.chapters) {
            assertTrue(
                "piece too large: ${piece.charCount}",
                piece.charCount <= ImportPlanner.SPLIT_THRESHOLD + 1_000
            )
        }
        // Split pieces of one chapter get suffixed titles.
        assertEquals("Big One", planned.chapters[0].title)
        assertEquals("Big One (2)", planned.chapters[1].title)
        // No text lost (piece joins differ only by separator placement).
        val rejoined = planned.chapters.sumOf { it.charCount }
        assertTrue(
            "lost text: $rejoined vs ${bigText.length}",
            rejoined > bigText.length - 100 && rejoined <= bigText.length
        )
        assertFileContains(planned.chapters.last().file, "Paragraph 600")
    }

    @Test
    fun `empty chapters drop, all-empty is a refusal`() {
        val parsed = ParsedBook(
            title = null, author = null, declaredLang = null, coverFile = null,
            chapters = listOf(
                chapter(null, ""),
                chapter("Real", paragraphs(3)),
                chapter(null, "")
            )
        )
        val planned = unwrap(ImportPlanner.plan(parsed, ImportFormat.TXT, temp.newFolder()))
        assertEquals(1, planned.chapters.size)

        val emptyParsed = ParsedBook(
            title = null, author = null, declaredLang = null, coverFile = null,
            chapters = listOf(chapter(null, ""), chapter(null, ""))
        )
        when (val result = ImportPlanner.plan(emptyParsed, ImportFormat.TXT, temp.newFolder())) {
            is AppResult.Ok -> fail("empty book must be refused")
            is AppResult.Err -> assertTrue(result.error is AppError.BadFile)
        }
    }

    @Test
    fun `metadata is trimmed and language normalised`() {
        val parsed = ParsedBook(
            title = "  Spaced Title  ",
            author = "  Some Author ",
            declaredLang = "en-US",
            coverFile = null,
            chapters = listOf(chapter("C1", paragraphs(3)))
        )
        val planned = unwrap(ImportPlanner.plan(parsed, ImportFormat.EPUB, temp.newFolder()))
        assertEquals("Spaced Title", planned.title)
        assertEquals("Some Author", planned.author)
        assertEquals("en", planned.declaredLang)
    }

    @Test
    fun `unusable declared language becomes null, never a bad code in the DB`() {
        val parsed = ParsedBook(
            title = null, author = null, declaredLang = "klingon-9", coverFile = null,
            chapters = listOf(chapter("C1", paragraphs(3)))
        )
        val planned = unwrap(ImportPlanner.plan(parsed, ImportFormat.EPUB, temp.newFolder()))
        assertEquals(null, planned.declaredLang)
    }
}
