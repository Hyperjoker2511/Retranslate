package app.retranslate.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.retranslate.core.error.AppResult
import app.retranslate.core.error.ErrorText
import app.retranslate.data.db.BookEntity
import app.retranslate.data.db.ChapterListItem
import app.retranslate.data.files.BookFiles
import app.retranslate.data.files.PickedFile
import app.retranslate.domain.books.BookImporter
import app.retranslate.domain.books.BookRepository
import app.retranslate.domain.books.ImportStage
import app.retranslate.providers.Languages
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Book page state (PLAN.md §9.3): header, status in plain words, the
 * visible action row, and the chapter list. Every visible action is
 * wired to a real feature; there are no placeholder buttons.
 */
data class BookDetailUiState(
    val isLoading: Boolean = true,
    val notFound: Boolean = false,
    val book: BookEntity? = null,
    val chapters: List<ChapterListItem> = emptyList(),
    val kbEntryCount: Int = 0,
    /** P5 (§6.5): translated chapters currently breaking a KB name. */
    val nameWarnings: Int = 0,
    /** P6 (§9.3): paragraphs the user flagged in the review screen. */
    val flaggedParagraphs: Int = 0,
    val flaggedChapters: Int = 0,
    /** P6 (§9.3): the undoable chapter change (re-translate/polish), if any. */
    val undoSnapshot: app.retranslate.domain.translate.ChapterSnapshot? = null,
    /** P6 (§7.5): the pre-export dialog's data, while it's open. */
    val pendingExport: PendingExport? = null,
    /** P6: the "Translated with ReTranslate" note toggle (default on, §7.5). */
    val exportIncludeNote: Boolean = true,
    /** P6: 0f–1f while an export file is being written. */
    val exportProgress: Float? = null,
    val translatedCount: Int = 0,
    val statusText: String = "",
    /** P8: plain-words progress while a picked chapter file is added. */
    val addingChaptersText: String? = null,
    /** The book's latest job (any state) — the status card's job line. */
    val latestJob: app.retranslate.data.db.JobRunEntity? = null,
    /** True while the latest job is QUEUED/RUNNING/PAUSED. */
    val jobActive: Boolean = false,
    val showDeleteConfirm: Boolean = false,
    // ---- v1.1.3 (§A/§G): Improve with AI ------------------------------
    /** The bulk-Improve sheet is open. */
    val showImproveSheet: Boolean = false,
    val improveScope: app.retranslate.domain.translate.PolishScope =
        app.retranslate.domain.translate.PolishScope.ALL,
    val improveStrength: app.retranslate.domain.translate.ImproveStrength =
        app.retranslate.domain.translate.ImproveStrength.GENTLE,
    /** The pre-flight count for the chosen scope — the engine's own filter. */
    val improveEstimate: Int = 0,
    /** Any AI configured (Gemini/OpenAI/Claude/APInex/gateway). */
    val aiAvailable: Boolean = false,
    /** Chapter list filter: only chapters the quality label calls rough. */
    val needsImprovementOnly: Boolean = false,
    /** The Consistency check's report, once run (§G). */
    val consistency: app.retranslate.domain.kb.ConsistencyReport? = null,
    val consistencyRunning: Boolean = false,
    /** A one-chapter improve/restore is running (its chapter id). */
    val busyChapterId: Long? = null
)

/** The pre-export dialog's facts (§7.5: say plainly what will be left out). */
data class PendingExport(
    val format: app.retranslate.domain.export.ExportFormat,
    val totalChapters: Int,
    val translatedChapters: Int
) {
    val skippedChapters: Int get() = totalChapters - translatedChapters
}

sealed interface BookDetailUiEvent {
    data class ShowMessage(val text: String) : BookDetailUiEvent
    data object NavigateBack : BookDetailUiEvent

    /** P6: an export file is written; the screen offers Save / Share. */
    data class ExportReady(
        val filePath: String,
        val fileName: String,
        val mimeType: String,
        val includedChapters: Int,
        val skippedChapters: Int
    ) : BookDetailUiEvent
}

