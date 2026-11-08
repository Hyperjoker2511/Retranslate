package app.retranslate.domain.export

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.core.error.runCatchingAppSuspend
import app.retranslate.data.db.BookDao
import app.retranslate.data.db.ChapterDao
import app.retranslate.data.db.JobKind
import app.retranslate.data.db.JobRunDao
import app.retranslate.data.db.JobRunEntity
import app.retranslate.data.db.JobState
import app.retranslate.data.files.TempFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.format.DateTimeFormatter

enum class ExportFormat(val extension: String, val mimeType: String) {
    EPUB("epub", "application/epub+zip"),
    TXT("txt", "text/plain")
}

/** A finished export: the temp file to hand to SAF/Share, and its facts. */
data class ExportOutcome(
    val file: File,
    val fileName: String,
    val mimeType: String,
    val includedChapters: Int,
    /** Chapters left out because they aren't translated yet (§7.5). */
    val skippedChapters: Int,
    val totalChapters: Int
)

/**
 * Book export (PLAN.md §7.5): translated chapters only, streamed from
 * Room page by page into a temp file — the book's text is never held
 * in memory (§7.3). The temp file is the delivery unit: the UI copies
 * it to the user's SAF destination or shares it (FileProvider), and
 * the §7.5 cache sweep reclaims it later. A failed export deletes
 * its temp file and reports [AppError.ExportFailed] — never a silent
 * partial file (§3.3). Progress and the outcome counts feed the
 * pre/post-export dialogs ("38 of 412 chapters aren't translated yet —
 * they'll be left out").
 *
 * Exports are mirrored as EXPORT job runs (the Jobs tab shows them);
 * the row is bookkeeping, never the source of truth for the file.
 */
