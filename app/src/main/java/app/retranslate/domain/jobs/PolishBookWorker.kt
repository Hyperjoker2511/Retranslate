package app.retranslate.domain.jobs

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.retranslate.ReTranslateApp
import app.retranslate.domain.translate.ImproveStrength
import app.retranslate.domain.translate.PolishScope
import kotlinx.coroutines.CancellationException

/**
 * The WorkManager adapter over [BookPolishEngine] (v1.1.3 §A2):
 * same result mapping as the translate worker — finished / user
 * pause → success; quota pause (servers busy) → retry after the
 * backoff, the run row still saying PAUSED with its counters and
 * scope intact, so the retry continues the same job.
 */
class PolishBookWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val bookId = inputData.getLong(KEY_BOOK_ID, -1L)
        val jobRunId = inputData.getLong(KEY_JOB_RUN_ID, -1L)
        val scope = PolishScope.fromName(inputData.getString(KEY_SCOPE))
        val strength = ImproveStrength.fromName(inputData.getString(KEY_STRENGTH))
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
                val result = container.bookPolishEngine.run(
                    bookId = bookId,
                    jobRunId = jobRunId,
                    scope = scope,
                    strength = strength,
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
        const val KEY_SCOPE = "polishScope"
        const val KEY_STRENGTH = "polishStrength"
    }
}
