package app.retranslate.domain.jobs

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.data.db.BookDao
import app.retranslate.data.db.ChapterDao
import app.retranslate.data.db.ChapterImproveHead
import app.retranslate.data.db.DbEnums
import app.retranslate.data.db.JobRunDao
import app.retranslate.data.db.JobRunEntity
import app.retranslate.data.db.JobState
import app.retranslate.data.db.ChapterStatus
import app.retranslate.data.db.TxRunner
import app.retranslate.domain.translate.ChapterPolisher
import app.retranslate.domain.translate.ImproveStrength
import app.retranslate.domain.translate.PolishResult
import app.retranslate.domain.translate.PolishScope
import app.retranslate.domain.translate.QualityLabel
import app.retranslate.providers.ProviderId

/**
 * The scope decision (§G candidate 4), pure and shared: the bulk
 * Improve engine, the scheduler's pre-flight estimate and the UI's
 * scope counts must all agree on what a scope means.
 */
object PolishScopeFilter {

    fun includes(
        head: ChapterImproveHead,
        scope: PolishScope,
        flaggedChapterIds: Set<Long>,
        activeKbVersion: Int?
    ): Boolean = when (scope) {
        PolishScope.ALL -> true
        PolishScope.ROUGH -> QualityLabel.isRough(head.qualityScore)
        PolishScope.FLAGGED -> head.id in flaggedChapterIds
        PolishScope.NOT_YET_IMPROVED ->
            head.improvedAt == null ||
                (activeKbVersion != null &&
                    (head.kbVersionUsed ?: 0) < activeKbVersion)
        PolishScope.OFFLINE_TRANSLATED ->
            head.translatedByProvider == ProviderId.OFFLINE.name
    }

    fun filter(
        heads: List<ChapterImproveHead>,
        scope: PolishScope,
        flaggedChapterIds: Set<Long>,
        activeKbVersion: Int?
    ): List<ChapterImproveHead> =
        heads.filter { includes(it, scope, flaggedChapterIds, activeKbVersion) }
}

/**
 * Runs one bulk-Improve job (v1.1.3 §A2 + §G): a polish-only pass
 * over a book's EXISTING translated chapters — the consumer the
 * per-book polish toggle never had. Deliberately a sibling of
 * [BookTranslationEngine], not a mode of it: that engine SKIPS
 * translated chapters (they are its finished work); this one's
 * whole scope is exactly those chapters (§G assumption 4 — the
 * loop is inverted, not reused).
 *
 * Discipline mirrors the translation engine: chapter heads first,
 * one chapter loaded at a time, counters persisted after every
 * chapter, cooperative pause via the run row, and outcomes in
 * three durable counters (applied / rejected / failed — never
 * discarded, §A2). Quota is a first-class ending (§G candidate 3):
 * a rate-limit answer parks the run PAUSED and asks WorkManager to
 * retry; resuming — by retry or by the user's Resume — continues
 * the same run, and chapters already improved in it are skipped.
 */
