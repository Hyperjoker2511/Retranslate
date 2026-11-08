package app.retranslate.ui.kb

import app.retranslate.data.db.BookEntity
import app.retranslate.data.db.JobRunEntity
import app.retranslate.data.db.KbEntryEntity
import app.retranslate.ui.jobs.JobsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLAN.md §6.1/§6.3/§9.3: the KB screens' words are the product — pin them. */
class KbUiBitsTest {

    private val book = BookEntity(
        titleOriginal = "소설", sourceLang = "ko", targetLang = "en",
        createdAt = 0, updatedAt = 0
    )

    @Test
    fun `sample languages are described in plain words`() {
        assertEquals(
            "English — already translated",
            StudySetupViewModel.sampleLanguageText("en", book)
        )
        assertEquals(
            "Korean — original",
            StudySetupViewModel.sampleLanguageText("ko", book)
        )
        assertEquals(
            "Japanese",
            StudySetupViewModel.sampleLanguageText("ja", book)
        )
        assertEquals(
            "Language not recognised",
            StudySetupViewModel.sampleLanguageText("", book)
        )
    }

    @Test
    fun `KB summary line counts by kind`() {
        fun entry(category: String) = KbEntryEntity(
            kbId = 1, category = category, canonicalForm = "X"
        )
        val entries = listOf(
            entry("CHARACTER"), entry("CHARACTER"), entry("LOCATION"),
            entry("TERM"), entry("TONE_RULE")
        )
        assertEquals(
            "Learned 2 names, 1 place, 1 term, 1 tone rule",
            KnowledgeBaseViewModel.summaryText(entries)
        )
        assertEquals(
            "Nothing learned yet",
            KnowledgeBaseViewModel.summaryText(emptyList())
        )
    }

    @Test
    fun `study runs render as learning, not translation`() {
        val run = JobRunEntity(
            id = 4, bookId = 2, kind = "STUDY", state = "RUNNING",
            totalChapters = 10, doneChapters = 0, startedAt = 0
        )
        val summary = JobsViewModel.summarize(run, "My Novel")
        assertEquals("Learning from samples", summary.kindText)
        assertEquals("Learning", summary.stateText)
        assertFalse(summary.canPause)
        assertFalse(summary.canResume)
        assertTrue(summary.canCancel)

        val done = JobsViewModel.summarize(
            run.copy(state = "FINISHED", doneChapters = 10), "My Novel"
        )
        assertTrue(done.detailText.contains("Knowledge Base updated"))
        assertFalse(done.detailText.contains("translated"))
    }
}
