package app.retranslate.domain.jobs

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.data.db.BookDao
import app.retranslate.data.db.ChapterDao
import app.retranslate.data.db.ChapterStatus
import app.retranslate.data.db.DbEnums
import app.retranslate.data.db.JobRunDao
import app.retranslate.data.db.JobRunEntity
import app.retranslate.data.db.JobState
import app.retranslate.data.db.TxRunner
import app.retranslate.domain.translate.ChapterPausedException
import app.retranslate.domain.translate.ChapterTranslator

/** How a job run ended — the worker maps these onto WorkManager results. */
sealed interface EngineResult {
    /** Every scoped chapter was attempted; [failed] of them failed. */
    data class Finished(val done: Int, val failed: Int) : EngineResult

    /** The user asked to pause (Jobs screen / notification / book page). */
    data object PausedByUser : EngineResult

    /**
     * Providers are rate-limiting in a storm: the run parks itself in
     * PAUSED and the worker asks WorkManager to retry after the
     * circuit-breaker window (§7.4) instead of burning retries.
     */
    data object PausedServersBusy : EngineResult

    /** The book or run vanished — nothing sensible to retry. */
    data class Aborted(val error: AppError) : EngineResult
}

/**
 * Runs one book-translation job to completion (PLAN.md §7.4).
 * Deliberately free of WorkManager/Android types: the Worker is a thin
 * adapter over this engine, which is what makes the §12.1 job
 * simulations (mid-book provider death, kill-and-resume) JVM-testable.
 *
 * Resume semantics live here and in [ChapterTranslator]:
 *  - The engine walks chapter HEADS (id/number/status — never the whole
 *    book's text) and loads one chapter at a time (§7.3 memory rule).
 *  - Already-TRANSLATED chapters are never re-requested; PARTIAL
 *    chapters resume mid-chapter from their persisted chunk state.
 *  - Counters are persisted after every chapter, so a process death
 *    loses at most the in-flight chapter.
 *  - Pause is cooperative: the JobRun row's state is the flag. The UI
 *    writes PAUSED; the engine notices at the next chunk boundary,
 *    persists partial progress, and stops. Every counter write
 *    re-reads the row first, so an engine write can never clobber a
 *    PAUSED/CANCELLED the user just wrote.
 */
