package app.retranslate.ui.jobs

import app.retranslate.data.db.JobRunEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLAN.md §9.3: the Jobs card mapping — states and actions in plain words. */
class JobsViewModelTest {

    private fun run(
        state: String,
        total: Int = 10,
        done: Int = 0,
        failed: Int = 0,
        switches: Int = 0,
        lastError: String? = null
    ) = JobRunEntity(
        id = 7, bookId = 3, kind = "TRANSLATE", state = state,
        totalChapters = total, doneChapters = done, failedChapters = failed,
        switchedProviders = switches, startedAt = 100, lastErrorClass = lastError
    )

    @Test
    fun `running job shows progress and pause-cancel`() {
        val s = JobsViewModel.summarize(run("RUNNING", done = 4), "My Novel")
        assertEquals("Translating", s.stateText)
        assertEquals("4 of 10 chapters", s.detailText)
        assertEquals(0.4f, s.progress!!, 0.001f)
        assertTrue(s.canPause)
        assertTrue(s.canCancel)
        assertFalse(s.canResume)
        assertEquals("Translation", s.kindText)
    }

    @Test
    fun `busy pause is named in plain words`() {
        val s = JobsViewModel.summarize(run("PAUSED", lastError = "RateLimited"), "B")
        assertEquals("Paused — servers busy", s.stateText)
        assertTrue(s.canResume)
    }

    @Test
    fun `user pause is just paused`() {
        val s = JobsViewModel.summarize(run("PAUSED"), "B")
        assertEquals("Paused", s.stateText)
    }

    @Test
    fun `finished summary counts translations fallbacks and failures`() {
        val s = JobsViewModel.summarize(
            run("FINISHED", total = 10, done = 8, failed = 2, switches = 3), "B"
        )
        assertEquals("Finished — some chapters failed", s.stateText)
        assertEquals(
            "10 of 10 chapters · 8 translated · 3 used a fallback provider " +
                "· 2 failed — open the book to retry",
            s.detailText
        )
        assertFalse(s.canPause)
        assertFalse(s.canCancel)
    }

    @Test
    fun `failed job offers resume`() {
        val s = JobsViewModel.summarize(run("FAILED", failed = 10), "B")
        assertEquals("Stopped — the chapters failed", s.stateText)
        assertTrue(s.canResume)
    }

    @Test
    fun `unknown stored state coerces to a safe card`() {
        // §10 defensive reads: a garbage state string must not crash the tab.
        val s = JobsViewModel.summarize(run("SOME_FUTURE_STATE"), "B")
        assertEquals("Waiting to start", s.stateText) // QUEUED is the safe default
    }
}
