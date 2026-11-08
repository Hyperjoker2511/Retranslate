package app.retranslate.domain.books

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * EPUB importer (PLAN.md §2.4/§7.2): a hand-written streaming parser on
 * platform APIs only. The zip is extracted entry-by-entry to disk (8 KB
 * buffer — never whole-book in RAM), then container.xml → OPF → spine
 * are walked with an XmlPullParser, converting one spine item at a time
 * to a plain-text chapter file (paragraphs separated by blank lines).
 *
 * Uses kxml2's parser — the same implementation Android bundles — so
 * behaviour is identical on device and in JVM unit tests.
 *
 * Chapter split/merge rules live in [ImportPlanner]; this parser emits
 * one chapter per spine item, faithfully.
 */
class EpubParser : BookParser {

    override fun parse(
        staged: File,
        workDir: File,
        onProgress: (fraction: Float?) -> Unit
    ): AppResult<ParsedBook> {
        val unzipped = File(workDir, "unzipped")
        extractZip(staged, unzipped)?.let { return AppResult.Err(it) }

        if (File(unzipped, "META-INF/encryption.xml").exists()) {
            return AppResult.Err(AppError.DrmLocked)
        }
        val container = File(unzipped, "META-INF/container.xml")
        if (!container.isFile) {
            return AppResult.Err(AppError.BadFile("this EPUB is missing its contents list"))
        }
        val opfPath = parseContainer(container)
            ?: return AppResult.Err(AppError.BadFile("this EPUB's contents list couldn't be read"))
        val opfFile = File(unzipped, opfPath)
        if (!opfFile.isFile) {
            return AppResult.Err(AppError.BadFile("this EPUB is missing its book file"))
        }
        val opf = parseOpf(opfFile)
            ?: return AppResult.Err(AppError.BadFile("this EPUB's book file couldn't be read"))

        val opfDir = opfPath.substringBeforeLast('/', "")
        val titles = mutableMapOf<String, String>()
        // NCX titles (EPUB 2) and nav-document titles (EPUB 3).
        opf.tocId?.let { tocId ->
            opf.manifest[tocId]?.let { tocItem ->
                val ncxFile = File(unzipped, resolve(opfDir, tocItem.href))
                if (ncxFile.isFile) {
                    val ncxDir = resolve(opfDir, tocItem.href).substringBeforeLast('/', "")
                    titles.putAll(parseNcx(ncxFile).mapKeys { (src, _) -> resolve(ncxDir, src) })
                }
            }
        }
        opf.manifest.values.firstOrNull { "nav" in it.properties }?.let { navItem ->
            val navPath = resolve(opfDir, navItem.href)
            val navFile = File(unzipped, navPath)
            if (navFile.isFile) {
                val navDir = navPath.substringBeforeLast('/', "")
                for ((href, label) in parseNav(navFile)) {
                    titles.putIfAbsent(resolve(navDir, href), label)
                }
            }
        }

        val chaptersDir = File(workDir, "chapters").apply { mkdirs() }
        val chapters = mutableListOf<ParsedChapter>()
        var failures = 0
        val spinePaths = opf.spine.mapNotNull { idref ->
            val item = opf.manifest[idref] ?: return@mapNotNull null
            // Colophon pages our own exporter marks (§7.5) are not
            // chapters — skipping them keeps the export→import round
            // trip exact.
            if ("retranslate-note" in item.properties) return@mapNotNull null
            if (item.mediaType != "application/xhtml+xml") null else resolve(opfDir, item.href)
        }
        spinePaths.forEachIndexed { index, relPath ->
            val itemFile = File(unzipped, relPath)
            val outFile = File(chaptersDir, "ch-%06d.txt".format(index))
            val converted = if (itemFile.isFile) convertXhtml(itemFile, outFile) else null
            if (converted == null) {
                failures++
                outFile.delete()
                chapters += ParsedChapter(title = titles[relPath], file = outFile, charCount = 0)
            } else {
                val (heading, chars) = converted
                chapters += ParsedChapter(
                    title = titles[relPath] ?: heading,
                    file = outFile,
                    charCount = chars
                )
            }
            onProgress(if (spinePaths.isEmpty()) 1f else (index + 1).toFloat() / spinePaths.size)
        }
        if (spinePaths.isNotEmpty() && failures * 2 > spinePaths.size) {
            return AppResult.Err(AppError.BadFile("this EPUB's chapters couldn't be read"))
        }

        val coverFile = opf.coverHref?.let { href ->
            val src = File(unzipped, resolve(opfDir, href))
            if (!src.isFile) null
            else {
                val ext = href.substringAfterLast('.', "img").lowercase().take(5)
                val dest = File(workDir, "cover.$ext")
                try {
                    src.copyTo(dest, overwrite = true)
                    dest
                } catch (e: IOException) {
                    null
                }
            }
        }

        return AppResult.Ok(
            ParsedBook(
                title = opf.title,
                author = opf.author,
                declaredLang = opf.language,
                coverFile = coverFile,
                chapters = chapters
            )
        )
    }

