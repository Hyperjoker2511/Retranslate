package app.retranslate.domain.kb

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.data.db.SampleChapterEntity
import app.retranslate.data.db.SampleDao
import app.retranslate.data.db.SampleSetEntity
import app.retranslate.data.db.TxRunner
import app.retranslate.data.files.PickedFile
import app.retranslate.domain.books.BookImporter
import kotlinx.coroutines.CancellationException

/**
 * Sample-file import (§6.1): the user's sample chapters arrive as
 * SEPARATE FILES (settled decision, 2026-10-27). Each file runs
 * through the exact §7.1 book-import pipeline (streamed staging,
 * format sniff, streaming parse, planner) — only the destination
 * differs: parsed chapters land in `sample_chapters` under a
 * [SampleSetEntity] for the book, ready for study ranges without
 * re-picking files. The import session's temp files are always
 * discarded afterwards; nothing reaches the book's own chapters.
 */
class SampleImporter(
    private val importer: BookImporter,
    private val sampleDao: SampleDao,
    private val tx: TxRunner
) {

    suspend fun importSample(
        bookId: Long,
        picked: PickedFile,
        onProgress: (app.retranslate.domain.books.ImportProgress) -> Unit = {}
    ): AppResult<SampleSetEntity> {
        val session = when (
            val result = importer.importFile(
                openStream = picked.openStream,
                displayName = picked.displayName,
                sizeBytes = picked.sizeBytes,
                onProgress = onProgress
            )
        ) {
            is AppResult.Err -> return result
            is AppResult.Ok -> result.value
        }
        return try {
            val chapters = session.planned.chapters.mapIndexed { index, parsed ->
                SampleChapterEntity(
                    sampleSetId = 0, // replaced below, after the set exists
                    number = index + 1,
                    text = parsed.file.readText(Charsets.UTF_8)
                )
            }
            if (chapters.isEmpty() || chapters.all { it.text.isBlank() }) {
                return AppResult.Err(
                    AppError.BadFile("no readable chapters were found in that file")
                )
            }
            tx.run {
                val setId = sampleDao.insertSet(
                    SampleSetEntity(
                        bookId = bookId,
                        fileName = picked.displayName,
                        detectedLang = session.detectedLang ?: "",
                        chapterCount = chapters.size
                    )
                )
                // Insert in the same 50-row batches the book import uses.
                chapters.chunked(50).forEach { batch ->
                    sampleDao.insertChapters(batch.map { it.copy(sampleSetId = setId) })
                }
                SampleSetEntity(
                    id = setId,
                    bookId = bookId,
                    fileName = picked.displayName,
                    detectedLang = session.detectedLang ?: "",
                    chapterCount = chapters.size
                )
            }.let { AppResult.Ok(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Err(AppError.StorageFailed("saving the samples"))
        } finally {
            importer.discard(session)
        }
    }
}
