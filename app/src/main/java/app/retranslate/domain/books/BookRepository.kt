package app.retranslate.domain.books

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.core.error.runCatchingAppSuspend
import app.retranslate.data.db.BookDao
import app.retranslate.data.db.BookEntity
import app.retranslate.data.db.BookShelfRow
import app.retranslate.data.db.ChapterDao
import app.retranslate.data.db.ChapterEntity
import app.retranslate.data.db.ChapterListItem
import app.retranslate.data.db.ChapterOrigin
import app.retranslate.data.db.ChapterStatus
import app.retranslate.data.db.KnowledgeBaseDao
import app.retranslate.data.db.TxRunner
import app.retranslate.data.files.BookFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * Everything the UI may do with stored books (PLAN.md §3.2: screens are
 * pure functions of this state). Owns the import-commit transaction
 * shape from §7.1: the Book row lands first, then chapters are inserted
 * in batches of 50 — one transaction per batch — each batch's entities
 * built by reading that batch's temp files, so commit memory stays
 * flat for a 1,500-chapter book.
 */
class BookRepository(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val knowledgeBaseDao: KnowledgeBaseDao,
    private val tx: TxRunner,
    /** v1.1 (book removal): owned-row cleanup beyond chapters/KB. */
    private val sampleDao: app.retranslate.data.db.SampleDao? = null,
    private val jobRunDao: app.retranslate.data.db.JobRunDao? = null,
    // Clock stays LAST: existing callers pass it as a trailing lambda.
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    fun observeShelf(): Flow<List<BookShelfRow>> = bookDao.observeShelf()

    fun observeBook(bookId: Long): Flow<BookEntity?> = bookDao.observeById(bookId)

    fun observeChapterItems(bookId: Long): Flow<List<ChapterListItem>> =
        chapterDao.observeListItems(bookId)

    fun observeChapter(chapterId: Long): Flow<ChapterEntity?> =
        chapterDao.observeChapter(chapterId)

    suspend fun getBook(bookId: Long): BookEntity? = bookDao.getById(bookId)

    /**
     * Duplicate = same file hash AND same title (normalised), §7.1.
     * Hash-only matches aren't duplicates: the same file may legitimately
     * be imported twice under different titles (separate copy flow).
     */
    suspend fun findDuplicate(sha256: String, title: String): BookEntity? {
        val wanted = title.trim().lowercase()
        return bookDao.findByHash(sha256)
            .firstOrNull { it.titleOriginal.trim().lowercase() == wanted }
    }

    suspend fun activeKbEntryCount(bookId: Long): Int {
        val kb = knowledgeBaseDao.getActiveKb(bookId) ?: return 0
        return knowledgeBaseDao.countEntries(kb.id)
    }

    /** Writes a planned import into Room. On any failure the partial book is removed. */
    suspend fun commitImport(
        planned: PlannedBook,
        choices: ImportChoices,
        sha256: String,
        bookFiles: BookFiles
    ): AppResult<Long> {
        val now = clock()
        val isTranslation = choices.sourceLang == choices.targetLang
        var bookId = 0L
        return try {
            bookId = tx.run {
                bookDao.insert(
                    BookEntity(
                        titleOriginal = choices.title,
                        author = choices.author,
                        sourceLang = choices.sourceLang,
                        targetLang = choices.targetLang,
                        genrePresetId = choices.genrePresetId,
                        qualityMode = choices.qualityMode,
                        sourceFileHash = sha256,
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }
            // Cover: copy out of the import work dir into the book's folder.
            var coverPath: String? = null
            val cover = planned.coverFile
            if (cover != null && cover.isFile) {
                val ext = cover.name.substringAfterLast('.', "img").lowercase()
                val dest = bookFiles.coverFile(bookId, ".$ext")
                cover.copyTo(dest, overwrite = true)
                coverPath = dest.absolutePath
            }
            val sizeBytes = insertChapterBatches(bookId, planned, isTranslation, now)
            tx.run {
                val book = bookDao.getById(bookId) ?: return@run
                bookDao.update(
                    book.copy(
                        coverPath = coverPath,
                        chapterCountCache = planned.chapters.size,
                        sizeBytesCache = sizeBytes,
                        updatedAt = clock()
                    )
                )
            }
            AppResult.Ok(bookId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never leave a half-imported book behind (§7.1 failure honesty).
            runCatching { if (bookId != 0L) bookDao.deleteById(bookId) }
            runCatching { if (bookId != 0L) bookFiles.deleteBook(bookId) }
            AppResult.Err(AppError.StorageFailed("saving the book"))
        }
    }

    /**
     * Inserts chapters in transactions of [IMPORT_BATCH_SIZE], building
     * each batch's entities from its temp files. Returns the total UTF-8
     * byte size of all chapter text.
     */
    private suspend fun insertChapterBatches(
        bookId: Long,
        planned: PlannedBook,
        isTranslation: Boolean,
        now: Long,
        startNumber: Int = 0
    ): Long {
        var sizeBytes = 0L
        var number = startNumber
        for (batch in planned.chapters.chunked(IMPORT_BATCH_SIZE)) {
            val entities = batch.map { parsed ->
                number++
                val text = parsed.file.readText(Charsets.UTF_8)
                sizeBytes += text.toByteArray(Charsets.UTF_8).size
                ChapterEntity(
                    bookId = bookId,
                    number = number,
                    title = parsed.title,
                    sourceText = if (isTranslation) null else text,
                    translatedText = if (isTranslation) text else null,
                    origin = if (isTranslation) {
                        ChapterOrigin.IMPORTED_TRANSLATION.name
                    } else {
                        ChapterOrigin.IMPORTED_SOURCE.name
                    },
                    status = if (isTranslation) {
                        ChapterStatus.TRANSLATED.name
                    } else {
                        ChapterStatus.PENDING.name
                    },
                    updatedAt = now
                )
            }
            tx.run { chapterDao.insertAll(entities) }
        }
        return sizeBytes
    }

    /**
     * P8 (§9.3 "Add chapters"): appends a planned import to an EXISTING
     * book, continuing its chapter numbering. The book's own language
     * pair decides whether the new chapters are sources or imported
     * translations. Returns how many chapters were added.
     */
    suspend fun appendImport(bookId: Long, planned: PlannedBook): AppResult<Int> {
        val book = bookDao.getById(bookId)
            ?: return AppResult.Err(AppError.StorageFailed("finding the book"))
        val now = clock()
        return try {
            val existingCount = chapterDao.countForBook(bookId)
            val isTranslation = book.sourceLang == book.targetLang
            val addedBytes = insertChapterBatches(
                bookId = bookId,
                planned = planned,
                isTranslation = isTranslation,
                now = now,
                startNumber = existingCount
            )
            tx.run {
                val latest = bookDao.getById(bookId) ?: return@run
                bookDao.update(
                    latest.copy(
                        chapterCountCache = existingCount + planned.chapters.size,
                        sizeBytesCache = latest.sizeBytesCache + addedBytes,
                        updatedAt = now
                    )
                )
            }
            AppResult.Ok(planned.chapters.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Err(AppError.StorageFailed("adding chapters"))
        }
    }

    /**
     * P8 (§9.3 "Paste a chapter"): appends one pasted chapter to a
     * book. Returns the new chapter's number.
     */
    suspend fun addChapter(
        bookId: Long,
        title: String?,
        text: String
    ): AppResult<Int> {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            return AppResult.Err(
                AppError.VerificationFailed("paste the chapter text first")
            )
        }
        val book = bookDao.getById(bookId)
            ?: return AppResult.Err(AppError.StorageFailed("finding the book"))
        val now = clock()
        val number = chapterDao.countForBook(bookId) + 1
        val isTranslation = book.sourceLang == book.targetLang
        return try {
            tx.run {
                chapterDao.insert(
                    ChapterEntity(
                        bookId = bookId,
                        number = number,
                        title = title?.trim()?.takeIf { it.isNotEmpty() }
                            ?: "Chapter $number",
                        sourceText = if (isTranslation) null else trimmed,
                        translatedText = if (isTranslation) trimmed else null,
                        origin = if (isTranslation) {
                            ChapterOrigin.IMPORTED_TRANSLATION.name
                        } else {
                            ChapterOrigin.IMPORTED_SOURCE.name
                        },
                        status = if (isTranslation) {
                            ChapterStatus.TRANSLATED.name
                        } else {
                            ChapterStatus.PENDING.name
                        },
                        updatedAt = now
                    )
                )
                val latest = bookDao.getById(bookId) ?: return@run
                bookDao.update(
                    latest.copy(
                        chapterCountCache = number,
                        sizeBytesCache = latest.sizeBytesCache +
                            trimmed.toByteArray(Charsets.UTF_8).size,
                        updatedAt = now
                    )
                )
            }
            AppResult.Ok(number)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Err(AppError.StorageFailed("adding the chapter"))
        }
    }

    /**
     * v1.1.3 (§C): the standalone chapter's souvenir — when the user
     * answers "Save this base for future chapters", the processed
     * chapter is filed into the new book with BOTH texts kept, so
     * Review and the book's Knowledge Base apply to it like any other
     * chapter. [origin] records how the chapter arrived.
     */
    suspend fun addChapterWithTexts(
        bookId: Long,
        title: String,
        sourceText: String?,
        translatedText: String?,
        origin: ChapterOrigin
    ): AppResult<Int> {
        val book = bookDao.getById(bookId)
            ?: return AppResult.Err(AppError.StorageFailed("finding the book"))
        val now = clock()
        val number = chapterDao.countForBook(bookId) + 1
        val bytes = (sourceText?.toByteArray(Charsets.UTF_8)?.size ?: 0) +
            (translatedText?.toByteArray(Charsets.UTF_8)?.size ?: 0)
        return try {
            tx.run {
                chapterDao.insert(
                    ChapterEntity(
                        bookId = bookId,
                        number = number,
                        title = title.trim().takeIf { it.isNotEmpty() }
                            ?: "Chapter $number",
                        sourceText = sourceText,
                        translatedText = translatedText,
                        origin = origin.name,
                        status = if (translatedText != null) {
                            ChapterStatus.TRANSLATED.name
                        } else {
                            ChapterStatus.PENDING.name
                        },
                        updatedAt = now
                    )
                )
                val latest = bookDao.getById(bookId) ?: return@run
                bookDao.update(
                    latest.copy(
                        chapterCountCache = number,
                        sizeBytesCache = latest.sizeBytesCache + bytes,
                        updatedAt = now
                    )
                )
            }
            AppResult.Ok(number)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Err(AppError.StorageFailed("adding the chapter"))
        }
    }

    /** "Type details myself" (§9.3): an empty book shell to fill later. */
    suspend fun createEmptyBook(choices: ImportChoices): AppResult<Long> =
        runCatchingAppSuspend({ AppError.StorageFailed("saving the book") }) {
            val now = clock()
            tx.run {
                bookDao.insert(
                    BookEntity(
                        titleOriginal = choices.title,
                        author = choices.author,
                        sourceLang = choices.sourceLang,
                        targetLang = choices.targetLang,
                        genrePresetId = choices.genrePresetId,
                        qualityMode = choices.qualityMode,
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }
        }

    suspend fun updateBook(book: BookEntity): AppResult<Unit> =
        runCatchingAppSuspend({ AppError.StorageFailed("saving the book") }) {
            bookDao.update(book.copy(updatedAt = clock()))
        }

    /** Deleting a book deletes its rows (FK cascade) and its files (§8.2). */
    suspend fun deleteBook(bookId: Long, bookFiles: BookFiles): AppResult<Unit> =
        runCatchingAppSuspend({ AppError.StorageFailed("saving the book") }) {
            bookDao.deleteById(bookId)
            bookFiles.deleteBook(bookId)
        }

    /**
     * v1.1 (workstream E): the user-facing "Remove book". Order matters:
     *  1. [cancelJobs] runs FIRST — a running translation/study job must
     *     be stopped before its rows vanish (the caller wires the real
     *     scheduler; its WorkManager cancel goes by the book's tag, so
     *     translate AND study work both stop).
     *  2. ONE transaction then deletes every row the book owns: job
     *     history, sample chapters + sets, KB entries + the KB itself,
     *     chapters (with their partial-chunk state), then the book row.
     *     The deletes are explicit (not just FK cascade) so the contract
     *     holds even if a future schema change loosens a foreign key.
     *     KB ownership: in this schema a Knowledge Base belongs to
     *     exactly one book (`knowledge_bases.bookId`) — no other book
     *     can share it, so removing it with the book touches nothing
     *     else's learned names.
     *  3. The book's app-private files (cover, samples, flags) go last.
     * Cached translations are global (keyed by text hash, shared by all
     * books) and deliberately survive.
     */
    suspend fun removeBook(
        bookId: Long,
        bookFiles: BookFiles,
        cancelJobs: suspend (Long) -> Unit = {}
    ): AppResult<Unit> =
        runCatchingAppSuspend({ AppError.StorageFailed("removing the book") }) {
            cancelJobs(bookId)
            tx.run {
                jobRunDao?.deleteForBook(bookId)
                sampleDao?.deleteChaptersForBook(bookId)
                sampleDao?.deleteSetsForBook(bookId)
                knowledgeBaseDao.deleteEntriesForBook(bookId)
                knowledgeBaseDao.deleteKbsForBook(bookId)
                chapterDao.deleteForBook(bookId)
                bookDao.deleteById(bookId)
            }
            bookFiles.deleteBook(bookId)
        }

    companion object {
        /** §7.1: chapters are inserted in batches of 50 per transaction. */
        const val IMPORT_BATCH_SIZE = 50
    }
}

/** Holds the one in-flight [ImportSession] between Library and the review screen. */
class ImportSessionStore {
    var current: ImportSession? = null
        private set

    fun set(session: ImportSession) {
        current = session
    }

    fun peek(): ImportSession? = current

    fun clear() {
        current = null
    }
}
