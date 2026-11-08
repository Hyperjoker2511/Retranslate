package app.retranslate.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.retranslate.core.error.AppResult
import app.retranslate.core.error.ErrorText
import app.retranslate.core.error.ErrorUi
import app.retranslate.data.db.BookShelfRow
import app.retranslate.data.files.PickedFile
import app.retranslate.domain.books.BookImporter
import app.retranslate.domain.books.BookRepository
import app.retranslate.domain.books.GenrePreset
import app.retranslate.domain.books.ImportChoices
import app.retranslate.domain.books.ImportProgress
import app.retranslate.domain.books.ImportSessionStore
import app.retranslate.domain.books.ImportStage
import app.retranslate.providers.Languages
import app.retranslate.providers.QualityMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One book row on the Library shelf (§9.3). */
data class BookSummary(
    val id: Long,
    val title: String,
    val author: String?,
    val languagePair: String,
    val translatedCount: Int,
    val totalCount: Int,
    val coverPath: String?,
    val progressText: String
)

/**
 * The Continue-checking hero's target (§9.3): the chapter the user was
 * last checking in the hero book, and how far through it they are.
 */
data class HeroChapter(
    val bookId: Long,
    val chapterId: Long,
    val chapterNumber: Int,
    val percentChecked: Int
)

enum class ShelfFilter { ALL, READING, FINISHED }

sealed interface ImportUiState {
    data object Idle : ImportUiState
    data class Working(val stageText: String, val fraction: Float?) : ImportUiState
    data class Failed(val error: ErrorUi) : ImportUiState
}

data class LibraryUiState(
    val isLoading: Boolean = true,
    val books: List<BookSummary> = emptyList(),
    val heroBook: BookSummary? = null,
    /** P6: where Continue checking lands, when a position is saved. */
    val heroChapter: HeroChapter? = null,
    val query: String = "",
    val filter: ShelfFilter = ShelfFilter.ALL,
    val import: ImportUiState = ImportUiState.Idle,
    /** v1.1 (workstream E): the book a long-press asked to remove. */
    val pendingRemoval: BookSummary? = null,
    /** v1.1.3 (F24): the one-time What's-new card is showing. */
    val showWhatsNew: Boolean = false
)

sealed interface LibraryUiEvent {
    data class ShowMessage(val text: String) : LibraryUiEvent
    data class OpenBook(val bookId: Long) : LibraryUiEvent
    data object OpenImportReview : LibraryUiEvent

    /** P6: Continue checking resumes INTO the review screen (§9.3). */
    data class OpenReview(val bookId: Long, val chapterId: Long) : LibraryUiEvent
}

/**
 * Library (PLAN.md §9.3): the shelf as a pure function of Room state,
 * plus the import trigger — picking a file stages + parses it (progress
 * as state, failure as an [ErrorUi] with its recovery action) and hands
 * the session to the review screen via [ImportSessionStore].
 */
