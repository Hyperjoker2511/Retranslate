package app.retranslate.domain.translate

import app.retranslate.core.error.AppResult
import app.retranslate.data.db.BookEntity
import app.retranslate.data.db.ChapterEntity
import app.retranslate.data.files.BookFiles
import app.retranslate.domain.books.DirectTxRunner
import app.retranslate.domain.books.FakeChapterDao
import app.retranslate.domain.books.FakeKnowledgeBaseDao
import app.retranslate.domain.kb.KbRepository
import app.retranslate.providers.QualityMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * v1.1.3 (§A1/§A3, D8): the two ways Improve silently did nothing in
 * v1.1.2 — a chapter over the old 30,000-char single-shot cap was
 * REFUSED, and an imported-MTL chapter (no source text) never got a
 * quality score. Both are fixed by the chunked core + local scoring.
 */
class ChunkedImproveTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** Echoes the block back unchanged — a polish that passes checks. */
    private class EchoAi : PolishAi {
        var calls = 0
        override fun isConfigured(): Boolean = true
        override suspend fun complete(
            systemPrompt: String,
            userPrompt: String,
            targetLang: String,
            quality: QualityMode
        ): AppResult<String> {
            calls++
            return AppResult.Ok(
                userPrompt.removePrefix("Polish this chapter:\n\n")
            )
        }
    }

    private val chapterDao = FakeChapterDao()
    private val ai = EchoAi()

    private fun polisher() = ChapterPolisher(
        chapterDao = chapterDao,
        kbRepository = KbRepository(
            FakeKnowledgeBaseDao(), chapterDao, DirectTxRunner
        ),
        snapshots = ChapterSnapshots(
            chapterDao, BookFiles(File(temp.root, "books"))
        ),
        ai = ai,
        tx = DirectTxRunner
    )

    private val book = BookEntity(
        id = 1, titleOriginal = "원본", sourceLang = "ko", targetLang = "en",
        createdAt = 1, updatedAt = 1
    )

    @Test
    fun `a chapter over the old cap improves block by block`() = runTest {
        // ~320 paragraphs, well over the old 30,000-char refusal.
        val paragraph = "Jin Woo walked through the silent hall and " +
            "looked at the rain falling over the quiet village below."
        val text = (1..320).joinToString("\n\n") { paragraph }
        assertTrue(text.length > 30_000)
        val chapter = ChapterEntity(
            bookId = 1, number = 1, sourceText = "원문",
            translatedText = text, status = "TRANSLATED", updatedAt = 1
        )
        val seeded = chapterDao.getChapter(chapterDao.insert(chapter))!!

        val result = polisher().polish(book, seeded, snapshotFirst = false)

        assertTrue("expected Applied, was $result", 
            result is AppResult.Ok && result.value is PolishResult.Applied)
        assertTrue("chunked core must make several calls", ai.calls > 1)
        assertNotNull(chapterDao.getChapter(seeded.id)!!.improvedAt)
    }

    @Test
    fun `an imported MTL chapter gains a quality score when improved`() = runTest {
        // IMPORTED_TRANSLATION shape: translation only, score null.
        val text = "Jin Woo drew his blade.\n\n" +
            "The hall fell silent as he stepped forward.\n\n" +
            "Outside, the rain kept falling over the village."
        val chapter = ChapterEntity(
            bookId = 1, number = 1, sourceText = null,
            translatedText = text, status = "TRANSLATED",
            origin = "IMPORTED_TRANSLATION", qualityScore = null, updatedAt = 1
        )
        val seeded = chapterDao.getChapter(chapterDao.insert(chapter))!!

        val result = polisher().polish(book, seeded, snapshotFirst = false)

        assertTrue(result is AppResult.Ok)
        val after = chapterDao.getChapter(seeded.id)!!
        assertNotNull("imported chapters must be scored", after.qualityScore)
        assertTrue(after.qualityScore!! in 0f..100f)
        assertNotNull(after.improvedAt)
    }
}
