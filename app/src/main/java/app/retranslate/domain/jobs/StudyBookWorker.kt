package app.retranslate.domain.jobs

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.retranslate.ReTranslateApp
import app.retranslate.domain.kb.StudyResult
import app.retranslate.domain.kb.StudySelectionCodec
import kotlinx.coroutines.CancellationException

/**
 * The WorkManager adapter over [app.retranslate.domain.kb.StudyEngine]
 * (PLAN.md §6.1: the study job shows progress and is cancellable).
 * Finished → success; failed → failure (nothing was written, §6.2);
 * cancelled → success. A real cancellation propagates untouched —
 * the engine checks the JobRun row before writing anything, so a
 * killed study never lands a half-built Knowledge Base.
 */
class StudyBookWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val bookId = inputData.getLong(KEY_BOOK_ID, -1L)
        val jobRunId = inputData.getLong(KEY_JOB_RUN_ID, -1L)
        val spec = inputData.getString(KEY_SELECTION_SPEC).orEmpty()
        if (bookId <= 0 || jobRunId <= 0 || spec.isBlank()) return Result.failure()

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
                container.studyEngine.run(
                    bookId = bookId,
                    jobRunId = jobRunId,
                    selection = StudySelectionCodec.decode(spec)
                )
            ) {
                is StudyResult.Finished -> Result.success()
                is StudyResult.Failed -> Result.failure()
                StudyResult.Cancelled -> Result.success()
            }
        } catch (ce: CancellationException) {
            throw ce
        }
    }

    companion object {
        const val KEY_BOOK_ID = "bookId"
        const val KEY_JOB_RUN_ID = "jobRunId"
        const val KEY_SELECTION_SPEC = "selectionSpec"
    }
}
