package app.retranslate.domain.kb

import app.retranslate.core.error.AppResult
import app.retranslate.data.db.BookEntity
import app.retranslate.data.db.KbCategory
import app.retranslate.data.db.KbGender
import app.retranslate.domain.books.DirectTxRunner
import app.retranslate.domain.books.FakeBookDao
import app.retranslate.domain.books.FakeChapterDao
import app.retranslate.domain.books.FakeKnowledgeBaseDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLAN.md §6.3: versioning, locked-entry survival, injection context. */
class KbRepositoryTest {

    private val kbDao = FakeKnowledgeBaseDao()
    private val chapterDao = FakeChapterDao()
    private val bookDao = FakeBookDao()
    private val repo = KbRepository(kbDao, chapterDao, DirectTxRunner) { 5_000L }

    private fun draft(
        canonical: String,
        category: KbCategory = KbCategory.TERM,
        gender: KbGender? = null
    ) = KbEntryDraft(
        category = category, sourceForm = null, canonicalForm = canonical,
        gender = gender, note = null, frequency = 3
    )

    private suspend fun book(): BookEntity {
        val id = bookDao.insert(
            BookEntity(
                titleOriginal = "소설", sourceLang = "ko", targetLang = "en",
                genrePresetId = "cultivation", createdAt = 0, updatedAt = 0
            )
        )
        return bookDao.getById(id)!!
    }

    @Test
    fun `study creates version 1 and the next study version 2`() = runTest {
        val first = repo.createFromStudy(1, listOf(draft("Qi")), "Studied 5 sample chapters")
        assertEquals(1, (first as AppResult.Ok).value.version)
        val second = repo.createFromStudy(1, listOf(draft("Aura")), "Studied 5 sample chapters")
        assertEquals(2, (second as AppResult.Ok).value.version)
        val active = repo.activeEntries(1)
        assertEquals(listOf("Aura"), active.map { it.canonicalForm })
    }

    @Test
    fun `locked entries survive a re-study verbatim, colliding fresh ones drop`() = runTest {
        repo.addManualEntry(1, draft("Jin Woo", KbCategory.CHARACTER, KbGender.MALE))
        val result = repo.createFromStudy(
            1,
            listOf(
                draft("Jin Woo", KbCategory.CHARACTER, KbGender.FEMALE), // collides (sourceForm key)
                draft("New Term")
            ),
            "Studied 4 sample chapters"
        )
        assertTrue(result is AppResult.Ok)
        val active = repo.activeEntries(1)
        val jin = active.first { it.canonicalForm == "Jin Woo" }
        assertTrue(jin.locked)
        assertEquals("MALE", jin.gender) // the locked original wins
        assertTrue(active.any { it.canonicalForm == "New Term" })
    }

    @Test
    fun `manual edits increment the version and copy the set`() = runTest {
        repo.createFromStudy(1, listOf(draft("Qi"), draft("Aura")), "s")
        val entries = repo.activeEntries(1)
        val qi = entries.first { it.canonicalForm == "Qi" }
        val edited = repo.editEntry(
            1, qi.id,
            draft("Qi Energy"),
            locked = false
        )
        assertEquals(2, (edited as AppResult.Ok).value.version)
        val active = repo.activeEntries(1)
        assertEquals(setOf("Qi Energy", "Aura"), active.map { it.canonicalForm }.toSet())
        // The old version's rows are untouched (history stays meaningful).
        val v1Entries = kbDao.getEntries(kbDao.kbs.first { it.version == 1 }.id)
        assertEquals(setOf("Qi", "Aura"), v1Entries.map { it.canonicalForm }.toSet())
    }

    @Test
    fun `lock toggle and delete are versioned edits too`() = runTest {
        repo.createFromStudy(1, listOf(draft("Qi")), "s")
        val qi = repo.activeEntries(1).first()
        assertFalse(qi.locked)
        repo.setLocked(1, qi.id, locked = true)
        val afterLock = repo.activeEntries(1).first()
        assertTrue(afterLock.locked)
        repo.deleteEntry(1, afterLock.id)
        assertTrue(repo.activeEntries(1).isEmpty())
        assertEquals(3, repo.activeKb(1)?.version)
    }

    @Test
    fun `buildContext injects the KB block and reports the version`() = runTest {
        repo.createFromStudy(
            1,
            listOf(
                draft("Jin Woo", KbCategory.CHARACTER, KbGender.MALE),
                KbEntryDraft(
                    KbCategory.TONE_RULE, null,
                    "Dialogue uses contractions and sounds spoken.", null, null, 9
                )
            ),
            "s"
        )
        val context = repo.buildContext(book())
        assertEquals(1, context.kbVersion)
        assertEquals(2, context.entries.size)
        val prompt = context.context.systemPrompt
        assertTrue(prompt.contains("Jin Woo (male)"))
        assertTrue(prompt.contains("Rule: Dialogue uses contractions"))
        assertTrue(prompt.contains("Cultivation novel")) // genre hint (§5.4)
        assertTrue(prompt.contains("Korean") && prompt.contains("English"))
    }

    @Test
    fun `book without a KB gets the genre prompt and a null version`() = runTest {
        val context = repo.buildContext(book())
        assertEquals(null, context.kbVersion)
        assertTrue(context.entries.isEmpty())
        assertTrue(!context.context.systemPrompt.contains("Knowledge Base"))
    }

    @Test
    fun `name warning scan counts only violating translated chapters`() = runTest {
        repo.createFromStudy(1, listOf(draft("Jin Woo", KbCategory.CHARACTER)), "s")
        chapterDao.insert(
            app.retranslate.data.db.ChapterEntity(
                bookId = 1, number = 1, sourceText = "원문",
                translatedText = "Jinwoo smiled.", status = "TRANSLATED", updatedAt = 0
            )
        )
        chapterDao.insert(
            app.retranslate.data.db.ChapterEntity(
                bookId = 1, number = 2, sourceText = "원문",
                translatedText = "Jin Woo smiled.", status = "TRANSLATED", updatedAt = 0
            )
        )
        chapterDao.insert(
            app.retranslate.data.db.ChapterEntity(
                bookId = 1, number = 3, sourceText = "원문",
                translatedText = null, status = "PENDING", updatedAt = 0
            )
        )
        assertEquals(1, repo.countChaptersWithNameWarnings(1))
    }
}
