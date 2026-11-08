package app.retranslate.domain.translate

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.data.db.BookEntity
import app.retranslate.data.db.ChapterEntity
import app.retranslate.data.db.KbEntryEntity
import app.retranslate.data.db.KnowledgeBaseEntity
import app.retranslate.data.files.BookFiles
import app.retranslate.domain.books.DirectTxRunner
import app.retranslate.domain.books.FakeChapterDao
import app.retranslate.domain.books.FakeKnowledgeBaseDao
import app.retranslate.domain.kb.KbRepository
import app.retranslate.providers.QualityMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * PLAN.md §5.2/§9.3: the single-chapter polish — applied only when it
 * provably keeps the Knowledge Base's locked terms and the chapter's
 * shape; otherwise the original stands and the reason is returned.
 */
class ChapterPolisherTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class FakePolishAi(
        var configured: Boolean = true,
        var output: String = ""
    ) : PolishAi {
        var calls = 0
        override fun isConfigured(): Boolean = configured
        override suspend fun complete(
            systemPrompt: String,
            userPrompt: String,
            targetLang: String,
            quality: QualityMode
        ): AppResult<String> {
            calls++
            return AppResult.Ok(output)
        }
    }

    private val chapterDao = FakeChapterDao()
    private val kbDao = FakeKnowledgeBaseDao()
    private val ai = FakePolishAi()

    private fun bookFiles() = BookFiles(File(temp.root, "books"))

    private fun polisher() = ChapterPolisher(
        chapterDao = chapterDao,
        kbRepository = KbRepository(kbDao, chapterDao, DirectTxRunner),
        snapshots = ChapterSnapshots(chapterDao, bookFiles()),
        ai = ai,
        tx = DirectTxRunner
    )

    private val book = BookEntity(
        id = 1, titleOriginal = "원본", sourceLang = "ko", targetLang = "en",
        createdAt = 1, updatedAt = 1
    )

    private val original = "Jin Woo drew his blade.\n\n" +
        "The hall fell silent as he stepped forward.\n\n" +
        "\"You came,\" the elder said.\n\n" +
        "Jin Woo nodded once.\n\n" +
        "Outside, the rain kept falling over the quiet village."

    private suspend fun seedChapter(withKb: Boolean = true): ChapterEntity {
        val chapter = ChapterEntity(
            bookId = 1, number = 3, sourceText = "진우 이야기",
            translatedText = original, status = "TRANSLATED",
            qualityScore = 70f, updatedAt = 1
        )
        val id = chapterDao.insert(chapter)
        if (withKb) {
            val kbId = kbDao.insertKb(
                KnowledgeBaseEntity(bookId = 1, version = 1, createdAt = 1)
            )
            kbDao.insertEntries(
                listOf(
                    KbEntryEntity(
                        kbId = kbId, category = "NAME", sourceForm = "진우",
                        canonicalForm = "Jin Woo", locked = true, frequency = 30
                    )
                )
            )
        }
        return chapter.copy(id = id)
    }

    @Test
    fun `good polish is applied and snapshotted for undo`() = runTest {
        val chapter = seedChapter()
        val polished = original.replace("drew his blade", "drew his blade slowly")
        ai.output = polished
        val result = polisher().polish(book, chapter)
        assertEquals(PolishResult.Applied, (result as AppResult.Ok).value)
        assertEquals(polished, chapterDao.getChapter(chapter.id)!!.translatedText)

        // Undo restores the pre-polish text byte-identically.
        val snapshots = ChapterSnapshots(chapterDao, bookFiles())
        val undone = snapshots.undo(book.id)
        assertEquals(3, (undone as AppResult.Ok).value)
        assertEquals(original, chapterDao.getChapter(chapter.id)!!.translatedText)
    }

    @Test
    fun `polish that renames a locked character is rejected`() = runTest {
        val chapter = seedChapter()
        ai.output = original.replace("Jin Woo", "Jinwoo")
        val result = polisher().polish(book, chapter)
        val outcome = (result as AppResult.Ok).value
        assertTrue(outcome is PolishResult.Rejected)
        assertTrue((outcome as PolishResult.Rejected).reason.contains("Jin Woo"))
        assertEquals(original, chapterDao.getChapter(chapter.id)!!.translatedText)
    }

    @Test
    fun `polish that adds a variant alongside the locked name is rejected`() = runTest {
        val chapter = seedChapter()
        ai.output = original + "\n\nJinwoo smiled in the doorway."
        val result = polisher().polish(book, chapter)
        val outcome = (result as AppResult.Ok).value
        assertTrue(outcome is PolishResult.Rejected)
        assertEquals(original, chapterDao.getChapter(chapter.id)!!.translatedText)
    }

    @Test
    fun `polish that drops paragraphs is rejected as drift`() = runTest {
        val chapter = seedChapter()
        ai.output = "Jin Woo drew his blade.\n\nThe hall fell silent.\n\nRain fell."
        val result = polisher().polish(book, chapter)
        val outcome = (result as AppResult.Ok).value
        assertTrue(outcome is PolishResult.Rejected)
        assertTrue((outcome as PolishResult.Rejected).reason.contains("paragraphs"))
    }

    @Test
    fun `without a Gemini key polish fails before any call`() = runTest {
        val chapter = seedChapter()
        ai.configured = false
        val result = polisher().polish(book, chapter)
        val err = (result as AppResult.Err).error
        assertTrue(err is AppError.AuthFailed)
        assertEquals(0, ai.calls)
    }

    @Test
    fun `an untranslated chapter cannot be polished`() = runTest {
        val id = chapterDao.insert(
            ChapterEntity(bookId = 1, number = 1, sourceText = "원본", updatedAt = 1)
        )
        val result = polisher().polish(book, chapterDao.getChapter(id)!!)
        assertTrue((result as AppResult.Err).error is AppError.VerificationFailed)
        assertEquals(0, ai.calls)
    }

    @Test
    fun `polish without a KB still applies when the shape holds`() = runTest {
        val chapter = seedChapter(withKb = false)
        ai.output = original.replace("fell silent", "went quiet")
        val result = polisher().polish(book, chapter)
        assertEquals(PolishResult.Applied, (result as AppResult.Ok).value)
    }
}
