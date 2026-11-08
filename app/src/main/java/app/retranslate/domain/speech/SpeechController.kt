package app.retranslate.domain.speech

import kotlinx.coroutines.flow.StateFlow

/** How a Listen request ended before a word was spoken (§9.3). */
enum class SpeakOutcome {
    /** Speech started (or queued); watch [SpeechController.isSpeaking]. */
    STARTED,

    /** The phone has no voice for this language — explain, don't fail silently. */
    NO_VOICE,

    /** No TTS engine at all / it failed to start. */
    UNAVAILABLE
}

/**
 * The Listen boundary (PLAN.md §9.3, P7): the one component behind the
 * review screen's and Quick Translate's Listen/Stop buttons. An
 * interface so the lifecycle rules are JVM-testable with a fake:
 *  - pressing Stop stops speech immediately,
 *  - switching Original/Translation, changing chapter, or leaving the
 *    screen stops speech immediately,
 *  - the engine is owned by the owning ViewModel and shut down in
 *    `onCleared` — speech NEVER survives its screen.
 */
interface SpeechController {

    /** True while the engine is talking (drives Listen ↔ Stop). */
    val isSpeaking: StateFlow<Boolean>

    /**
     * Speak [text] in the language of BCP-47 [languageTag] ("en",
     * "ko", "zh-CN" — the app's own codes). Long text is queued in
     * paragraph-sized utterances by the implementation.
     */
    suspend fun speak(text: String, languageTag: String): SpeakOutcome

    /** Stop now (utterance queue flushed); safe to call when silent. */
    fun stop()

    /** Release the engine. The owner calls this exactly once. */
    fun shutdown()
}
