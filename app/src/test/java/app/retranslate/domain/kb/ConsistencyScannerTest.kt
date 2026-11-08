package app.retranslate.domain.kb

import app.retranslate.data.db.ChapterEntity
import app.retranslate.domain.books.DirectTxRunner
import app.retranslate.domain.books.FakeChapterDao
import app.retranslate.domain.books.FakeKnowledgeBaseDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.1.3 (§G): the free book-wide Consistency check — every
 * translated chapter scanned against the Knowledge Base with the
 * existing compliance engine; worst chapters first, variant totals
 * counted across chapters; honest "no KB" when there is nothing to
 * check against.
 */
class ConsistencyScannerTest {

    private val chapterDao = FakeChapterDao()
    private val kbRepository = KbRepository(
        FakeKnowledgeBaseDao(), chapterDao, DirectTxRunner
    )
    private val scanner = ConsistencyScanner(chapterDao, kbRepository)

    private suspend fun seedChapter(number: Int, text: String) {
        chapterDao.insert(
            ChapterEntity(
                bookId = 1, number = number, title = "Chapter $number",
                sourceText = "원문", translatedText = text,
                status = "TRANSLATED", updatedAt = 1
            )
        )
    }

    @Test
    fun `no knowledge base is reported honestly`() = runTest {
        seedChapter(1, "Jinwoo drew his blade.")
        val report = scanner.scan(1)
        assertFalse(report.hasKnowledgeBase)
        assertEquals(0, report.totalViolations)
    }

    @Test
    fun `violations are totalled and chapters ranked worst first`() = runTest {
        kbRepository.createFromStudy(
            1,
            listOf(
                KbEntryDraft(
                    category = KbCategory.CHARACTER, sourceForm = null,
                    canonicalForm = "Jin Woo", gender = null,
                    note = null, frequency = 10
                )
            ),
            "test"
        )
        seedChapter(1, "Jinwoo drew his blade. Jinwoo smiled.")
        seedChapter(2, "Jin Woo was ready. Jinwoo nodded.")
        seedChapter(3, "Jin Woo stood alone in the hall.")

        val report = scanner.scan(1)

        assertTrue(report.hasKnowledgeBase)
        assertEquals(3, report.chaptersScanned)
        assertEquals(2, report.chaptersWithViolations)
        assertEquals(3, report.totalViolations)
        // Worst first: chapter 1 (2 violations) before chapter 2 (1).
        assertEquals(listOf(1, 2), report.worstChapters.map { it.number })
        val variant = report.variants.single()
        assertEquals("Jin Woo", variant.canonicalForm)
        assertEquals("Jinwoo", variant.variantFound)
        assertEquals(3, variant.occurrences)
        assertEquals(2, variant.chapterCount)
    }
}
