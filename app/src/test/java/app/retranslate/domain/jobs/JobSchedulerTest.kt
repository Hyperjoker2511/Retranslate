package app.retranslate.domain.jobs

import app.retranslate.core.error.AppResult
import app.retranslate.data.db.BookEntity
import app.retranslate.data.db.ChapterEntity
import app.retranslate.data.db.JobState
import app.retranslate.domain.books.FakeBookDao
import app.retranslate.domain.books.FakeChapterDao
import app.retranslate.domain.books.FakeJobRunDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private data class EnqueueCall(val bookId: Long, val onlyChapterId: Long?, val replace: Boolean)

private class FakeEnqueuer : WorkEnqueuer {
    val enqueues = mutableListOf<EnqueueCall>()
    val cancels = mutableListOf<Long>()

    override fun enqueue(bookId: Long, jobRunId: Long, onlyChapterId: Long?, replace: Boolean) {
        enqueues += EnqueueCall(bookId, onlyChapterId, replace)
    }

    override fun cancelBook(bookId: Long) {
        cancels += bookId
    }
}

/** PLAN.md §7.4: unique-work decisions + the JobRun mirror's lifecycle. */
class JobSchedulerTest {

    private var tick = 0L
    private val bookDao = FakeBookDao()
    private val chapterDao = FakeChapterDao()
    private val jobRunDao = FakeJobRunDao()
    private val enqueuer = FakeEnqueuer()
    private val scheduler = JobScheduler(
        bookDao, chapterDao, jobRunDao, enqueuer,
        clock = { ++tick * 1_000L }
    )

    private suspend fun book(chapters: Int, translated: Int = 0): Long {
        val bookId = bookDao.insert(
            BookEntity(
                titleOriginal = "소설", sourceLang = "ko", targetLang = "en",
                createdAt = 0, updatedAt = 0
            )
        )
        chapterDao.insertAll(
            (1..chapters).map { n ->
                ChapterEntity(
                    bookId = bookId, number = n,
                    sourceText = "본문 $n",
                    status = if (n <= translated) "TRANSLATED" else "PENDING",
                    updatedAt = 0
                )
            }
        )
        return bookId
    }

    @Test
    fun `translateBook creates the run row and enqueues with KEEP`() = runTest {
        val bookId = book(chapters = 10, translated = 3)
        val result = scheduler.translateBook(bookId)
        val runId = (result as AppResult.Ok).value
        val run = jobRunDao.getById(runId)!!
        assertEquals("QUEUED", run.state)
        assertEquals(7, run.totalChapters) // only the untranslated count
        assertEquals(listOf(EnqueueCall(bookId, null, false)), enqueuer.enqueues)
    }

    @Test
    fun `second start while queued returns the same job, no duplicate`() = runTest {
        val bookId = book(chapters = 4)
        val first = (scheduler.translateBook(bookId) as AppResult.Ok).value
        val second = (scheduler.translateBook(bookId) as AppResult.Ok).value
        assertEquals(first, second)
        assertEquals(1, jobRunDao.runs.value.size)
        assertEquals(1, enqueuer.enqueues.size)
    }

    @Test
    fun `start on a paused job resumes it instead of duplicating`() = runTest {
        val bookId = book(chapters = 4)
        val runId = (scheduler.translateBook(bookId) as AppResult.Ok).value
        jobRunDao.update(jobRunDao.getById(runId)!!.copy(state = "PAUSED"))
        val again = (scheduler.translateBook(bookId) as AppResult.Ok).value
        assertEquals(runId, again)
        assertEquals(1, jobRunDao.runs.value.size)
        assertEquals("QUEUED", jobRunDao.getById(runId)!!.state)
        assertEquals(EnqueueCall(bookId, null, true), enqueuer.enqueues.last())
    }

    @Test
    fun `pause on a queued job cancels its pending work`() = runTest {
        val bookId = book(chapters = 4)
        val runId = (scheduler.translateBook(bookId) as AppResult.Ok).value
        scheduler.pause(bookId)
        assertEquals("PAUSED", jobRunDao.getById(runId)!!.state)
        assertEquals(listOf(bookId), enqueuer.cancels)
    }

    @Test
    fun `pause on a running job only writes the flag`() = runTest {
        val bookId = book(chapters = 4)
        val runId = (scheduler.translateBook(bookId) as AppResult.Ok).value
        jobRunDao.update(jobRunDao.getById(runId)!!.copy(state = "RUNNING"))
        scheduler.pause(bookId)
        assertEquals("PAUSED", jobRunDao.getById(runId)!!.state)
        assertTrue(enqueuer.cancels.isEmpty()) // engine stops itself, safely
    }

    @Test
    fun `resume re-enqueues with REPLACE and clears the ending`() = runTest {
        val bookId = book(chapters = 4)
        val runId = (scheduler.translateBook(bookId) as AppResult.Ok).value
        jobRunDao.update(
            jobRunDao.getById(runId)!!.copy(state = "FAILED", finishedAt = 999L)
        )
        val resumed = (scheduler.resume(bookId) as AppResult.Ok).value
        assertEquals(runId, resumed)
        val run = jobRunDao.getById(runId)!!
        assertEquals("QUEUED", run.state)
        assertNull(run.finishedAt)
        assertEquals(EnqueueCall(bookId, null, true), enqueuer.enqueues.last())
    }

    @Test
    fun `cancel marks the row and cancels the work`() = runTest {
        val bookId = book(chapters = 4)
        val runId = (scheduler.translateBook(bookId) as AppResult.Ok).value
        jobRunDao.update(jobRunDao.getById(runId)!!.copy(state = "RUNNING"))
        scheduler.cancel(bookId)
        val run = jobRunDao.getById(runId)!!
        assertEquals(JobState.CANCELLED.name, run.state)
        assertTrue(run.finishedAt != null)
        assertEquals(listOf(bookId), enqueuer.cancels)
    }

    @Test
    fun `translateChapter creates a one-chapter job carrying its chapter`() = runTest {
        val bookId = book(chapters = 4)
        val chapter = chapterDao.getChapters(bookId).first { it.number == 3 }
        val result = scheduler.translateChapter(bookId, chapter.id)
        val runId = (result as AppResult.Ok).value
        assertEquals(1, jobRunDao.getById(runId)!!.totalChapters)
        assertEquals(
            listOf(EnqueueCall(bookId, chapter.id, false)),
            enqueuer.enqueues
        )
    }

    @Test
    fun `translateChapter defers to a live book job`() = runTest {
        val bookId = book(chapters = 4)
        val bookRun = (scheduler.translateBook(bookId) as AppResult.Ok).value
        val chapter = chapterDao.getChapters(bookId).first()
        val result = (scheduler.translateChapter(bookId, chapter.id) as AppResult.Ok).value
        assertEquals(bookRun, result)
        assertEquals(1, jobRunDao.runs.value.size)
        assertEquals(1, enqueuer.enqueues.size)
    }

    @Test
    fun `translateChapter refuses a chapter with no original text`() = runTest {
        val bookId = book(chapters = 0)
        val id = chapterDao.insert(
            ChapterEntity(
                bookId = bookId, number = 1, sourceText = null,
                translatedText = "already english", updatedAt = 0
            )
        )
        val result = scheduler.translateChapter(bookId, id)
        assertTrue(result is AppResult.Err)
        assertTrue(enqueuer.enqueues.isEmpty())
    }
}
