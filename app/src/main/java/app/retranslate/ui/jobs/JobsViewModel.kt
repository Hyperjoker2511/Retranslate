package app.retranslate.ui.jobs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.retranslate.core.error.AppResult
import app.retranslate.core.error.ErrorText
import app.retranslate.data.db.BookDao
import app.retranslate.data.db.DbEnums
import app.retranslate.data.db.JobKind
import app.retranslate.data.db.JobRunDao
import app.retranslate.data.db.JobRunEntity
import app.retranslate.data.db.JobState
import app.retranslate.domain.jobs.JobScheduler
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One job card on the Jobs tab — a pure rendering of a [JobRunEntity]. */
data class JobSummary(
    val runId: Long,
    val bookId: Long,
    val bookTitle: String,
    val kindText: String,
    val stateText: String,
    /** "128 of 412 chapters" / final summaries, one plain line. */
    val detailText: String,
    /** 0f–1f for the progress bar; null when there's nothing to measure. */
    val progress: Float?,
    val canPause: Boolean,
    val canResume: Boolean,
    val canCancel: Boolean
)

data class JobsUiState(
    val isLoading: Boolean = true,
    val jobs: List<JobSummary> = emptyList()
)

sealed interface JobsUiEvent {
    data class ShowMessage(val text: String) : JobsUiEvent
}

/**
 * Jobs tab state (PLAN.md §9.3): the Room JobRun mirror is the truth —
 * this ViewModel only renders it and forwards Pause/Resume/Cancel to
 * [JobScheduler]. Killing the app changes nothing here (§3.2).
 */
class JobsViewModel(
    private val jobRunDao: JobRunDao,
    private val bookDao: BookDao,
    private val scheduler: JobScheduler
) : ViewModel() {

    private val _state = MutableStateFlow(JobsUiState())
    val state: StateFlow<JobsUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<JobsUiEvent>(extraBufferCapacity = 2)
    val events: SharedFlow<JobsUiEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            combine(
                jobRunDao.observeRecent(MAX_JOBS_SHOWN),
                bookDao.observeAll()
            ) { runs, books ->
                val titles = books.associate { it.id to it.titleOriginal }
                runs.map { run -> summarize(run, titles[run.bookId] ?: "Book") }
            }.collect { jobs ->
                _state.update { it.copy(isLoading = false, jobs = jobs) }
            }
        }
    }

    fun pause(bookId: Long) = act { scheduler.pause(bookId) }

    fun resume(bookId: Long) = act { scheduler.resume(bookId).map { } }

    fun cancel(bookId: Long) = act { scheduler.cancel(bookId) }

    private fun act(block: suspend () -> AppResult<*>) {
        viewModelScope.launch {
            when (val result = block()) {
                is AppResult.Ok -> Unit
                is AppResult.Err -> _events.tryEmit(
                    JobsUiEvent.ShowMessage(ErrorText.of(result.error).message)
                )
            }
        }
    }

    companion object {
        const val MAX_JOBS_SHOWN = 50

        /** The mapping the whole screen renders — unit-tested directly. */
        fun summarize(run: JobRunEntity, bookTitle: String): JobSummary {
            val state = DbEnums.jobState(run.state)
            val kind = DbEnums.jobKind(run.kind)
            val stateText = when (state) {
                JobState.QUEUED -> "Waiting to start"
                JobState.RUNNING -> when (kind) {
                    JobKind.STUDY -> "Learning"
                    // v1.1.3 (§A2): the bulk-Improve job's own word.
                    JobKind.POLISH -> "Improving"
                    else -> "Translating"
                }
                JobState.PAUSED ->
                    if (run.lastErrorClass == "RateLimited") {
                        "Paused — servers busy"
                    } else {
                        "Paused"
                    }
                JobState.FINISHED ->
                    if (run.failedChapters > 0) "Finished — some chapters failed"
                    else "Finished"
                JobState.FAILED -> when (kind) {
                    JobKind.STUDY -> "Stopped — studying failed"
                    JobKind.POLISH -> "Stopped — nothing was improved"
                    else -> "Stopped — the chapters failed"
                }
                JobState.CANCELLED -> "Cancelled"
            }
            val detail = buildString {
                if (run.totalChapters > 0) {
                    append("${run.doneChapters + run.failedChapters} of ")
                    append("${run.totalChapters} chapters")
                }
                if (kind == JobKind.STUDY) {
                    if (state == JobState.FINISHED) {
                        if (isNotEmpty()) append(" · ")
                        append("Knowledge Base updated")
                    }
                } else if (kind == JobKind.POLISH) {
                    // v1.1.3 (§A2): the three durable counters, always.
                    if (isNotEmpty()) append(" · ")
                    append("${run.polishApplied} improved · ")
                    append("${run.polishRejected} kept original")
                    if (run.failedChapters > 0) {
                        append(" · ${run.failedChapters} failed — open the book to retry")
                    }
                    run.providerPick?.let { append(" · engine: $it") }
                } else if (state == JobState.FINISHED || state == JobState.FAILED) {
                    if (isNotEmpty()) append(" · ")
                    append("${run.doneChapters} translated")
                    // v1.1.3 (§I): a one-engine job names its engine.
                    run.providerPick?.let { pick ->
                        append(
                            if (pick.startsWith("gw")) {
                                " · engine: your added provider"
                            } else {
                                " · engine: $pick"
                            }
                        )
                    }
                    if (run.switchedProviders > 0) {
                        append(" · ${run.switchedProviders} used a fallback provider")
                    }
                    if (run.failedChapters > 0) {
                        append(" · ${run.failedChapters} failed — open the book to retry")
                    }
                }
            }
            val progress = if (run.totalChapters > 0) {
                (run.doneChapters + run.failedChapters).toFloat() / run.totalChapters
            } else {
                null
            }
            return JobSummary(
                runId = run.id,
                bookId = run.bookId,
                bookTitle = bookTitle,
                kindText = when (kind) {
                    JobKind.TRANSLATE -> "Translation"
                    JobKind.STUDY -> "Learning from samples"
                    JobKind.EXPORT -> "Export"
                    // v1.1.3 (§A2)
                    JobKind.POLISH -> "Improve with AI"
                },
                stateText = stateText,
                detailText = detail,
                progress = progress,
                // Study runs are short and re-runnable from the study
                // screen; Pause/Resume there would steer the WRONG job
                // (the scheduler acts on the book's latest run), so
                // only Cancel is offered for them (P5).
                canPause = kind != JobKind.STUDY &&
                    (state == JobState.QUEUED || state == JobState.RUNNING),
                canResume = kind != JobKind.STUDY &&
                    (state == JobState.PAUSED || state == JobState.FAILED ||
                        state == JobState.CANCELLED),
                canCancel = state == JobState.QUEUED || state == JobState.RUNNING ||
                    state == JobState.PAUSED
            )
        }
    }
}
