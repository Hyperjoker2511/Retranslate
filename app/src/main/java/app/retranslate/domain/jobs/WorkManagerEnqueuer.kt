package app.retranslate.domain.jobs

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * The production [WorkEnqueuer]: WorkManager with unique work per job
 * (PLAN.md §7.4). Book jobs are named `translate-book-<id>`, single-
 * chapter jobs `translate-chapter-<id>`; every request carries the
 * `book-<id>` tag so Cancel stops all of a book's work at once.
 *
 * Backoff: LINEAR, 5-minute base — the only worker-level retry is the
 * engine's servers-busy self-pause, and 5 minutes matches the §4.4.2
 * circuit-breaker window. (The plan §7.4 also mentions a 30s base for
 * generic worker retries; there is no generic retry path in v1 —
 * provider retries live in §3.4, inside the providers. Deviation noted
 * in the P4 report.)
 */
class WorkManagerEnqueuer(private val context: Context) :
    WorkEnqueuer, app.retranslate.domain.kb.StudyEnqueuer {

    override fun enqueue(bookId: Long, jobRunId: Long, onlyChapterId: Long?, replace: Boolean) {
        val request = OneTimeWorkRequestBuilder<TranslateBookWorker>()
            .setInputData(
                workDataOf(
                    TranslateBookWorker.KEY_BOOK_ID to bookId,
                    TranslateBookWorker.KEY_JOB_RUN_ID to jobRunId,
                    TranslateBookWorker.KEY_ONLY_CHAPTER_ID to (onlyChapterId ?: -1L)
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, BUSY_RETRY_MINUTES, TimeUnit.MINUTES)
            .addTag(tagFor(bookId))
            .build()
        val name = if (onlyChapterId != null) {
            "translate-chapter-$onlyChapterId"
        } else {
            "translate-book-$bookId"
        }
        WorkManager.getInstance(context).enqueueUniqueWork(
            name,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
    }

    override fun enqueueChapters(
        bookId: Long, jobRunId: Long, chapterIds: Set<Long>, replace: Boolean
    ) {
        val request = OneTimeWorkRequestBuilder<TranslateBookWorker>()
            .setInputData(
                workDataOf(
                    TranslateBookWorker.KEY_BOOK_ID to bookId,
                    TranslateBookWorker.KEY_JOB_RUN_ID to jobRunId,
                    TranslateBookWorker.KEY_ONLY_CHAPTER_IDS to
                        chapterIds.joinToString(",")
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, BUSY_RETRY_MINUTES, TimeUnit.MINUTES)
            .addTag(tagFor(bookId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "translate-book-$bookId",
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
    }

    override fun cancelBook(bookId: Long) {
        WorkManager.getInstance(context).cancelAllWorkByTag(tagFor(bookId))
    }

    // ---- v1.1.3: polish jobs (unique name polish-book-<id>, same tag) --

    override fun enqueuePolish(
        bookId: Long, jobRunId: Long, scope: String, strength: String, replace: Boolean
    ) {
        val request = OneTimeWorkRequestBuilder<PolishBookWorker>()
            .setInputData(
                workDataOf(
                    PolishBookWorker.KEY_BOOK_ID to bookId,
                    PolishBookWorker.KEY_JOB_RUN_ID to jobRunId,
                    PolishBookWorker.KEY_SCOPE to scope,
                    PolishBookWorker.KEY_STRENGTH to strength
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, BUSY_RETRY_MINUTES, TimeUnit.MINUTES)
            .addTag(tagFor(bookId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "polish-book-$bookId",
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
    }

    // ---- v1.1.3: offline jobs — deliberately NO network constraint ----

    override fun enqueueOffline(bookId: Long, jobRunId: Long, replace: Boolean) {
        val request = OneTimeWorkRequestBuilder<OfflineBookWorker>()
            .setInputData(
                workDataOf(
                    OfflineBookWorker.KEY_BOOK_ID to bookId,
                    OfflineBookWorker.KEY_JOB_RUN_ID to jobRunId
                )
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, BUSY_RETRY_MINUTES, TimeUnit.MINUTES)
            .addTag(tagFor(bookId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "offline-book-$bookId",
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
    }

    // ---- P5: study jobs (unique name study-book-<id>, same book tag) --

    override fun enqueueStudy(bookId: Long, jobRunId: Long, selectionSpec: String, replace: Boolean) {
        val request = OneTimeWorkRequestBuilder<StudyBookWorker>()
            .setInputData(
                workDataOf(
                    StudyBookWorker.KEY_BOOK_ID to bookId,
                    StudyBookWorker.KEY_JOB_RUN_ID to jobRunId,
                    StudyBookWorker.KEY_SELECTION_SPEC to selectionSpec
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(tagFor(bookId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "study-book-$bookId",
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
    }

    override fun cancelStudy(bookId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork("study-book-$bookId")
    }

    companion object {
        const val BUSY_RETRY_MINUTES = 5L
        fun tagFor(bookId: Long): String = "book-$bookId"
    }
}
