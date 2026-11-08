package app.retranslate.domain.jobs

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import kotlinx.coroutines.CancellationException
import app.retranslate.data.db.BookDao
import app.retranslate.data.db.ChapterDao
import app.retranslate.data.db.DbEnums
import app.retranslate.data.db.JobKind
import app.retranslate.data.db.JobRunDao
import app.retranslate.data.db.JobRunEntity
import app.retranslate.data.db.JobState

/**
 * The WorkManager boundary, small enough to fake in tests: the
 * scheduler's decisions (which run row, which policy, which state) are
 * the logic worth testing; the enqueuer is a dumb adapter.
 */
interface WorkEnqueuer {
    fun enqueue(bookId: Long, jobRunId: Long, onlyChapterId: Long?, replace: Boolean)
    fun cancelBook(bookId: Long)

    /**
     * P5 (§6.1 target ranges): a job scoped to a SET of chapters.
     * The default falls back to book scope — production overrides it;
     * the engine honours whatever scope the work input carries.
     */
    fun enqueueChapters(bookId: Long, jobRunId: Long, chapterIds: Set<Long>, replace: Boolean) {
        enqueue(bookId, jobRunId, onlyChapterId = null, replace = replace)
    }

    /**
     * v1.1.3 (§A2): a bulk-Improve (POLISH) job. The default degrades
     * to a plain enqueue — production overrides it with the polish
     * worker; the scheduler's decisions are what's tested.
     */
    fun enqueuePolish(
        bookId: Long,
        jobRunId: Long,
        scope: String,
        strength: String,
        replace: Boolean
    ) {
        enqueue(bookId, jobRunId, onlyChapterId = null, replace = replace)
    }

    /**
     * v1.1.3 (§H): a whole-book Offline job — production enqueues
     * the offline worker with NO network requirement.
     */
    fun enqueueOffline(bookId: Long, jobRunId: Long, replace: Boolean) {
        enqueue(bookId, jobRunId, onlyChapterId = null, replace = replace)
    }
}

/**
 * Owns job lifecycle decisions (PLAN.md §7.4): one job per book, KEEP
 * for a second start tap (never a duplicate), REPLACE only for resume
 * (clears a backoff-pending retry), the JobRun mirror row the UI reads,
 * and the pause/resume/cancel state writes the engine honours.
 *
 * Scope note: a JobRun row doesn't store a chapter scope. Single-
 * chapter jobs carry their chapter in the WORK input (which WorkManager
 * persists), and Resume always means "this book's unfinished chapters"
 * — resuming a failed single chapter via the book page's Retry does
 * exactly that chapter again.
 */
