package app.retranslate.domain.books

import org.junit.Assert.assertTrue
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds tiny synthetic EPUBs in-test (PLAN.md §12.1 fixtures). */
object EpubFixtures {

    data class ChapterSpec(
        val path: String,
        val navTitle: String,
        val heading: String,
        val paragraphs: List<String>
    )

    fun chapter(index: Int, title: String, vararg paragraphs: String): ChapterSpec =
        ChapterSpec(
            path = "text/chapter$index.xhtml",
            navTitle = title,
            heading = title,
            paragraphs = paragraphs.toList()
        )

    /**
     * Writes a complete EPUB 3 (+ NCX) file: mimetype, container, OPF
     * with the given metadata, one XHTML per chapter, optional cover,
     * optional DRM marker.
     */
    fun buildEpub(
        file: File,
        chapters: List<ChapterSpec>,
        bookTitle: String = "Fixture Book",
        author: String = "Fixture Author",
        language: String = "ko",
        withCover: Boolean = false,
        drm: Boolean = false,
        extraManifest: String = "",
        navTitles: Boolean = true
    ) {
        file.outputStream().buffered().use { raw ->
            ZipOutputStream(raw).use { zip ->
                fun entry(name: String, content: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                fun entryBytes(name: String, content: ByteArray) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content)
                    zip.closeEntry()
                }
                entry("mimetype", "application/epub+zip")
                entry(
                    "META-INF/container.xml",
                    """<?xml version="1.0" encoding="UTF-8"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>""".trimIndent()
                )
                if (drm) {
                    entry(
                        "META-INF/encryption.xml",
                        """<?xml version="1.0" encoding="UTF-8"?>
                        <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                          <EncryptedData><CipherData><CipherReference URI="OEBPS/text/chapter1.xhtml"/></CipherData></EncryptedData>
                        </encryption>""".trimIndent()
                    )
                }
                val manifestItems = StringBuilder()
                val spineItems = StringBuilder()
                chapters.forEachIndexed { i, spec ->
                    val id = "ch${i + 1}"
                    manifestItems.append(
                        """<item id="$id" href="${spec.path}" media-type="application/xhtml+xml"/>"""
                    )
                    spineItems.append("""<itemref idref="$id"/>""")
                }
                manifestItems.append(
                    """<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>"""
                )
                if (withCover) {
                    manifestItems.append(
                        """<item id="cover" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>"""
                    )
                }
                manifestItems.append(extraManifest)
                entry(
                    "OEBPS/content.opf",
                    """<?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:identifier id="bookid">fixture-1</dc:identifier>
                        <dc:title>$bookTitle</dc:title>
                        <dc:creator>$author</dc:creator>
                        <dc:language>$language</dc:language>
                      </metadata>
                      <manifest>$manifestItems</manifest>
                      <spine toc="ncx">$spineItems</spine>
                    </package>""".trimIndent()
                )
                val navPoints = chapters.mapIndexed { i, spec ->
                    """<navPoint id="nav${i + 1}" playOrder="${i + 1}">
                      <navLabel><text>${if (navTitles) spec.navTitle else ""}</text></navLabel>
                      <content src="${spec.path}"/>
                    </navPoint>""".trimIndent()
                }.joinToString("\n")
                entry(
                    "OEBPS/toc.ncx",
                    """<?xml version="1.0" encoding="UTF-8"?>
                    <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                      <head><meta name="dtb:uid" content="fixture-1"/></head>
                      <docTitle><text>$bookTitle</text></docTitle>
                      <navMap>$navPoints</navMap>
                    </ncx>""".trimIndent()
                )
                for (spec in chapters) {
                    val body = spec.paragraphs.joinToString("\n") { "<p>$it</p>" }
                    entry(
                        "OEBPS/${spec.path}",
                        """<?xml version="1.0" encoding="utf-8"?>
                        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>${spec.heading}</title></head>
                        <body><h1>${spec.heading}</h1>
                        $body
                        </body></html>""".trimIndent()
                    )
                }
                if (withCover) {
                    entryBytes(
                        "OEBPS/images/cover.jpg",
                        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x42, 0x43)
                    )
                }
            }
        }
    }
}

/** Shared assertion helpers for parser tests. */
internal fun <T> unwrap(result: app.retranslate.core.error.AppResult<T>): T =
    when (result) {
        is app.retranslate.core.error.AppResult.Ok -> result.value
        is app.retranslate.core.error.AppResult.Err ->
            throw AssertionError("unexpected error: ${result.error}")
    }

internal fun assertFileContains(file: File, needle: String) {
    assertTrue(
        "expected file to contain '$needle'",
        file.readText(Charsets.UTF_8).contains(needle)
    )
}
