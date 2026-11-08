package app.retranslate.domain.books

import app.retranslate.core.error.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset

/**
 * TXT import (PLAN.md §7.2/§7.3): charset-chosen decoding, heading
 * splitting in three scripts, fallback chunking, and the 120 MB
 * streaming guarantee — chapter files on disk, sizes bounded, nothing
 * materialises a whole book in memory.
 */
class TxtBookParserTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun parse(file: File): ParsedBook {
        val workDir = temp.newFolder()
        return when (val result = TxtBookParser().parse(file, workDir) {}) {
            is AppResult.Ok -> result.value
            is AppResult.Err -> throw AssertionError("parse failed: ${result.error}")
        }
    }

    private fun chapterText(chapter: ParsedChapter): String =
        chapter.file.readText(Charsets.UTF_8)

    @Test
    fun `English headings split chapters`() {
        val file = temp.newFile("novel.txt")
        file.writeText(
            "Chapter 1 The Beginning\nHe woke up early.\n\nThe sun was up.\n\n" +
                "Chapter 2 The Journey\nHe packed his bag.\n\nHe left the village.\n",
            Charsets.UTF_8
        )
        val book = parse(file)
        assertEquals(2, book.chapters.size)
        assertEquals("Chapter 1 The Beginning", book.chapters[0].title)
        assertEquals("Chapter 2 The Journey", book.chapters[1].title)
        assertTrue(chapterText(book.chapters[0]).contains("He woke up early."))
        assertTrue(chapterText(book.chapters[1]).contains("He packed his bag."))
    }

    @Test
    fun `Korean EUC-KR file decodes and splits on hangul headings`() {
        val korean = buildString {
            append("제1장 시작\n그는 천천히 눈을 떴다. 마을은 조용했다.\n\n")
            append("제2장 여정\n그는 가방을 챙겼다. 그리고 마을을 떠났다.\n")
        }
        val file = temp.newFile("korean.txt")
        file.writeText(korean, Charset.forName("EUC-KR"))
        val book = parse(file)
        assertEquals(2, book.chapters.size)
        assertEquals("제1장 시작", book.chapters[0].title)
        assertTrue(chapterText(book.chapters[0]).contains("눈을 떴다"))
        assertTrue(chapterText(book.chapters[1]).contains("마을을 떠났다"))
    }

    @Test
    fun `Chinese GBK file decodes and splits on chapter headings`() {
        val chinese = buildString {
            append("第一章 开始\n他缓缓睁开眼睛。村庄很安静。\n\n")
            append("第二章 旅程\n他收拾好行囊。然后离开了村庄。\n")
        }
        val file = temp.newFile("chinese.txt")
        file.writeText(chinese, Charset.forName("GB18030"))
        val book = parse(file)
        assertEquals(2, book.chapters.size)
        assertEquals("第一章 开始", book.chapters[0].title)
        assertTrue(chapterText(book.chapters[0]).contains("睁开眼睛"))
        assertTrue(chapterText(book.chapters[1]).contains("离开了村庄"))
    }

    @Test
    fun `wrapped lines join into paragraphs, CJK without spaces`() {
        val file = temp.newFile("wrapped.txt")
        file.writeText(
            "Chapter 1\n" +
                "그는 천천히 눈을\n떴다. 마을은\n조용했다.\n\n" +
                "다음 문단이다.\n",
            Charsets.UTF_8
        )
        val book = parse(file)
        assertEquals(1, book.chapters.size)
        val text = chapterText(book.chapters[0])
        assertTrue(text.contains("그는 천천히 눈을떴다"))
        assertTrue(text.contains("다음 문단이다."))
    }

    @Test
    fun `no headings falls back to 30k paragraph chunks`() {
        val paragraph = "This is a paragraph of the story that keeps going on. ".repeat(4) + "\n\n"
        val file = temp.newFile("noheadings.txt")
        file.writeText(paragraph.repeat(500), Charsets.UTF_8) // ~110k chars, no headings
        val book = parse(file)
        assertTrue("expected chunking, got ${book.chapters.size}", book.chapters.size >= 3)
        for (chapter in book.chapters) {
            assertTrue(
                "chunk too big: ${chapter.charCount}",
                chapter.charCount <= ChapterSink.FALLBACK_CHUNK + 1_000
            )
        }
        val total = book.chapters.sumOf { it.charCount }
        assertTrue("lost text: $total", total > 100_000)
    }

    @Test
    fun `numbered list lines mid-paragraph do not split chapters`() {
        val file = temp.newFile("list.txt")
        file.writeText(
            "Chapter 1\nHe made a list:\n1. Sword\n2. Rope\n3. Bread\n\nThen he left.\n",
            Charsets.UTF_8
        )
        val book = parse(file)
        assertEquals(1, book.chapters.size)
    }

    @Test
    fun `single huge chapter is cut at the stream ceiling`() {
        val line = "A very long chapter with no headings at all, just prose. ".repeat(3)
        val file = temp.newFile("huge.txt")
        // ~450k chars under one heading.
        file.writeText("Chapter 1\n" + (line + "\n\n").repeat(2_600), Charsets.UTF_8)
        val book = parse(file)
        assertTrue(book.chapters.size >= 2)
        for (chapter in book.chapters) {
            assertTrue(
                "chapter over ceiling: ${chapter.charCount}",
                chapter.charCount <= ChapterSink.MAX_STREAM_CHARS + 40_000
            )
        }
    }

    /**
     * The §7.3 headline test: a ~120 MB TXT streams end-to-end. Asserts
     * the observable shape of streaming — many bounded chapter files,
     * all text accounted for — rather than a fragile heap measurement.
     * Scratch space lives in the module build dir, NOT /tmp (a 512 MB
     * tmpfs in this sandbox).
     */
    @Test(timeout = 600_000)
    fun `120 MB TXT streams with flat bounded chapters`() {
        val scratch = File(System.getProperty("user.dir"), "build/big120-test")
        scratch.deleteRecursively()
        scratch.mkdirs()
        try {
            runBigFileScenario(scratch)
        } finally {
            scratch.deleteRecursively()
        }
    }

    private fun runBigFileScenario(scratch: File) {
        val file = File(scratch, "big120.txt")
        val block = buildString {
            append("Chapter prose line: the hero walked the long road under a grey sky, ".repeat(8))
            append("\n")
        }
        val blockBytes = block.toByteArray(Charsets.UTF_8)
        val targetBytes = 120L * 1024 * 1024
        file.outputStream().buffered().use { out ->
            var written = 0L
            var chapter = 0
            while (written < targetBytes) {
                chapter++
                val heading = "Chapter $chapter\n".toByteArray(Charsets.UTF_8)
                out.write(heading)
                written += heading.size
                // ~64 KB of prose per chapter
                repeat(110) {
                    out.write(blockBytes)
                    written += blockBytes.size
                }
            }
        }
        assertTrue(file.length() >= targetBytes)

        val workDir = File(scratch, "work").apply { mkdirs() }
        val book = when (val result = TxtBookParser().parse(file, workDir) {}) {
            is AppResult.Ok -> result.value
            is AppResult.Err -> throw AssertionError("parse failed: ${result.error}")
        }
        assertTrue("chapters: ${book.chapters.size}", book.chapters.size > 1_500)
        var totalChars = 0L
        for (chapter in book.chapters) {
            assertTrue(
                "chapter file too large: ${chapter.charCount}",
                chapter.charCount <= ChapterSink.MAX_STREAM_CHARS + 40_000
            )
            totalChars += chapter.charCount
        }
        // All prose survived (headings' own text becomes titles, so the
        // body total is a little under the file's char count).
        assertTrue("total chars $totalChars", totalChars > targetBytes * 0.9)
    }

    @Test
    fun `empty file parses to no chapters, planner refuses it later`() {
        val file = temp.newFile("empty.txt")
        file.writeText("", Charsets.UTF_8)
        val book = parse(file)
        assertEquals(0, book.chapters.size)
    }
}
