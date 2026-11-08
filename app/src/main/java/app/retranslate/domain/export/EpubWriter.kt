package app.retranslate.domain.export

import java.io.OutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Book-level metadata for one export (§7.5). */
data class ExportBookMeta(
    val title: String,
    val author: String?,
    /** The book's TARGET language — the export is the translation. */
    val language: String,
    /** §7.5: the "Translated with ReTranslate" note (a colophon page). */
    val includeTranslatorNote: Boolean,
    /** OPF unique identifier; generated when null. */
    val identifier: String? = null,
    /** dcterms:modified value (UTC, e.g. 2026-10-27T04:00:00Z). */
    val modifiedUtc: String = "2026-01-01T00:00:00Z"
)

/** One chapter handed to a writer: number, display title, full text. */
data class ExportChapter(
    val number: Int,
    val title: String?,
    val text: String
) {
    /** The heading every surface agrees on: the title, or "Chapter N". */
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() } ?: "Chapter $number"
}

/**
 * The EPUB writer (PLAN.md §7.5): EPUB 3 (nav document) with an NCX
 * fallback for older readers. Chapters are PULLED one at a time from
 * [nextChapter] and written straight into the zip stream — the whole
 * book is never in memory (§7.3); only the small TOC list (number +
 * title per chapter) is collected, which the OPF/NCX/nav need.
 *
 * Layout (what the structure tests assert):
 *  - `mimetype` is the FIRST entry and STORED (the EPUB rule);
 *  - chapter files under `OEBPS/`, container → `OEBPS/content.opf`;
 *  - OPF manifest + spine list exactly the chapters written, in order;
 *  - NCX + nav carry the same titles, so the book's own P3 importer
 *    (and any reader) sees the original chapter titles.
 *
 * Round-trip contract with the P3 parser: one `<p>` per source
 * paragraph (blank-line separated in the stored text), the chapter
 * title as `<h1>` — the parser reads paragraphs back separated by
 * blank lines and captures the heading as the title, so
 * export → import returns the same chapter texts.
 */
class EpubWriter {

    suspend fun write(
        meta: ExportBookMeta,
        out: OutputStream,
        nextChapter: suspend () -> ExportChapter?
    ) {
        val toc = mutableListOf<TocEntry>()
        ZipOutputStream(out.buffered()).use { zip ->
            writeMimetype(zip)
            var index = 0
            while (true) {
                val chapter = nextChapter() ?: break
                index++
                val href = chapterHref(index)
                writeChapter(zip, href, chapter)
                toc += TocEntry(href, chapter.displayTitle)
            }
            if (meta.includeTranslatorNote) {
                writeNotePage(zip)
                toc += TocEntry(NOTE_HREF, NOTE_TITLE, isNote = true)
            }
            writeTextEntry(zip, "OEBPS/style.css", STYLE_CSS)
            writeTextEntry(zip, "OEBPS/nav.xhtml", navDocument(meta, toc))
            writeTextEntry(zip, "OEBPS/toc.ncx", ncxDocument(meta, toc))
            writeTextEntry(zip, "OEBPS/content.opf", opfDocument(meta, toc))
            writeTextEntry(zip, "META-INF/container.xml", CONTAINER_XML)
        }
    }

    private data class TocEntry(val href: String, val title: String, val isNote: Boolean = false)

    /** The mimetype entry: first, STORED, with its CRC pre-computed (zip rule). */
    private fun writeMimetype(zip: ZipOutputStream) {
        val bytes = MIMETYPE.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(bytes) }
        val entry = ZipEntry("mimetype").apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            this.crc = crc.value
            time = FIXED_TIME_MS
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun writeChapter(zip: ZipOutputStream, href: String, chapter: ExportChapter) {
        val entry = ZipEntry("OEBPS/$href").apply { time = FIXED_TIME_MS }
        zip.putNextEntry(entry)
        val writer = zip.bufferedWriter(Charsets.UTF_8)
        writer.write(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<!DOCTYPE html>\n" +
                "<html xmlns=\"http://www.w3.org/1999/xhtml\" " +
                "xmlns:epub=\"http://www.idpf.org/2007/ops\">\n" +
                "<head><title>${escapeXml(chapter.displayTitle)}</title>" +
                "<link rel=\"stylesheet\" type=\"text/css\" href=\"style.css\"/></head>\n" +
                "<body>\n<h1>${escapeXml(chapter.displayTitle)}</h1>\n"
        )
        for (paragraph in paragraphsOf(chapter.text)) {
            writer.write("<p>")
            writer.write(escapeXml(paragraph))
            writer.write("</p>\n")
        }
        writer.write("</body>\n</html>\n")
        writer.flush()
        zip.closeEntry()
    }

    private fun writeNotePage(zip: ZipOutputStream) {
        writeTextEntry(
            zip, "OEBPS/$NOTE_HREF",
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<!DOCTYPE html>\n" +
                "<html xmlns=\"http://www.w3.org/1999/xhtml\" " +
                "xmlns:epub=\"http://www.idpf.org/2007/ops\">\n" +
                "<head><title>$NOTE_TITLE</title>" +
                "<link rel=\"stylesheet\" type=\"text/css\" href=\"style.css\"/></head>\n" +
                "<body>\n<h1>$NOTE_TITLE</h1>\n" +
                "<p>This book was translated with ReTranslate. Machine " +
                "translation can make mistakes — names or terms may differ " +
                "from an official translation.</p>\n</body>\n</html>\n"
        )
    }

