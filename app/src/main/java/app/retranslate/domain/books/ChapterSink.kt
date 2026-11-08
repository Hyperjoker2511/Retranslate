package app.retranslate.domain.books

import java.io.BufferedWriter
import java.io.File

/**
 * Streaming chapter builder (PLAN.md §7.2/§7.3): lines go in one at a
 * time; at most one paragraph and one chapter file handle are ever in
 * memory. Used by the TXT parser directly and by the PDF parser over
 * its cleaned text, so both formats split identically.
 *
 * Rules:
 *  - Heading lines (`Chapter 12`, `제12장`, `第12章`, and generic
 *    numbered headings at a paragraph boundary) start a new chapter.
 *  - Blank lines end paragraphs. Consecutive lines join into one
 *    paragraph — with no space around CJK script, a space otherwise
 *    (wrapped lines in the source files).
 *  - A chapter that grows past [MAX_STREAM_CHARS] is cut at the next
 *    paragraph boundary (continuation keeps the title; the planner
 *    suffixes repeats).
 *  - At [finish], any chapter still larger than [RECHUNK_THRESHOLD] —
 *    the sign headings were missing — is re-cut into ~[FALLBACK_CHUNK]
 *    pieces at paragraph boundaries (the plan's fallback chunking).
 */
class ChapterSink(private val chaptersDir: File) {

    private val chapters = mutableListOf<ParsedChapter>()
    private var writer: BufferedWriter? = null
    private var currentFile: File? = null
    private var currentTitle: String? = null
    private var currentChars = 0
    private var currentParagraphs = 0
    private var fileIndex = 0
    private val paragraph = StringBuilder()
    private var atBoundary = true // start-of-text counts as a boundary

    fun onLine(rawLine: String) {
        val line = rawLine.trim()
        val heading = detectHeading(line, atBoundary)
        if (heading != null) {
            flushParagraph()
            startChapter(heading)
            atBoundary = true
            return
        }
        if (line.isEmpty()) {
            flushParagraph()
            atBoundary = true
            return
        }
        appendLine(line)
        atBoundary = false
    }

    fun finish(): List<ParsedChapter> {
        flushParagraph()
        closeChapter()
        rechunkOversized()
        return chapters.toList()
    }

    // -- internals ------------------------------------------------------

    private fun appendLine(line: String) {
        if (paragraph.isNotEmpty()) {
            paragraph.append(if (joinsWithoutSpace(line)) "" else " ")
        }
        paragraph.append(line)
        if (paragraph.length >= PARAGRAPH_HARD_CUT) flushParagraph()
    }

    private fun joinsWithoutSpace(line: String): Boolean {
        val prev = paragraph.lastOrNull() ?: return false
        val next = line.firstOrNull() ?: return false
        return isCjk(prev) || isCjk(next)
    }

    private fun isCjk(ch: Char): Boolean =
        ch in '\uAC00'..'\uD7A3' || ch in '\u4E00'..'\u9FFF' ||
            ch in '\u3040'..'\u30FF' || ch in '\u3400'..'\u4DBF'

    private fun flushParagraph() {
        val text = paragraph.toString().trim()
        paragraph.setLength(0)
        if (text.isEmpty()) return
        val w = ensureWriter()
        if (currentParagraphs > 0) {
            w.write("\n\n")
            currentChars += 2
        }
        w.write(text)
        currentChars += text.length
        currentParagraphs++
        if (currentChars >= MAX_STREAM_CHARS) {
            closeChapter()
            // Continuation of the same chapter: keep the title; the
            // planner suffixes consecutive duplicates ("… (2)").
            currentTitle = chapters.lastOrNull()?.title
        }
    }

    private fun startChapter(title: String) {
        if (currentChars == 0 && currentParagraphs == 0) {
            // Nothing written yet — this heading just names chapter 1
            // (also swallows a title-page preamble of pure headings).
            currentTitle = title
            return
        }
        closeChapter()
        currentTitle = title
    }

    private fun ensureWriter(): BufferedWriter {
        writer?.let { return it }
        chaptersDir.mkdirs()
        val file = File(chaptersDir, "ch-%06d.txt".format(fileIndex++))
        currentFile = file
        val w = file.bufferedWriter(Charsets.UTF_8)
        writer = w
        return w
    }

    private fun closeChapter() {
        writer?.close()
        writer = null
        val file = currentFile
        if (file != null && currentChars > 0) {
            chapters += ParsedChapter(title = currentTitle, file = file, charCount = currentChars)
        } else {
            file?.delete()
        }
        currentFile = null
        currentChars = 0
        currentParagraphs = 0
        currentTitle = null
    }

