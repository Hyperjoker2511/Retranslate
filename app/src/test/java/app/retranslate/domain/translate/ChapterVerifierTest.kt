package app.retranslate.domain.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLAN.md §5.2/§10 #12: local sanity checks + the quality score. */
class ChapterVerifierTest {

    private val source = (1..6).joinToString("\n\n") { "한국어 단락 $it 입니다. 내용이 있습니다." }

    @Test
    fun `clean translation passes`() {
        val translated = (1..6).joinToString("\n\n") { "English paragraph $it with real content." }
        assertTrue(ChapterVerifier.chunkIssues(source, translated, "ko", "en").isEmpty())
    }

    @Test
    fun `empty translation fails`() {
        val issues = ChapterVerifier.chunkIssues(source, "  ", "ko", "en")
        assertEquals(1, issues.size)
    }

    @Test
    fun `leftover source script fails the chunk`() {
        // Provider returned the Korean basically untranslated.
        val issues = ChapterVerifier.chunkIssues(source, source, "ko", "en")
        assertTrue(issues.any { it.contains("leftover") })
    }

    @Test
    fun `a few borrowed words do not fail the chunk`() {
        val mostlyEnglish = (1..6).joinToString("\n\n") {
            "English paragraph $it — the sect called 무림 was far away, long ago."
        }
        assertTrue(ChapterVerifier.chunkIssues(source, mostlyEnglish, "ko", "en").isEmpty())
    }

    @Test
    fun `dropped paragraphs fail the chunk`() {
        val twoOnly = (1..2).joinToString("\n\n") { "English paragraph $it." }
        val issues = ChapterVerifier.chunkIssues(source, twoOnly, "ko", "en")
        assertTrue(issues.any { it.contains("paragraphs changed") })
    }

    @Test
    fun `score is high for a faithful translation and low for leftovers`() {
        val good = (1..6).joinToString("\n\n") { "English paragraph $it with real content." }
        val goodScore = ChapterVerifier.qualityScore(source, good, "ko", "en")
        assertTrue("good score was $goodScore", goodScore >= 90f)
        val badScore = ChapterVerifier.qualityScore(source, source, "ko", "en")
        assertTrue("bad score was $badScore", badScore < 50f)
    }

    @Test
    fun `same-script pairs never report leftovers`() {
        // zh -> zh-TW legitimately keeps Han characters.
        val han = "这是第一章的内容，故事开始了。"
        assertEquals(
            0.0,
            ChapterVerifier.leftoverFraction(han.repeat(10), "zh-CN", "zh-TW"),
            0.001
        )
    }
}
