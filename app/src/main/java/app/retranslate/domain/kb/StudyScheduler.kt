package app.retranslate.domain.kb

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.data.db.BookDao
import app.retranslate.data.db.DbEnums
import app.retranslate.data.db.JobKind
import app.retranslate.data.db.JobRunDao
import app.retranslate.data.db.JobRunEntity
import app.retranslate.data.db.JobState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** The WorkManager boundary for study jobs — fakeable like [app.retranslate.domain.jobs.WorkEnqueuer]. */
interface StudyEnqueuer {
    fun enqueueStudy(bookId: Long, jobRunId: Long, selectionSpec: String, replace: Boolean)
    fun cancelStudy(bookId: Long)
}

/**
 * Study lifecycle decisions (PLAN.md §6.1/§7.4): one study job per
 * book (KEEP — a second tap reports the live run, never a duplicate),
 * the JobRun mirror row (kind STUDY) the Jobs tab and the setup
 * screen read, and cancel that writes the row first (the engine
 * checks it before every AI call and before writing the KB).
 *
 * The chapter selection rides in the work input (WorkManager
 * persists it), encoded by [StudySelectionCodec].
 */
class StudyScheduler(
    private val bookDao: BookDao,
    private val jobRunDao: JobRunDao,
    private val enqueuer: StudyEnqueuer,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    suspend fun startStudy(bookId: Long, selection: StudySelection): AppResult<Long> =
        guardedStudy("starting the study") {
            bookDao.getById(bookId)
                ?: return AppResult.Err(AppError.StorageFailed("loading the book"))
            if (selection.isEmpty) {
                return AppResult.Err(
                    AppError.VerificationFailed("no sample chapters are selected")
                )
            }
            val live = jobRunDao.observeForBook(bookId).first()
                .firstOrNull { run ->
                    DbEnums.jobKind(run.kind) == JobKind.STUDY &&
                        DbEnums.jobState(run.state) in
                        setOf(JobState.QUEUED, JobState.RUNNING)
                }
            if (live != null) return AppResult.Ok(live.id)
            val runId = jobRunDao.insert(
                JobRunEntity(
                    bookId = bookId,
                    kind = JobKind.STUDY.name,
                    state = JobState.QUEUED.name,
                    totalChapters = selection.totalChapters,
                    startedAt = clock()
                )
            )
            enqueuer.enqueueStudy(
                bookId, runId, StudySelectionCodec.encode(selection), replace = false
            )
            AppResult.Ok(runId)
        }

    /** Cancel a study run: the row tells the truth first, then the work. */
    suspend fun cancelStudy(runId: Long): AppResult<Unit> =
        guardedStudy("cancelling the study") {
            val run = jobRunDao.getById(runId) ?: return AppResult.Ok(Unit)
            if (DbEnums.jobState(run.state) in
                setOf(JobState.QUEUED, JobState.RUNNING, JobState.PAUSED)
            ) {
                jobRunDao.update(
                    run.copy(state = JobState.CANCELLED.name, finishedAt = clock())
                )
            }
            enqueuer.cancelStudy(run.bookId)
            AppResult.Ok(Unit)
        }
}

/** `setId:from-to;setId:from-to` — compact, WorkManager-input safe. */
object StudySelectionCodec {

    fun encode(selection: StudySelection): String =
        selection.ranges.joinToString(";") { "${it.setId}:${it.fromChapter}-${it.toChapter}" }

    fun decode(spec: String): StudySelection {
        val ranges = spec.split(";").mapNotNull { part ->
            val pieces = part.trim().split(":", "-")
            if (pieces.size != 3) return@mapNotNull null
            val setId = pieces[0].toLongOrNull() ?: return@mapNotNull null
            val from = pieces[1].toIntOrNull() ?: return@mapNotNull null
            val to = pieces[2].toIntOrNull() ?: return@mapNotNull null
            if (to < from) return@mapNotNull null
            StudySelection.SetRange(setId, from, to)
        }
        return StudySelection(ranges)
    }
}

/** The §3.3 boundary for study decisions (mirrors the job scheduler's). */
private suspend inline fun <T> guardedStudy(
    operation: String,
    block: suspend () -> AppResult<T>
): AppResult<T> = try {
    block()
} catch (ce: CancellationException) {
    throw ce
} catch (e: Exception) {
    AppResult.Err(AppError.StorageFailed(operation))
}