class BookTranslationEngine(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val jobRunDao: JobRunDao,
    private val translator: ChapterTranslator,
    private val tx: TxRunner,
    /**
     * P5 (§6.4): resolves the book's KB context once per run — system
     * prompt (genre + KB block), the KB version for the cache key,
     * and the entries the verifier checks against. Null (the default,
     * and every pre-P5 test) = no context, exactly the P4 behaviour.
     */
    private val kbContext: (suspend (app.retranslate.data.db.BookEntity) ->
        app.retranslate.domain.kb.KbBookContext?)? = null,
    /**
     * P7 (§5.2 dual-model): the per-book polish hook, invoked with the
     * freshly translated chapter when the book's polish toggle is ON.
     * A hook (not the polisher class) keeps the engine WorkManager-
     * and Room-fake-friendly in tests. Polish is an enhancement: its
     * outcome never changes the chapter's done/failed accounting —
     * a rejected or failed polish simply leaves the Model A text.
     */
    private val polishHook: (suspend (
        app.retranslate.data.db.BookEntity,
        app.retranslate.data.db.ChapterEntity
    ) -> Unit)? = null,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    suspend fun run(
        bookId: Long,
        jobRunId: Long,
        onlyChapterIds: Set<Long>? = null,
        onProgress: suspend (done: Int, total: Int, currentChapter: Int) -> Unit =
            { _, _, _ -> }
    ): EngineResult {
        val book = bookDao.getById(bookId)
            ?: return EngineResult.Aborted(AppError.StorageFailed("loading the book"))
        val start = jobRunDao.getById(jobRunId)
            ?: return EngineResult.Aborted(AppError.StorageFailed("loading the job"))
        if (DbEnums.jobState(start.state) == JobState.CANCELLED) {
            // Cancelled before the worker got going — nothing to do.
            return EngineResult.PausedByUser
        }

        val bookContext = kbContext?.invoke(book)
        val heads = chapterDao.getChapterHeads(bookId)
        val scope = heads.filter { head ->
            if (onlyChapterIds != null) {
                head.id in onlyChapterIds
            } else {
                DbEnums.chapterStatus(head.status) in TRANSLATABLE
            }
        }
        val total = scope.size
        var done = 0
        var failed = 0
        var switches = 0
        var busyStreak = 0
        var lastError: AppError? = null

        updateRun(jobRunId) {
            it.copy(
                state = JobState.RUNNING.name,
                totalChapters = total,
                doneChapters = 0,
                failedChapters = 0,
                finishedAt = null
            )
        }
        onProgress(0, total, scope.firstOrNull()?.number ?: 0)

        /** Re-read, then write counters — never clobbers a user state write. */
        suspend fun persistCounters() {
            updateRun(jobRunId) {
                it.copy(
                    doneChapters = done,
                    failedChapters = failed,
                    switchedProviders = switches,
                    lastErrorClass = lastError?.let { e -> e::class.simpleName }
                )
            }
        }

        suspend fun pauseRequested(): Boolean =
            jobRunDao.getById(jobRunId)?.let { DbEnums.jobState(it.state) } ==
                JobState.PAUSED

        for (head in scope) {
            if (pauseRequested()) {
                persistCounters()
                return EngineResult.PausedByUser
            }
            val chapter = chapterDao.getChapter(head.id) ?: continue
            if (DbEnums.chapterStatus(chapter.status) == ChapterStatus.TRANSLATED) {
                // Finished by someone else while this job ran (e.g. a
                // single-chapter job) — count it, never re-request it.
                done++
                persistCounters()
                continue
            }
            val outcome = try {
                translator.translateChapter(
                    book = book,
                    chapter = chapter,
                    context = bookContext?.context,
                    kbVersion = bookContext?.kbVersion,
                    kbEntries = bookContext?.entries.orEmpty(),
                    shouldStop = { pauseRequested() }
                )
            } catch (paused: ChapterPausedException) {
                persistCounters()
                return EngineResult.PausedByUser
            }
            when (outcome) {
                is AppResult.Ok -> {
                    done++
                    switches += outcome.value.switches
                    busyStreak = 0
                    lastError = null
                    // §5.2 dual-model (P7): with the book's polish
                    // toggle ON, Model B runs per chapter immediately
                    // after Model A, inside the same job. The hook
                    // re-reads the chapter itself; any polish outcome
                    // (applied, rejected, failed) leaves the chapter
                    // counted as translated — polish never fails a job.
                    if (book.polishEnabled && polishHook != null) {
                        val fresh = chapterDao.getChapter(head.id)
                        if (fresh != null &&
                            DbEnums.chapterStatus(fresh.status) == ChapterStatus.TRANSLATED
                        ) {
                            try {
                                polishHook.invoke(book, fresh)
                            } catch (paused: ChapterPausedException) {
                                persistCounters()
                                return EngineResult.PausedByUser
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                // Real job cancellation is never swallowed.
                                throw cancelled
                            } catch (t: Throwable) {
                                // A polish crash must not kill the job:
                                // the translation stands (§10 spirit).
                            }
                        }
                    }
                }
                is AppResult.Err -> {
                    // The translator persisted partial progress; the
                    // row keeps that state under a FAILED status so
                    // Resume (which re-reads partialStateJson) and the
                    // chapter list both tell the truth.
                    val fresh = chapterDao.getChapter(head.id)
                    if (fresh != null &&
                        DbEnums.chapterStatus(fresh.status) != ChapterStatus.TRANSLATED
                    ) {
                        chapterDao.update(
                            fresh.copy(
                                status = ChapterStatus.FAILED.name,
                                updatedAt = clock()
                            )
                        )
                    }
                    failed++
                    lastError = outcome.error
                    busyStreak =
                        if (outcome.error.isServersBusy) busyStreak + 1 else 0
                }
            }
            persistCounters()
            onProgress(done + failed, total, head.number)
            if (busyStreak >= BUSY_STREAK_LIMIT) {
                updateRun(jobRunId) {
                    it.copy(
                        state = JobState.PAUSED.name,
                        lastErrorClass = AppError.RateLimited::class.simpleName
                    )
                }
                return EngineResult.PausedServersBusy
            }
        }

        val computed =
            if (total > 0 && done == 0 && failed > 0) JobState.FAILED else JobState.FINISHED
        tx.run {
            updateRun(jobRunId) { current ->
                val keepState = DbEnums.jobState(current.state)
                if (keepState == JobState.PAUSED || keepState == JobState.CANCELLED) {
                    // The user stopped this job during its final
                    // chapter — their state wins; counters still land.
                    current.copy(
                        doneChapters = done,
                        failedChapters = failed,
                        switchedProviders = switches
                    )
                } else {
                    current.copy(
                        state = computed.name,
                        doneChapters = done,
                        failedChapters = failed,
                        switchedProviders = switches,
                        finishedAt = clock()
                    )
                }
            }
            bookDao.update(book.copy(updatedAt = clock()))
        }
        return EngineResult.Finished(done, failed)
    }

    private suspend fun updateRun(jobRunId: Long, transform: (JobRunEntity) -> JobRunEntity) {
        val current = jobRunDao.getById(jobRunId) ?: return
        jobRunDao.update(transform(current))
    }

    /** Failures that mean "the servers" rather than "this chapter". */
    private val AppError.isServersBusy: Boolean
        get() = this is AppError.AllProvidersFailed || this is AppError.RateLimited ||
            this is AppError.NoNetwork

    companion object {
        private val TRANSLATABLE = setOf(
            ChapterStatus.PENDING, ChapterStatus.PARTIAL, ChapterStatus.FAILED
        )

        /** Consecutive server-busy chapter failures before self-pause. */
        const val BUSY_STREAK_LIMIT = 2
    }
}
