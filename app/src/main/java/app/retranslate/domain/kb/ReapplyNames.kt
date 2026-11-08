package app.retranslate.domain.kb

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.core.error.runCatchingAppSuspend
import app.retranslate.data.db.ChapterDao
import app.retranslate.data.db.TxRunner
import app.retranslate.data.files.BookFiles
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** A rename to push into already-translated chapters (§6.3). */
data class ReapplyRequest(
    val entryId: Long,
    /** The rendering chapters may still carry (the previous canonical). */
    val oldForm: String,
    /** The rendering every chapter must end up with (the new canonical). */
    val newForm: String
)

/** One affected chapter in the preview: where, and how many places. */
data class ChapterFix(
    val chapterId: Long,
    val chapterNumber: Int,
    val occurrences: Int,
    /** The wrong spellings found, e.g. ["Jinwoo", "Jin-Woo"]. */
    val variantsFound: List<String>
)

/**
 * "Re-apply names" (PLAN.md §6.3): when a canonical name changes, the
 * cheap fix runs BEFORE any paid re-translation — a local
 * find/replace of the old rendering and its spacing variants across
 * the book's translated chapters. Always previewed first (which
 * chapters, how many places), always undoable: applying snapshots
 * the affected chapters' current text to a file in the book's
 * folder, and Undo restores it byte-identical. One snapshot per book
 * (the latest apply is the undoable one).
 */
class ReapplyNames(
    private val chapterDao: ChapterDao,
    private val bookFiles: BookFiles,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /** Spellings an apply would rewrite: the old form + its variants. */
    fun searchForms(request: ReapplyRequest): List<String> {
        val forms = linkedSetOf<String>()
        forms += request.oldForm.trim()
        forms += KbCompliance.variantsOf(request.oldForm)
        forms.remove(request.newForm.trim())
        forms.remove("")
        return forms.toList()
    }

    /** Walks the translated chapters in pages (§7.3) and counts the damage. */
    suspend fun preview(bookId: Long, request: ReapplyRequest): List<ChapterFix> {
        val forms = searchForms(request)
        if (forms.isEmpty() || request.oldForm.trim() == request.newForm.trim()) {
            return emptyList()
        }
        val fixes = mutableListOf<ChapterFix>()
        var offset = 0
        while (true) {
            val page = chapterDao.getTranslatedPage(bookId, PAGE_SIZE, offset)
            if (page.isEmpty()) break
            for (slice in page) {
                val text = slice.translatedText ?: continue
                var total = 0
                val found = mutableListOf<String>()
                for (form in forms) {
                    val count = KbCompliance.countOccurrences(text, form)
                    if (count > 0) {
                        total += count
                        found += form
                    }
                }
                if (total > 0) {
                    fixes += ChapterFix(slice.id, slice.number, total, found)
                }
            }
            if (page.size < PAGE_SIZE) break
            offset += page.size
        }
        return fixes
    }

    /**
     * Applies the rename. Snapshots first — if the snapshot can't be
     * written, nothing is changed (an unundoable edit never happens).
     * Returns how many chapters changed.
     */
    suspend fun apply(bookId: Long, request: ReapplyRequest): AppResult<Int> =
        runCatchingAppSuspend({ AppError.StorageFailed("re-applying names") }) {
            val fixes = preview(bookId, request)
            if (fixes.isEmpty()) return@runCatchingAppSuspend 0
            val forms = searchForms(request)
            val changes = mutableListOf<SnapshotChange>()
            val rewritten = mutableListOf<Pair<Long, String>>()
            for (fix in fixes) {
                val chapter = chapterDao.getChapter(fix.chapterId) ?: continue
                val original = chapter.translatedText ?: continue
                var text = original
                for (form in forms) {
                    text = KbCompliance.replaceVariant(text, form, request.newForm.trim()).first
                }
                if (text != original) {
                    changes += SnapshotChange(chapterId = fix.chapterId, text = original)
                    rewritten += fix.chapterId to text
                }
            }
            if (rewritten.isEmpty()) return@runCatchingAppSuspend 0
            writeSnapshot(bookId, changes)
            for ((chapterId, newText) in rewritten) {
                val chapter = chapterDao.getChapter(chapterId) ?: continue
                chapterDao.update(
                    chapter.copy(translatedText = newText, updatedAt = clock())
                )
            }
            rewritten.size
        }

    /** Restores the snapshot byte-identical and clears it. Chapters restored. */
    suspend fun undo(bookId: Long): AppResult<Int> =
        runCatchingAppSuspend({ AppError.StorageFailed("undoing the rename") }) {
            val file = snapshotFile(bookId)
            if (!file.isFile) return@runCatchingAppSuspend 0
            val snapshot = Json.decodeFromString(Snapshot.serializer(), file.readText(Charsets.UTF_8))
            var restored = 0
            for (change in snapshot.changes) {
                val chapter = chapterDao.getChapter(change.chapterId) ?: continue
                chapterDao.update(
                    chapter.copy(translatedText = change.text, updatedAt = clock())
                )
                restored++
            }
            file.delete()
            restored
        }

    fun hasUndo(bookId: Long): Boolean = snapshotFile(bookId).isFile

    private fun snapshotFile(bookId: Long): File =
        File(bookFiles.ensureBookDir(bookId), SNAPSHOT_NAME)

    private fun writeSnapshot(bookId: Long, changes: List<SnapshotChange>) {
        val snapshot = Snapshot(createdAt = clock(), changes = changes)
        snapshotFile(bookId).writeText(
            Json.encodeToString(Snapshot.serializer(), snapshot), Charsets.UTF_8
        )
    }

    @Serializable
    private data class Snapshot(val createdAt: Long, val changes: List<SnapshotChange>)

    @Serializable
    private data class SnapshotChange(val chapterId: Long, val text: String)

    companion object {
        const val PAGE_SIZE = 100
        const val SNAPSHOT_NAME = "reapply-snapshot.json"
    }
}
