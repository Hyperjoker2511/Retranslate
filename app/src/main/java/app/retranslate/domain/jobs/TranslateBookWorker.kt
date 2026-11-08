package app.retranslate.domain.jobs

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.retranslate.ReTranslateApp
import kotlinx.coroutines.CancellationException

/**
 * The WorkManager adapter over [BookTranslationEngine] (PLAN.md §7.4):
 * reads its input, keeps the foreground notification honest, and maps
 * the engine's ending onto WorkManager results —
 *  - finished / user-paused → success (the job is done for now);
 *  - servers-busy pause → retry (WorkManager re-runs after the
 *    5-minute backoff, the run row still says PAUSED — servers busy);
 *  - aborted → failure.
 * A real cancellation (user Cancel, OS stop) propagates as
 * [CancellationException]: completed chapters and the in-flight
 * chapter's partial chunks are already persisted, so nothing is lost
 * and nothing here needs to write state on the way out.
 */
class TranslateBookWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val bookId = inputData.getLong(KEY_BOOK_ID, -1L)
        val jobRunId = inputData.getLong(KEY_JOB_RUN_ID, -1L)
        val onlyChapterId = inputData.getLong(KEY_ONLY_CHAPTER_ID, -1L).takeIf { it > 0 }
        // P5: a chapter-range job carries its scope as a comma list.
        val onlyChapterIds = inputData.getString(KEY_ONLY_CHAPTER_IDS)
            ?.split(",")?.mapNotNull { it.trim().toLongOrNull() }?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: onlyChapterId?.let { setOf(it) }
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
        // v1.1.3 (§I): the run's stored engine pick decides which
        // engine instance runs it — a specific pick means a
        // single-provider engine; no pick means the Automatic one.
        val runRow = container.database.jobRunDao().getById(jobRunId)
        val engine = container.bookEngineForPick(runRow?.providerPick)
        return try {
            when (
                val result = engine.run(
                    bookId = bookId,
                    jobRunId = jobRunId,
                    onlyChapterIds = onlyChapterIds,
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
        const val KEY_ONLY_CHAPTER_ID = "onlyChapterId"
        const val KEY_ONLY_CHAPTER_IDS = "onlyChapterIds"
    }
}
