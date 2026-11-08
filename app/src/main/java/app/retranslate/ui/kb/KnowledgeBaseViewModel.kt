package app.retranslate.ui.kb

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.retranslate.core.error.AppResult
import app.retranslate.core.error.ErrorText
import app.retranslate.data.db.BookEntity
import app.retranslate.data.db.KbEntryEntity
import app.retranslate.data.db.KnowledgeBaseEntity
import app.retranslate.domain.books.BookRepository
import app.retranslate.domain.kb.ChapterFix
import app.retranslate.domain.kb.KbEntryDraft
import app.retranslate.domain.kb.KbRepository
import app.retranslate.domain.kb.ReapplyNames
import app.retranslate.domain.kb.ReapplyRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class KnowledgeBaseUiState(
    val isLoading: Boolean = true,
    val book: BookEntity? = null,
    val kb: KnowledgeBaseEntity? = null,
    val entries: List<KbEntryEntity> = emptyList(),
    /** A canonical name just changed: offered for re-apply (§6.3). */
    val pendingReapply: ReapplyRequest? = null,
    /** The previewed chapters for [pendingReapply]; null = not previewed yet. */
    val reapplyPreview: List<ChapterFix>? = null,
    val reapplyBusy: Boolean = false,
    val hasUndo: Boolean = false
)

sealed interface KnowledgeBaseUiEvent {
    data class ShowMessage(val text: String) : KnowledgeBaseUiEvent
}

/**
 * Knowledge Base screen state (PLAN.md §6.3): the active version's
 * entries (grouped by the screen), manual add/edit/lock/delete —
 * every mutation writes a new KB version via [KbRepository] — and
 * the "Re-apply names" flow with preview + undo.
 */
