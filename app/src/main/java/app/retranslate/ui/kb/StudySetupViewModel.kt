package app.retranslate.ui.kb

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.retranslate.core.error.AppResult
import app.retranslate.core.error.ErrorText
import app.retranslate.data.db.BookEntity
import app.retranslate.data.db.ChapterListItem
import app.retranslate.data.db.JobRunDao
import app.retranslate.data.db.JobRunEntity
import app.retranslate.data.db.SampleDao
import app.retranslate.data.db.SampleSetEntity
import app.retranslate.data.files.PickedFile
import app.retranslate.domain.books.BookRepository
import app.retranslate.domain.jobs.JobScheduler
import app.retranslate.domain.kb.SampleImporter
import app.retranslate.domain.kb.StudyScheduler
import app.retranslate.domain.kb.StudySelection
import app.retranslate.providers.Languages
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One sample file on the setup screen, with its study range (§6.1). */
data class SampleSetRow(
    val set: SampleSetEntity,
    /** "English — already translated" / "Korean — original" (§6.1). */
    val languageText: String,
    val fromChapter: Int,
    val toChapter: Int,
    val alreadyStudied: Boolean
)

data class StudySetupUiState(
    val isLoading: Boolean = true,
    val book: BookEntity? = null,
    val sets: List<SampleSetRow> = emptyList(),
    /** True when samples include both the original and the translation. */
    val hasBothLanguages: Boolean = false,
    val importing: Boolean = false,
    /** The book's latest STUDY run, if any. */
    val studyRun: JobRunEntity? = null,
    val studyActive: Boolean = false,
    val hasKey: Boolean = false,
    val targetFrom: Int = 1,
    val targetTo: Int = 0,
    /** Untranslated chapters inside the target range. */
    val targetCount: Int = 0,
    val totalChapters: Int = 0
)

sealed interface StudySetupUiEvent {
    data class ShowMessage(val text: String) : StudySetupUiEvent
    data object OpenKnowledgeBase : StudySetupUiEvent
    data object SetUpKey : StudySetupUiEvent
}

/**
 * Study-setup state (PLAN.md §6.1): sample files with detected
 * languages in plain words, per-set chapter ranges (default: first
 * 10), the target-chapter range, and the study job's state. The
 * screen itself owns nothing — killing the app mid-study loses
 * nothing (the JobRun row + WorkManager carry it, §7.4).
 */
