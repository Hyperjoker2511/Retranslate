package app.retranslate.domain.kb

import app.retranslate.core.error.AppResult
import app.retranslate.data.db.ChapterEntity
import app.retranslate.data.files.BookFiles
import app.retranslate.domain.books.FakeChapterDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** PLAN.md §6.3/§12 flow 14: preview → apply → undo restores byte-identical text. */
class ReapplyNamesTest {

    private val chapterDao = FakeChapterDao()
    private val root = Files.createTempDirectory("reapply-test").toFile()
    private val bookFiles = BookFiles(File(root, "books"))
    private val reapply = ReapplyNames(chapterDao, bookFiles) { 7_000L }

    private val request = ReapplyRequest(entryId = 5, oldForm = "Jin Woo", newForm = "Jin-Wu")

    private suspend fun chapter(number: Int, translated: String?): Long =
        chapterDao.insert(
            ChapterEntity(
                bookId = 1, number = number, sourceText = "원문",
                translatedText = translated, status = "TRANSLATED", updatedAt = 0
            )
        )

    @Test
    fun `preview lists affected chapters with counts and variants`() = runTest {
        chapter(1, "Jinwoo left. Jin-Woo returned. Jin Woo waved.")
        chapter(2, "Jin Woo was already right here.")
        chapter(3, "No names in this one.")
        val fixes = reapply.preview(1, request)
        assertEquals(2, fixes.size)
        val first = fixes.first { it.chapterNumber == 1 }
        assertEquals(3, first.occurrences) // Jinwoo + Jin-Woo + Jin Woo itself
        assertTrue("Jinwoo" in first.variantsFound)
        val second = fixes.first { it.chapterNumber == 2 }
        assertEquals(1, second.occurrences)
    }

    @Test
    fun `apply rewrites, and undo restores byte-identical text`() = runTest {
        val id1 = chapter(1, "Jinwoo left. Jin Woo waved.")
        val id2 = chapter(2, "Clean chapter.")
        val before1 = chapterDao.getChapter(id1)!!.translatedText

        val changed = reapply.apply(1, request)
        assertEquals(1, (changed as AppResult.Ok).value)
        assertEquals("Jin-Wu left. Jin-Wu waved.", chapterDao.getChapter(id1)!!.translatedText)
        assertEquals("Clean chapter.", chapterDao.getChapter(id2)!!.translatedText)
        assertTrue(reapply.hasUndo(1))

        val restored = reapply.undo(1)
        assertEquals(1, (restored as AppResult.Ok).value)
        assertEquals(before1, chapterDao.getChapter(id1)!!.translatedText)
        assertFalse(reapply.hasUndo(1))
    }

    @Test
    fun `apply with nothing to fix changes nothing and keeps no snapshot`() = runTest {
        chapter(1, "Jin-Wu is already everywhere.")
        val changed = reapply.apply(1, request)
        assertEquals(0, (changed as AppResult.Ok).value)
        assertFalse(reapply.hasUndo(1))
    }

    @Test
    fun `same old and new form is a no-op`() = runTest {
        chapter(1, "Jin Woo waved.")
        val same = ReapplyRequest(entryId = 5, oldForm = "Jin Woo", newForm = "Jin Woo")
        assertTrue(reapply.preview(1, same).isEmpty())
        assertEquals(0, (reapply.apply(1, same) as AppResult.Ok).value)
    }
}