class BookPolishEngine(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val jobRunDao: JobRunDao,
    private val polisher: ChapterPolisher,
    private val tx: TxRunner,
    /** FLAGGED scope's signal: chapters with a flagged paragraph. */
    private val flaggedChapterIds: suspend (bookId: Long) -> Set<Long> = { emptySet() },
    /** NOT_YET_IMPROVED compares against the book's active KB version. */
    private val activeKbVersion: suspend (bookId: Long) -> Int? = { null },
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    suspend fun run(
        bookId: Long,
        jobRunId: Long,
        scope: PolishScope,
        strength: ImproveStrength,
        onProgress: suspend (done: Int, total: Int, currentChapter: Int) -> Unit =
            { _, _, _ -> }
    ): EngineResult {
        val book = bookDao.getById(bookId)
            ?: return EngineResult.Aborted(AppError.StorageFailed("loading the book"))
        val start = jobRunDao.getById(jobRunId)
            ?: return EngineResult.Aborted(AppError.StorageFailed("loading the job"))
        if (DbEnums.jobState(start.state) == JobState.CANCELLED) {
            return EngineResult.PausedByUser
        }

        val flagged = flaggedChapterIds(bookId)
        val kbVersion = activeKbVersion(bookId)
        val heads = chapterDao.getImproveHeads(bookId)
        val inScope = PolishScopeFilter.filter(heads, scope, flagged, kbVersion)
            .filter { head ->
                // Resume-safety: a chapter improved earlier in THIS
                // run (before a quota pause) is never re-improved —
                // and never re-charged — by the run's continuation.
                head.improvedAt == null || head.improvedAt < start.startedAt
            }
        val total = inScope.size
        var applied = start.polishApplied
        var rejected = start.polishRejected
        var failed = start.failedChapters
        var lastError: AppError? = null

        updateRun(jobRunId) {
            it.copy(
                state = JobState.RUNNING.name,
                totalChapters = total,
                finishedAt = null
            )
        }
        onProgress(applied + rejected + failed, total, inScope.firstOrNull()?.number ?: 0)

        suspend fun persistCounters() {
            updateRun(jobRunId) {
                it.copy(
                    doneChapters = applied + rejected,
                    failedChapters = failed,
                    polishApplied = applied,
                    polishRejected = rejected,
                    lastErrorClass = lastError?.let { e -> e::class.simpleName }
                )
            }
        }

        suspend fun pauseRequested(): Boolean =
            jobRunDao.getById(jobRunId)?.let { DbEnums.jobState(it.state) } ==
                JobState.PAUSED

        for (head in inScope) {
            if (pauseRequested()) {
                persistCounters()
                return EngineResult.PausedByUser
            }
            val chapter = chapterDao.getChapter(head.id) ?: continue
            if (DbEnums.chapterStatus(chapter.status) != ChapterStatus.TRANSLATED ||
                chapter.translatedText.isNullOrBlank()
            ) {
                continue // changed under us — not this job's business
            }
            when (
                val outcome = polisher.polish(
                    book = book,
                    chapter = chapter,
                    snapshotFirst = false,
                    strength = strength
                )
            ) {
                is AppResult.Ok -> when (outcome.value) {
                    PolishResult.Applied -> applied++
                    is PolishResult.Rejected -> rejected++
                }
                is AppResult.Err -> when (outcome.error) {
                    is AppError.RateLimited -> {
                        // §G: quota is a pause, not a failure. The
                        // run parks; WorkManager retries after the
                        // backoff, or the user resumes — either way
                        // the SAME run continues where it stopped.
                        lastError = outcome.error
                        persistCounters()
                        updateRun(jobRunId) {
                            it.copy(
                                state = JobState.PAUSED.name,
                                lastErrorClass = AppError.RateLimited::class.simpleName
                            )
                        }
                        return EngineResult.PausedServersBusy
                    }
                    is AppError.AuthFailed -> {
                        // A key problem only the user can fix: park
                        // without a retry loop; Resume continues.
                        lastError = outcome.error
                        failed++
                        persistCounters()
                        updateRun(jobRunId) {
                            it.copy(
                                state = JobState.PAUSED.name,
                                lastErrorClass = AppError.AuthFailed::class.simpleName
                            )
                        }
                        return EngineResult.PausedByUser
                    }
                    else -> {
                        failed++
                        lastError = outcome.error
                    }
                }
            }
            persistCounters()
            onProgress(applied + rejected + failed, total, head.number)
        }

        val computed = if (total > 0 && applied + rejected == 0 && failed > 0) {
            JobState.FAILED
        } else {
            JobState.FINISHED
        }
        tx.run {
            updateRun(jobRunId) { current ->
                val keepState = DbEnums.jobState(current.state)
                if (keepState == JobState.PAUSED || keepState == JobState.CANCELLED) {
                    current.copy(
                        doneChapters = applied + rejected,
                        failedChapters = failed,
                        polishApplied = applied,
                        polishRejected = rejected
                    )
                } else {
                    current.copy(
                        state = computed.name,
                        doneChapters = applied + rejected,
                        failedChapters = failed,
                        polishApplied = applied,
                        polishRejected = rejected,
                        finishedAt = clock()
                    )
                }
            }
            bookDao.update(book.copy(updatedAt = clock()))
        }
        return EngineResult.Finished(applied + rejected, failed)
    }

    private suspend fun updateRun(jobRunId: Long, transform: (JobRunEntity) -> JobRunEntity) {
        val current = jobRunDao.getById(jobRunId) ?: return
        jobRunDao.update(transform(current))
    }
}