    private fun writeTextEntry(zip: ZipOutputStream, path: String, text: String) {
        val entry = ZipEntry(path).apply { time = FIXED_TIME_MS }
        zip.putNextEntry(entry)
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun navDocument(meta: ExportBookMeta, toc: List<TocEntry>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!DOCTYPE html>\n")
        append("<html xmlns=\"http://www.w3.org/1999/xhtml\" ")
        append("xmlns:epub=\"http://www.idpf.org/2007/ops\" ")
        append("xml:lang=\"").append(escapeXml(meta.language)).append("\">\n")
        append("<head><title>").append(escapeXml(meta.title)).append("</title></head>\n")
        append("<body>\n<nav epub:type=\"toc\">\n<h1>Contents</h1>\n<ol>\n")
        for (entry in toc) {
            append("<li><a href=\"").append(entry.href).append("\">")
            append(escapeXml(entry.title)).append("</a></li>\n")
        }
        append("</ol>\n</nav>\n</body>\n</html>\n")
    }

    private fun ncxDocument(meta: ExportBookMeta, toc: List<TocEntry>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\" ")
        append("xml:lang=\"").append(escapeXml(meta.language)).append("\">\n")
        append("<head><meta name=\"dtb:uid\" content=\"")
        append(escapeXml(meta.identifier ?: DEFAULT_ID)).append("\"/></head>\n")
        append("<docTitle><text>").append(escapeXml(meta.title)).append("</text></docTitle>\n")
        append("<navMap>\n")
        toc.forEachIndexed { i, entry ->
            append("<navPoint id=\"nav-").append(i + 1).append("\" playOrder=\"")
            append(i + 1).append("\"><navLabel><text>")
            append(escapeXml(entry.title))
            append("</text></navLabel><content src=\"").append(entry.href)
            append("\"/></navPoint>\n")
        }
        append("</navMap>\n</ncx>\n")
    }

    private fun opfDocument(meta: ExportBookMeta, toc: List<TocEntry>): String = buildString {
        val id = meta.identifier ?: DEFAULT_ID
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" ")
        append("unique-identifier=\"book-id\">\n")
        append("<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n")
        append("<dc:identifier id=\"book-id\">").append(escapeXml(id)).append("</dc:identifier>\n")
        append("<dc:title>").append(escapeXml(meta.title)).append("</dc:title>\n")
        meta.author?.takeIf { it.isNotBlank() }?.let {
            append("<dc:creator>").append(escapeXml(it)).append("</dc:creator>\n")
        }
        append("<dc:language>").append(escapeXml(meta.language)).append("</dc:language>\n")
        append("<dc:description>Translated with ReTranslate.</dc:description>\n")
        append("<meta property=\"dcterms:modified\">")
        append(escapeXml(meta.modifiedUtc)).append("</meta>\n")
        append("</metadata>\n<manifest>\n")
        append("<item id=\"nav\" href=\"nav.xhtml\" ")
        append("media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
        append("<item id=\"ncx\" href=\"toc.ncx\" ")
        append("media-type=\"application/x-dtbncx+xml\"/>\n")
        append("<item id=\"style\" href=\"style.css\" media-type=\"text/css\"/>\n")
        toc.forEachIndexed { i, entry ->
            append("<item id=\"ch-").append(i + 1).append("\" href=\"")
            append(entry.href).append("\" media-type=\"application/xhtml+xml\"")
            // The translator's note is a colophon, not a chapter: the
            // marker lets importers (ours included) skip it in the
            // spine walk, so an exported book re-imports to exactly
            // its chapters (§7.5 round trip).
            if (entry.isNote) append(" properties=\"retranslate-note\"")
            append("/>\n")
        }
        append("</manifest>\n<spine toc=\"ncx\">\n")
        toc.forEachIndexed { i, _ ->
            append("<itemref idref=\"ch-").append(i + 1).append("\"/>\n")
        }
        append("</spine>\n</package>\n")
    }

    companion object {
        const val MIMETYPE = "application/epub+zip"
        const val NOTE_HREF = "note.xhtml"
        const val NOTE_TITLE = "About this translation"
        private const val DEFAULT_ID = "urn:uuid:00000000-0000-0000-0000-000000000000"

        /** 1980-01-01 (the zip epoch): fixed entry times keep exports reproducible. */
        private const val FIXED_TIME_MS = 315_532_800_000L

        private const val CONTAINER_XML =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<container version=\"1.0\" " +
                "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n" +
                "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" " +
                "media-type=\"application/oebps-package+xml\"/></rootfiles>\n</container>\n"

        private const val STYLE_CSS =
            "body { line-height: 1.5; margin: 5%; }\n" +
                "h1 { font-size: 1.4em; margin-bottom: 1em; }\n" +
                "p { margin: 0 0 0.9em 0; }\n"

        fun chapterHref(index: Int): String = "chapter-%04d.xhtml".format(index)

        /** New identifier for one export run (OPF/NCX share it). */
        fun newIdentifier(): String = "urn:uuid:" + UUID.randomUUID().toString()

        /**
         * The stored text's paragraphs: split on blank lines, each
         * paragraph trimmed with internal line breaks folded to spaces
         * (the same canonical shape the importers produce, §7.1).
         */
        fun paragraphsOf(text: String): List<String> =
            text.split(BLANK_LINE)
                .map { it.replace(INLINE_WHITESPACE, " ").trim() }
                .filter { it.isNotEmpty() }

        private val BLANK_LINE = Regex("""\n[ \t]*\n""")
        private val INLINE_WHITESPACE = Regex("""\s+""")

        /** XML text/attribute escaping (the five predefined entities). */
        fun escapeXml(value: String): String = buildString(value.length) {
            for (c in value) {
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(c)
                }
            }
        }
    }
}
