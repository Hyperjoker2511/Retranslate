package app.retranslate.domain.jobs

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.data.db.BookEntity
import app.retranslate.data.db.ChapterEntity
import app.retranslate.data.db.ChapterImproveHead
import app.retranslate.data.db.JobKind
import app.retranslate.data.db.JobRunEntity
import app.retranslate.data.db.JobState
import app.retranslate.data.files.BookFiles
import app.retranslate.domain.books.DirectTxRunner
import app.retranslate.domain.books.FakeBookDao
import app.retranslate.domain.books.FakeChapterDao
import app.retranslate.domain.books.FakeJobRunDao
import app.retranslate.domain.books.FakeKnowledgeBaseDao
import app.retranslate.domain.kb.KbRepository
import app.retranslate.domain.translate.ChapterOriginalArchive
import app.retranslate.domain.translate.ChapterPolisher
import app.retranslate.domain.translate.ChapterSnapshots
import app.retranslate.domain.translate.ImproveStrength
import app.retranslate.domain.translate.PolishAi
import app.retranslate.domain.translate.PolishScope
import app.retranslate.domain.translate.PolishScopeFilter
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
 * v1.1.3 (§A2/§G): the bulk-Improve engine — scope filtering, the
 * three durable counters, quota as a pause (never a failure), and
 * the blocker-grade rule: a bulk improve NEVER destroys an original
 * (every chapter's pre-improve text is in the per-chapter archive).
 */
class BookPolishEngineTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** A PolishAi whose behaviour the test scripts per call. */
    private class ScriptedAi(
        var handler: (call: Int, userPrompt: String) -> AppResult<String>
    ) : PolishAi {
        var calls = 0
        override fun isConfigured(): Boolean = true
        override suspend fun complete(
            systemPrompt: String,
            userPrompt: String,
            targetLang: String,
            quality: QualityMode
        ): AppResult<String> {
            calls++
            return handler(calls, userPrompt)
        }
    }

    private val bookDao = FakeBookDao()
    private val chapterDao = FakeChapterDao()
    private val jobRunDao = FakeJobRunDao()

    private fun bookFiles() = BookFiles(File(temp.root, "books-$bookCounter"))
    private var bookCounter = 0

    private fun engine(ai: ScriptedAi): BookPolishEngine {
        val files = bookFiles()
        val polisher = ChapterPolisher(
            chapterDao = chapterDao,
            kbRepository = KbRepository(
                FakeKnowledgeBaseDao(), chapterDao, DirectTxRunner
            ),
            snapshots = ChapterSnapshots(chapterDao, files),
            ai = ai,
            tx = DirectTxRunner,
            archive = ChapterOriginalArchive(chapterDao, files)
        )
        return BookPolishEngine(
            bookDao = bookDao,
            chapterDao = chapterDao,
            jobRunDao = jobRunDao,
            polisher = polisher,
            tx = DirectTxRunner,
            clock = { 5_000L }
        )
    }

    private val originalText = "Jin Woo drew his blade.\n\n" +
        "The hall fell silent as he stepped forward.\n\n" +
        "Outside, the rain kept falling over the village."

    private val improvedText = "Jin Woo drew his blade.\n\n" +
        "The hall fell silent as he stepped forward, breath held.\n\n" +
        "Outside, the rain kept falling over the quiet village."

    private suspend fun seedBook(chapterCount: Int): Pair<Long, Long> {
        bookCounter++
        val bookId = bookDao.insert(
            BookEntity(
                titleOriginal = "소설", sourceLang = "ko", targetLang = "en",
                createdAt = 1, updatedAt = 1
            )
        )
        chapterDao.insertAll(
            (1..chapterCount).map { n ->
                ChapterEntity(
                    bookId = bookId, number = n, title = "Chapter $n",
                    sourceText = "원문 $n", translatedText = originalText,
                    status = "TRANSLATED", qualityScore = 40f, updatedAt = 1
                )
            }
        )
        val runId = jobRunDao.insert(
            JobRunEntity(
                bookId = bookId, kind = JobKind.POLISH.name,
                state = JobState.QUEUED.name, startedAt = 100
            )
        )
        return bookId to runId
    }

    @Test
    fun `bulk improve applies, counts, and archives every original`() = runTest {
        val ai = ScriptedAi { _, _ -> AppResult.Ok(improvedText) }
        val (bookId, runId) = seedBook(3)

        val result = engine(ai).run(bookId, runId, PolishScope.ALL, ImproveStrength.GENTLE)

        assertTrue(result is EngineResult.Finished)
        val run = jobRunDao.getById(runId)!!
        assertEquals(JobState.FINISHED.name, run.state)
        assertEquals(3, run.polishApplied)
        assertEquals(0, run.polishRejected)
        assertEquals(0, run.failedChapters)
        // Every chapter improved AND still restorable (§G blocker).
        val archive = ChapterOriginalArchive(chapterDao, bookFiles())
        for (head in chapterDao.getImproveHeads(bookId)) {
            val chapter = chapterDao.getChapter(head.id)!!
            assertEquals(improvedText, chapter.translatedText)
            assertNotNull("improvedAt must be set", chapter.improvedAt)
            assertTrue(
                "original of chapter ${head.number} must be archived",
                archive.has(bookId, head.id)
            )
            assertEquals(originalText, archive.peek(bookId, head.id)?.translatedText)
        }
    }

    @Test
    fun `a rejected polish is counted, never silently kept`() = runTest {
        // One merged mega-paragraph: the verifier rejects the shape.
        val ai = ScriptedAi { _, _ -> AppResult.Ok("Everything merged into one block.") }
        val (bookId, runId) = seedBook(2)

        val result = engine(ai).run(bookId, runId, PolishScope.ALL, ImproveStrength.GENTLE)

        assertTrue(result is EngineResult.Finished)
        val run = jobRunDao.getById(runId)!!
        assertEquals(0, run.polishApplied)
        assertEquals(2, run.polishRejected)
        // Chapters unchanged.
        for (head in chapterDao.getImproveHeads(bookId)) {
            assertEquals(originalText, chapterDao.getChapter(head.id)!!.translatedText)
        }
    }

    @Test
    fun `rate limit parks the run PAUSED with counters intact`() = runTest {
        val ai = ScriptedAi { call, _ ->
            if (call == 1) {
                AppResult.Ok(improvedText)
            } else {
                AppResult.Err(AppError.RateLimited("GEMINI", retryAfterSeconds = 30))
            }
        }
        val (bookId, runId) = seedBook(3)

        val result = engine(ai).run(bookId, runId, PolishScope.ALL, ImproveStrength.GENTLE)

        assertEquals(EngineResult.PausedServersBusy, result)
        val run = jobRunDao.getById(runId)!!
        assertEquals(JobState.PAUSED.name, run.state)
        assertEquals(1, run.polishApplied)
        assertEquals("RateLimited", run.lastErrorClass)
    }

    @Test
    fun `scope filter reads quality, flags, kb version and offline marks`() {
        fun head(
            quality: Float? = null,
            provider: String? = null,
            improvedAt: Long? = null,
            kbVersion: Int? = null,
            id: Long = 1
        ) = ChapterImproveHead(
            id = id, number = 1, status = "TRANSLATED",
            qualityScore = quality, translatedByProvider = provider,
            improvedAt = improvedAt, kbVersionUsed = kbVersion
        )
        // ROUGH: below 50 — and un-scored chapters count as rough
        // (QualityLabel's rule: no label yet = needs improvement).
        assertTrue(PolishScopeFilter.includes(head(quality = 30f), PolishScope.ROUGH, emptySet(), null))
        assertTrue(!PolishScopeFilter.includes(head(quality = 80f), PolishScope.ROUGH, emptySet(), null))
        assertTrue(PolishScopeFilter.includes(head(quality = null), PolishScope.ROUGH, emptySet(), null))
        // NOT_YET_IMPROVED: never improved, or improved under an older KB.
        assertTrue(PolishScopeFilter.includes(head(), PolishScope.NOT_YET_IMPROVED, emptySet(), 2))
        assertTrue(PolishScopeFilter.includes(head(improvedAt = 9, kbVersion = 1), PolishScope.NOT_YET_IMPROVED, emptySet(), 2))
        assertTrue(!PolishScopeFilter.includes(head(improvedAt = 9, kbVersion = 2), PolishScope.NOT_YET_IMPROVED, emptySet(), 2))
        // OFFLINE_TRANSLATED: the provider mark decides.
        assertTrue(PolishScopeFilter.includes(head(provider = "OFFLINE"), PolishScope.OFFLINE_TRANSLATED, emptySet(), null))
        assertTrue(!PolishScopeFilter.includes(head(provider = "GEMINI"), PolishScope.OFFLINE_TRANSLATED, emptySet(), null))
        // FLAGGED: the flag set decides.
        assertTrue(PolishScopeFilter.includes(head(id = 7), PolishScope.FLAGGED, setOf(7), null))
        assertTrue(!PolishScopeFilter.includes(head(id = 8), PolishScope.FLAGGED, setOf(7), null))
    }
}
