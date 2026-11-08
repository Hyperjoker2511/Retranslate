package app.retranslate.domain.export

import app.retranslate.data.files.BookFiles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Paragraph flags for the review screen (§9.3): the user taps a
 * paragraph that "looks wrong"; the flag must survive relaunch and
 * show as a count on the book page.
 *
 * Storage is a small JSON file in the book's own folder
 * (`books/<bookId>/flags.json`, chapterId → flagged paragraph
 * indices) — the same pattern as P5's re-apply snapshot, chosen over
 * a schema change: schema v1 has no flags column (§8.1), and flags
 * are checking state, not translation data, so they live beside the
 * book's other working files and are deleted with the book (§8.2).
 * A corrupt file reads as "no flags", never a crash (§10 defensive
 * reads); writes are read-modify-write under a mutex.
 */
class ChapterFlags(
    private val bookFiles: BookFiles,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /** chapterId → flagged paragraph indices (0-based, sorted). */
    suspend fun flagsFor(bookId: Long): Map<Long, Set<Int>> = mutex.withLock {
        withContext(ioDispatcher) { read(bookId) }
    }

    suspend fun flaggedParagraphs(bookId: Long, chapterId: Long): Set<Int> =
        flagsFor(bookId)[chapterId].orEmpty()

    /** How many paragraphs are flagged across the whole book. */
    suspend fun countForBook(bookId: Long): Int =
        flagsFor(bookId).values.sumOf { it.size }

    /** How many chapters carry at least one flag. */
    suspend fun countFlaggedChapters(bookId: Long): Int =
        flagsFor(bookId).count { it.value.isNotEmpty() }

    suspend fun setFlagged(
        bookId: Long,
        chapterId: Long,
        paragraph: Int,
        flagged: Boolean
    ) {
        if (paragraph < 0) return
        mutex.withLock {
            withContext(ioDispatcher) {
                val current = read(bookId).toMutableMap()
                val set = current[chapterId].orEmpty().toMutableSet()
                if (flagged) set += paragraph else set -= paragraph
                if (set.isEmpty()) current.remove(chapterId) else current[chapterId] = set
                write(bookId, current)
            }
        }
    }

    private fun read(bookId: Long): Map<Long, Set<Int>> {
        val file = flagsFile(bookId)
        if (!file.isFile) return emptyMap()
        return try {
            val stored = json.decodeFromString(Stored.serializer(), file.readText(Charsets.UTF_8))
            stored.chapters.mapNotNull { (id, paragraphs) ->
                val chapterId = id.toLongOrNull() ?: return@mapNotNull null
                chapterId to paragraphs.filter { it >= 0 }.toSet()
            }.toMap()
        } catch (e: Exception) {
            emptyMap() // a broken flags file means "no flags", not a crash
        }
    }

    private fun write(bookId: Long, flags: Map<Long, Set<Int>>) {
        val stored = Stored(
            chapters = flags.mapKeys { it.key.toString() }
                .mapValues { it.value.sorted() }
        )
        val file = flagsFile(bookId)
        // The book's folder may not exist yet (flags can be the
        // first thing ever written for a book) — create it.
        file.parentFile?.mkdirs()
        file.writeText(
            json.encodeToString(Stored.serializer(), stored), Charsets.UTF_8
        )
    }

    private fun flagsFile(bookId: Long): File =
        File(bookFiles.bookDir(bookId), FILE_NAME)

    @Serializable
    private data class Stored(val chapters: Map<String, List<Int>> = emptyMap())

    companion object {
        const val FILE_NAME = "flags.json"
    }
}
