package app.retranslate.domain.jobs

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.retranslate.ReTranslateApp
import kotlinx.coroutines.CancellationException

/**
 * A whole-book OFFLINE translation job (v1.1.3 §H): the same
 * [BookTranslationEngine] discipline (progress, pause/resume,
 * cancel, counters) over a chapter translator whose only provider
 * is the on-device ML Kit engine. Enqueued with NO network
 * requirement — needing connectivity for an offline job would be
 * absurd. Chapters it produces are marked translatedBy = OFFLINE,
 * which is what the Improve scope "Offline-translated" reads (§H).
 */
class OfflineBookWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val bookId = inputData.getLong(KEY_BOOK_ID, -1L)
        val jobRunId = inputData.getLong(KEY_JOB_RUN_ID, -1L)
        if (bookId <= 0 || jobRunId <= 0) return Result.failure()

        val container = (applicationContext as ReTranslateApp).container
        val book = container.bookRepository.getBook(bookId) ?: return Result.failure()
        val notifications = container.jobNotifications
        notifications.ensureChannel()
        setForeground(
            notifications.foregroundInfo(
                id, jobRunId, bookId, book.titleOriginal,
                done = 0, total = 0, currentChapter = 0
            )
        )
        return try {
            when (
                val result = container.offlineBookTranslationEngine.run(
                    bookId = bookId,
                    jobRunId = jobRunId,
                    onProgress = { done, total, chapter ->
                        setForeground(
                            notifications.foregroundInfo(
                                id, jobRunId, bookId, book.titleOriginal,
                                done = done, total = total, currentChapter = chapter
                            )
                        )
                    }
                )
            ) {
                is EngineResult.Finished -> Result.success()
                EngineResult.PausedByUser -> Result.success()
                EngineResult.PausedServersBusy -> Result.retry()
                is EngineResult.Aborted -> Result.failure()
            }
        } catch (ce: CancellationException) {
            throw ce
        }
    }

    companion object {
        const val KEY_BOOK_ID = "bookId"
        const val KEY_JOB_RUN_ID = "jobRunId"
    }
}
