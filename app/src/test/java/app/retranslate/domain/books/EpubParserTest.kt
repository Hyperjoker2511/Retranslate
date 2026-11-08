package app.retranslate.domain.books

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * EPUB import (PLAN.md §7.2): structure parsing, NCX titles, metadata,
 * cover extraction, DRM honesty, corrupt-file honesty.
 * Split/merge rules are the planner's tests; this class checks the raw
 * parse (one chapter per spine item).
 */
class EpubParserTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun parse(file: File): AppResult<ParsedBook> {
        val workDir = temp.newFolder()
        return EpubParser().parse(file, workDir) {}
    }

    @Test
    fun `normal EPUB parses metadata, NCX titles, chapters and cover`() {
        val epub = File(temp.root, "book.epub")
        EpubFixtures.buildEpub(
            epub,
            chapters = listOf(
                EpubFixtures.chapter(1, "Chapter One", "He woke up.", "The sun was bright."),
                EpubFixtures.chapter(2, "Chapter Two", "He packed a bag.", "Then he left.")
            ),
            bookTitle = "The Fixture Saga",
            author = "Jane Fixture",
            language = "en",
            withCover = true
        )
        val book = unwrap(parse(epub))
        assertEquals("The Fixture Saga", book.title)
        assertEquals("Jane Fixture", book.author)
        assertEquals("en", book.declaredLang)
        assertEquals(2, book.chapters.size)
        assertEquals("Chapter One", book.chapters[0].title)
        assertEquals("Chapter Two", book.chapters[1].title)
        assertFileContains(book.chapters[0].file, "He woke up.")
        assertFileContains(book.chapters[0].file, "The sun was bright.")
        assertFileContains(book.chapters[1].file, "Then he left.")
        assertNotNull("cover extracted", book.coverFile)
        assertTrue(book.coverFile!!.isFile)
        assertTrue(book.chapters.all { it.charCount > 0 })
    }

    @Test
    fun `DRM EPUB is refused with DrmLocked`() {
        val epub = File(temp.root, "drm.epub")
        EpubFixtures.buildEpub(
            epub,
            chapters = listOf(EpubFixtures.chapter(1, "One", "Locked text.")),
            drm = true
        )
        when (val result = parse(epub)) {
            is AppResult.Ok -> fail("DRM book must not parse")
            is AppResult.Err -> assertEquals(AppError.DrmLocked, result.error)
        }
    }

    @Test
    fun `corrupt file is refused as BadFile`() {
        val notEpub = temp.newFile("junk.epub")
        notEpub.writeText("this is not a zip file at all", Charsets.UTF_8)
        when (val result = parse(notEpub)) {
            is AppResult.Ok -> fail("junk must not parse")
            is AppResult.Err -> assertTrue(result.error is AppError.BadFile)
        }
    }

    @Test
    fun `zip without EPUB structure is refused`() {
        val zipFile = File(temp.root, "plain.zip")
        java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("hello.txt"))
            zip.write("hello".toByteArray())
            zip.closeEntry()
        }
        when (val result = parse(zipFile)) {
            is AppResult.Ok -> fail("plain zip must not parse as EPUB")
            is AppResult.Err -> assertTrue(result.error is AppError.BadFile)
        }
    }

    @Test
    fun `titles fall back to the chapter heading when NCX has none`() {
        val epub = File(temp.root, "nonav.epub")
        EpubFixtures.buildEpub(
            epub,
            chapters = listOf(
                EpubFixtures.chapter(1, "The Real Heading", "Body text here.")
            ),
            navTitles = false
        )
        val book = unwrap(parse(epub))
        assertEquals("The Real Heading", book.chapters[0].title)
    }

    @Test
    fun `Korean declared language survives for the planner to normalise`() {
        val epub = File(temp.root, "ko.epub")
        EpubFixtures.buildEpub(
            epub,
            chapters = listOf(
                EpubFixtures.chapter(1, "제1장", "그는 천천히 눈을 떴다. 마을은 조용했다.")
            ),
            language = "ko"
        )
        val book = unwrap(parse(epub))
        assertEquals("ko", book.declaredLang)
        assertFileContains(book.chapters[0].file, "눈을 떴다")
    }

    @Test
    fun `resolve normalises relative paths`() {
        assertEquals("OEBPS/text/ch1.xhtml", EpubParser.resolve("OEBPS", "text/ch1.xhtml"))
        assertEquals("OEBPS/ch1.xhtml", EpubParser.resolve("OEBPS/text", "../ch1.xhtml"))
        assertEquals("ch1.xhtml", EpubParser.resolve("", "ch1.xhtml"))
        assertEquals("OEBPS/ch 1.xhtml", EpubParser.resolve("OEBPS", "ch%201.xhtml"))
    }

    @Test
    fun `percentDecode leaves plus signs alone`() {
        assertEquals("a+b.xhtml", EpubParser.percentDecode("a+b.xhtml"))
        assertEquals("a b.xhtml", EpubParser.percentDecode("a%20b.xhtml"))
    }
}