class BookDetailViewModel(
    private val repository: BookRepository,
    private val bookFiles: BookFiles,
    private val bookId: Long,
    private val jobScheduler: app.retranslate.domain.jobs.JobScheduler? = null,
    private val jobRunDao: app.retranslate.data.db.JobRunDao? = null,
    /** P5: live KB entry count + the §6.5 name-warning count. */
    private val kbRepository: app.retranslate.domain.kb.KbRepository? = null,
    /** P6: export engine (§7.5). */
    private val bookExporter: app.retranslate.domain.export.BookExporter? = null,
    /** P6: review-screen paragraph flags (§9.3). */
    private val chapterFlags: app.retranslate.domain.export.ChapterFlags? = null,
    /** P6: chapter-change snapshots for Undo (§9.3). */
    private val chapterSnapshots: app.retranslate.domain.translate.ChapterSnapshots? = null,
    /** P8: file import pipeline reused by "Add chapters" (§9.3). */
    private val bookImporter: BookImporter? = null,
    /** v1.1.3 (§A3): one-chapter Improve with AI. */
    private val polisher: app.retranslate.domain.translate.ChapterPolisher? = null,
    /** v1.1.3 (§G): per-chapter originals — Restore Original. */
    private val originals: app.retranslate.domain.translate.ChapterOriginalArchive? = null,
    /** v1.1.3 (§G): the free book-wide Consistency check. */
    private val consistencyScanner: app.retranslate.domain.kb.ConsistencyScanner? = null,
    /** v1.1.3 (§A): is any AI configured for Improve? */
    private val aiConfigured: () -> Boolean = { false },
    /** v1.1.3 (§I): start a whole-book job with ONE picked engine. */
    private val chapterDao: app.retranslate.data.db.ChapterDao? = null
) : ViewModel() {

    private val _state = MutableStateFlow(BookDetailUiState())
    val state: StateFlow<BookDetailUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<BookDetailUiEvent>(extraBufferCapacity = 2)
    val events: SharedFlow<BookDetailUiEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            repository.observeBook(bookId).collect { book ->
                if (book == null) {
                    _state.update { it.copy(isLoading = false, notFound = true, book = null) }
                } else {
                    _state.update {
                        it.copy(isLoading = false, notFound = false, book = book)
                    }
                }
            }
        }
        viewModelScope.launch {
            repository.observeChapterItems(bookId).collect { items ->
                val translated = items.count { it.status == "TRANSLATED" }
                _state.update {
                    it.copy(
                        chapters = items,
                        translatedCount = translated,
                        statusText = statusText(items.size, translated)
                    )
                }
                refreshNameWarnings()
            }
        }
        if (kbRepository != null) {
            viewModelScope.launch {
                kbRepository.observeActiveEntries(bookId).collect { entries ->
                    _state.update { it.copy(kbEntryCount = entries.size) }
                    refreshNameWarnings()
                }
            }
        } else {
            viewModelScope.launch {
                val count = repository.activeKbEntryCount(bookId)
                _state.update { it.copy(kbEntryCount = count) }
            }
        }
        if (jobRunDao != null) {
            viewModelScope.launch {
                jobRunDao.observeForBook(bookId).collect { runs ->
                    val latest = runs.firstOrNull()
                    val active = latest != null && latest.state in ACTIVE_JOB_STATES
                    _state.update { it.copy(latestJob = latest, jobActive = active) }
                }
            }
        }
        refreshFlagsAndUndo()
    }

    /**
     * P6: flags and the undo snapshot live in the book's files, not in
     * Room flows — refresh them when the page (re)appears and after
     * actions here change them.
     */
    fun refreshFlagsAndUndo() {
        val flags = chapterFlags
        val snapshots = chapterSnapshots
        viewModelScope.launch {
            if (flags != null) {
                _state.update {
                    it.copy(
                        flaggedParagraphs = flags.countForBook(bookId),
                        flaggedChapters = flags.countFlaggedChapters(bookId)
                    )
                }
            }
            if (snapshots != null) {
                _state.update { it.copy(undoSnapshot = snapshots.peek(bookId)) }
            }
        }
    }

    /** §6.5: recount name warnings (paged scan) when inputs change. */
    private fun refreshNameWarnings() {
        val kb = kbRepository ?: return
        if (_state.value.kbEntryCount == 0) {
            _state.update { it.copy(nameWarnings = 0) }
            return
        }
        viewModelScope.launch {
            val count = kb.countChaptersWithNameWarnings(bookId)
            _state.update { it.copy(nameWarnings = count) }
        }
    }

    private fun statusText(total: Int, translated: Int): String = when {
        total == 0 -> "No chapters yet"
        translated >= total -> "All $total chapters are translated"
        translated == 0 -> "0 of $total chapters translated"
        else -> "$translated of $total chapters translated"
    }

    /**
     * P7 (§5.2 dual-model): the per-book polish toggle. Persisted on
     * the book itself; the job engine reads it per run and polishes
     * each freshly translated chapter (verified — a rejected polish
     * keeps the plain translation).
     */
    fun setPolishEnabled(enabled: Boolean) {
        val book = _state.value.book ?: return
        if (book.polishEnabled == enabled) return
        viewModelScope.launch {
            repository.updateBook(book.copy(polishEnabled = enabled))
        }
    }

    // ---- P8: add chapters to an existing book (§9.3) --------------------

    /**
     * "Add chapters": parses a picked EPUB/TXT/PDF with the normal
     * streaming importer, then appends its chapters after this book's
     * existing chapters. The import session's staging is always cleaned,
     * whether the append succeeds or fails.
     */
    fun addChapters(picked: PickedFile) {
        val importer = bookImporter ?: return
        if (_state.value.addingChaptersText != null) return
        viewModelScope.launch {
            _state.update { it.copy(addingChaptersText = "Copying the file…") }
            when (
                val imported = importer.importFile(
                    openStream = picked.openStream,
                    displayName = picked.displayName,
                    sizeBytes = picked.sizeBytes,
                    onProgress = { progress ->
                        val stageText = when (progress.stage) {
                            ImportStage.COPYING -> "Copying the file…"
                            ImportStage.READING -> "Reading the chapters…"
                            ImportStage.CHECKING -> "Checking the chapters…"
                        }
                        _state.update { it.copy(addingChaptersText = stageText) }
                    }
                )
            ) {
                is AppResult.Err -> {
                    _state.update { it.copy(addingChaptersText = null) }
                    _events.tryEmit(
                        BookDetailUiEvent.ShowMessage(
                            ErrorText.of(imported.error).message
                        )
                    )
                }
                is AppResult.Ok -> {
                    val session = imported.value
                    try {
                        when (val added = repository.appendImport(bookId, session.planned)) {
                            is AppResult.Ok -> _events.tryEmit(
                                BookDetailUiEvent.ShowMessage(
                                    if (added.value == 1) {
                                        "Added 1 chapter to this book."
                                    } else {
                                        "Added ${added.value} chapters to this book."
                                    }
                                )
                            )
                            is AppResult.Err -> _events.tryEmit(
                                BookDetailUiEvent.ShowMessage(
                                    ErrorText.of(added.error).message
                                )
                            )
                        }
                    } finally {
                        importer.discard(session)
                        _state.update { it.copy(addingChaptersText = null) }
                    }
                }
            }
        }
    }

    /**
     * v1.1.3 (§C refinement, D9): paste with the user's explicit
     * answer — the pasted text is the ORIGINAL (goes to the source
     * side, to be translated) or already-translated MTL (goes to the
     * translation side, ready to Improve). Never guessed silently.
     */
    fun pasteChapter(
        title: String,
        text: String,
        kind: app.retranslate.domain.translate.ChapterTextKind
    ) {
        if (kind == app.retranslate.domain.translate.ChapterTextKind.ORIGINAL) {
            pasteChapter(title, text)
            return
        }
        if (text.isBlank()) {
            _events.tryEmit(
                BookDetailUiEvent.ShowMessage("Paste the chapter text first.")
            )
            return
        }
        viewModelScope.launch {
            when (
                val result = repository.addChapterWithTexts(
                    bookId = bookId,
                    title = title,
                    sourceText = null,
                    translatedText = text.trim(),
                    origin = app.retranslate.data.db.ChapterOrigin.IMPORTED_TRANSLATION
                )
            ) {
                is AppResult.Ok -> _events.tryEmit(
                    BookDetailUiEvent.ShowMessage(
                        "Chapter ${result.value} added as a translation — " +
                            "Improve with AI is on its row."
                    )
                )
                is AppResult.Err -> _events.tryEmit(
                    BookDetailUiEvent.ShowMessage(ErrorText.of(result.error).message)
                )
            }
        }
    }

    /** "Paste a chapter": appends one pasted chapter to this book. */
    fun pasteChapter(title: String, text: String) {
        if (text.isBlank()) {
            _events.tryEmit(
                BookDetailUiEvent.ShowMessage("Paste the chapter text first.")
            )
            return
        }
        viewModelScope.launch {
            when (val result = repository.addChapter(bookId, title, text)) {
                is AppResult.Ok -> _events.tryEmit(
                    BookDetailUiEvent.ShowMessage(
                        "Chapter ${result.value} added to this book."
                    )
                )
                is AppResult.Err -> _events.tryEmit(
                    BookDetailUiEvent.ShowMessage(ErrorText.of(result.error).message)
                )
            }
        }
    }

    // ---- P6: export (§7.5) ----------------------------------------------

    /** Export buttons: first the plain-words dialog (what's left out). */
    fun askExport(format: app.retranslate.domain.export.ExportFormat) {
        val exporter = bookExporter ?: return
        viewModelScope.launch {
            val preview = exporter.preview(bookId)
            _state.update {
                it.copy(
                    pendingExport = PendingExport(
                        format = format,
                        totalChapters = preview.totalChapters,
                        translatedChapters = preview.translatedChapters
                    )
                )
            }
        }
    }

    fun dismissExport() {
        _state.update { it.copy(pendingExport = null) }
    }

    fun setExportIncludeNote(include: Boolean) {
        _state.update { it.copy(exportIncludeNote = include) }
    }

    fun confirmExport() {
        val exporter = bookExporter ?: return
        val pending = _state.value.pendingExport ?: return
        val includeNote = _state.value.exportIncludeNote
        _state.update { it.copy(pendingExport = null, exportProgress = 0f) }
        viewModelScope.launch {
            when (
                val result = exporter.exportBook(
                    bookId = bookId,
                    format = pending.format,
                    includeTranslatorNote = includeNote,
                    onProgress = { fraction ->
                        _state.update { it.copy(exportProgress = fraction) }
                    }
                )
            ) {
                is AppResult.Ok -> {
                    _state.update { it.copy(exportProgress = null) }
                    val outcome = result.value
                    _events.tryEmit(
                        BookDetailUiEvent.ExportReady(
                            filePath = outcome.file.absolutePath,
                            fileName = outcome.fileName,
                            mimeType = outcome.mimeType,
                            includedChapters = outcome.includedChapters,
                            skippedChapters = outcome.skippedChapters
                        )
                    )
                }
                is AppResult.Err -> {
                    _state.update { it.copy(exportProgress = null) }
                    _events.tryEmit(
                        BookDetailUiEvent.ShowMessage(
                            ErrorText.of(result.error).message
                        )
                    )
                }
            }
        }
    }

    // ---- P6: undo the last chapter change (§9.3) -------------------------

    fun undoChapterChange() {
        val snapshots = chapterSnapshots ?: return
        viewModelScope.launch {
            when (val result = snapshots.undo(bookId)) {
                is AppResult.Ok -> {
                    refreshFlagsAndUndo()
                    _events.tryEmit(
                        BookDetailUiEvent.ShowMessage(
                            if (result.value > 0) {
                                "Chapter ${result.value} restored to the text " +
                                    "before the change."
                            } else {
                                "Nothing to undo."
                            }
                        )
                    )
                }
                is AppResult.Err -> _events.tryEmit(
                    BookDetailUiEvent.ShowMessage(ErrorText.of(result.error).message)
                )
            }
        }
    }

    // ---- P4: translation jobs ------------------------------------------

    /** "Translate all untranslated" (§9.3) — one job, KEEP semantics. */
    fun translateAll() {
        val scheduler = jobScheduler ?: return
        val state = _state.value
        val untranslated = state.chapters.count { it.status != "TRANSLATED" }
        if (state.chapters.isNotEmpty() && untranslated == 0) {
            _events.tryEmit(
                BookDetailUiEvent.ShowMessage("Every chapter is already translated.")
            )
            return
        }
        val book = state.book
        if (book != null && book.sourceLang == book.targetLang) {
            _events.tryEmit(
                BookDetailUiEvent.ShowMessage(
                    "This book is already in ${Languages.nameOf(book.targetLang)} — " +
                        "there's nothing to translate."
                )
            )
            return
        }
        viewModelScope.launch {
            when (val result = scheduler.translateBook(bookId)) {
                is AppResult.Ok -> _events.tryEmit(
                    BookDetailUiEvent.ShowMessage(
                        "Translation started — follow it on the Jobs tab. " +
                            "You can close the app; it keeps going."
                    )
                )
                is AppResult.Err -> _events.tryEmit(
                    BookDetailUiEvent.ShowMessage(ErrorText.of(result.error).message)
                )
            }
        }
    }

    /** Per-chapter Translate / Resume / Retry (§9.3) — same job machinery. */
    fun translateChapter(chapterId: Long) {
        val scheduler = jobScheduler ?: return
        viewModelScope.launch {
            when (val result = scheduler.translateChapter(bookId, chapterId)) {
                is AppResult.Ok -> _events.tryEmit(
                    BookDetailUiEvent.ShowMessage("Chapter queued for translation.")
                )
                is AppResult.Err -> _events.tryEmit(
                    BookDetailUiEvent.ShowMessage(ErrorText.of(result.error).message)
                )
            }
        }
    }

    fun pauseJob() {
        val scheduler = jobScheduler ?: return
        viewModelScope.launch {
            scheduler.pause(bookId)
            _events.tryEmit(BookDetailUiEvent.ShowMessage("Pausing — the current chapter finishes safely."))
        }
    }

    fun resumeJob() {
        val scheduler = jobScheduler ?: return
        viewModelScope.launch {
            when (val result = scheduler.resume(bookId)) {
                is AppResult.Ok -> _events.tryEmit(
                    BookDetailUiEvent.ShowMessage("Job resumed.")
                )
                is AppResult.Err -> _events.tryEmit(
                    BookDetailUiEvent.ShowMessage(ErrorText.of(result.error).message)
                )
            }
        }
    }

    fun cancelJob() {
        val scheduler = jobScheduler ?: return
        viewModelScope.launch {
            scheduler.cancel(bookId)
            _events.tryEmit(
                BookDetailUiEvent.ShowMessage("Job cancelled — finished chapters are kept.")
            )
        }
    }

    /** Languages are editable here, in the open (§9.3) — never hidden. */
    fun changeSourceLanguage(code: String) {
        if (!Languages.isKnown(code)) return
        val book = _state.value.book ?: return
        if (code == book.sourceLang) return
        viewModelScope.launch {
            repository.updateBook(book.copy(sourceLang = code))
        }
    }

    fun changeTargetLanguage(code: String) {
        if (!Languages.isKnown(code)) return
        val book = _state.value.book ?: return
        if (code == book.targetLang) return
        viewModelScope.launch {
            repository.updateBook(book.copy(targetLang = code))
        }
    }

    fun updateDetails(title: String, author: String) {
        val book = _state.value.book ?: return
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) {
            _events.tryEmit(BookDetailUiEvent.ShowMessage("The title can't be empty."))
            return
        }
        viewModelScope.launch {
            when (
                val result = repository.updateBook(
                    book.copy(
                        titleOriginal = trimmedTitle,
                        author = author.trim().takeIf { it.isNotEmpty() }
                    )
                )
            ) {
                is AppResult.Ok -> _events.tryEmit(
                    BookDetailUiEvent.ShowMessage("Details saved.")
                )
                is AppResult.Err -> _events.tryEmit(
                    BookDetailUiEvent.ShowMessage(ErrorText.of(result.error).message)
                )
            }
        }
    }

    // ---- v1.1.3: Improve with AI (§A2/§A3/§G) -------------------------

    /** Opens the bulk-Improve sheet with a fresh estimate + key check. */
    fun openImproveSheet() {
        val scheduler = jobScheduler ?: return
        viewModelScope.launch {
            val estimate = scheduler.estimatePolish(bookId, _state.value.improveScope)
            _state.update {
                it.copy(
                    showImproveSheet = true,
                    improveEstimate = estimate,
                    aiAvailable = aiConfigured()
                )
            }
        }
    }

    fun dismissImproveSheet() {
        _state.update { it.copy(showImproveSheet = false) }
    }

    fun setImproveScope(scope: app.retranslate.domain.translate.PolishScope) {
        val scheduler = jobScheduler ?: return
        _state.update { it.copy(improveScope = scope) }
        viewModelScope.launch {
            val estimate = scheduler.estimatePolish(bookId, scope)
            _state.update { it.copy(improveEstimate = estimate) }
        }
    }

    fun setImproveStrength(strength: app.retranslate.domain.translate.ImproveStrength) {
        _state.update { it.copy(improveStrength = strength) }
    }

    /** Starts the bulk-Improve job (quota-aware engine, §G). */
    fun startImprove() {
        val scheduler = jobScheduler ?: return
        val state = _state.value
        _state.update { it.copy(showImproveSheet = false) }
        if (!aiConfigured()) {
            _events.tryEmit(
                BookDetailUiEvent.ShowMessage(
                    "Improve needs an AI key — add one under More → " +
                        "Providers & keys (a free Gemini key works)."
                )
            )
            return
        }
        viewModelScope.launch {
            when (
                val result = scheduler.polishBook(
                    bookId, state.improveScope, state.improveStrength
                )
            ) {
                is AppResult.Ok -> _events.tryEmit(
                    BookDetailUiEvent.ShowMessage(
                        "Improving started — ${state.improveEstimate} chapters " +
                            "in scope. Follow it here or on the Jobs tab; if the " +
                            "AI gets busy it pauses and continues by itself."
                    )
                )
                is AppResult.Err -> _events.tryEmit(
                    BookDetailUiEvent.ShowMessage(ErrorText.of(result.error).message)
                )
            }
        }
    }

    /** §H: translate the whole book with the on-device Offline engine. */
    fun translateAllOffline() {
        val scheduler = jobScheduler ?: return
        viewModelScope.launch {
            when (val result = scheduler.translateBookOffline(bookId)) {
                is AppResult.Ok -> _events.tryEmit(
                    BookDetailUiEvent.ShowMessage(
                        "Offline translation started — it runs on this phone, " +
                            "no internet needed. Chapters are marked " +
                            "Offline-translated so you can AI-improve them later."
                    )
                )
                is AppResult.Err -> _events.tryEmit(
                    BookDetailUiEvent.ShowMessage(ErrorText.of(result.error).message)
                )
            }
        }
    }

    /** §A3: Improve ONE chapter with AI, right from its row. */
    fun improveChapter(chapterId: Long) {
        val engine = polisher ?: return
        val book = _state.value.book ?: return
        if (!aiConfigured()) {
            _events.tryEmit(
                BookDetailUiEvent.ShowMessage(
                    "Improve needs an AI key — add one under More → " +
                        "Providers & keys (a free Gemini key works)."
                )
            )
            return
        }
        if (_state.value.busyChapterId != null) return
        viewModelScope.launch {
            _state.update { it.copy(busyChapterId = chapterId) }
            try {
                val chapter = chapterDao?.getChapter(chapterId)
                    ?: return@launch
                when (val result = engine.polish(book, chapter)) {
                    is AppResult.Ok -> when (val value = result.value) {
                        is app.retranslate.domain.translate.PolishResult.Applied ->
                            _events.tryEmit(
                                BookDetailUiEvent.ShowMessage(
                                    "Chapter ${chapter.number} improved. Its " +
                                        "original is saved — Restore original " +
                                        "is on the chapter."
                                )
                            )
                        is app.retranslate.domain.translate.PolishResult.Rejected ->
                            _events.tryEmit(
                                BookDetailUiEvent.ShowMessage(
                                    "The AI's version wasn't kept: ${value.reason} " +
                                        "The chapter is unchanged."
                                )
                            )
                    }
                    is AppResult.Err -> _events.tryEmit(
                        BookDetailUiEvent.ShowMessage(
                            ErrorText.of(result.error).message
                        )
                    )
                }
            } finally {
                _state.update { it.copy(busyChapterId = null) }
            }
        }
    }

    /** §G: Restore Original for one chapter (per-chapter archive). */
    fun restoreChapterOriginal(chapterId: Long) {
        val archive = originals ?: return
        viewModelScope.launch {
            when (val result = archive.restore(bookId, chapterId)) {
                is AppResult.Ok -> _events.tryEmit(
                    BookDetailUiEvent.ShowMessage(
                        if (result.value > 0) {
                            "Chapter ${result.value} restored to its original text."
                        } else {
                            "No saved original for this chapter."
                        }
                    )
                )
                is AppResult.Err -> _events.tryEmit(
                    BookDetailUiEvent.ShowMessage(ErrorText.of(result.error).message)
                )
            }
        }
    }

    /** §G: the free Consistency check — no AI calls, worst first. */
    fun runConsistencyCheck() {
        val scanner = consistencyScanner ?: return
        if (_state.value.consistencyRunning) return
        viewModelScope.launch {
            _state.update { it.copy(consistencyRunning = true) }
            try {
                val report = scanner.scan(bookId)
                _state.update { it.copy(consistency = report) }
            } finally {
                _state.update { it.copy(consistencyRunning = false) }
            }
        }
    }

    fun dismissConsistency() {
        _state.update { it.copy(consistency = null) }
    }

    fun toggleNeedsImprovement() {
        _state.update { it.copy(needsImprovementOnly = !it.needsImprovementOnly) }
    }

    fun askDelete() {
        _state.update { it.copy(showDeleteConfirm = true) }
    }

    fun dismissDelete() {
        _state.update { it.copy(showDeleteConfirm = false) }
    }

    fun confirmDelete() {
        viewModelScope.launch {
            // v1.1 (workstream E): cancel any running/paused job for this
            // book FIRST (the scheduler's cancel goes by the book's
            // WorkManager tag — translate and study work both stop),
            // then the repository removes every owned row in one
            // transaction and deletes the book's files.
            when (
                val result = repository.removeBook(bookId, bookFiles) { id ->
                    jobScheduler?.cancel(id)
                }
            ) {
                is AppResult.Ok -> _events.tryEmit(BookDetailUiEvent.NavigateBack)
                is AppResult.Err -> {
                    _state.update { it.copy(showDeleteConfirm = false) }
                    _events.tryEmit(
                        BookDetailUiEvent.ShowMessage(ErrorText.of(result.error).message)
                    )
                }
            }
        }
    }

    companion object {
        private val ACTIVE_JOB_STATES = setOf("QUEUED", "RUNNING", "PAUSED")
    }
}
