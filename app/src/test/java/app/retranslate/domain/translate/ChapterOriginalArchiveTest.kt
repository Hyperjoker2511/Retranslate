package app.retranslate.domain.translate

import app.retranslate.core.error.AppResult
import app.retranslate.data.db.ChapterEntity
import app.retranslate.data.files.BookFiles
import app.retranslate.domain.books.FakeChapterDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * v1.1.3 (§G blocker): the per-chapter ORIGINAL ARCHIVE — the first
 * pre-improve text of EVERY chapter survives any number of improves,
 * and Restore brings it back byte-for-byte. (The one-per-book Undo
 * snapshot could never promise this for a bulk run.)
 */
class ChapterOriginalArchiveTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val chapterDao = FakeChapterDao()
    private fun archive() = ChapterOriginalArchive(
        chapterDao, BookFiles(File(temp.root, "books"))
    )

    private fun chapter(id: Long, number: Int, text: String) = ChapterEntity(
        id = id, bookId = 7, number = number, title = "Chapter $number",
        sourceText = "원문", translatedText = text, status = "TRANSLATED",
        updatedAt = 1
    )

    @Test
    fun `first archive wins and is never overwritten`() = runTest {
        val archive = archive()
        assertTrue(archive.archiveIfAbsent(7, chapter(11, 1, "original text")))
        // A later improve sees a different current text — the archive
        // must still hold the FIRST original.
        assertTrue(archive.archiveIfAbsent(7, chapter(11, 1, "first improve")))
        assertEquals("original text", archive.peek(7, 11)?.translatedText)
    }

    @Test
    fun `restore writes the archived text back and clears improvedAt`() = runTest {
        val archive = archive()
        // The fake DAO assigns ids — work with the stored copy.
        val id = chapterDao.insert(chapter(0, 1, "the original wording"))
        val original = chapterDao.getChapter(id)!!
        assertTrue(archive.archiveIfAbsent(7, original))
        // Simulate an improve landing in the DAO.
        chapterDao.update(
            original.copy(translatedText = "the improved wording", improvedAt = 42L)
        )
        val restored = archive.restore(7, id)
        assertTrue(restored is AppResult.Ok)
        assertEquals(1, (restored as AppResult.Ok).value)
        val after = chapterDao.getChapter(id)!!
        assertEquals("the original wording", after.translatedText)
        assertEquals(null, after.improvedAt)
    }

    @Test
    fun `bulk scenario - every chapter keeps its own original`() = runTest {
        val archive = archive()
        val stored = (1..5).map { n ->
            val id = chapterDao.insert(chapter(0, n, "original $n"))
            chapterDao.getChapter(id)!!
        }
        stored.forEach { assertTrue(archive.archiveIfAbsent(7, it)) }
        // Improve them all (DAO texts change), then restore chapter 3.
        stored.forEach {
            chapterDao.update(it.copy(translatedText = "improved ${it.number}"))
        }
        val third = stored[2]
        val result = archive.restore(7, third.id)
        assertTrue(result is AppResult.Ok)
        assertEquals("original 3", chapterDao.getChapter(third.id)!!.translatedText)
        // The others are untouched by chapter 3's restore.
        assertEquals("improved 2", chapterDao.getChapter(stored[1].id)!!.translatedText)
        assertTrue(archive.has(7, stored[4].id))
        assertFalse(archive.has(7, 999))
    }
}