class BookExporter(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val jobRunDao: JobRunDao,
    private val tempFiles: TempFiles,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /** The counts the pre-export dialog states before anything is written. */
    data class ExportPreview(
        val totalChapters: Int,
        val translatedChapters: Int
    ) {
        val skippedChapters: Int get() = totalChapters - translatedChapters
    }

    suspend fun preview(bookId: Long): ExportPreview {
        val total = chapterDao.countForBook(bookId)
        var translated = 0
        var offset = 0
        while (true) {
            val page = chapterDao.getTranslatedExportPage(bookId, PAGE_SIZE, offset)
            if (page.isEmpty()) break
            // A chapter counts as translated when it HAS usable text —
            // the same rule the writers apply, so the dialog's numbers
            // always match what lands in the file.
            translated += page.count { !it.translatedText.isNullOrBlank() }
            if (page.size < PAGE_SIZE) break
            offset += page.size
        }
        return ExportPreview(total, translated)
    }

    suspend fun exportBook(
        bookId: Long,
        format: ExportFormat,
        includeTranslatorNote: Boolean = true,
        onProgress: (fraction: Float?) -> Unit = {}
    ): AppResult<ExportOutcome> {
        val book = bookDao.getById(bookId)
            ?: return AppResult.Err(AppError.ExportFailed("this book isn't in the library"))
        val preview = preview(bookId)
        if (preview.totalChapters == 0) {
            return AppResult.Err(AppError.ExportFailed("this book has no chapters yet"))
        }
        if (preview.translatedChapters == 0) {
            return AppResult.Err(
                AppError.ExportFailed("no chapters are translated yet")
            )
        }
        val runId = startRunRow(bookId, preview.totalChapters)
        val exportDir = tempFiles.newExportDir()
        // The file is named for the title the reader knows — the
        // translated title when there is one (same rule as the
        // book's own title page, §7.5).
        val displayTitle = book.titleTranslated?.takeIf { it.isNotBlank() }
            ?: book.titleOriginal
        val fileName = fileNameFor(displayTitle, bookId, format)
        val outFile = File(exportDir, fileName)
        val result = runCatchingAppSuspend({ AppError.ExportFailed("couldn't write the file") }) {
            withContext(Dispatchers.IO) {
                outFile.outputStream().buffered().use { out ->
                    when (format) {
                        ExportFormat.EPUB -> writeEpub(
                            bookId, book, includeTranslatorNote, preview, out, onProgress
                        )
                        ExportFormat.TXT -> writeTxt(bookId, out, preview, onProgress)
                    }
                }
            }
            ExportOutcome(
                file = outFile,
                fileName = fileName,
                mimeType = format.mimeType,
                includedChapters = preview.translatedChapters,
                skippedChapters = preview.skippedChapters,
                totalChapters = preview.totalChapters
            )
        }
        when (result) {
            is AppResult.Ok -> finishRunRow(runId, JobState.FINISHED, preview.translatedChapters)
            is AppResult.Err -> {
                tempFiles.cleanup(exportDir)
                finishRunRow(runId, JobState.FAILED, 0, result.error.javaClass.simpleName)
            }
        }
        return result
    }

    /**
     * Pulls translated chapters page by page (§7.3) and feeds them to
     * [consume] one at a time. Returns how many were delivered.
     */
    private suspend fun streamChapters(
        bookId: Long,
        totalTranslated: Int,
        onProgress: (Float?) -> Unit,
        consume: suspend (ExportChapter) -> Unit
    ): Int {
        var delivered = 0
        var offset = 0
        while (true) {
            val page = chapterDao.getTranslatedExportPage(bookId, PAGE_SIZE, offset)
            if (page.isEmpty()) break
            for (slice in page) {
                val text = slice.translatedText?.takeIf { it.isNotBlank() } ?: continue
                consume(ExportChapter(slice.number, slice.title, text))
                delivered++
                onProgress(
                    if (totalTranslated > 0) delivered.toFloat() / totalTranslated else null
                )
            }
            if (page.size < PAGE_SIZE) break
            offset += page.size
        }
        return delivered
    }

    private suspend fun writeEpub(
        bookId: Long,
        book: app.retranslate.data.db.BookEntity,
        includeTranslatorNote: Boolean,
        preview: ExportPreview,
        out: OutputStream,
        onProgress: (Float?) -> Unit
    ) {
        // The pull source for the writer: a small page buffer over the
        // DAO, so the writer stays streaming while the DAO stays paged.
        // All state is local to this call — exports never share it.
        val buffer = ArrayDeque<ExportChapter>()
        var offset = 0
        var exhausted = false
        var delivered = 0
        EpubWriter().write(
            meta = ExportBookMeta(
                title = book.titleTranslated?.takeIf { it.isNotBlank() }
                    ?: book.titleOriginal,
                author = book.author,
                language = book.targetLang,
                includeTranslatorNote = includeTranslatorNote,
                identifier = EpubWriter.newIdentifier(),
                modifiedUtc = DateTimeFormatter.ISO_INSTANT.format(
                    Instant.ofEpochMilli(clock())
                )
            ),
            out = out,
            nextChapter = {
                while (buffer.isEmpty() && !exhausted) {
                    val page = chapterDao.getTranslatedExportPage(bookId, PAGE_SIZE, offset)
                    offset += page.size
                    if (page.isEmpty() || page.size < PAGE_SIZE) exhausted = true
                    for (slice in page) {
                        slice.translatedText?.takeIf { it.isNotBlank() }?.let {
                            buffer.addLast(ExportChapter(slice.number, slice.title, it))
                        }
                    }
                }
                val next = buffer.removeFirstOrNull()
                if (next != null) {
                    delivered++
                    onProgress(
                        if (preview.translatedChapters > 0) {
                            delivered.toFloat() / preview.translatedChapters
                        } else {
                            null
                        }
                    )
                }
                next
            }
        )
    }

    private suspend fun writeTxt(
        bookId: Long,
        out: OutputStream,
        preview: ExportPreview,
        onProgress: (Float?) -> Unit
    ) {
        val writer = out.bufferedWriter(Charsets.UTF_8)
        var first = true
        streamChapters(bookId, preview.translatedChapters, onProgress) { chapter ->
            if (!first) writer.write("\n\n")
            first = false
            writer.write(chapter.displayTitle)
            writer.write("\n\n")
            writer.write(
                EpubWriter.paragraphsOf(chapter.text).joinToString("\n\n")
            )
        }
        writer.write("\n")
        writer.flush()
    }

    // -- EXPORT job-run mirror ------------------------------------------

    private suspend fun startRunRow(bookId: Long, total: Int): Long? = try {
        jobRunDao.insert(
            JobRunEntity(
                bookId = bookId,
                kind = JobKind.EXPORT.name,
                state = JobState.RUNNING.name,
                totalChapters = total,
                startedAt = clock()
            )
        )
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null // bookkeeping must never fail the export itself
    }

    private suspend fun finishRunRow(
        runId: Long?,
        state: JobState,
        done: Int,
        errorClass: String? = null
    ) {
        if (runId == null) return
        try {
            val run = jobRunDao.getById(runId) ?: return
            jobRunDao.update(
                run.copy(
                    state = state.name,
                    doneChapters = done,
                    finishedAt = clock(),
                    lastErrorClass = errorClass
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Bookkeeping only — the file (or its typed error) already exists.
        }
    }

    companion object {
        const val PAGE_SIZE = 50

        /** A filesystem-safe file name from the book's title. */
        fun fileNameFor(title: String, bookId: Long, format: ExportFormat): String {
            val cleaned = title.trim()
                .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
                .take(80)
                .trimEnd('.', ' ')
            val base = cleaned.ifEmpty { "book-$bookId" }
            return "$base.${format.extension}"
        }
    }
}
