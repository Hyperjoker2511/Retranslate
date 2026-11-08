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

/**
 * The per-chapter ORIGINAL archive (v1.1.3 §G candidate 1 — the
 * blocker-grade fix): before ANY improve touches a chapter, that
 * chapter's pre-improve state is written to the book's folder, one
 * file per chapter, and NEVER overwritten — the first original is
 * the original, forever. "Restore original" (§F18) reads it back.
 *
 * Why this exists: the one-chapter undo ([ChapterSnapshots]) holds
 * ONE snapshot per book. A bulk Improve-all over a 1,500-chapter MTL
 * book would have destroyed every chapter's original except the
 * last one's. The archive is written before the AI call; if it
 * cannot be written, the improve does not start (the same
 * no-unundoable-edits rule as snapshots).
 */
class ChapterOriginalArchive(
    private val chapterDao: ChapterDao,
    private val bookFiles: BookFiles,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    fun has(bookId: Long, chapterId: Long): Boolean = archiveFile(bookId, chapterId).isFile

    fun peek(bookId: Long, chapterId: Long): ArchivedChapterOriginal? {
        val file = archiveFile(bookId, chapterId)
        if (!file.isFile) return null
        return try {
            Json.decodeFromString(ArchivedChapterOriginal.serializer(), file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            null // corrupt archive = no restore offered, never a crash
        }
    }

    /**
     * Records [chapter]'s current state as its permanent original —
     * unless one is already archived, in which case the existing
     * (earlier, truer) original stands. Returns false only when the
     * write failed; the caller must then refuse the improve.
     */
    suspend fun archiveIfAbsent(bookId: Long, chapter: ChapterEntity): Boolean {
        if (has(bookId, chapter.id)) return true
        return try {
            val record = ArchivedChapterOriginal(
                chapterId = chapter.id,
                chapterNumber = chapter.number,
                archivedAt = clock(),
                translatedText = chapter.translatedText,
                status = chapter.status,
                qualityScore = chapter.qualityScore,
                translatedByProvider = chapter.translatedByProvider,
                kbVersionUsed = chapter.kbVersionUsed
            )
            archiveFile(bookId, chapter.id).writeText(
                Json.encodeToString(ArchivedChapterOriginal.serializer(), record),
                Charsets.UTF_8
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Puts the archived original back byte-identical and clears the
     * chapter's improved marker. Returns the chapter's number, or 0
     * when no original is archived for it.
     */
    suspend fun restore(bookId: Long, chapterId: Long): AppResult<Int> =
        runCatchingAppSuspend({ AppError.StorageFailed("restoring the chapter's original") }) {
            val record = peek(bookId, chapterId) ?: return@runCatchingAppSuspend 0
            val chapter = chapterDao.getChapter(chapterId)
                ?: return@runCatchingAppSuspend 0
            chapterDao.update(
                chapter.copy(
                    translatedText = record.translatedText,
                    status = record.status,
                    qualityScore = record.qualityScore,
                    translatedByProvider = record.translatedByProvider,
                    kbVersionUsed = record.kbVersionUsed,
                    improvedAt = null,
                    updatedAt = clock()
                )
            )
            record.chapterNumber
        }

    private fun archiveFile(bookId: Long, chapterId: Long): File {
        val dir = File(bookFiles.ensureBookDir(bookId), DIR_NAME).apply { mkdirs() }
        return File(dir, "chapter-$chapterId.json")
    }

    companion object {
        const val DIR_NAME = "originals"
    }
}

/** The persisted per-chapter original (JSON in the book's folder). */
@Serializable
data class ArchivedChapterOriginal(
    val chapterId: Long,
    val chapterNumber: Int,
    val archivedAt: Long,
    val translatedText: String?,
    val status: String,
    val qualityScore: Float?,
    val translatedByProvider: String?,
    val kbVersionUsed: Int?
)
