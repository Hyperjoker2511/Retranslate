package app.retranslate.domain.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLAN.md §5.1/§12.1: chunk boundaries + byte-exact reassembly. */
class TextChunkerTest {

    @Test
    fun `small text is one chunk, unchanged`() {
        val text = "첫 번째 단락입니다.\n\n두 번째 단락입니다."
        assertEquals(listOf(text), TextChunker.chunk(text))
    }

    @Test
    fun `empty text chunks to nothing`() {
        assertEquals(emptyList<String>(), TextChunker.chunk(""))
    }

    @Test
    fun `reassembly is byte-exact for paragraph text`() {
        val paragraphs = (1..40).map { n -> "단락 $n. " + "가나다라마바사 ".repeat(120) }
        val text = paragraphs.joinToString("\n\n")
        val chunks = TextChunker.chunk(text)
        assertTrue("expected multiple chunks", chunks.size > 1)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= TextChunker.MAX_CHARS })
    }

    @Test
    fun `chunk boundaries fall on paragraph breaks when possible`() {
        // 10 paragraphs of ~2000 chars each: chunks must end mid-text
        // only at paragraph separators (i.e. chunk text ends with the
        // separator or at the very end of input).
        val paragraphs = (1..10).map { "P$it " + "x".repeat(1_990) }
        val text = paragraphs.joinToString("\n\n")
        val chunks = TextChunker.chunk(text)
        assertEquals(text, chunks.joinToString(""))
        chunks.dropLast(1).forEach { chunk ->
            assertTrue("chunk should end at a paragraph break", chunk.endsWith("\n\n"))
        }
    }

    @Test
    fun `one giant paragraph is sliced under the hard max and stays exact`() {
        val text = "word ".repeat(6_000) // 30,000 chars, no paragraph breaks
        val chunks = TextChunker.chunk(text)
        assertTrue(chunks.size >= 4)
        assertTrue(chunks.all { it.length <= TextChunker.MAX_CHARS })
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun `one giant CJK paragraph without spaces still slices exactly`() {
        val text = "가".repeat(25_000)
        val chunks = TextChunker.chunk(text)
        assertTrue(chunks.all { it.length <= TextChunker.MAX_CHARS })
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun `text just over target stays within hard max`() {
        val text = "a".repeat(TextChunker.TARGET_CHARS + 500)
        val chunks = TextChunker.chunk(text)
        assertTrue(chunks.all { it.length <= TextChunker.MAX_CHARS })
        assertEquals(text, chunks.joinToString(""))
    }
}
