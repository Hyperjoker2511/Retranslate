package app.retranslate.domain.kb

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.data.db.BookEntity
import app.retranslate.data.db.JobRunEntity
import app.retranslate.data.db.SampleChapterEntity
import app.retranslate.domain.books.DirectTxRunner
import app.retranslate.domain.books.FakeBookDao
import app.retranslate.domain.books.FakeChapterDao
import app.retranslate.domain.books.FakeJobRunDao
import app.retranslate.domain.books.FakeKnowledgeBaseDao
import app.retranslate.domain.books.FakeSampleDao
import app.retranslate.providers.QualityMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scripted [StudyAi]: answers queued responses, records every call. */
private class FakeStudyAi(
    var configured: Boolean = true,
    responses: List<AppResult<String>>
) : StudyAi {
    private val queue = ArrayDeque(responses)
    val calls = mutableListOf<String>()

    override fun isConfigured(): Boolean = configured

    override suspend fun complete(
        systemPrompt: String,
        userPrompt: String,
        targetLang: String,
        quality: QualityMode
    ): AppResult<String> {
        calls += userPrompt
        return if (queue.isEmpty()) {
            AppResult.Err(AppError.AiBadOutput("GEMINI"))
        } else {
            queue.removeFirst()
        }
    }
}

/**
 * PLAN.md §6.2/§12 flow 13: study → KB, the repair retry, and the
 * "nothing is written on failure" guarantee.
 */
class StudyEngineTest {

    private val bookDao = FakeBookDao()
    private val sampleDao = FakeSampleDao()
    private val jobRunDao = FakeJobRunDao()
    private val kbDao = FakeKnowledgeBaseDao()
    private val repo = KbRepository(kbDao, FakeChapterDao(), DirectTxRunner) { 9_000L }

    private val goodJson = """
        {"entries":[
          {"category":"CHARACTER","sourceForm":"진우","canonicalForm":"Jin Woo",
           "gender":"MALE","note":"referred to as 'he'","frequency":6},
          {"category":"TONE_RULE","sourceForm":null,
           "canonicalForm":"Short, direct sentences; dialogue sounds spoken.",
           "gender":null,"note":null,"frequency":6}
        ]}
    """.trimIndent()

    private suspend fun setup(): Pair<Long, Long> {
        val bookId = bookDao.insert(
            BookEntity(
                titleOriginal = "소설", sourceLang = "ko", targetLang = "en",
                createdAt = 0, updatedAt = 0
            )
        )
        val setId = sampleDao.insertSet(
            app.retranslate.data.db.SampleSetEntity(
                bookId = bookId, fileName = "good-chapters.txt",
                detectedLang = "en", chapterCount = 3
            )
        )
        sampleDao.insertChapters(
            (1..3).map { n ->
                SampleChapterEntity(
                    sampleSetId = setId, number = n,
                    text = "Chapter $n. Jin Woo trained at dawn, his sword humming with qi."
                )
            }
        )
        val runId = jobRunDao.insert(
            JobRunEntity(
                bookId = bookId, kind = "STUDY", state = "QUEUED", startedAt = 0
            )
        )
        return bookId to runId
    }

    private fun selection(setId: Long) = StudySelection(
        listOf(StudySelection.SetRange(setId, 1, 3))
    )

    private fun engine(ai: StudyAi) =
        StudyEngine(bookDao, sampleDao, jobRunDao, repo, ai) { 9_000L }

    @Test
    fun `study builds the KB, finishes the run and marks the set studied`() = runTest {
        val (bookId, runId) = setup()
        val setId = sampleDao.getSets(bookId).first().id
        val ai = FakeStudyAi(responses = listOf(AppResult.Ok(goodJson)))
        val result = engine(ai).run(bookId, runId, selection(setId))
        val finished = result as StudyResult.Finished
        assertEquals(2, finished.entries)
        assertEquals(3, finished.chapters)
        assertEquals(1, finished.kbVersion)
        assertEquals(1, ai.calls.size)
        assertTrue(ai.calls[0].contains("Jin Woo trained at dawn"))

        val entries = repo.activeEntries(bookId)
        assertEquals(setOf("Jin Woo", "Short, direct sentences; dialogue sounds spoken."),
            entries.map { it.canonicalForm }.toSet())
        val run = jobRunDao.getById(runId)!!
        assertEquals("FINISHED", run.state)
        assertEquals(3, run.doneChapters)
        assertEquals(1, sampleDao.getSets(bookId).first().studiedInKbVersion)
    }

