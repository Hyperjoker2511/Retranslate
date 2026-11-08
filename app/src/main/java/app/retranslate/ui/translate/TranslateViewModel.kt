package app.retranslate.ui.translate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.core.error.ErrorText
import app.retranslate.data.prefs.LanguagePair
import app.retranslate.data.prefs.MergePrefs
import app.retranslate.data.prefs.ProviderChoicePrefs
import app.retranslate.data.prefs.SavedLanguages
import app.retranslate.domain.speech.SpeakOutcome
import app.retranslate.domain.speech.SpeechController
import app.retranslate.domain.translate.LargeTextTranslator
import app.retranslate.domain.translate.MergeCandidate
import app.retranslate.domain.translate.MergeOrchestrator
import app.retranslate.domain.translate.MergeOutcome
import app.retranslate.providers.LANG_AUTO
import app.retranslate.providers.LangCode
import app.retranslate.providers.Languages
import app.retranslate.providers.ProviderId
import app.retranslate.providers.hub.ProviderRouter
import app.retranslate.providers.hub.TranslatePurpose
import app.retranslate.ui.settings.ProviderRowState
import app.retranslate.ui.settings.ProviderStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Quick Translate state (PLAN.md §9.3). From P1 the provider hub fills
 * `result` through the cascading chain; the language pair is owned by
 * [SavedLanguages] and merely reflected here.
 *
 * P7 adds §5.6 AI Compare & Merge (the "Best result" switch, the
 * merged-via chip, the "See other versions" candidates) and the
 * Listen button (§9.3) over the same [SpeechController] component
 * the review screen uses.
 */
data class TranslateUiState(
    val input: String = "",
    val sourceLang: LangCode = LANG_AUTO,
    val targetLang: LangCode = "en",
    val result: String? = null,
    val isTranslating: Boolean = false,
    /** Inline, plain-words problem with the input itself (e.g. it is empty). */
    val inputError: String? = null,
    /** Plain-words failure from the translation chain (ErrorText output). */
    val errorMessage: String? = null,
    /** v1.0.1: technical detail under [errorMessage] (the AppError's detail). */
    val errorDetail: String? = null,
    /** Source of the shown result, e.g. "Lingva" — the §4.4.3 "via X" chip. */
    val viaProvider: String? = null,
    /** Set when the result was detected as this language (Auto source). */
    val detectedLanguage: String? = null,
    /** §5.6: an AI key exists, so the merge switch is shown at all. */
    val mergeAvailable: Boolean = false,
    /** §5.6: the "Best result (AI merge)" switch (persisted, default ON). */
    val mergeEnabled: Boolean = true,
    /** §5.6: today's merges hit the daily soft cap — switch disables itself. */
    val mergeCapReached: Boolean = false,
    val mergesUsedToday: Int = 0,
    val mergeDailyCap: Int = MergePrefs.DEFAULT_DAILY_CAP,
    /** §5.6: the raw candidates behind the shown result (viewer data). */
    val mergeCandidates: List<MergeCandidate> = emptyList(),
    /** §5.6: the "See other versions" sheet is open. */
    val showVersions: Boolean = false,
    /** §9.3 Listen: the phone is reading the result aloud right now. */
    val isSpeaking: Boolean = false,
    /** P8: Large text / paste-a-chapter mode (§9.3) is showing. */
    val largeTextMode: Boolean = false,
    /** P8: "Translating part 2 of 5…" while a large paste runs. */
    val largeProgressText: String? = null,
    /**
     * v1.1 provider picker: the persisted quick-translate choice.
     * Null = "Automatic" — the plain chain order, unchanged.
     */
    val providerChoice: ProviderId? = null,
    /** v1.1: display name of [providerChoice], for the chip label. */
    val providerChoiceName: String? = null,
    /** v1.1: the picker's rows — the same [ProviderRowState]s Providers & keys shows. */
    val providerOptions: List<ProviderRowState> = emptyList(),
    /** v1.1: the provider picker sheet is open. */
    val showProviderPicker: Boolean = false,
    // ---- v1.1.3 (§I/§K/§C/§E) ------------------------------------------
    /**
     * §I: the engine pick as a string — a ProviderId name or a
     * gateway slot key ("gw1".."gw6"). Null = Automatic, the ONLY
     * mode where the routing chain runs. A pick = that engine only.
     */
    val providerPickKey: String? = null,
    /** §K: the user's added gateways, pickable in the same sheet. */
    val gatewayOptions: List<GatewayPickOption> = emptyList(),
    /** §C: the chapter-mode choice — Original language vs MTL. */
    val chapterKind: app.retranslate.domain.translate.ChapterTextKind =
        app.retranslate.domain.translate.ChapterTextKind.ORIGINAL,
    /** §C: the user picked the kind themselves (detection stops pre-selecting). */
    val chapterKindTouched: Boolean = false,
    /** §C: what detection thinks, in words ("Looks like Korean…"). */
    val detectedNote: String? = null,
    /** §G: a long quick-mode paste that looks like a whole chapter. */
    val showChapterNudge: Boolean = false,
    /** §C: the "Save this base for future chapters" ask is showing. */
    val baseAskVisible: Boolean = false,
    /** §C: how many names/terms the chapter's base holds. */
    val baseEntryCount: Int = 0,
    /** §E: the save-as-file format sheet is open. */
    val showSaveSheet: Boolean = false,
    /** §C/§G: plain-words notes under a chapter-mode result. */
    val resultNotes: List<String> = emptyList()
)