class JobScheduler(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val jobRunDao: JobRunDao,
    private val enqueuer: WorkEnqueuer,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** v1.1.3 (§G): FLAGGED polish scope's signal (chapter flags). */
    private val flaggedChapterIds: suspend (bookId: Long) -> Set<Long> = { emptySet() },
    /** v1.1.3 (§G): the book's active KB version, for polish scopes. */
    private val activeKbVersion: suspend (bookId: Long) -> Int? = { null }
) {

    /**
     * Start (or report) the book's translate-everything job.
     * [providerPick] (§I): a ProviderId name (or gateway slot key)
     * when the user picked ONE engine for this job; null = Automatic.
     * The pick is stored on the run row — the worker resolves it,
     * and Resume keeps it.
     */
    suspend fun translateBook(
        bookId: Long,
        providerPick: String? = null
    ): AppResult<Long> =
        guarded("starting the job") {
            bookDao.getById(bookId)
                ?: return AppResult.Err(AppError.StorageFailed("loading the book"))
            val latest = jobRunDao.latestForBook(bookId)
            when (latest?.let { DbEnums.jobState(it.state) }) {
                JobState.QUEUED, JobState.RUNNING -> return AppResult.Ok(latest!!.id)
                JobState.PAUSED -> return resumeLocked(latest)
                else -> Unit
            }
            val total = chapterDao.countTranslatable(bookId)
            val runId = jobRunDao.insert(
                JobRunEntity(
                    bookId = bookId,
                    kind = JobKind.TRANSLATE.name,
                    state = JobState.QUEUED.name,
                    totalChapters = total,
                    providerPick = providerPick,
                    startedAt = clock()
                )
            )
            enqueuer.enqueue(bookId, runId, onlyChapterId = null, replace = false)
            AppResult.Ok(runId)
        }

    /**
     * v1.1.3 (§H): start the book's whole-book Offline job. The run
     * is a TRANSLATE run whose providerPick is OFFLINE — Resume
     * routes it back to the offline worker, never the cloud one.
     */
    suspend fun translateBookOffline(bookId: Long): AppResult<Long> =
        guarded("starting the offline job") {
            bookDao.getById(bookId)
                ?: return AppResult.Err(AppError.StorageFailed("loading the book"))
            val latest = jobRunDao.latestForBook(bookId)
            when (latest?.let { DbEnums.jobState(it.state) }) {
                JobState.QUEUED, JobState.RUNNING -> return AppResult.Ok(latest!!.id)
                JobState.PAUSED -> return resumeLocked(latest)
                else -> Unit
            }
            val total = chapterDao.countTranslatable(bookId)
            val runId = jobRunDao.insert(
                JobRunEntity(
                    bookId = bookId,
                    kind = JobKind.TRANSLATE.name,
                    state = JobState.QUEUED.name,
                    totalChapters = total,
                    providerPick = app.retranslate.providers.ProviderId.OFFLINE.name,
                    startedAt = clock()
                )
            )
            enqueuer.enqueueOffline(bookId, runId, replace = false)
            AppResult.Ok(runId)
        }

    /**
     * v1.1.3 (§A2/§G): start (or report) the book's bulk-Improve job.
     * The estimate is computed with the SAME scope filter the engine
     * runs, so the number the user confirms is the number the job
     * attempts (§G: say plainly what it involves, before it starts).
     */
    suspend fun polishBook(
        bookId: Long,
        scope: app.retranslate.domain.translate.PolishScope,
        strength: app.retranslate.domain.translate.ImproveStrength
    ): AppResult<Long> =
        guarded("starting the improve job") {
            bookDao.getById(bookId)
                ?: return AppResult.Err(AppError.StorageFailed("loading the book"))
            val latest = jobRunDao.latestForBook(bookId)
            when (latest?.let { DbEnums.jobState(it.state) }) {
                JobState.QUEUED, JobState.RUNNING -> return AppResult.Ok(latest!!.id)
                JobState.PAUSED -> return resumeLocked(latest)
                else -> Unit
            }
            val estimate = estimatePolish(bookId, scope)
            val runId = jobRunDao.insert(
                JobRunEntity(
                    bookId = bookId,
                    kind = JobKind.POLISH.name,
                    state = JobState.QUEUED.name,
                    totalChapters = estimate,
                    polishScope = scope.name,
                    polishStrength = strength.name,
                    startedAt = clock()
                )
            )
            enqueuer.enqueuePolish(bookId, runId, scope.name, strength.name, replace = false)
            AppResult.Ok(runId)
        }

    /** The §G pre-flight count for one scope — the engine's own filter. */
    suspend fun estimatePolish(
        bookId: Long,
        scope: app.retranslate.domain.translate.PolishScope
    ): Int {
        val heads = chapterDao.getImproveHeads(bookId)
        return PolishScopeFilter.filter(
            heads, scope, flaggedChapterIds(bookId), activeKbVersion(bookId)
        ).size
    }

    /** Start (or report) a job for one chapter (book-page Translate/Resume/Retry). */
    suspend fun translateChapter(bookId: Long, chapterId: Long): AppResult<Long> =
        guarded("starting the job") {
            val chapter = chapterDao.getChapter(chapterId)
                ?: return AppResult.Err(AppError.StorageFailed("loading the chapter"))
            if (chapter.bookId != bookId) {
                return AppResult.Err(AppError.StorageFailed("loading the chapter"))
            }
            if (chapter.sourceText.isNullOrBlank()) {
                return AppResult.Err(
                    AppError.VerificationFailed("this chapter has no original text")
                )
            }
            val latest = jobRunDao.latestForBook(bookId)
            val latestState = latest?.let { DbEnums.jobState(it.state) }
            if (latestState == JobState.QUEUED || latestState == JobState.RUNNING) {
                // A live job already covers unfinished chapters —
                // including this one. Starting a second writer for the
                // same book would only double provider calls.
                return AppResult.Ok(latest!!.id)
            }
            val runId = jobRunDao.insert(
                JobRunEntity(
                    bookId = bookId,
                    kind = JobKind.TRANSLATE.name,
                    state = JobState.QUEUED.name,
                    totalChapters = 1,
                    startedAt = clock()
                )
            )
            enqueuer.enqueue(bookId, runId, onlyChapterId = chapterId, replace = false)
            AppResult.Ok(runId)
        }

    /**
     * P5 (§6.1): a job for a chosen RANGE of chapters (the study
     * setup sheet's target pickers). Same deference rule as the
     * single-chapter job: a live book job already covers these
     * chapters, so a second writer is never started.
     */
    suspend fun translateChapterRange(bookId: Long, chapterIds: Set<Long>): AppResult<Long> =
        guarded("starting the job") {
            if (chapterIds.isEmpty()) {
                return AppResult.Err(
                    AppError.VerificationFailed("no chapters are selected")
                )
            }
            val latest = jobRunDao.latestForBook(bookId)
            val latestState = latest?.let { DbEnums.jobState(it.state) }
            if (latestState == JobState.QUEUED || latestState == JobState.RUNNING) {
                return AppResult.Ok(latest!!.id)
            }
            if (latestState == JobState.PAUSED) return resumeLocked(latest!!)
            val runId = jobRunDao.insert(
                JobRunEntity(
                    bookId = bookId,
                    kind = JobKind.TRANSLATE.name,
                    state = JobState.QUEUED.name,
                    totalChapters = chapterIds.size,
                    startedAt = clock()
                )
            )
            enqueuer.enqueueChapters(bookId, runId, chapterIds, replace = false)
            AppResult.Ok(runId)
        }

    /**
     * Cooperative pause: write PAUSED; a running engine notices at the
     * next chunk boundary and stops with progress intact. If the work
     * hasn't started yet (still QUEUED) it is cancelled outright —
     * Resume re-enqueues it.
     */
    suspend fun pause(bookId: Long): AppResult<Unit> =
        guarded("pausing the job") {
            val latest = jobRunDao.latestForBook(bookId)
                ?: return AppResult.Ok(Unit)
            when (DbEnums.jobState(latest.state)) {
                JobState.QUEUED -> {
                    enqueuer.cancelBook(bookId)
                    jobRunDao.update(
                        latest.copy(state = JobState.PAUSED.name, lastErrorClass = null)
                    )
                }
                JobState.RUNNING ->
                    jobRunDao.update(
                        latest.copy(state = JobState.PAUSED.name, lastErrorClass = null)
                    )
                else -> Unit
            }
            AppResult.Ok(Unit)
        }

    /** Resume the book's latest paused/failed/cancelled job. */
    suspend fun resume(bookId: Long): AppResult<Long> =
        guarded("resuming the job") {
            val latest = jobRunDao.latestForBook(bookId)
                ?: return translateBook(bookId)
            when (DbEnums.jobState(latest.state)) {
                JobState.QUEUED, JobState.RUNNING -> AppResult.Ok(latest.id)
                JobState.PAUSED, JobState.FAILED, JobState.CANCELLED ->
                    resumeLocked(latest)
                JobState.FINISHED -> when {
                    // v1.1.3: a finished polish run's "again" is a
                    // fresh polish run with the same scope/strength.
                    DbEnums.jobKind(latest.kind) == JobKind.POLISH -> polishBook(
                        bookId,
                        app.retranslate.domain.translate.PolishScope.fromName(
                            latest.polishScope
                        ),
                        app.retranslate.domain.translate.ImproveStrength.fromName(
                            latest.polishStrength
                        )
                    )
                    latest.providerPick ==
                        app.retranslate.providers.ProviderId.OFFLINE.name ->
                        translateBookOffline(bookId)
                    else -> translateBook(bookId, providerPick = latest.providerPick)
                }
            }
        }

    /** Cancel: the row tells the truth first, then the work is cancelled. */
    suspend fun cancel(bookId: Long): AppResult<Unit> =
        guarded("cancelling the job") {
            val latest = jobRunDao.latestForBook(bookId)
            if (latest != null &&
                DbEnums.jobState(latest.state) in ACTIVE_STATES
            ) {
                jobRunDao.update(
                    latest.copy(
                        state = JobState.CANCELLED.name,
                        finishedAt = clock()
                    )
                )
            }
            enqueuer.cancelBook(bookId)
            AppResult.Ok(Unit)
        }

    private suspend fun resumeLocked(run: JobRunEntity): AppResult<Long> {
        jobRunDao.update(
            run.copy(
                state = JobState.QUEUED.name,
                finishedAt = null,
                lastErrorClass = null
            )
        )
        // v1.1.3: the run's own kind/pick decides which worker
        // continues it — a polish run never resumes as a translation.
        when {
            DbEnums.jobKind(run.kind) == JobKind.POLISH ->
                enqueuer.enqueuePolish(
                    run.bookId, run.id,
                    run.polishScope ?: "ALL",
                    run.polishStrength ?: "GENTLE",
                    replace = true
                )
            run.providerPick ==
                app.retranslate.providers.ProviderId.OFFLINE.name ->
                enqueuer.enqueueOffline(run.bookId, run.id, replace = true)
            else ->
                enqueuer.enqueue(run.bookId, run.id, onlyChapterId = null, replace = true)
        }
        return AppResult.Ok(run.id)
    }

    companion object {
        private val ACTIVE_STATES = setOf(JobState.QUEUED, JobState.RUNNING, JobState.PAUSED)
    }
}

/**
 * The §3.3 boundary for scheduler decisions: any unexpected failure
 * becomes a typed StorageFailed with the operation named; coroutine
 * cancellation is never swallowed. (Inline so the guarded blocks can
 * use plain returns.)
 */
private suspend inline fun <T> guarded(
    operation: String,
    block: suspend () -> AppResult<T>
): AppResult<T> = try {
    block()
} catch (ce: CancellationException) {
    throw ce
} catch (e: Exception) {
    AppResult.Err(AppError.StorageFailed(operation))
}
