package app.retranslate.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.retranslate.core.error.AppResult
import app.retranslate.core.error.ErrorText
import app.retranslate.core.error.ErrorUi
import app.retranslate.domain.books.BookImporter
import app.retranslate.domain.books.BookRepository
import app.retranslate.domain.books.GenrePreset
import app.retranslate.domain.books.ImportChoices
import app.retranslate.domain.books.ImportSession
import app.retranslate.domain.books.ImportSessionStore
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

/**
 * Import review state (PLAN.md §7.1): everything parsed is shown before
 * anything is saved; the user confirms (or fixes) title, language,
 * genre and quality, and only then does commit touch Room.
 */
data class ImportReviewUiState(
    /** The import didn't survive (process death) — honest dead-end-free state. */
    val missing: Boolean = false,
    val formatText: String = "",
    val coverPath: String? = null,
    val title: String = "",
    val author: String = "",
    val chapterCount: Int = 0,
    val totalChars: Int = 0,
    /** True when detection failed and the user must choose (never a dead end). */
    val needsLanguageChoice: Boolean = false,
    val sourceLang: String? = null,
    val targetLang: String = "en",
    val genreId: String = GenrePreset.OTHER.id,
    val qualityMode: String = QualityMode.BALANCED.name,
    val duplicateBookId: Long? = null,
    val duplicateTitle: String? = null,
    val showDuplicateDialog: Boolean = false,
    val isCommitting: Boolean = false,
    val error: ErrorUi? = null
)

sealed interface ImportReviewUiEvent {
    data class Done(val bookId: Long) : ImportReviewUiEvent
    data class OpenExisting(val bookId: Long) : ImportReviewUiEvent
    data object Back : ImportReviewUiEvent
    data class ShowMessage(val text: String) : ImportReviewUiEvent
}

class ImportReviewViewModel(
    private val sessionStore: ImportSessionStore,
    private val importer: BookImporter,
    private val repository: BookRepository
) : ViewModel() {

    private val session: ImportSession? = sessionStore.peek()

    /** True once the session has been committed or deliberately opened-elsewhere. */
    private var finished = false

    private val _state = MutableStateFlow(buildInitialState())
    val state: StateFlow<ImportReviewUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ImportReviewUiEvent>(extraBufferCapacity = 2)
    val events: SharedFlow<ImportReviewUiEvent> = _events.asSharedFlow()

    private fun buildInitialState(): ImportReviewUiState {
        val s = session ?: return ImportReviewUiState(missing = true)
        val fallbackTitle = s.displayName.substringBeforeLast('.', s.displayName)
        return ImportReviewUiState(
            formatText = when (s.format) {
                app.retranslate.domain.books.ImportFormat.EPUB -> "EPUB book"
                app.retranslate.domain.books.ImportFormat.PDF -> "PDF file"
                app.retranslate.domain.books.ImportFormat.TXT -> "Text file"
            },
            coverPath = s.planned.coverFile?.absolutePath,
            title = s.planned.title ?: fallbackTitle,
            author = s.planned.author ?: "",
            chapterCount = s.planned.chapters.size,
            totalChars = s.planned.totalChars,
            needsLanguageChoice = s.detectedLang == null,
            sourceLang = s.detectedLang,
            duplicateBookId = s.duplicate?.id,
            duplicateTitle = s.duplicate?.titleOriginal
        )
    }

    fun setTitle(title: String) {
        _state.update { it.copy(title = title) }
        recheckDuplicate(title)
    }

    fun setAuthor(author: String) {
        _state.update { it.copy(author = author) }
    }

    fun chooseSourceLang(code: String) {
        if (!Languages.isKnown(code)) return
        _state.update { it.copy(sourceLang = code, needsLanguageChoice = false) }
    }

    fun chooseTargetLang(code: String) {
        if (!Languages.isKnown(code)) return
        _state.update { it.copy(targetLang = code) }
    }

    fun setGenre(genre: GenrePreset) {
        _state.update { it.copy(genreId = genre.id) }
    }

    fun setQuality(quality: QualityMode) {
        _state.update { it.copy(qualityMode = quality.name) }
    }

    private fun recheckDuplicate(title: String) {
        val s = session ?: return
        viewModelScope.launch {
            val duplicate = repository.findDuplicate(s.sha256, title)
            _state.update {
                it.copy(duplicateBookId = duplicate?.id, duplicateTitle = duplicate?.titleOriginal)
            }
        }
    }

    /** The big "Add to library" button. */
    fun onAddClicked() {
        val s = session ?: return
        val current = _state.value
        if (current.title.isBlank()) {
            _events.tryEmit(ImportReviewUiEvent.ShowMessage("Give the book a title first."))
            return
        }
        if (current.sourceLang == null) {
            _events.tryEmit(
                ImportReviewUiEvent.ShowMessage("Choose the book's language first.")
            )
            return
        }
        if (current.duplicateBookId != null) {
            _state.update { it.copy(showDuplicateDialog = true) }
            return
        }
        commit(asSeparateCopy = false)
    }

    fun onImportAsSeparateCopy() {
        _state.update { it.copy(showDuplicateDialog = false) }
        commit(asSeparateCopy = true)
    }

    fun onOpenExisting() {
        val duplicateId = _state.value.duplicateBookId ?: return
        _state.update { it.copy(showDuplicateDialog = false) }
        finished = true
        session?.let { importer.discard(it) }
        sessionStore.clear()
        _events.tryEmit(ImportReviewUiEvent.OpenExisting(duplicateId))
    }

    fun dismissDuplicateDialog() {
        _state.update { it.copy(showDuplicateDialog = false) }
    }

    private fun commit(asSeparateCopy: Boolean) {
        val s = session ?: return
        val current = _state.value
        val source = current.sourceLang ?: return
        _state.update { it.copy(isCommitting = true, error = null) }
        viewModelScope.launch {
            val choices = ImportChoices(
                title = current.title.trim(),
                author = current.author.trim().takeIf { it.isNotEmpty() },
                sourceLang = source,
                targetLang = current.targetLang,
                genrePresetId = current.genreId,
                qualityMode = current.qualityMode,
                importAsSeparateCopy = asSeparateCopy
            )
            when (val result = importer.commit(s, choices)) {
                is AppResult.Ok -> {
                    finished = true
                    sessionStore.clear()
                    _events.tryEmit(ImportReviewUiEvent.Done(result.value))
                }
                is AppResult.Err -> {
                    _state.update {
                        it.copy(isCommitting = false, error = ErrorText.of(result.error))
                    }
                }
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    /** Back = abandon the import; temp files are cleaned, nothing saved. */
    fun onBack() {
        if (!finished) {
            session?.let { importer.discard(it) }
            sessionStore.clear()
            finished = true
        }
        _events.tryEmit(ImportReviewUiEvent.Back)
    }

    override fun onCleared() {
        // Navigating away without confirming (e.g. process death) must
        // not leak the staging directory (§7.1).
        if (!finished) {
            session?.let { importer.discard(it) }
            sessionStore.clear()
        }
        super.onCleared()
    }
}
