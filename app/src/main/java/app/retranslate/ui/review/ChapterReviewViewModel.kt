package app.retranslate.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.retranslate.core.error.AppResult
import app.retranslate.core.error.ErrorText
import app.retranslate.data.db.BookEntity
import app.retranslate.data.db.ChapterDao
import app.retranslate.data.db.ChapterEntity
import app.retranslate.data.prefs.AppearancePrefs
import app.retranslate.data.prefs.AppearanceState
import app.retranslate.domain.books.BookRepository
import app.retranslate.domain.export.ChapterFlags
import app.retranslate.domain.export.EpubWriter
import app.retranslate.domain.kb.KbCompliance
import app.retranslate.domain.kb.KbRepository
import app.retranslate.domain.speech.SpeakOutcome
import app.retranslate.domain.speech.SpeechController
import app.retranslate.domain.translate.ChapterChangeAction
import app.retranslate.domain.translate.ChapterPolisher
import app.retranslate.domain.translate.ChapterSnapshots
import app.retranslate.domain.translate.ChapterTranslator
import app.retranslate.domain.translate.PolishResult
import app.retranslate.providers.Languages
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How one paragraph renders in the review list (§9.3). */
enum class ParagraphKind { NORMAL, SYSTEM_WINDOW, THOUGHT }

/**
 * System windows (`[ … ]`) render as tinted cards; thought paragraphs
 * (wrapped in *…* or _…_ by the source's convention) render italic —
 * so formatting problems are visible while checking (§9.3).
 */
fun paragraphKind(text: String): ParagraphKind {
    val trimmed = text.trim()
    if (trimmed.length >= 2 && trimmed.first() == '[' && trimmed.last() == ']') {
        return ParagraphKind.SYSTEM_WINDOW
    }
    if (trimmed.length >= 4 &&
        ((trimmed.first() == '*' && trimmed.last() == '*') ||
            (trimmed.first() == '_' && trimmed.last() == '_'))
    ) {
        return ParagraphKind.THOUGHT
    }
    return ParagraphKind.NORMAL
}

data class ReviewUiState(
    val isLoading: Boolean = true,
    val notFound: Boolean = false,
    val bookTitle: String = "",
    val chapterNumber: Int = 0,
    val chapterTitle: String = "",
    /** Which text is on screen: the translation (default) or the original. */
    val showingOriginal: Boolean = false,
    val hasOriginal: Boolean = false,
    val hasTranslation: Boolean = false,
    val originalParagraphs: List<String> = emptyList(),
    val translatedParagraphs: List<String> = emptyList(),
    /** State chip wording ("Done", "Half-done", …). */
    val statusText: String = "",
    val qualityScore: Float? = null,
    /** §6.5 warnings for this chapter, in words ("“Jinwoo” should be “Jin Woo”"). */
    val kbWarnings: List<String> = emptyList(),
    val flagMode: Boolean = false,
    /** Flagged paragraph indices — always of the TRANSLATION (§9.3). */
    val flagged: Set<Int> = emptySet(),
    /** "Polishing…" / "Re-translating…" while an action runs. */
    val workingText: String? = null,
    val showRetranslateConfirm: Boolean = false,
    val canPolish: Boolean = false,
    /**
     * v1.1.3 (§A2): a polish outcome that STAYS on screen until the
     * user acts — a rejection reason or the applied note. Never only
     * a vanishing snackbar again.
     */
    val polishNote: String? = null,
    /** v1.1.3 (§G): before/after compare after an applied improve. */
    val compareBefore: String? = null,
    val compareAfter: String? = null,
    /** v1.1.3 (§G): this chapter's pre-improve original is archived. */
    val originalArchived: Boolean = false,
    /** v1.1.3 (§A3): the chapter was AI-improved at least once. */
    val improved: Boolean = false,
    /** A snapshot exists for THIS chapter → Undo is offered here too. */
    val undoAvailable: Boolean = false,
    val prevChapterId: Long? = null,
    val nextChapterId: Long? = null,
    /** Where a returning reader lands (§9.3: position is remembered). */
    val initialParagraph: Int = 0,
    /** §9.3 Listen (P7): the phone is reading the shown text aloud. */
    val isSpeaking: Boolean = false
)