/** §K: one added gateway in the engine picker. */
data class GatewayPickOption(val pickKey: String, val name: String)

/**
 * §C: files the finished standalone chapter into a new Library book
 * and seeds its Knowledge Base with the chapter's base — the
 * "Save this base for future chapters" answer. Implemented by the
 * container over BookRepository + KbRepository.
 */
interface ChapterSouvenirSaver {
    suspend fun save(
        title: String,
        sourceLang: String,
        targetLang: String,
        sourceText: String?,
        translatedText: String,
        drafts: List<app.retranslate.domain.kb.KbEntryDraft>
    ): AppResult<Unit>
}

sealed interface TranslateUiEvent {
    data class ShowMessage(val text: String) : TranslateUiEvent

    /** v1.1: a picker row needs set-up first — route to Providers & keys. */
    data object OpenProviderSettings : TranslateUiEvent

    /** v1.1.3 (§E): a result file is written — offer Save / Share. */
    data class SaveFileReady(
        val filePath: String,
        val fileName: String,
        val mimeType: String
    ) : TranslateUiEvent
}

class TranslateViewModel(
    private val router: ProviderRouter,
    private val savedLanguages: SavedLanguages,
    private val largeTextTranslator: LargeTextTranslator? = null,
    private val mergeOrchestrator: MergeOrchestrator? = null,
    private val mergePrefs: MergePrefs? = null,
    private val mergesUsedToday: (suspend () -> Int)? = null,
    private val aiKeyPresent: () -> Boolean = { false },
    private val speech: SpeechController? = null,
    /** v1.1: the persisted provider choice (null-safe: absent = picker off). */
    private val providerChoicePrefs: ProviderChoicePrefs? = null,
    /** v1.1: rows for the picker sheet, from the shared [ProviderStatus] builder. */
    private val pickerRows: (suspend () -> List<ProviderRowState>)? = null,
    /** v1.1.3 (§C): the standalone-chapter engine (mini-base + process). */
    private val makeBetter: app.retranslate.domain.translate.ChapterMakeBetter? = null,
    /** v1.1.3 (§I): a pick string → the ONE provider it names. */
    private val providerForPick: (String) -> app.retranslate.providers.Provider? =
        { null },
    /** v1.1.3 (§K): the added gateways for the picker sheet. */
    private val gatewayPicks: (suspend () -> List<GatewayPickOption>)? = null,
    /** v1.1.3 (§E): save-a-result-as-a-file writer. */
    private val resultExporter: app.retranslate.domain.export.ResultFileExporter? = null,
    /** v1.1.3 (§C): the "Save this base" souvenir writer. */
    private val souvenirSaver: ChapterSouvenirSaver? = null
) : ViewModel() {

    /** §C: the last chapter-mode run, kept for the base-ask answer. */
    private var lastOutcome: app.retranslate.domain.translate.MakeBetterOutcome? = null
    private var lastChapterInput: String = ""
    private var lastChapterSource: String = LANG_AUTO

    private val _state = MutableStateFlow(TranslateUiState())
    val state: StateFlow<TranslateUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TranslateUiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<TranslateUiEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            savedLanguages.pair.collect { pair ->
                _state.update { it.copy(sourceLang = pair.source, targetLang = pair.target) }
            }
        }
        // §5.6: the switch exists only with an AI key; its state and
        // the daily cap are persisted (MergePrefs) and merely reflected.
        _state.update { it.copy(mergeAvailable = aiKeyPresent() && mergeOrchestrator != null) }
        if (mergePrefs != null) {
            viewModelScope.launch {
                mergePrefs.enabled.collect { enabled ->
                    _state.update { it.copy(mergeEnabled = enabled) }
                }
            }
            viewModelScope.launch {
                mergePrefs.dailyCap.collect { cap ->
                    _state.update { it.copy(mergeDailyCap = cap) }
                    refreshMergeUsage()
                }
            }
        }
        // v1.1: the persisted provider choice, merely reflected here.
        // The chip's name resolves from the picker rows when they load;
        // until then the enum-derived name keeps the label truthful.
        if (providerChoicePrefs != null) {
            viewModelScope.launch {
                providerChoicePrefs.choice.collect { choice ->
                    _state.update {
                        it.copy(
                            providerChoice = choice,
                            // v1.1.3 (§I): a legacy ProviderId choice IS
                            // a pick — unless a string pick exists.
                            providerPickKey = it.providerPickKey ?: choice?.name,
                            providerChoiceName = choice?.let { id ->
                                it.providerOptions.firstOrNull { row -> row.id == id }?.name
                                    ?: fallbackProviderName(id)
                            }
                        )
                    }
                }
            }
            // v1.1.3 (§I/§K): the string pick (gateways included).
            viewModelScope.launch {
                providerChoicePrefs.pickKey.collect { key ->
                    _state.update { current ->
                        if (key == null) {
                            current
                        } else {
                            current.copy(
                                providerPickKey = key,
                                providerChoiceName = if (key.startsWith("gw")) {
                                    current.gatewayOptions
                                        .firstOrNull { it.pickKey == key }?.name
                                        ?: "Added provider"
                                } else {
                                    current.providerChoiceName
                                }
                            )
                        }
                    }
                }
            }
        }
        refreshMergeUsage()
        speech?.let { controller ->
            viewModelScope.launch {
                controller.isSpeaking.collect { speaking ->
                    _state.update { it.copy(isSpeaking = speaking) }
                }
            }
        }
    }

    private fun refreshMergeUsage() {
        val counter = mergesUsedToday ?: return
        viewModelScope.launch {
            val used = counter()
            _state.update {
                it.copy(
                    mergesUsedToday = used,
                    mergeCapReached = used >= it.mergeDailyCap
                )
            }
        }
    }

    fun onInputChanged(text: String) {
        _state.update { current ->
            // v1.1.3 (§G): a long quick-mode paste is probably a whole
            // chapter — offer the Chapter mode instead of silently
            // running it as quick text.
            val nudge = !current.largeTextMode && text.trim().length > NUDGE_CHARS
            // §C: in Chapter mode, detection pre-selects the kind and
            // explains itself — until the user picks the kind, which
            // then decides (the §C refinement).
            var kind = current.chapterKind
            var note = current.detectedNote
            if (current.largeTextMode && text.trim().length > 120) {
                val detected = app.retranslate.domain.books.LanguageDetector
                    .detect(text.take(4000))
                if (detected != null) {
                    val looksTranslated = detected == current.targetLang
                    note = if (looksTranslated) {
                        "Looks like ${Languages.nameOf(detected)} — if this is " +
                            "already translated, MTL is the right choice."
                    } else {
                        "Looks like ${Languages.nameOf(detected)} — treated as " +
                            "the original language."
                    }
                    if (!current.chapterKindTouched) {
                        kind = if (looksTranslated) {
                            app.retranslate.domain.translate.ChapterTextKind.MTL
                        } else {
                            app.retranslate.domain.translate.ChapterTextKind.ORIGINAL
                        }
                    }
                }
            }
            current.copy(
                input = text, inputError = null,
                showChapterNudge = nudge,
                chapterKind = kind,
                detectedNote = note
            )
        }
    }

    /** §C: the user picked the kind — their option decides from now on. */
    fun onChapterKindChosen(
        kind: app.retranslate.domain.translate.ChapterTextKind
    ) {
        _state.update { it.copy(chapterKind = kind, chapterKindTouched = true) }
    }

    /** §G: the nudge's button — switch this paste to Chapter mode. */
    fun onSwitchToChapterMode() {
        _state.update {
            it.copy(largeTextMode = true, showChapterNudge = false, inputError = null)
        }
    }

    fun onDismissChapterNudge() {
        _state.update { it.copy(showChapterNudge = false) }
    }

    fun onSourceChanged(code: LangCode) {
        viewModelScope.launch {
            savedLanguages.save(LanguagePair(source = code, target = _state.value.targetLang))
        }
    }

    fun onTargetChanged(code: LangCode) {
        viewModelScope.launch {
            savedLanguages.save(LanguagePair(source = _state.value.sourceLang, target = code))
        }
    }

    fun onSwapClicked() {
        val current = _state.value
        if (current.sourceLang == LANG_AUTO) {
            _events.tryEmit(
                TranslateUiEvent.ShowMessage(
                    "Turn off Auto detect to swap languages — pick the language your text is in."
                )
            )
            return
        }
        viewModelScope.launch {
            savedLanguages.save(
                LanguagePair(source = current.targetLang, target = current.sourceLang)
            )
        }
    }

    fun onMergeToggleChanged(enabled: Boolean) {
        _state.update { it.copy(mergeEnabled = enabled) }
        viewModelScope.launch { mergePrefs?.setEnabled(enabled) }
    }

    fun onShowVersions() {
        _state.update { it.copy(showVersions = true) }
    }

    fun onDismissVersions() {
        _state.update { it.copy(showVersions = false) }
    }

    fun onTranslateClicked() {
        val current = _state.value
        if (current.isTranslating) return
        if (current.input.isBlank()) {
            _state.update { it.copy(inputError = "Type or paste something first.") }
            return
        }
        // §4.4.1: same-language is stopped BEFORE any call, in plain words.
        // v1.1.3 (§C): MTL chapter mode is the exception — its text is
        // already IN the target language; improving it is the point.
        val mtlChapter = current.largeTextMode &&
            current.chapterKind ==
            app.retranslate.domain.translate.ChapterTextKind.MTL
        if (!mtlChapter && current.sourceLang != LANG_AUTO &&
            current.sourceLang == current.targetLang
        ) {
            val error = AppError.SameLanguage(Languages.nameOf(current.targetLang))
            _state.update {
                it.copy(
                    errorMessage = ErrorText.of(error).message, errorDetail = null,
                    result = null, viaProvider = null
                )
            }
            return
        }
        // A new translation retires the old result's speech (§9.3).
        speech?.stop()
        _state.update {
            it.copy(
                isTranslating = true, errorMessage = null, errorDetail = null,
                inputError = null, mergeCandidates = emptyList(), showVersions = false
            )
        }
        viewModelScope.launch {
            val text = current.input.trim()
            // v1.1.3 (§C): Chapter mode runs the mini-base flow.
            if (current.largeTextMode && makeBetter != null) {
                runChapterMode(current, text)
                return@launch
            }
            if (current.largeTextMode && largeTextTranslator != null) {
                runLargeText(current, text)
                return@launch
            }
            // v1.1.3 (§I): a specific engine pick runs THAT engine
            // only — no chain, no merge, no silent switch.
            if (current.providerPickKey != null) {
                runSpecificProvider(current, text)
                return@launch
            }
            if (mergeActive(current)) {
                when (
                    val outcome = mergeOrchestrator!!.merge(
                        text = text,
                        source = current.sourceLang,
                        target = current.targetLang
                    )
                ) {
                    is MergeOutcome.Merged -> {
                        _state.update {
                            it.copy(
                                isTranslating = false,
                                result = outcome.text,
                                viaProvider = "AI merge " +
                                    "(${outcome.candidates.size} versions checked)",
                                detectedLanguage = outcome.candidates
                                    .firstNotNullOfOrNull { c -> c.detectedSource }
                                    ?.let { code -> Languages.nameOf(code) },
                                mergeCandidates = outcome.candidates,
                                mergesUsedToday = outcome.mergesUsedToday,
                                mergeCapReached =
                                    outcome.mergesUsedToday >= it.mergeDailyCap
                            )
                        }
                        return@launch
                    }
                    is MergeOutcome.BestSingle -> {
                        // §5.6 fallback: judge down/garbage — the best
                        // single candidate, with its normal "via X" chip.
                        _state.update {
                            it.copy(
                                isTranslating = false,
                                result = outcome.candidate.text,
                                viaProvider = outcome.candidate.providerName,
                                detectedLanguage = outcome.candidate.detectedSource
                                    ?.let { code -> Languages.nameOf(code) },
                                mergeCandidates = outcome.candidates
                            )
                        }
                        refreshMergeUsage()
                        return@launch
                    }
                    is MergeOutcome.UsePlainChain -> {
                        // Cap reached / too few candidates: the plain
                        // chain below answers instead (§5.6).
                        refreshMergeUsage()
                    }
                }
            }
            runPlainChain(current, text)
        }
    }

    /** P8 (§9.3): a large paste goes through the chunked chapter chain. */
    private suspend fun runLargeText(current: TranslateUiState, text: String) {
        val translator = largeTextTranslator ?: return
        when (
            val outcome = translator.translate(
                text = text,
                source = current.sourceLang,
                target = current.targetLang
            ) { done, total ->
                _state.update {
                    it.copy(largeProgressText = "Translating part $done of $total…")
                }
            }
        ) {
            is AppResult.Ok -> {
                val value = outcome.value
                val via = value.viaNames.joinToString(" + ") +
                    if (value.chunkCount > 1) " (${value.chunkCount} parts)" else ""
                _state.update {
                    it.copy(
                        isTranslating = false,
                        largeProgressText = null,
                        result = value.text,
                        viaProvider = via,
                        detectedLanguage = null
                    )
                }
                if (value.switched) {
                    _events.tryEmit(
                        TranslateUiEvent.ShowMessage(
                            "One provider was busy — another finished this large text."
                        )
                    )
                }
            }
            is AppResult.Err -> {
                _state.update {
                    it.copy(
                        isTranslating = false,
                        largeProgressText = null,
                        result = null,
                        viaProvider = null,
                        errorMessage = ErrorText.of(outcome.error).message,
                        errorDetail = outcome.error.detail
                    )
                }
            }
        }
    }

    /** Merge runs only when available, switched on, and under the cap. */
    /**
     * v1.1.3 (§C): Chapter mode — build the chapter's mini-base, then
     * translate (Original) or improve (MTL) with the base riding
     * along; afterwards the base-ask card offers the user's exact
     * choice: save the base for future chapters, or just this chapter.
     */
    private suspend fun runChapterMode(current: TranslateUiState, text: String) {
        val engine = makeBetter ?: return
        val pickKey = current.providerPickKey
        val picked = pickKey?.let { providerForPick(it) }
        // §I in chapter mode: the pick is the translation engine for
        // Original; for MTL only an AI pick can steer the improve —
        // a classic pick is said in the notes, never silently used.
        val notes = mutableListOf<String>()
        val override = when {
            picked == null -> null
            current.chapterKind ==
                app.retranslate.domain.translate.ChapterTextKind.MTL &&
                picked.kind != app.retranslate.providers.Provider.Kind.KEY_AI -> {
                notes += "MTL improve uses your AI, so the picked engine " +
                    "(${picked.displayName}) wasn't used for this one."
                null
            }
            else -> picked
        }
        val source = if (
            current.chapterKind ==
            app.retranslate.domain.translate.ChapterTextKind.MTL
        ) {
            current.targetLang
        } else {
            current.sourceLang
        }
        _state.update { it.copy(largeProgressText = "Building this chapter's base…") }
        when (
            val outcome = engine.makeBetter(
                text = text,
                kind = current.chapterKind,
                sourceLang = source,
                targetLang = current.targetLang,
                providerOverride = override
            ) { done, total ->
                _state.update {
                    it.copy(largeProgressText = "Working on part $done of $total…")
                }
            }
        ) {
            is AppResult.Ok -> {
                val value = outcome.value
                lastOutcome = value
                lastChapterInput = text
                lastChapterSource = source
                _state.update {
                    it.copy(
                        isTranslating = false,
                        largeProgressText = null,
                        result = value.text,
                        viaProvider = value.viaNames.joinToString(" + "),
                        resultNotes = notes + value.notes,
                        baseAskVisible = value.base.entries.isNotEmpty(),
                        baseEntryCount = value.base.entries.size
                    )
                }
            }
            is AppResult.Err -> {
                _state.update {
                    it.copy(
                        isTranslating = false,
                        largeProgressText = null,
                        result = null,
                        viaProvider = null,
                        errorMessage = ErrorText.of(outcome.error).message,
                        errorDetail = outcome.error.detail
                    )
                }
            }
        }
    }

    /**
     * v1.1.3 (§I): ONE picked engine, alone. Its failure is reported
     * plainly with the way out (pick another, or Automatic) — the
     * chain is never quietly run behind the user's pick.
     */
    private suspend fun runSpecificProvider(current: TranslateUiState, text: String) {
        val key = current.providerPickKey ?: return
        val provider = providerForPick(key)
        if (provider == null) {
            _state.update {
                it.copy(
                    isTranslating = false,
                    errorMessage = "That engine isn't available anymore — " +
                        "tap the engine chip and pick another, or choose Automatic."
                )
            }
            return
        }
        when (
            val result = provider.translate(
                app.retranslate.providers.TranslateRequest(
                    text = text,
                    source = current.sourceLang,
                    target = current.targetLang,
                    quality = app.retranslate.providers.QualityMode.BALANCED,
                    deadlineMs = 30_000L
                )
            )
        ) {
            is AppResult.Ok -> _state.update {
                it.copy(
                    isTranslating = false,
                    result = result.value.text,
                    viaProvider = provider.displayName,
                    detectedLanguage = result.value.detectedSource
                        ?.let { code -> Languages.nameOf(code) }
                )
            }
            is AppResult.Err -> _state.update {
                it.copy(
                    isTranslating = false,
                    result = null,
                    viaProvider = null,
                    errorMessage = "${provider.displayName} couldn't do this one: " +
                        ErrorText.of(result.error).message +
                        " No other engine was tried — tap the engine chip " +
                        "to pick another, or choose Automatic.",
                    errorDetail = result.error.detail
                )
            }
        }
    }

    /** §C: "Save this base for future chapters" — the souvenir book. */
    fun onSaveBaseChosen() {
        val outcome = lastOutcome ?: return
        val saver = souvenirSaver ?: return
        _state.update { it.copy(baseAskVisible = false) }
        val title = app.retranslate.domain.export.ResultFileExporter
            .suggestedTitle(lastChapterInput, "Pasted chapter")
        val isOriginal = _state.value.chapterKind ==
            app.retranslate.domain.translate.ChapterTextKind.ORIGINAL
        viewModelScope.launch {
            when (
                val saved = saver.save(
                    title = title,
                    sourceLang = if (lastChapterSource == LANG_AUTO) {
                        // Detection inside the flow resolved it; the
                        // target is the honest fallback label.
                        _state.value.targetLang
                    } else {
                        lastChapterSource
                    },
                    targetLang = _state.value.targetLang,
                    sourceText = if (isOriginal) lastChapterInput else null,
                    translatedText = outcome.text,
                    drafts = outcome.base.drafts
                )
            ) {
                is AppResult.Ok -> _events.tryEmit(
                    TranslateUiEvent.ShowMessage(
                        "Saved to your Library as “$title” — its base " +
                            "(${outcome.base.entries.size} names and terms) " +
                            "now applies to that book's future chapters."
                    )
                )
                is AppResult.Err -> _events.tryEmit(
                    TranslateUiEvent.ShowMessage(ErrorText.of(saved.error).message)
                )
            }
        }
    }

    /** §C: "Just this chapter" — also what dismissing the card means. */
    fun onJustThisChapter() {
        _state.update { it.copy(baseAskVisible = false) }
    }

    // -- Save as file (§E, ask-first) -------------------------------------

    fun onSaveAsFileClicked() {
        if (_state.value.result.isNullOrBlank()) return
        _state.update { it.copy(showSaveSheet = true) }
    }

    fun onDismissSaveSheet() {
        _state.update { it.copy(showSaveSheet = false) }
    }

    /** The user confirmed a format in the sheet — NOW the file is written. */
    fun onSaveFormatChosen(format: app.retranslate.domain.export.ExportFormat) {
        val exporter = resultExporter ?: return
        val result = _state.value.result ?: return
        _state.update { it.copy(showSaveSheet = false) }
        viewModelScope.launch {
            when (
                val written = exporter.writeTempFile(
                    title = app.retranslate.domain.export.ResultFileExporter
                        .suggestedTitle(result),
                    author = null,
                    targetLang = _state.value.targetLang,
                    text = result,
                    format = format
                )
            ) {
                is AppResult.Ok -> _events.tryEmit(
                    TranslateUiEvent.SaveFileReady(
                        filePath = written.value.file.absolutePath,
                        fileName = written.value.fileName,
                        mimeType = written.value.mimeType
                    )
                )
                is AppResult.Err -> _events.tryEmit(
                    TranslateUiEvent.ShowMessage(ErrorText.of(written.error).message)
                )
            }
        }
    }

    private fun mergeActive(state: TranslateUiState): Boolean =
        mergeOrchestrator != null && state.mergeAvailable && state.mergeEnabled &&
            !state.mergeCapReached

    // -- Provider picker (v1.1, the user's own feature) ---------------------

    /** The chip was tapped: open the sheet with fresh statuses. */
    fun onProviderChipClicked() {
        _state.update { it.copy(showProviderPicker = true) }
        val rows = pickerRows
        viewModelScope.launch {
            // §K: the added gateways are pickable in the same sheet.
            gatewayPicks?.let { source ->
                val picks = source()
                _state.update { current ->
                    current.copy(
                        gatewayOptions = picks,
                        providerChoiceName = current.providerPickKey
                            ?.takeIf { key -> key.startsWith("gw") }
                            ?.let { key ->
                                picks.firstOrNull { it.pickKey == key }?.name
                            } ?: current.providerChoiceName
                    )
                }
            }
            if (rows == null) return@launch
            val fresh = rows()
            _state.update { current ->
                current.copy(
                    providerOptions = fresh,
                    providerChoiceName = current.providerChoice?.let { id ->
                        fresh.firstOrNull { row -> row.id == id }?.name
                            ?: current.providerChoiceName
                    }
                )
            }
        }
    }

    fun onDismissProviderPicker() {
        _state.update { it.copy(showProviderPicker = false) }
    }

    /** "Automatic (recommended)" was chosen — the plain chain order. */
    fun onAutomaticChosen() {
        persistChoice(null)
    }

    /** §K: an added gateway was chosen in the picker (§I: it alone runs). */
    fun onGatewayPickChosen(option: GatewayPickOption) {
        _state.update {
            it.copy(
                showProviderPicker = false,
                providerPickKey = option.pickKey,
                providerChoice = null,
                providerChoiceName = option.name
            )
        }
        viewModelScope.launch {
            providerChoicePrefs?.setPickKey(option.pickKey)
            providerChoicePrefs?.setChoice(null)
        }
    }

    /**
     * A provider row was tapped. A row that still needs set-up (no key
     * saved, no instance, Ollama unconfigured) does NOT select — it
     * routes the user to Providers & keys, where the set-up lives.
     */
    fun onProviderRowTapped(row: ProviderRowState) {
        if (ProviderStatus.needsSetup(row)) {
            _state.update { it.copy(showProviderPicker = false) }
            _events.tryEmit(TranslateUiEvent.OpenProviderSettings)
            return
        }
        persistChoice(row.id)
    }

    private fun persistChoice(id: ProviderId?) {
        val name = id?.let { chosen ->
            _state.value.providerOptions.firstOrNull { it.id == chosen }?.name
                ?: fallbackProviderName(chosen)
        }
        _state.update {
            it.copy(
                showProviderPicker = false,
                providerChoice = id,
                // v1.1.3 (§I): the pick string is the one source of
                // truth for what runs — a choice IS a pick now.
                providerPickKey = id?.name,
                providerChoiceName = name
            )
        }
        viewModelScope.launch {
            providerChoicePrefs?.setChoice(id)
            providerChoicePrefs?.setPickKey(id?.name)
        }
    }

    /** Chip label before the picker rows have ever loaded. */
    private fun fallbackProviderName(id: ProviderId): String = when (id) {
        ProviderId.GEMINI -> "Gemini AI"
        ProviderId.DEEPL -> "DeepL"
        ProviderId.AZURE -> "Azure Translator"
        ProviderId.OPENAI -> "OpenAI"
        ProviderId.CLAUDE -> "Claude"
        ProviderId.MOZHI -> "Mozhi"
        ProviderId.LINGVA -> "Lingva"
        ProviderId.SIMPLYTRANSLATE -> "SimplyTranslate"
        ProviderId.LIBRETRANSLATE -> "LibreTranslate"
        ProviderId.MYMEMORY -> "MyMemory"
        ProviderId.OLLAMA -> "Ollama"
        ProviderId.APINEX -> "APInex"
        ProviderId.OFFLINE -> "Offline"
        ProviderId.CUSTOM -> "Added provider"
    }

    private suspend fun runPlainChain(current: TranslateUiState, text: String) {
        val outcome = router.translate(
            text = text,
            source = current.sourceLang,
            target = current.targetLang,
            purpose = TranslatePurpose.QUICK_TEXT
            // v1.1.3 (§I): NO preference here — this chain runs only
            // in Automatic mode. A specific pick is intercepted in
            // onTranslateClicked and runs alone (runSpecificProvider);
            // the v1.1 "leads but chain continues" behaviour is gone.
        )
        when (outcome) {
            is AppResult.Ok -> {
                val routed = outcome.value
                _state.update {
                    it.copy(
                        isTranslating = false,
                        result = routed.text,
                        viaProvider = routed.viaName,
                        detectedLanguage = routed.detectedSource
                            ?.let { code -> Languages.nameOf(code) }
                    )
                }
                // §4.4.3: at most ONE snackbar, only when a switch happened.
                routed.switchedFromName?.let { from ->
                    _events.tryEmit(
                        TranslateUiEvent.ShowMessage("$from is busy — switched to ${routed.viaName}")
                    )
                }
            }
            is AppResult.Err -> {
                _state.update {
                    it.copy(
                        isTranslating = false,
                        result = null,
                        viaProvider = null,
                        errorMessage = ErrorText.of(outcome.error).message,
                        errorDetail = outcome.error.detail
                    )
                }
            }
        }
    }

    // -- Listen (§9.3) ------------------------------------------------------

    fun onListenClicked() {
        val controller = speech ?: return
        val current = _state.value
        if (current.isSpeaking) {
            controller.stop()
            return
        }
        val result = current.result?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            when (controller.speak(result, current.targetLang)) {
                SpeakOutcome.STARTED -> Unit
                SpeakOutcome.NO_VOICE -> _events.tryEmit(
                    TranslateUiEvent.ShowMessage(
                        "Your phone has no voice for " +
                            "${Languages.nameOf(current.targetLang)} — " +
                            "Listen can't read this language yet."
                    )
                )
                SpeakOutcome.UNAVAILABLE -> _events.tryEmit(
                    TranslateUiEvent.ShowMessage(
                        "Listen isn't available on this phone."
                    )
                )
            }
        }
    }

    /** The screen left view: speech stops immediately (§9.3). */
    fun onScreenClosed() {
        speech?.stop()
    }

    override fun onCleared() {
        // The TTS engine is owned by this ViewModel — never left running.
        speech?.stop()
        speech?.shutdown()
        super.onCleared()
    }

    /** §9.3: the visible Large text button toggles paste-a-chapter mode. */
    fun onLargeTextClicked() {
        if (_state.value.isTranslating) return
        _state.update {
            it.copy(
                largeTextMode = !it.largeTextMode,
                largeProgressText = null,
                inputError = null
            )
        }
    }

    /** v1.1.3 (F21): the [Quick text | Chapter] segmented control. */
    fun onModeSelected(chapter: Boolean) {
        if (_state.value.isTranslating) return
        _state.update {
            it.copy(
                largeTextMode = chapter,
                largeProgressText = null,
                inputError = null,
                showChapterNudge = false
            )
        }
    }

    companion object {
        /** §G: pastes longer than this offer the Chapter mode. */
        const val NUDGE_CHARS = 4000
    }
}