class KnowledgeBaseViewModel(
    private val bookId: Long,
    private val repository: BookRepository,
    private val kbRepository: KbRepository,
    private val reapplyNames: ReapplyNames
) : ViewModel() {

    private val _state = MutableStateFlow(KnowledgeBaseUiState())
    val state: StateFlow<KnowledgeBaseUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<KnowledgeBaseUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<KnowledgeBaseUiEvent> = _events.asSharedFlow()

    private var entriesJob: Job? = null

    init {
        viewModelScope.launch {
            repository.observeBook(bookId).collect { book ->
                _state.update { it.copy(isLoading = false, book = book) }
            }
        }
        viewModelScope.launch {
            _state.update { it.copy(hasUndo = reapplyNames.hasUndo(bookId)) }
        }
        viewModelScope.launch {
            kbRepository.observeActiveKb(bookId).collect { kb ->
                _state.update { it.copy(kb = kb) }
                entriesJob?.cancel()
                entriesJob = if (kb == null) {
                    _state.update { it.copy(entries = emptyList()) }
                    null
                } else {
                    viewModelScope.launch {
                        kbRepository.observeEntries(kb.id).collect { entries ->
                            _state.update { it.copy(entries = entries) }
                        }
                    }
                }
            }
        }
    }

    fun addEntry(draft: KbEntryDraft) {
        viewModelScope.launch {
            when (val result = kbRepository.addManualEntry(bookId, draft)) {
                is AppResult.Ok -> _events.tryEmit(
                    KnowledgeBaseUiEvent.ShowMessage("“${draft.canonicalForm}” added and locked.")
                )
                is AppResult.Err -> _events.tryEmit(
                    KnowledgeBaseUiEvent.ShowMessage(ErrorText.of(result.error).message)
                )
            }
        }
    }

    /**
     * Saves an edit. When the canonical form changed, the re-apply
     * offer is armed (§6.3: cheap local fix before any re-translation).
     */
    fun editEntry(entry: KbEntryEntity, draft: KbEntryDraft, locked: Boolean) {
        viewModelScope.launch {
            when (val result = kbRepository.editEntry(bookId, entry.id, draft, locked)) {
                is AppResult.Ok -> {
                    if (draft.canonicalForm.trim() != entry.canonicalForm.trim()) {
                        _state.update {
                            it.copy(
                                pendingReapply = ReapplyRequest(
                                    entryId = entry.id,
                                    oldForm = entry.canonicalForm,
                                    newForm = draft.canonicalForm
                                ),
                                reapplyPreview = null
                            )
                        }
                        _events.tryEmit(
                            KnowledgeBaseUiEvent.ShowMessage(
                                "Name saved. You can re-apply it to chapters " +
                                    "that are already translated."
                            )
                        )
                    } else {
                        _events.tryEmit(KnowledgeBaseUiEvent.ShowMessage("Saved."))
                    }
                }
                is AppResult.Err -> _events.tryEmit(
                    KnowledgeBaseUiEvent.ShowMessage(ErrorText.of(result.error).message)
                )
            }
        }
    }

    fun toggleLock(entry: KbEntryEntity) {
        viewModelScope.launch {
            kbRepository.setLocked(bookId, entry.id, !entry.locked)
        }
    }

    fun deleteEntry(entry: KbEntryEntity) {
        viewModelScope.launch {
            when (val result = kbRepository.deleteEntry(bookId, entry.id)) {
                is AppResult.Ok -> _events.tryEmit(
                    KnowledgeBaseUiEvent.ShowMessage("“${entry.canonicalForm}” removed.")
                )
                is AppResult.Err -> _events.tryEmit(
                    KnowledgeBaseUiEvent.ShowMessage(ErrorText.of(result.error).message)
                )
            }
        }
    }

    fun previewReapply() {
        val request = _state.value.pendingReapply ?: return
        viewModelScope.launch {
            _state.update { it.copy(reapplyBusy = true) }
            val fixes = reapplyNames.preview(bookId, request)
            _state.update { it.copy(reapplyBusy = false, reapplyPreview = fixes) }
            if (fixes.isEmpty()) {
                _events.tryEmit(
                    KnowledgeBaseUiEvent.ShowMessage(
                        "No translated chapter uses the old name — nothing to re-apply."
                    )
                )
            }
        }
    }

    fun applyReapply() {
        val request = _state.value.pendingReapply ?: return
        viewModelScope.launch {
            _state.update { it.copy(reapplyBusy = true) }
            when (val result = reapplyNames.apply(bookId, request)) {
                is AppResult.Ok -> {
                    _state.update {
                        it.copy(
                            reapplyBusy = false,
                            pendingReapply = null,
                            reapplyPreview = null,
                            hasUndo = reapplyNames.hasUndo(bookId)
                        )
                    }
                    _events.tryEmit(
                        KnowledgeBaseUiEvent.ShowMessage(
                            "New name applied to ${result.value} chapter(s). Undo is available."
                        )
                    )
                }
                is AppResult.Err -> {
                    _state.update { it.copy(reapplyBusy = false) }
                    _events.tryEmit(
                        KnowledgeBaseUiEvent.ShowMessage(ErrorText.of(result.error).message)
                    )
                }
            }
        }
    }

    fun dismissReapply() {
        _state.update { it.copy(pendingReapply = null, reapplyPreview = null) }
    }

    fun undoReapply() {
        viewModelScope.launch {
            when (val result = reapplyNames.undo(bookId)) {
                is AppResult.Ok -> {
                    _state.update { it.copy(hasUndo = reapplyNames.hasUndo(bookId)) }
                    _events.tryEmit(
                        KnowledgeBaseUiEvent.ShowMessage(
                            "Rename undone — ${result.value} chapter(s) restored exactly."
                        )
                    )
                }
                is AppResult.Err -> _events.tryEmit(
                    KnowledgeBaseUiEvent.ShowMessage(ErrorText.of(result.error).message)
                )
            }
        }
    }

    companion object {
        /** The §6.1 step-3 summary line: "Learned 24 names, 11 places, 9 terms". */
        fun summaryText(entries: List<KbEntryEntity>): String {
            if (entries.isEmpty()) return "Nothing learned yet"
            val counts = entries.groupingBy { it.category }.eachCount()
            val parts = mutableListOf<String>()
            fun add(category: String, singular: String, plural: String) {
                val n = counts[category] ?: return
                parts += "$n ${if (n == 1) singular else plural}"
            }
            add("CHARACTER", "name", "names")
            add("LOCATION", "place", "places")
            add("ORGANISATION", "organisation", "organisations")
            add("TECHNIQUE", "technique", "techniques")
            add("ITEM", "item", "items")
            add("TERM", "term", "terms")
            add("TONE_RULE", "tone rule", "tone rules")
            return "Learned " + parts.joinToString(", ")
        }
    }
}