    @Test
    fun `garbled first answer triggers exactly one repair retry`() = runTest {
        val (bookId, runId) = setup()
        val setId = sampleDao.getSets(bookId).first().id
        val ai = FakeStudyAi(
            responses = listOf(
                AppResult.Ok("Sure! The names are: Jin Woo and…"),
                AppResult.Ok(goodJson)
            )
        )
        val result = engine(ai).run(bookId, runId, selection(setId))
        assertTrue(result is StudyResult.Finished)
        assertEquals(2, ai.calls.size)
        assertTrue(ai.calls[1].contains("not valid JSON")) // the repair prompt
        assertEquals(2, repo.activeEntries(bookId).size)
    }

    @Test
    fun `garbage twice fails the run and writes NO Knowledge Base`() = runTest {
        val (bookId, runId) = setup()
        val setId = sampleDao.getSets(bookId).first().id
        val ai = FakeStudyAi(
            responses = listOf(
                AppResult.Ok("not json at all"),
                AppResult.Ok("{\"entries\":[{\"category\":\"CHARACTER\"") // truncated
            )
        )
        val result = engine(ai).run(bookId, runId, selection(setId))
        val failed = result as StudyResult.Failed
        assertTrue(failed.error is AppError.AiBadOutput)
        assertTrue(repo.activeEntries(bookId).isEmpty())
        assertEquals(null, repo.activeKb(bookId))
        val run = jobRunDao.getById(runId)!!
        assertEquals("FAILED", run.state)
        assertEquals("AiBadOutput", run.lastErrorClass)
    }

    @Test
    fun `without a key the run fails in words and the AI is never called`() = runTest {
        val (bookId, runId) = setup()
        val setId = sampleDao.getSets(bookId).first().id
        val ai = FakeStudyAi(configured = false, responses = emptyList())
        val result = engine(ai).run(bookId, runId, selection(setId))
        val failed = result as StudyResult.Failed
        assertTrue(failed.error is AppError.AuthFailed)
        assertEquals(0, ai.calls.size)
        assertEquals("FAILED", jobRunDao.getById(runId)!!.state)
    }

    @Test
    fun `provider failure lands as a failed run with nothing written`() = runTest {
        val (bookId, runId) = setup()
        val setId = sampleDao.getSets(bookId).first().id
        val ai = FakeStudyAi(
            responses = listOf(AppResult.Err(AppError.RateLimited("GEMINI", 30)))
        )
        val result = engine(ai).run(bookId, runId, selection(setId))
        assertTrue((result as StudyResult.Failed).error is AppError.RateLimited)
        assertEquals(null, repo.activeKb(bookId))
        assertEquals("RateLimited", jobRunDao.getById(runId)!!.lastErrorClass)
    }

    @Test
    fun `a cancelled run writes nothing`() = runTest {
        val (bookId, runId) = setup()
        val setId = sampleDao.getSets(bookId).first().id
        jobRunDao.update(jobRunDao.getById(runId)!!.copy(state = "CANCELLED"))
        val ai = FakeStudyAi(responses = listOf(AppResult.Ok(goodJson)))
        val result = engine(ai).run(bookId, runId, selection(setId))
        assertTrue(result is StudyResult.Cancelled)
        assertEquals(null, repo.activeKb(bookId))
    }

    @Test
    fun `only the chosen chapter range is studied`() = runTest {
        val (bookId, runId) = setup()
        val setId = sampleDao.getSets(bookId).first().id
        val ai = FakeStudyAi(responses = listOf(AppResult.Ok(goodJson)))
        val result = engine(ai).run(
            bookId, runId,
            StudySelection(listOf(StudySelection.SetRange(setId, 2, 2)))
        )
        assertEquals(1, (result as StudyResult.Finished).chapters)
        assertTrue(ai.calls[0].contains("Chapter 2."))
        assertTrue(!ai.calls[0].contains("Chapter 1."))
    }
}
