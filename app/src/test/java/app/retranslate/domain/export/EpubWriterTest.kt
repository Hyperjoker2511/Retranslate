package app.retranslate.domain.export

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * PLAN.md §7.5/§12.1: the EPUB structure checklist — mimetype first
 * and STORED, container → OPF, manifest/spine agreeing with the
 * chapters written, NCX + nav carrying the titles, metadata right.
 */
class EpubWriterTest {

    private data class Entry(val name: String, val method: Int, val text: String)

    private fun unzip(bytes: ByteArray): List<Entry> {
        val entries = mutableListOf<Entry>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries += Entry(
                    entry.name, entry.method,
                    zip.readBytes().toString(Charsets.UTF_8)
                )
                entry = zip.nextEntry
            }
        }
        return entries
    }

    private suspend fun buildEpub(
        chapters: List<ExportChapter>,
        note: Boolean = false,
        title: String = "Sword & Sorcery <Tales>",
        author: String? = "Kim Author"
    ): List<Entry> {
        val out = ByteArrayOutputStream()
        val iterator = chapters.iterator()
        EpubWriter().write(
            meta = ExportBookMeta(
                title = title,
                author = author,
                language = "en",
                includeTranslatorNote = note,
                identifier = "urn:uuid:test-id"
            ),
            out = out,
            nextChapter = { if (iterator.hasNext()) iterator.next() else null }
        )
        return unzip(out.toByteArray())
    }

    private fun chapter(number: Int, paragraphs: Int = 3): ExportChapter =
        ExportChapter(
            number = number,
            title = "Chapter $number: The Beginning",
            text = (1..paragraphs).joinToString("\n\n") {
                "Paragraph $it of chapter $number, with some story in it."
            }
        )

    @Test
    fun `mimetype is the first entry and stored uncompressed`() = runTest {
        val entries = buildEpub(listOf(chapter(1)))
        assertEquals("mimetype", entries.first().name)
        assertEquals(ZipEntry.STORED, entries.first().method)
        assertEquals("application/epub+zip", entries.first().text)
    }

    @Test
    fun `container points at the OPF and the OPF carries the metadata`() = runTest {
        val entries = buildEpub(listOf(chapter(1)))
        val container = entries.first { it.name == "META-INF/container.xml" }.text
        assertTrue(container.contains("OEBPS/content.opf"))
        val opf = entries.first { it.name == "OEBPS/content.opf" }.text
        // The title's XML-hostile characters are escaped, never raw.
        assertTrue(opf.contains("Sword &amp; Sorcery &lt;Tales&gt;"))
        assertTrue(opf.contains("<dc:creator>Kim Author</dc:creator>"))
        assertTrue(opf.contains("<dc:language>en</dc:language>"))
        assertTrue(opf.contains("<dc:identifier id=\"book-id\">urn:uuid:test-id</dc:identifier>"))
    }

    @Test
    fun `manifest and spine list exactly the chapters, in order`() = runTest {
        val chapters = listOf(chapter(1), chapter(2), chapter(3))
        val entries = buildEpub(chapters)
        val opf = entries.first { it.name == "OEBPS/content.opf" }.text
        val chapterFiles = entries
            .map { it.name }
            .filter { it.matches(Regex("OEBPS/chapter-\\d+\\.xhtml")) }
        assertEquals(
            listOf(
                "OEBPS/chapter-0001.xhtml",
                "OEBPS/chapter-0002.xhtml",
                "OEBPS/chapter-0003.xhtml"
            ),
            chapterFiles
        )
        for (file in chapterFiles) {
            val href = file.removePrefix("OEBPS/")
            assertTrue("manifest lists $href", opf.contains("href=\"$href\""))
        }
        val spineOrder = Regex("idref=\"(ch-\\d+)\"").findAll(opf).map { it.groupValues[1] }.toList()
        assertEquals(listOf("ch-1", "ch-2", "ch-3"), spineOrder)
        assertTrue(opf.contains("properties=\"nav\""))
        assertTrue(opf.contains("toc=\"ncx\""))
    }

    @Test
    fun `chapters carry an h1 title and one p per paragraph`() = runTest {
        val entries = buildEpub(listOf(chapter(7, paragraphs = 4)))
        val xhtml = entries.first { it.name == "OEBPS/chapter-0001.xhtml" }.text
        assertTrue(xhtml.contains("<h1>Chapter 7: The Beginning</h1>"))
        assertEquals(4, Regex("<p>").findAll(xhtml).count())
        assertTrue(xhtml.contains("Paragraph 3 of chapter 7"))
    }

    @Test
    fun `ncx and nav carry the same titles as the chapters`() = runTest {
        val entries = buildEpub(listOf(chapter(1), chapter(2)))
        val ncx = entries.first { it.name == "OEBPS/toc.ncx" }.text
        assertTrue(ncx.contains("<text>Chapter 1: The Beginning</text>"))
        assertTrue(ncx.contains("<text>Chapter 2: The Beginning</text>"))
        assertTrue(ncx.contains("src=\"chapter-0002.xhtml\""))
        val playOrders = Regex("playOrder=\"(\\d+)\"").findAll(ncx)
            .map { it.groupValues[1].toInt() }.toList()
        assertEquals(listOf(1, 2), playOrders)
        val nav = entries.first { it.name == "OEBPS/nav.xhtml" }.text
        assertTrue(nav.contains("<a href=\"chapter-0001.xhtml\">Chapter 1: The Beginning</a>"))
    }

    @Test
    fun `untitled chapters fall back to Chapter N everywhere`() = runTest {
        val entries = buildEpub(listOf(ExportChapter(5, null, "Only text here.")))
        val xhtml = entries.first { it.name == "OEBPS/chapter-0001.xhtml" }.text
        assertTrue(xhtml.contains("<h1>Chapter 5</h1>"))
        val ncx = entries.first { it.name == "OEBPS/toc.ncx" }.text
        assertTrue(ncx.contains("<text>Chapter 5</text>"))
    }

    @Test
    fun `translator note page appears only when asked for`() = runTest {
        val withNote = buildEpub(listOf(chapter(1)), note = true)
        assertTrue(withNote.any { it.name == "OEBPS/note.xhtml" })
        val note = withNote.first { it.name == "OEBPS/note.xhtml" }.text
        assertTrue(note.contains("translated with ReTranslate"))
        val opf = withNote.first { it.name == "OEBPS/content.opf" }.text
        assertTrue(opf.contains("href=\"note.xhtml\""))
        // The note is marked as a colophon so importers skip it as a
        // chapter (the §7.5 round-trip rule).
        assertTrue(opf.contains("properties=\"retranslate-note\""))

        val without = buildEpub(listOf(chapter(1)), note = false)
        assertFalse(without.any { it.name == "OEBPS/note.xhtml" })
    }

    @Test
    fun `paragraph splitting folds inline whitespace like the importers`() {
        val paragraphs = EpubWriter.paragraphsOf(
            "First line\nstill first paragraph.\n\n  Second\tparagraph.  \n\n\nThird."
        )
        assertEquals(
            listOf("First line still first paragraph.", "Second paragraph.", "Third."),
            paragraphs
        )
    }
}
