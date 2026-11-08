package app.retranslate.domain.kb

import app.retranslate.core.error.AppResult
import app.retranslate.data.db.BookEntity
import app.retranslate.domain.books.FakeBookDao
import app.retranslate.domain.books.FakeJobRunDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeStudyEnqueuer : StudyEnqueuer {
    val enqueued = mutableListOf<Triple<Long, Long, String>>()
    val cancelled = mutableListOf<Long>()

    override fun enqueueStudy(bookId: Long, jobRunId: Long, selectionSpec: String, replace: Boolean) {
        enqueued += Triple(bookId, jobRunId, selectionSpec)
    }

    override fun cancelStudy(bookId: Long) {
        cancelled += bookId
    }
}

/** PLAN.md §6.1/§7.4: one live study per book (KEEP), selection codec, cancel. */
class StudySchedulerTest {

    private val bookDao = FakeBookDao()
    private val jobRunDao = FakeJobRunDao()
    private val enqueuer = FakeStudyEnqueuer()
    private val scheduler = StudyScheduler(bookDao, jobRunDao, enqueuer) { 3_000L }

    private suspend fun bookId(): Long = bookDao.insert(
        BookEntity(
            titleOriginal = "소설", sourceLang = "ko", targetLang = "en",
            createdAt = 0, updatedAt = 0
        )
    )

    private val selection = StudySelection(
        listOf(StudySelection.SetRange(7, 1, 10), StudySelection.SetRange(9, 2, 4))
    )

    @Test
    fun `start creates a STUDY run and enqueues the encoded selection`() = runTest {
        val bookId = bookId()
        val result = scheduler.startStudy(bookId, selection)
        val runId = (result as AppResult.Ok).value
        val run = jobRunDao.getById(runId)!!
        assertEquals("STUDY", run.kind)
        assertEquals("QUEUED", run.state)
        assertEquals(13, run.totalChapters)
        assertEquals(1, enqueuer.enqueued.size)
        assertEquals("7:1-10;9:2-4", enqueuer.enqueued[0].third)
    }

    @Test
    fun `a second start while one is live returns the same run (KEEP)`() = runTest {
        val bookId = bookId()
        val first = (scheduler.startStudy(bookId, selection) as AppResult.Ok).value
        val second = (scheduler.startStudy(bookId, selection) as AppResult.Ok).value
        assertEquals(first, second)
        assertEquals(1, enqueuer.enqueued.size)
        assertEquals(1, jobRunDao.runs.value.count { it.kind == "STUDY" })
    }

    @Test
    fun `empty selection is refused in words`() = runTest {
        val bookId = bookId()
        val result = scheduler.startStudy(bookId, StudySelection(emptyList()))
        assertTrue(result is AppResult.Err)
        assertTrue(enqueuer.enqueued.isEmpty())
    }

    @Test
    fun `cancel writes the row first, then cancels the work`() = runTest {
        val bookId = bookId()
        val runId = (scheduler.startStudy(bookId, selection) as AppResult.Ok).value
        val result = scheduler.cancelStudy(runId)
        assertTrue(result is AppResult.Ok)
        assertEquals("CANCELLED", jobRunDao.getById(runId)!!.state)
        assertEquals(listOf(bookId), enqueuer.cancelled)
    }

    @Test
    fun `selection codec round-trips and tolerates junk`() {
        assertEquals(selection, StudySelectionCodec.decode(StudySelectionCodec.encode(selection)))
        assertEquals(StudySelection(emptyList()), StudySelectionCodec.decode(""))
        assertEquals(StudySelection(emptyList()), StudySelectionCodec.decode("junk;;x:y-z"))
    }
}