class StudySetupViewModel(
    private val bookId: Long,
    private val repository: BookRepository,
    private val sampleDao: SampleDao,
    private val sampleImporter: SampleImporter,
    private val studyScheduler: StudyScheduler,
    private val jobScheduler: JobScheduler,
    private val jobRunDao: JobRunDao,
    private val geminiKeyPresent: () -> Boolean,
    private val describeFile: (Uri) -> PickedFile
) : ViewModel() {

    private val _state = MutableStateFlow(StudySetupUiState())
    val state: StateFlow<StudySetupUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<StudySetupUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<StudySetupUiEvent> = _events.asSharedFlow()

    /** User-chosen ranges by set id; defaults apply on first sight. */
    private val ranges = mutableMapOf<Long, Pair<Int, Int>>()
    private var chapters: List<ChapterListItem> = emptyList()
    private var targetRange: Pair<Int, Int>? = null

    init {
        _state.update { it.copy(hasKey = geminiKeyPresent()) }
        viewModelScope.launch {
            repository.observeBook(bookId).collect { book ->
                _state.update { it.copy(isLoading = false, book = book) }
                refreshSets()
            }
        }
        viewModelScope.launch {
            sampleDao.observeSets(bookId).collect { refreshSets() }
        }
        viewModelScope.launch {
            repository.observeChapterItems(bookId).collect { items ->
                chapters = items
                if (targetRange == null && items.isNotEmpty()) {
                    targetRange = items.first().number to items.last().number
                }
                refreshTargets()
            }
        }
        viewModelScope.launch {
            jobRunDao.observeForBook(bookId).collect { runs ->
                val study = runs.firstOrNull { it.kind == "STUDY" }
                _state.update {
                    it.copy(
                        studyRun = study,
                        studyActive = study?.state in ACTIVE_STATES
                    )
                }
            }
        }
    }

    private suspend fun refreshSets() {
        val book = _state.value.book ?: return
        val sets = sampleDao.getSets(bookId)
        val rows = sets.map { set ->
            val range = ranges.getOrPut(set.id) {
                1 to minOf(DEFAULT_STUDY_CHAPTERS, set.chapterCount).coerceAtLeast(1)
            }
            SampleSetRow(
                set = set,
                languageText = sampleLanguageText(set.detectedLang, book),
                fromChapter = range.first,
                toChapter = range.second,
                alreadyStudied = set.studiedInKbVersion != null
            )
        }
        val langs = sets.map { it.detectedLang }.filter { it.isNotBlank() }.toSet()
        _state.update {
            it.copy(
                sets = rows,
                hasBothLanguages = book.sourceLang in langs && book.targetLang in langs &&
                    book.sourceLang != book.targetLang
            )
        }
    }

    private fun refreshTargets() {
        val range = targetRange
        val count = if (range == null) 0 else chapters.count { item ->
            item.number in range.first..range.second && item.status != "TRANSLATED"
        }
        _state.update {
            it.copy(
                targetFrom = range?.first ?: 1,
                targetTo = range?.second ?: 0,
                targetCount = count,
                totalChapters = chapters.size
            )
        }
    }

    fun setSampleRange(setId: Long, from: Int, to: Int) {
        val row = _state.value.sets.firstOrNull { it.set.id == setId } ?: return
        val max = row.set.chapterCount.coerceAtLeast(1)
        val clampedFrom = from.coerceIn(1, max)
        val clampedTo = to.coerceIn(clampedFrom, max)
        ranges[setId] = clampedFrom to clampedTo
        viewModelScope.launch { refreshSets() }
    }

    fun setTargetRange(from: Int, to: Int) {
        if (chapters.isEmpty()) return
        val first = chapters.first().number
        val last = chapters.last().number
        val clampedFrom = from.coerceIn(first, last)
        targetRange = clampedFrom to to.coerceIn(clampedFrom, last)
        refreshTargets()
    }

    /**
     * v1.1 (sweep): remove one added sample file. Before this, a file
     * added by mistake could never be taken off the list — the only
     * way out was removing the whole book. The learned Knowledge Base
     * (if this set was already studied) is NOT touched: unlearning is
     * the KB screen's job (edit/delete entries there).
     */
    fun removeSampleSet(setId: Long) {
        viewModelScope.launch {
            sampleDao.deleteChaptersForSet(setId)
            sampleDao.deleteSetById(setId)
            ranges.remove(setId)
            refreshSets()
            _events.tryEmit(
                StudySetupUiEvent.ShowMessage("Sample file removed.")
            )
        }
    }

    /** SAF results → sample sets, one file at a time (§6.1). */
    fun addSampleFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(importing = true) }
            var added = 0
            var lastError: String? = null
            for (uri in uris) {
                when (val result = sampleImporter.importSample(bookId, describeFile(uri))) {
                    is AppResult.Ok -> added++
                    is AppResult.Err -> lastError = ErrorText.of(result.error).message
                }
            }
            _state.update { it.copy(importing = false) }
            when {
                added > 0 && lastError == null -> _events.tryEmit(
                    StudySetupUiEvent.ShowMessage(
                        if (added == 1) "Sample file added." else "$added sample files added."
                    )
                )
                added > 0 -> _events.tryEmit(
                    StudySetupUiEvent.ShowMessage("Added $added file(s); one failed: $lastError")
                )
                else -> _events.tryEmit(
                    StudySetupUiEvent.ShowMessage(lastError ?: "That file couldn't be read.")
                )
            }
        }
    }

    fun selection(): StudySelection = StudySelection(
        _state.value.sets.map { row ->
            StudySelection.SetRange(row.set.id, row.fromChapter, row.toChapter)
        }
    )

    fun startStudy() {
        if (!geminiKeyPresent()) {
            _events.tryEmit(
                StudySetupUiEvent.ShowMessage(
                    "Studying needs a free Gemini key — tap 'Set up key', " +
                        "or add names by hand on the Knowledge Base screen."
                )
            )
            return
        }
        val selection = selection()
        if (selection.isEmpty) {
            _events.tryEmit(
                StudySetupUiEvent.ShowMessage("Add a sample file and choose chapters first.")
            )
            return
        }
        viewModelScope.launch {
            when (val result = studyScheduler.startStudy(bookId, selection)) {
                is AppResult.Ok -> _events.tryEmit(
                    StudySetupUiEvent.ShowMessage(
                        "Studying started — the Knowledge Base appears when it finishes."
                    )
                )
                is AppResult.Err -> _events.tryEmit(
                    StudySetupUiEvent.ShowMessage(ErrorText.of(result.error).message)
                )
            }
        }
    }

    fun cancelStudy() {
        val run = _state.value.studyRun ?: return
        viewModelScope.launch {
            studyScheduler.cancelStudy(run.id)
            _events.tryEmit(StudySetupUiEvent.ShowMessage("Study cancelled — nothing was saved."))
        }
    }

    /** "Translate" from the setup sheet (§6.1 step 4): the chosen range. */
    fun translateTargets() {
        val range = targetRange ?: return
        val ids = chapters
            .filter { it.number in range.first..range.second && it.status != "TRANSLATED" }
            .map { it.id }
            .toSet()
        if (ids.isEmpty()) {
            _events.tryEmit(
                StudySetupUiEvent.ShowMessage("Those chapters are already translated.")
            )
            return
        }
        val book = _state.value.book
        if (book != null && book.sourceLang == book.targetLang) {
            _events.tryEmit(
                StudySetupUiEvent.ShowMessage(
                    "This book is already in ${Languages.nameOf(book.targetLang)} — " +
                        "there's nothing to translate."
                )
            )
            return
        }
        viewModelScope.launch {
            val coversAll = range.first <= (chapters.firstOrNull()?.number ?: 1) &&
                range.second >= (chapters.lastOrNull()?.number ?: 0)
            val result = if (coversAll) {
                jobScheduler.translateBook(bookId)
            } else {
                jobScheduler.translateChapterRange(bookId, ids)
            }
            when (result) {
                is AppResult.Ok -> _events.tryEmit(
                    StudySetupUiEvent.ShowMessage(
                        "Translation started — follow it on the Jobs tab."
                    )
                )
                is AppResult.Err -> _events.tryEmit(
                    StudySetupUiEvent.ShowMessage(ErrorText.of(result.error).message)
                )
            }
        }
    }

    fun openKnowledgeBase() {
        _events.tryEmit(StudySetupUiEvent.OpenKnowledgeBase)
    }

    fun setUpKey() {
        _events.tryEmit(StudySetupUiEvent.SetUpKey)
    }

    companion object {
        /** §6.1: "Sample chapters to study" defaults to the first 10. */
        const val DEFAULT_STUDY_CHAPTERS = 10

        private val ACTIVE_STATES = setOf("QUEUED", "RUNNING")

        /** A sample's language, in the plain words §6.1 asks for. */
        fun sampleLanguageText(detectedLang: String, book: BookEntity): String = when {
            detectedLang.isBlank() -> "Language not recognised"
            detectedLang == book.targetLang && detectedLang != book.sourceLang ->
                "${Languages.nameOf(detectedLang)} — already translated"
            detectedLang == book.sourceLang ->
                "${Languages.nameOf(detectedLang)} — original"
            else -> Languages.nameOf(detectedLang)
        }
    }
}