    // -- zip -------------------------------------------------------------

    /** Returns an [AppError] on failure, null on success. */
    private fun extractZip(staged: File, destDir: File): AppError? {
        return try {
            destDir.mkdirs()
            val destCanonical = destDir.canonicalPath
            var entries = 0
            ZipInputStream(staged.inputStream().buffered()).use { zip ->
                val buffer = ByteArray(8 * 1024)
                var entry = zip.nextEntry
                while (entry != null) {
                    if (++entries > MAX_ENTRIES) {
                        return AppError.BadFile("this EPUB has too many files inside")
                    }
                    val name = entry.name.replace('\\', '/')
                    if (!entry.isDirectory && !name.startsWith("__MACOSX")) {
                        val out = File(destDir, name)
                        if (!out.canonicalPath.startsWith(destCanonical + File.separator)) {
                            return AppError.BadFile("this EPUB contains an unsafe file path")
                        }
                        out.parentFile?.mkdirs()
                        out.outputStream().buffered().use { sink ->
                            var n = zip.read(buffer)
                            while (n >= 0) {
                                sink.write(buffer, 0, n)
                                n = zip.read(buffer)
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            null
        } catch (e: ZipException) {
            AppError.BadFile("this file isn't a readable EPUB")
        } catch (e: IOException) {
            AppError.BadFile("the EPUB couldn't be opened")
        } catch (e: Exception) {
            AppError.Unknown(e)
        }
    }

    // -- XML plumbing ------------------------------------------------------

    private fun newParser(file: File): XmlPullParser {
        // Via the factory, not KXmlParser directly: on device the factory
        // returns Android's bundled KXmlParser (the same implementation),
        // and in JVM tests the kxml2 jar's service registration does.
        // Keeping kxml2 out of the APK also keeps R8 from treating
        // org.xmlpull.v1 as program code (P8 release fix).
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(file.inputStream().buffered(), null)
        return parser
    }

    private fun parseContainer(file: File): String? = try {
        val parser = newParser(file)
        var event = parser.eventType
        var result: String? = null
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "rootfile") {
                result = parser.getAttributeValue(null, "full-path")
                if (result != null) break
            }
            event = parser.next()
        }
        result
    } catch (e: Exception) {
        null
    }

    private data class ManifestItem(
        val href: String,
        val mediaType: String,
        val properties: Set<String>
    )

    private data class Opf(
        val title: String?,
        val author: String?,
        val language: String?,
        val manifest: Map<String, ManifestItem>,
        val spine: List<String>,
        val tocId: String?,
        val coverHref: String?
    )

    private fun parseOpf(file: File): Opf? = try {
        val parser = newParser(file)
        var title: String? = null
        var author: String? = null
        var language: String? = null
        var coverId: String? = null
        val manifest = mutableMapOf<String, ManifestItem>()
        val spine = mutableListOf<String>()
        var tocId: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "title" -> title = parser.nextText()?.trim()?.takeIf { it.isNotEmpty() } ?: title
                    "creator" -> author = parser.nextText()?.trim()?.takeIf { it.isNotEmpty() } ?: author
                    "language" -> language = parser.nextText()?.trim()?.takeIf { it.isNotEmpty() } ?: language
                    "meta" -> {
                        if (parser.getAttributeValue(null, "name") == "cover") {
                            coverId = parser.getAttributeValue(null, "content")
                        }
                    }
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        val type = parser.getAttributeValue(null, "media-type") ?: ""
                        val props = parser.getAttributeValue(null, "properties")
                            ?.split(' ')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
                        if (id != null && href != null) {
                            manifest[id] = ManifestItem(percentDecode(href), type, props)
                        }
                    }
                    "spine" -> tocId = parser.getAttributeValue(null, "toc")
                    "itemref" -> parser.getAttributeValue(null, "idref")?.let { spine += it }
                }
            }
            event = parser.next()
        }
        val coverHref = coverId?.let { manifest[it]?.href }
            ?: manifest.values.firstOrNull { "cover-image" in it.properties }?.href
        Opf(title, author, language, manifest, spine, tocId, coverHref)
    } catch (e: Exception) {
        null
    }

    /** NCX: content src → nav label. */
    private fun parseNcx(file: File): Map<String, String> = try {
        val parser = newParser(file)
        val result = mutableMapOf<String, String>()
        // One accumulator per open navPoint (they nest): label and src
        // appear in either order inside a navPoint (navLabel first in
        // practice), so both are collected and paired when it closes.
        class NavPoint {
            var src: String? = null
            var label: String? = null
            var inLabel = false
        }
        val stack = mutableListOf<NavPoint>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "navPoint" -> stack += NavPoint()
                    "content" -> stack.lastOrNull()?.let {
                        it.src = parser.getAttributeValue(null, "src")
                    }
                    "navLabel" -> stack.lastOrNull()?.inLabel = true
                    "text" -> {
                        val point = stack.lastOrNull()
                        if (point != null && point.inLabel) {
                            point.label = parser.nextText()?.trim()
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "navLabel" -> stack.lastOrNull()?.inLabel = false
                    "navPoint" -> {
                        val point = stack.removeAt(stack.lastIndex)
                        val src = point.src
                        val label = point.label
                        if (src != null && !label.isNullOrEmpty()) {
                            result.putIfAbsent(src.substringBefore('#'), label)
                        }
                    }
                }
            }
            event = parser.next()
        }
        result
    } catch (e: Exception) {
        emptyMap()
    }

    /** EPUB 3 nav document: anchor href → its text. */
    private fun parseNav(file: File): Map<String, String> = try {
        val parser = newParser(file)
        val result = mutableMapOf<String, String>()
        var navDepth = -1
        var depth = 0
        var currentHref: String? = null
        val text = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    depth++
                    if (parser.name == "nav" && navDepth < 0) navDepth = depth
                    if (navDepth >= 0 && parser.name == "a") {
                        currentHref = parser.getAttributeValue(null, "href")
                        text.setLength(0)
                    }
                }
                XmlPullParser.TEXT -> if (currentHref != null) text.append(parser.text)
                XmlPullParser.END_TAG -> {
                    if (parser.name == "a" && currentHref != null) {
                        val label = text.toString().replace(WHITESPACE, " ").trim()
                        if (label.isNotEmpty()) {
                            result.putIfAbsent(currentHref!!.substringBefore('#'), label)
                        }
                        currentHref = null
                    }
                    if (parser.name == "nav" && depth == navDepth) navDepth = -1
                    depth--
                }
            }
            event = parser.next()
        }
        result
    } catch (e: Exception) {
        emptyMap()
    }

    // -- XHTML → text -------------------------------------------------------

    /**
     * Converts one spine item to a chapter file. Returns (first heading,
     * char count), or null when the file can't be parsed at all.
     */
    private fun convertXhtml(file: File, outFile: File): Pair<String?, Int>? = try {
        val parser = newParser(file)
        var chars = 0
        var paragraphs = 0
        var heading: String? = null
        var titleTag: String? = null
        var skipDepth = 0
        var inTitle = false
        var headingCapture: StringBuilder? = null
        val paragraph = StringBuilder()
        outFile.bufferedWriter(Charsets.UTF_8).use { writer ->
            fun flushParagraph() {
                val text = paragraph.toString().replace(WHITESPACE, " ").trim()
                paragraph.setLength(0)
                if (text.isEmpty()) return
                if (paragraphs > 0) {
                    writer.write("\n\n")
                    chars += 2
                }
                writer.write(text)
                chars += text.length
                paragraphs++
            }
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name
                        if (name in SKIP_TAGS) skipDepth++
                        if (skipDepth == 0) {
                            when {
                                name == "title" -> inTitle = true
                                name == "br" -> flushParagraph()
                                name in BLOCK_TAGS -> {
                                    flushParagraph()
                                    if ((name == "h1" || name == "h2") && heading == null) {
                                        headingCapture = StringBuilder()
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> if (skipDepth == 0) {
                        val t = parser.text ?: ""
                        when {
                            inTitle -> titleTag = ((titleTag ?: "") + t).trim()
                            headingCapture != null -> headingCapture.append(t)
                            else -> paragraph.append(t)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = parser.name
                        if (name in SKIP_TAGS && skipDepth > 0) skipDepth--
                        if (skipDepth == 0) {
                            when {
                                name == "title" -> inTitle = false
                                // Headings BEFORE the block branch: h1/h2 are
                                // block tags too, and the capture must be
                                // finalised (and cleared) here — leaving it
                                // set would swallow every following text
                                // event into the capture buffer.
                                name == "h1" || name == "h2" -> {
                                    headingCapture?.let {
                                        heading = it.toString()
                                            .replace(WHITESPACE, " ").trim()
                                            .takeIf { h -> h.isNotEmpty() } ?: heading
                                        headingCapture = null
                                    }
                                    flushParagraph()
                                }
                                name in BLOCK_TAGS -> flushParagraph()
                            }
                        }
                    }
                }
                event = parser.next()
            }
            flushParagraph()
        }
        (heading ?: titleTag?.takeIf { it.isNotEmpty() }) to chars
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val MAX_ENTRIES = 10_000
        private val WHITESPACE = Regex("\\s+")
        private val SKIP_TAGS = setOf("script", "style", "head")
        private val BLOCK_TAGS = setOf(
            "p", "div", "section", "article", "header", "footer", "main", "aside",
            "h1", "h2", "h3", "h4", "h5", "h6", "li", "ul", "ol", "blockquote",
            "pre", "table", "tr", "figure", "figcaption", "hr"
        )

        /** Resolves [href] against a directory inside the EPUB, normalising `..`/`.`. */
        fun resolve(baseDir: String, href: String): String {
            val clean = percentDecode(href.substringBefore('#'))
            val stack = mutableListOf<String>()
            if (!clean.startsWith("/")) {
                baseDir.split('/').filter { it.isNotEmpty() }.forEach { stack += it }
            }
            for (segment in clean.split('/')) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                    else -> stack += segment
                }
            }
            return stack.joinToString("/")
        }

        /** Percent-decodes a URI path without mangling '+' (unlike URLDecoder). */
        fun percentDecode(value: String): String {
            if ('%' !in value) return value
            val bytes = mutableListOf<Byte>()
            var i = 0
            while (i < value.length) {
                val ch = value[i]
                if (ch == '%' && i + 2 <= value.lastIndex) {
                    val hex = value.substring(i + 1, i + 3)
                    val byte = hex.toIntOrNull(16)
                    if (byte != null) {
                        bytes += byte.toByte()
                        i += 3
                        continue
                    }
                }
                for (b in ch.toString().toByteArray(Charsets.UTF_8)) bytes += b
                i++
            }
            return bytes.toByteArray().toString(Charsets.UTF_8)
        }
    }
}