sealed interface ReviewUiEvent {
    data class ShowMessage(val text: String) : ReviewUiEvent
    data class OpenChapter(val chapterId: Long) : ReviewUiEvent
}

/**
 * Chapter review (PLAN.md §9.3): the quality-checking screen.
 * Original/Translation toggle, paragraph flags, KB name warnings and
 * the quality score computed on the fly (P5's pattern), one-chapter
 * polish (verified, §5.2) and re-translate (snapshotted, §9.3), and
 * the reading position persisted per chapter.
 */
class ChapterReviewViewModel(
    private val repository: BookRepository,
    private val chapterDao: ChapterDao,
    private val kbRepository: KbRepository,
    private val chapterFlags: ChapterFlags,
    private val polisher: ChapterPolisher,
    private val chapterTranslator: ChapterTranslator,
    private val snapshots: ChapterSnapshots,
    private val geminiKeyPresent: () -> Boolean,
    /** v1.1.3 (§G): per-chapter originals — Restore Original's source. */
    private val originals: app.retranslate.domain.translate.ChapterOriginalArchive? = null,
    private val bookId: Long,
    private val chapterId: Long,
    /** P7 (§9.3): Listen's engine — owned here, shut down in onCleared. */
    private val speech: SpeechController? = null,
    /** P7 (§9.3/§9.5): review theme + text size for the Aa sheet. */
    private val appearancePrefs: AppearancePrefs? = null
) : ViewModel() {

    /** The reading look (theme + text size), reflected from prefs. */
    val appearance: StateFlow<AppearanceState> =
        appearancePrefs?.state?.stateIn(
            viewModelScope, SharingStarted.Eagerly, AppearanceState()
        ) ?: MutableStateFlow(AppearanceState())

    private var book: BookEntity? = null

    private val _state = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ReviewUiEvent>(extraBufferCapacity = 2)
    val events: SharedFlow<ReviewUiEvent> = _events.asSharedFlow()

    private var chapter: ChapterEntity? = null
    private var lastSavedParagraph = -1

    init {
        viewModelScope.launch {
            repository.observeBook(bookId).collect { entity ->
                book = entity
                _state.update { it.copy(bookTitle = entity?.titleOriginal.orEmpty()) }
            }
        }
        speech?.let { controller ->
            viewModelScope.launch {
                controller.isSpeaking.collect { speaking ->
                    _state.update { it.copy(isSpeaking = speaking) }
                }
            }
        }
        viewModelScope.launch {
            repository.observeChapter(chapterId).collect { entity ->
                if (entity == null) {
                    _state.update { it.copy(isLoading = false, notFound = true) }
                    return@collect
                }
                val firstLoad = chapter == null
                chapter = entity
                _state.update {
                    it.copy(
                        isLoading = false,
                        chapterNumber = entity.number,
                        chapterTitle = entity.title?.takeIf { t -> t.isNotBlank() }
                            ?: "Chapter ${entity.number}",
                        hasOriginal = !entity.sourceText.isNullOrBlank(),
                        hasTranslation = !entity.translatedText.isNullOrBlank(),
                        originalParagraphs = entity.sourceText
                            ?.let { text -> EpubWriter.paragraphsOf(text) }.orEmpty(),
                        translatedParagraphs = entity.translatedText
                            ?.let { text -> EpubWriter.paragraphsOf(text) }.orEmpty(),
                        statusText = statusText(entity.status),
                        qualityScore = entity.qualityScore,
                        canPolish = geminiKeyPresent() &&
                            !entity.translatedText.isNullOrBlank(),
                        improved = entity.improvedAt != null,
                        initialParagraph = if (firstLoad) {
                            entity.lastReadParagraph
                        } else {
                            it.initialParagraph
                        }
                    )
                }
                refreshWarnings()
                refreshArchived()
            }
        }
        viewModelScope.launch {
            kbRepository.observeActiveEntries(bookId).collect { refreshWarnings() }
        }
        viewModelScope.launch {
            val heads = chapterDao.getChapterHeads(bookId)
            val index = heads.indexOfFirst { it.id == chapterId }
            _state.update {
                it.copy(
                    prevChapterId = heads.getOrNull(index - 1)?.id,
                    nextChapterId = heads.getOrNull(index + 1)?.id
                )
            }
        }
        viewModelScope.launch { refreshFlags() }
        viewModelScope.launch { refreshUndo() }
    }

    private fun statusText(status: String): String = when (status) {
        "TRANSLATED" -> "Done"
        "PARTIAL" -> "Half-done"
        "FAILED" -> "Failed"
        else -> "Not started"
    }

    /** §6.5: this chapter's KB violations, computed on the fly. */
    private fun refreshWarnings() {
        val entity = chapter ?: return
        val text = entity.translatedText ?: return
        viewModelScope.launch {
            val entries = kbRepository.activeEntries(bookId)
            val warnings = KbCompliance.findViolations(text, entries).map { violation ->
                "“${violation.variantFound}” should be “${violation.canonicalForm}”" +
                    if (violation.occurrences > 1) " (${violation.occurrences}×)" else ""
            }
            _state.update { it.copy(kbWarnings = warnings) }
        }
    }

    private suspend fun refreshFlags() {
        val flags = chapterFlags.flaggedParagraphs(bookId, chapterId)
        _state.update { it.copy(flagged = flags) }
    }

    private fun refreshUndo() {
        val snapshot = snapshots.peek(bookId)
        _state.update { it.copy(undoAvailable = snapshot?.chapterId == chapterId) }
    }

    /** v1.1.3 (§G): is this chapter's pre-improve original archived? */
    private fun refreshArchived() {
        val archive = originals ?: return
        viewModelScope.launch {
            val has = archive.has(bookId, chapterId)
            _state.update { it.copy(originalArchived = has) }
        }
    }

    // -- reading position ---------------------------------------------------

    /** The screen reports the first visible paragraph; we persist it. */
    fun onPositionChanged(paragraphIndex: Int) {
        val entity = chapter ?: return
        if (paragraphIndex == lastSavedParagraph || paragraphIndex < 0) return
        lastSavedParagraph = paragraphIndex
        // Keep the state's start paragraph live, so toggling
        // Original/Translation and back lands where the user is now,
        // not where they were when the screen opened.
        _state.update { it.copy(initialParagraph = paragraphIndex) }
        val total = _state.value.translatedParagraphs.size
            .takeIf { !_state.value.showingOriginal }
            ?: _state.value.originalParagraphs.size
        val progress = if (total > 0) {
            ((paragraphIndex + 1).toFloat() / total).coerceIn(0f, 1f)
        } else {
            0f
        }
        viewModelScope.launch {
            chapterDao.update(
                entity.copy(readProgress = progress, lastReadParagraph = paragraphIndex)
            )
        }
    }

    // -- toggle + flags -------------------------------------------------------

    fun showOriginal() {
        // §9.3: switching the shown text stops speech immediately.
        speech?.stop()
        if (!_state.value.hasOriginal) {
            _events.tryEmit(
                ReviewUiEvent.ShowMessage(
                    "The original text isn't available for this book."
                )
            )
            return
        }
        _state.update { it.copy(showingOriginal = true, flagMode = false) }
    }

    fun showTranslation() {
        speech?.stop()
        _state.update { it.copy(showingOriginal = false) }
    }

    fun toggleFlagMode() {
        if (_state.value.showingOriginal) {
            _events.tryEmit(
                ReviewUiEvent.ShowMessage(
                    "Flags mark the translation — switch to Translation to flag."
                )
            )
            return
        }
        _state.update { it.copy(flagMode = !it.flagMode) }
    }

    fun onParagraphTapped(index: Int) {
        val state = _state.value
        if (!state.flagMode || state.showingOriginal) return
        val nowFlagged = index !in state.flagged
        viewModelScope.launch {
            chapterFlags.setFlagged(bookId, chapterId, index, nowFlagged)
            refreshFlags()
        }
    }

    // -- prev / next -----------------------------------------------------------

    fun openPrevious() {
        // §9.3: changing chapter stops speech immediately.
        speech?.stop()
        _state.value.prevChapterId?.let {
            _events.tryEmit(ReviewUiEvent.OpenChapter(it))
        }
    }

    fun openNext() {
        speech?.stop()
        _state.value.nextChapterId?.let {
            _events.tryEmit(ReviewUiEvent.OpenChapter(it))
        }
    }

    // -- Listen (§9.3, P7) ----------------------------------------------------

    /**
     * Listen reads the CURRENTLY SHOWN text in ITS language: the
     * translation in the book's target language, the original in the
     * source language. Stop is the same button while speaking.
     */
    fun listen() {
        val controller = speech ?: return
        if (_state.value.isSpeaking) {
            controller.stop()
            return
        }
        val state = _state.value
        val paragraphs =
            if (state.showingOriginal) state.originalParagraphs
            else state.translatedParagraphs
        if (paragraphs.isEmpty()) {
            _events.tryEmit(
                ReviewUiEvent.ShowMessage("There's nothing on screen to read aloud.")
            )
            return
        }
        val language =
            if (state.showingOriginal) book?.sourceLang ?: "en"
            else book?.targetLang ?: "en"
        val text = paragraphs.joinToString("\n\n")
        viewModelScope.launch {
            when (controller.speak(text, language)) {
                SpeakOutcome.STARTED -> Unit
                SpeakOutcome.NO_VOICE -> _events.tryEmit(
                    ReviewUiEvent.ShowMessage(
                        "Your phone has no voice for " +
                            "${Languages.nameOf(language)} — " +
                            "Listen can't read this language yet."
                    )
                )
                SpeakOutcome.UNAVAILABLE -> _events.tryEmit(
                    ReviewUiEvent.ShowMessage("Listen isn't available on this phone.")
                )
            }
        }
    }

    fun stopSpeaking() {
        speech?.stop()
    }

    /** The screen left view: speech stops immediately (§9.3). */
    fun onScreenClosed() {
        speech?.stop()
    }

    override fun onCleared() {
        // The TTS engine is owned by this screen's ViewModel — it is
        // never left running in the background (§9.3).
        speech?.stop()
        speech?.shutdown()
        super.onCleared()
    }

    // -- appearance (Aa sheet, §9.3) ------------------------------------------

    fun setReviewTheme(id: String) {
        viewModelScope.launch { appearancePrefs?.setReviewTheme(id) }
    }

    fun setReviewTextScale(scale: Float) {
        viewModelScope.launch { appearancePrefs?.setReviewTextScale(scale) }
    }

    // -- polish (§5.2) -----------------------------------------------------------

    fun polish() {
        val entity = chapter ?: return
        if (_state.value.workingText != null) return
        if (!geminiKeyPresent()) {
            // v1.1.3 (§G honesty): no AI key at all — said in words,
            // and it stays on screen as the polish note too.
            val message = "Improve needs an AI key — add one under " +
                "More → Providers & keys (a free Gemini key works). " +
                "Nothing was changed."
            _state.update { it.copy(polishNote = message) }
            _events.tryEmit(ReviewUiEvent.ShowMessage(message))
            return
        }
        val beforeText = entity.translatedText
        viewModelScope.launch {
            val bookEntity = repository.getBook(bookId) ?: return@launch
            _state.update {
                it.copy(
                    workingText = "Improving…",
                    polishNote = null,
                    compareBefore = null,
                    compareAfter = null
                )
            }
            // v1.1.3 (§A): the busy state ALWAYS clears — a thrown
            // failure can no longer leave the screen stuck "working".
            try {
                when (val result = polisher.polish(bookEntity, entity)) {
                    is AppResult.Ok -> when (val value = result.value) {
                        is PolishResult.Applied -> {
                            refreshUndo()
                            refreshArchived()
                            val afterText = chapterDao.getChapter(chapterId)
                                ?.translatedText
                            _state.update {
                                it.copy(
                                    polishNote = "Improved with AI. Compare " +
                                        "below — keep yours or use the improved text.",
                                    compareBefore = beforeText,
                                    compareAfter = afterText
                                )
                            }
                        }
                        is PolishResult.Rejected -> {
                            // §A2: the reason stays on screen; the
                            // chapter is unchanged and says so.
                            val note = "The AI's version wasn't kept: " +
                                "${value.reason} Your chapter is unchanged."
                            _state.update { it.copy(polishNote = note) }
                            _events.tryEmit(ReviewUiEvent.ShowMessage(note))
                        }
                    }
                    is AppResult.Err -> {
                        val message = ErrorText.of(result.error).message
                        _state.update { it.copy(polishNote = message) }
                        _events.tryEmit(ReviewUiEvent.ShowMessage(message))
                    }
                }
            } finally {
                _state.update { it.copy(workingText = null) }
            }
        }
    }

    /** v1.1.3 (§G): "Use improved" — accept the compare, close it. */
    fun keepImproved() {
        _state.update { it.copy(compareBefore = null, compareAfter = null, polishNote = null) }
    }

    /** v1.1.3 (§G): "Keep mine" — undo the improve, close the compare. */
    fun keepMine() {
        _state.update { it.copy(compareBefore = null, compareAfter = null, polishNote = null) }
        undoChapterChange()
    }

    fun dismissPolishNote() {
        _state.update { it.copy(polishNote = null) }
    }

    /**
     * v1.1.3 (§G): Restore Original — bring back the text exactly as
     * it was before the FIRST improve touched this chapter (the
     * per-chapter archive; the one-per-book Undo snapshot is separate).
     */
    fun restoreOriginal() {
        val archive = originals ?: return
        viewModelScope.launch {
            when (val result = archive.restore(bookId, chapterId)) {
                is AppResult.Ok -> {
                    refreshArchived()
                    _state.update {
                        it.copy(
                            polishNote = null,
                            compareBefore = null,
                            compareAfter = null
                        )
                    }
                    _events.tryEmit(
                        ReviewUiEvent.ShowMessage(
                            if (result.value > 0) {
                                "Chapter ${result.value} restored to its original text."
                            } else {
                                "No saved original for this chapter."
                            }
                        )
                    )
                }
                is AppResult.Err -> _events.tryEmit(
                    ReviewUiEvent.ShowMessage(ErrorText.of(result.error).message)
                )
            }
        }
    }

    // -- re-translate (§9.3) ------------------------------------------------------

    fun askRetranslate() {
        if (!_state.value.hasOriginal) {
            _events.tryEmit(
                ReviewUiEvent.ShowMessage(
                    "This chapter has no original text to re-translate from."
                )
            )
            return
        }
        _state.update { it.copy(showRetranslateConfirm = true) }
    }

    fun dismissRetranslate() {
        _state.update { it.copy(showRetranslateConfirm = false) }
    }

    fun confirmRetranslate() {
        val entity = chapter ?: return
        _state.update { it.copy(showRetranslateConfirm = false) }
        if (_state.value.workingText != null) return
        viewModelScope.launch {
            val bookEntity = repository.getBook(bookId) ?: return@launch
            if (!snapshots.save(bookId, entity, ChapterChangeAction.RETRANSLATE)) {
                _events.tryEmit(
                    ReviewUiEvent.ShowMessage(
                        "Couldn't save an undo copy — nothing was changed."
                    )
                )
                return@launch
            }
            _state.update { it.copy(workingText = "Re-translating…") }
            val context = kbRepository.buildContext(bookEntity)
            when (
                val result = chapterTranslator.translateChapter(
                    book = bookEntity,
                    chapter = entity,
                    context = context.context,
                    kbVersion = context.kbVersion,
                    kbEntries = context.entries
                )
            ) {
                is AppResult.Ok -> {
                    refreshUndo()
                    _events.tryEmit(
                        ReviewUiEvent.ShowMessage(
                            "Chapter re-translated with the Knowledge Base. " +
                                "Undo is available if you prefer the old text."
                        )
                    )
                }
                is AppResult.Err -> _events.tryEmit(
                    ReviewUiEvent.ShowMessage(ErrorText.of(result.error).message)
                )
            }
            _state.update { it.copy(workingText = null) }
        }
    }

    // -- undo -----------------------------------------------------------------------

    fun undoChapterChange() {
        viewModelScope.launch {
            when (val result = snapshots.undo(bookId)) {
                is AppResult.Ok -> {
                    refreshUndo()
                    _events.tryEmit(
                        ReviewUiEvent.ShowMessage(
                            if (result.value > 0) {
                                "Chapter ${result.value} restored to the text before the change."
                            } else {
                                "Nothing to undo."
                            }
                        )
                    )
                }
                is AppResult.Err -> _events.tryEmit(
                    ReviewUiEvent.ShowMessage(ErrorText.of(result.error).message)
                )
            }
        }
    }
}
