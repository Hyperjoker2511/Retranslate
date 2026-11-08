package app.retranslate.domain.translate

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.core.error.runCatchingAppSuspend
import app.retranslate.data.db.ChapterDao
import app.retranslate.data.db.ChapterEntity
import app.retranslate.data.files.BookFiles
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** What produced a snapshot (§9.3): a re-translation or a polish. */
enum class ChapterChangeAction { RETRANSLATE, POLISH }

/**
 * The one-chapter undo (§9.3): before the review screen replaces a
 * chapter's translation (re-translate or polish), the chapter's full
 * pre-change state is written to the book's folder; Undo restores it
 * byte-identical. One snapshot per book — the latest change is the
 * undoable one — mirroring P5's "Re-apply names" snapshot, and the
 * same safety rule applies: a change whose snapshot cannot be
 * written never starts (no unundoable edits).
 */
class ChapterSnapshots(
    private val chapterDao: ChapterDao,
    private val bookFiles: BookFiles,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /** The snapshot currently held for a book, if any (book-page Undo). */
    fun peek(bookId: Long): ChapterSnapshot? {
        val file = snapshotFile(bookId)
        if (!file.isFile) return null
        return try {
            Json.decodeFromString(ChapterSnapshot.serializer(), file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            null // corrupt snapshot = no undo offered, never a crash
        }
    }

    fun hasSnapshot(bookId: Long): Boolean = peek(bookId) != null

    /**
     * Records [chapter]'s current state as the book's undo point.
     * Returns false when the write failed — the caller must then
     * refuse the change (an unundoable edit can never start).
     */
    suspend fun save(
        bookId: Long,
        chapter: ChapterEntity,
        action: ChapterChangeAction
    ): Boolean = try {
        val snapshot = ChapterSnapshot(
            chapterId = chapter.id,
            chapterNumber = chapter.number,
            action = action.name,
            createdAt = clock(),
            translatedText = chapter.translatedText,
            status = chapter.status,
            qualityScore = chapter.qualityScore,
            translatedByProvider = chapter.translatedByProvider,
            kbVersionUsed = chapter.kbVersionUsed,
            partialStateJson = chapter.partialStateJson
        )
        snapshotFile(bookId).writeText(
            Json.encodeToString(ChapterSnapshot.serializer(), snapshot), Charsets.UTF_8
        )
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Restores the snapshotted chapter byte-identical and clears the
     * snapshot. Returns the restored chapter's number, or 0 when
     * there was nothing to undo.
     */
    suspend fun undo(bookId: Long): AppResult<Int> =
        runCatchingAppSuspend({ AppError.StorageFailed("undoing the chapter change") }) {
            val snapshot = peek(bookId) ?: return@runCatchingAppSuspend 0
            val chapter = chapterDao.getChapter(snapshot.chapterId)
                ?: return@runCatchingAppSuspend 0
            chapterDao.update(
                chapter.copy(
                    translatedText = snapshot.translatedText,
                    status = snapshot.status,
                    qualityScore = snapshot.qualityScore,
                    translatedByProvider = snapshot.translatedByProvider,
                    kbVersionUsed = snapshot.kbVersionUsed,
                    partialStateJson = snapshot.partialStateJson,
                    updatedAt = clock()
                )
            )
            snapshotFile(bookId).delete()
            snapshot.chapterNumber
        }

    private fun snapshotFile(bookId: Long): File =
        File(bookFiles.ensureBookDir(bookId), FILE_NAME)

    companion object {
        const val FILE_NAME = "chapter-snapshot.json"
    }
}

/** The persisted undo record (JSON in the book's folder). */
@Serializable
data class ChapterSnapshot(
    val chapterId: Long,
    val chapterNumber: Int,
    /** [ChapterChangeAction] name. */
    val action: String,
    val createdAt: Long,
    val translatedText: String?,
    val status: String,
    val qualityScore: Float?,
    val translatedByProvider: String?,
    val kbVersionUsed: Int?,
    val partialStateJson: String?
)
