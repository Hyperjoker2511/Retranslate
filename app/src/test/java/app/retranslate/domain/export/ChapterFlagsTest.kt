package app.retranslate.domain.export

import app.retranslate.data.files.BookFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** PLAN.md §9.3: paragraph flags persist per book, per chapter. */
class ChapterFlagsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun flags() = ChapterFlags(
        BookFiles(File(temp.root, "books")),
        Dispatchers.Unconfined
    )

    @Test
    fun `flags persist, toggle off, and count`() = runTest {
        val store = flags()
        store.setFlagged(7L, 3L, 0, true)
        store.setFlagged(7L, 3L, 2, true)
        assertEquals(setOf(0, 2), store.flaggedParagraphs(7L, 3L))
        store.setFlagged(7L, 3L, 0, false)
        assertEquals(setOf(2), store.flaggedParagraphs(7L, 3L))
        assertEquals(1, store.countForBook(7L))
        assertEquals(1, store.countFlaggedChapters(7L))
        assertEquals(mapOf(3L to setOf(2)), store.flagsFor(7L))
        // A fresh store over the same files sees the same flags.
        assertEquals(setOf(2), flags().flaggedParagraphs(7L, 3L))
    }

    @Test
    fun `flags are per book and per chapter`() = runTest {
        val store = flags()
        store.setFlagged(1L, 1L, 0, true)
        store.setFlagged(2L, 1L, 5, true)
        assertEquals(setOf(0), store.flaggedParagraphs(1L, 1L))
        assertEquals(setOf(5), store.flaggedParagraphs(2L, 1L))
        assertEquals(emptySet<Int>(), store.flaggedParagraphs(1L, 2L))
    }

    @Test
    fun `a corrupt flags file reads as no flags, never a crash`() = runTest {
        val files = BookFiles(File(temp.root, "books"))
        files.bookDir(4L).mkdirs()
        File(files.bookDir(4L), "flags.json").writeText("{ not json !!")
        val store = ChapterFlags(files, Dispatchers.Unconfined)
        assertEquals(emptySet<Int>(), store.flaggedParagraphs(4L, 1L))
        // …and the store can still write afresh.
        store.setFlagged(4L, 1L, 1, true)
        assertEquals(setOf(1), store.flaggedParagraphs(4L, 1L))
        assertTrue(true)
    }
}