    /** Re-cut any chapter > [RECHUNK_THRESHOLD] into ~[FALLBACK_CHUNK] pieces. */
    private fun rechunkOversized() {
        var i = 0
        while (i < chapters.size) {
            val chapter = chapters[i]
            if (chapter.charCount <= RECHUNK_THRESHOLD) {
                i++
                continue
            }
            val pieces = splitChapterFile(chapter)
            chapters.removeAt(i)
            chapters.addAll(i, pieces)
            i += pieces.size
        }
    }

    private fun splitChapterFile(chapter: ParsedChapter): List<ParsedChapter> {
        val pieces = mutableListOf<ParsedChapter>()
        var writer: BufferedWriter? = null
        var pieceChars = 0
        var pieceParagraphs = 0
        var pieceFile: File? = null
        var first = true

        fun openPiece(): BufferedWriter {
            val f = File(chaptersDir, "ch-%06d.txt".format(fileIndex++))
            pieceFile = f
            pieceChars = 0
            pieceParagraphs = 0
            val w = f.bufferedWriter(Charsets.UTF_8)
            writer = w
            return w
        }

        fun closePiece() {
            writer?.close()
            writer = null
            val f = pieceFile
            if (f != null && pieceChars > 0) {
                pieces += ParsedChapter(
                    title = if (first) chapter.title else null,
                    file = f,
                    charCount = pieceChars
                )
                first = false
            } else {
                f?.delete()
            }
            pieceFile = null
        }

        chapter.file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            // Chapter files store paragraphs separated by blank lines.
            val para = StringBuilder()
            fun emitParagraph() {
                val text = para.toString().trim()
                para.setLength(0)
                if (text.isEmpty()) return
                val w = writer ?: openPiece()
                if (pieceParagraphs > 0) {
                    w.write("\n\n")
                    pieceChars += 2
                }
                w.write(text)
                pieceChars += text.length
                pieceParagraphs++
                if (pieceChars >= FALLBACK_CHUNK) closePiece()
            }
            for (line in lines) {
                if (line.isBlank()) emitParagraph()
                else {
                    if (para.isNotEmpty()) para.append(' ')
                    para.append(line.trim())
                }
            }
            emitParagraph()
        }
        closePiece()
        chapter.file.delete()
        return pieces
    }

    companion object {
        /** §7.2: chapters are cut at paragraph boundaries past this size. */
        const val MAX_STREAM_CHARS = 200_000

        /** A single "paragraph" longer than this is hard-flushed (safety). */
        const val PARAGRAPH_HARD_CUT = 32_768

        /** Chapters bigger than this after heading-splitting get re-chunked. */
        const val RECHUNK_THRESHOLD = 60_000

        /** §7.2 fallback chunk size (~30k chars at paragraph boundaries). */
        const val FALLBACK_CHUNK = 30_000

        private val STRONG_HEADINGS = listOf(
            Regex("^#{1,3}\\s+.*"), // markdown-ish exports
            Regex("^chapter\\s+\\d+\\b.{0,70}$", RegexOption.IGNORE_CASE),
            Regex("^제\\s*[\\d一二三四五六七八九十]+\\s*장.{0,60}$"),
            // Chinese numerals are as common as Arabic ones in 第X章 headings.
            Regex("^第\\s*[\\d一二三四五六七八九十百千零]+\\s*[章回节節卷].{0,60}$"),
            Regex("^\\d{1,5}\\s*화\\b.{0,60}$") // Korean episode style: 12화
        )
        private val GENERIC_HEADINGS = listOf(
            Regex("^(episode|part|book)\\s+\\d+\\b.{0,60}$", RegexOption.IGNORE_CASE),
            Regex("^\\d{1,5}\\s*[.、)）]\\s*.{1,60}$"),
            Regex("^\\d{1,5}\\s+.{1,60}$")
        )

        /**
         * The heading text if [line] starts a chapter, else null. Strong
         * patterns match anywhere; generic numbered lines only at a
         * paragraph boundary (a numbered list item mid-chapter must not
         * split the book).
         */
        fun detectHeading(line: String, atBoundary: Boolean): String? {
            if (line.isEmpty() || line.length > 90) return null
            for (pattern in STRONG_HEADINGS) {
                if (pattern.matches(line)) return line
            }
            if (atBoundary) {
                for (pattern in GENERIC_HEADINGS) {
                    if (pattern.matches(line)) return line
                }
            }
            return null
        }
    }
}