class LibraryViewModel(
    private val repository: BookRepository,
    private val importer: BookImporter,
    private val sessionStore: ImportSessionStore,
    /** P6: saved checking positions for the hero (optional in tests). */
    private val chapterDao: app.retranslate.data.db.ChapterDao? = null,
    /** v1.1 (workstream E): job cancelling + file cleanup on removal. */
    private val jobScheduler: app.retranslate.domain.jobs.JobScheduler? = null,
    private val bookFiles: app.retranslate.data.files.BookFiles? = null,
    /** v1.1.3 (F24): the What's-new card's one-time memory. */
    private val whatsNewPrefs: app.retranslate.data.prefs.WhatsNewPrefs? = null,
    /** SAF uri → importer input (PickedFiles on device; a fake in tests).
     * LAST on purpose: tests construct with a trailing lambda. */
    private val describeFile: (Uri) -> PickedFile
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<LibraryUiEvent>(extraBufferCapacity = 2)
    val events: SharedFlow<LibraryUiEvent> = _events.asSharedFlow()

    private var allRows: List<BookShelfRow> = emptyList()

    init {
        viewModelScope.launch {
            repository.observeShelf().collect { rows ->
                allRows = rows
                refresh()
                updateHeroChapter()
            }
        }
        // v1.1.3 (F24): show the What's-new card until it's dismissed.
        whatsNewPrefs?.let { prefs ->
            viewModelScope.launch {
                prefs.seen113.collect { seen ->
                    _state.update { it.copy(showWhatsNew = !seen) }
                }
            }
        }
    }

    /** F24: dismiss the What's-new card — it never comes back. */
    fun dismissWhatsNew() {
        _state.update { it.copy(showWhatsNew = false) }
        viewModelScope.launch { whatsNewPrefs?.markSeen113() }
    }

    /**
     * P6 (§9.3): the hero resumes at the chapter the user was last
     * checking — the most recently touched chapter with a saved
     * position in the hero book. No saved position → the hero opens
     * the book page, as before.
     */
    private fun updateHeroChapter() {
        val dao = chapterDao ?: return
        val heroId = _state.value.heroBook?.id ?: run {
            _state.update { it.copy(heroChapter = null) }
            return
        }
        viewModelScope.launch {
            val slice = dao.getLastCheckedChapter(heroId)
            // The hero may have moved on while the query ran.
            if (_state.value.heroBook?.id != heroId) return@launch
            _state.update {
                it.copy(
                    heroChapter = slice?.let { s ->
                        HeroChapter(
                            bookId = heroId,
                            chapterId = s.id,
                            chapterNumber = s.number,
                            percentChecked = (s.readProgress * 100).toInt()
                                .coerceIn(0, 100)
                        )
                    }
                )
            }
        }
    }

    /** Hero tap: into the review screen when a position exists (§9.3). */
    fun onHeroClicked() {
        val hero = _state.value.heroChapter
        val book = _state.value.heroBook ?: return
        if (hero != null && hero.bookId == book.id) {
            _events.tryEmit(LibraryUiEvent.OpenReview(hero.bookId, hero.chapterId))
        } else {
            _events.tryEmit(LibraryUiEvent.OpenBook(book.id))
        }
    }

    private fun refresh() {
        val summaries = allRows.map { it.toSummary() }
        val query = _state.value.query.trim().lowercase()
        val filtered = summaries.filter { summary ->
            val matchesQuery = query.isEmpty() ||
                summary.title.lowercase().contains(query) ||
                (summary.author?.lowercase()?.contains(query) == true)
            val matchesFilter = when (_state.value.filter) {
                ShelfFilter.ALL -> true
                ShelfFilter.READING -> summary.totalCount > 0 &&
                    summary.translatedCount in 1 until summary.totalCount
                ShelfFilter.FINISHED -> summary.totalCount > 0 &&
                    summary.translatedCount >= summary.totalCount
            }
            matchesQuery && matchesFilter
        }
        // "Continue checking" hero: the most recently touched book that
        // is part-way through (§9.3). Rows arrive newest-first.
        val hero = summaries.firstOrNull {
            it.totalCount > 0 && it.translatedCount in 1 until it.totalCount
        }
        _state.update {
            it.copy(isLoading = false, books = filtered, heroBook = hero)
        }
    }

    private fun BookShelfRow.toSummary(): BookSummary {
        val pair = "${Languages.nameOf(book.sourceLang)} → ${Languages.nameOf(book.targetLang)}"
        val progress = when {
            totalChapters == 0 -> "No chapters yet"
            translatedChapters >= totalChapters -> "All $totalChapters chapters translated"
            else -> "$translatedChapters / $totalChapters translated"
        }
        return BookSummary(
            id = book.id,
            title = book.titleOriginal,
            author = book.author,
            languagePair = pair,
            translatedCount = translatedChapters,
            totalCount = totalChapters,
            coverPath = book.coverPath,
            progressText = progress
        )
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        refresh()
    }

    fun setFilter(filter: ShelfFilter) {
        _state.update { it.copy(filter = filter) }
        refresh()
    }

    fun onBookClicked(bookId: Long) {
        _events.tryEmit(LibraryUiEvent.OpenBook(bookId))
    }

    // -- Remove book (v1.1, workstream E): long-press on the shelf ------

    /** Long-press on a shelf card: the SAME removal the book page offers. */
    fun askRemoveBook(book: BookSummary) {
        _state.update { it.copy(pendingRemoval = book) }
    }

    fun dismissRemoveBook() {
        _state.update { it.copy(pendingRemoval = null) }
    }

    fun confirmRemoveBook() {
        val target = _state.value.pendingRemoval ?: return
        val files = bookFiles ?: run {
            // No file store wired (tests): rows still go, dialog closes.
            _state.update { it.copy(pendingRemoval = null) }
            return
        }
        _state.update { it.copy(pendingRemoval = null) }
        viewModelScope.launch {
            when (
                val result = repository.removeBook(target.id, files) { id ->
                    jobScheduler?.cancel(id)
                }
            ) {
                is AppResult.Ok -> _events.tryEmit(
                    LibraryUiEvent.ShowMessage("“${target.title}” removed.")
                )
                is AppResult.Err -> _events.tryEmit(
                    LibraryUiEvent.ShowMessage(ErrorText.of(result.error).message)
                )
            }
        }
    }

    /** A picked SAF file: stage → parse → review (progress via state). */
    fun onFilePicked(uri: Uri) {
        if (_state.value.import is ImportUiState.Working) return
        val picked = describeFile(uri)
        viewModelScope.launch {
            _state.update {
                it.copy(import = ImportUiState.Working("Copying the file…", 0f))
            }
            val result = importer.importFile(
                openStream = picked.openStream,
                displayName = picked.displayName,
                sizeBytes = picked.sizeBytes,
                onProgress = { progress -> onImportProgress(progress) }
            )
            when (result) {
                is AppResult.Ok -> {
                    sessionStore.set(result.value)
                    _state.update { it.copy(import = ImportUiState.Idle) }
                    _events.tryEmit(LibraryUiEvent.OpenImportReview)
                }
                is AppResult.Err -> {
                    _state.update {
                        it.copy(import = ImportUiState.Failed(ErrorText.of(result.error)))
                    }
                }
            }
        }
    }

    private fun onImportProgress(progress: ImportProgress) {
        val stageText = when (progress.stage) {
            ImportStage.COPYING -> "Copying the file…"
            ImportStage.READING -> "Reading the book…"
            ImportStage.CHECKING -> "Checking the chapters…"
        }
        _state.update { it.copy(import = ImportUiState.Working(stageText, progress.fraction)) }
    }

    fun dismissImportError() {
        _state.update { it.copy(import = ImportUiState.Idle) }
    }

    /** "Type details myself" (§9.3): create an empty book shell. */
    fun createManualBook(
        title: String,
        sourceLang: String,
        targetLang: String,
        genre: GenrePreset,
        quality: QualityMode
    ) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) {
            _events.tryEmit(LibraryUiEvent.ShowMessage("Give the book a title first."))
            return
        }
        viewModelScope.launch {
            when (
                val result = repository.createEmptyBook(
                    ImportChoices(
                        title = trimmed,
                        author = null,
                        sourceLang = sourceLang,
                        targetLang = targetLang,
                        genrePresetId = genre.id,
                        qualityMode = quality.name
                    )
                )
            ) {
                is AppResult.Ok -> _events.tryEmit(LibraryUiEvent.OpenBook(result.value))
                is AppResult.Err -> _events.tryEmit(
                    LibraryUiEvent.ShowMessage(ErrorText.of(result.error).message)
                )
            }
        }
    }
}
