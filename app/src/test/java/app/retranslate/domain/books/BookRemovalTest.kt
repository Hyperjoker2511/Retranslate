package app.retranslate.domain.books

import app.retranslate.core.error.AppResult
import app.retranslate.data.db.BookEntity
import app.retranslate.data.db.ChapterEntity
import app.retranslate.data.db.JobRunEntity
import app.retranslate.data.db.KbEntryEntity
import app.retranslate.data.db.KnowledgeBaseEntity
import app.retranslate.data.db.SampleChapterEntity
import app.retranslate.data.db.SampleSetEntity
import app.retranslate.data.files.BookFiles
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.1 (workstream E): "Remove book" — the removal deletes EVERY row
 * the book owns (chapters and their partial state, its Knowledge Base,
 * sample sets, job history) plus its files, cancels its jobs FIRST,
 * and leaves every other book completely untouched.
 */
class BookRemovalTest {

    private val bookDao = FakeBookDao()
    private val chapterDao = FakeChapterDao()
    private val kbDao = FakeKnowledgeBaseDao()
    private val sampleDao = FakeSampleDao()
    private val jobRunDao = FakeJobRunDao()

    private val repository = BookRepository(
        bookDao, chapterDao, kbDao, DirectTxRunner,
        sampleDao = sampleDao, jobRunDao = jobRunDao, clock = { 5_000L }
    )

    private fun book(title: String): BookEntity = BookEntity(
        titleOriginal = title, sourceLang = "ko", targetLang = "en",
        createdAt = 1L, updatedAt = 1L
    )

    /** Seeds one fully-loaded book; returns its id. */
    private suspend fun seedBook(title: String): Long {
        val bookId = bookDao.insert(book(title))
        chapterDao.insert(
            ChapterEntity(
                bookId = bookId, number = 1, sourceText = "원문", updatedAt = 1L
            )
        )
        chapterDao.insert(
            ChapterEntity(
                bookId = bookId, number = 2, sourceText = "원문2",
                translatedText = "done", status = "TRANSLATED", updatedAt = 1L
            )
        )
        val kbId = kbDao.insertKb(
            KnowledgeBaseEntity(bookId = bookId, version = 1, createdAt = 1L)
        )
        kbDao.insertEntries(
            listOf(
                KbEntryEntity(
                    kbId = kbId, category = "CHARACTER", canonicalForm = "Jin"
                )
            )
        )
        val setId = sampleDao.insertSet(
            SampleSetEntity(
                bookId = bookId, fileName = "samples.txt", detectedLang = "ko"
            )
        )
        sampleDao.insertChapters(
            listOf(SampleChapterEntity(sampleSetId = setId, number = 1, text = "s"))
        )
        jobRunDao.insert(
            JobRunEntity(
                bookId = bookId, kind = "TRANSLATE", state = "RUNNING",
                startedAt = 1L
            )
        )
        return bookId
    }

    @Test
    fun `removal deletes every owned row and file, cancels jobs first`() = runTest {
        val root = Files.createTempDirectory("book-removal").toFile()
        val files = BookFiles(root)
        val doomed = seedBook("Doomed")
        val spared = seedBook("Spared")
        // Files on disk for both books.
        files.coverFile(doomed, ".jpg").writeBytes(byteArrayOf(1))
        files.coverFile(spared, ".jpg").writeBytes(byteArrayOf(2))

        var cancelCalls = 0
        var cancelSawRowsStillThere = false
        val result = repository.removeBook(doomed, files) { id ->
            cancelCalls++
            assertEquals(doomed, id)
            // Cancel must run BEFORE the rows disappear.
            cancelSawRowsStillThere = bookDao.getById(doomed) != null
        }

        assertTrue(result is AppResult.Ok)
        assertEquals(1, cancelCalls)
        assertTrue("jobs are cancelled before any row is deleted", cancelSawRowsStillThere)

        // The doomed book is GONE, everywhere.
        assertNull(bookDao.getById(doomed))
        assertTrue(chapterDao.chapters.none { it.bookId == doomed })
        assertNull(kbDao.getActiveKb(doomed))
        // Only the spared book's KB entry / sample chapter survive.
        assertEquals(1, kbDao.entries.size)
        assertTrue(sampleDao.sets.none { it.bookId == doomed })
        assertEquals(1, sampleDao.chapters.size)
        assertTrue(jobRunDao.runs.value.none { it.bookId == doomed })
        assertFalse(files.bookDir(doomed).exists())

        // The spared book is fully intact.
        assertNotNull(bookDao.getById(spared))
        assertEquals(2, chapterDao.chapters.count { it.bookId == spared })
        assertNotNull(kbDao.getActiveKb(spared))
        assertEquals(1, sampleDao.sets.count { it.bookId == spared })
        assertEquals(1, jobRunDao.runs.value.count { it.bookId == spared })
        assertTrue(files.bookDir(spared).exists())
        root.deleteRecursively()
    }

    @Test
    fun `removing a bare book with nothing attached still works`() = runTest {
        val root = Files.createTempDirectory("book-removal-bare").toFile()
        val files = BookFiles(root)
        val bare = bookDao.insert(book("Bare"))
        val result = repository.removeBook(bare, files)
        assertTrue(result is AppResult.Ok)
        assertNull(bookDao.getById(bare))
        root.deleteRecursively()
    }
}
