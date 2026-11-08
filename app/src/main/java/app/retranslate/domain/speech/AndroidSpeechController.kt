package app.retranslate.domain.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [SpeechController] over Android's [TextToSpeech] (PLAN.md §9.3).
 * The engine initialises lazily on first Listen and asynchronously —
 * the first speak() awaits readiness (bounded by the engine itself:
 * a failed init answers UNAVAILABLE, never a hang). Speaking state is
 * tracked from the utterance listener: the LAST queued utterance's
 * onDone/onError flips it off, so Listen↔Stop always tells the truth.
 *
 * Device-bound: none of this runs on the JVM. The lifecycle rules are
 * unit-tested against the [SpeechController] interface with a fake;
 * this class is labelled UNVERIFIED in the P7 report.
 */
class AndroidSpeechController(context: Context) : SpeechController {

    private val appContext: Context = context.applicationContext

    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private var shutDown = false

    /** Id of the most recently queued utterance — its onDone ends speech. */
    @Volatile
    private var lastUtteranceId: String? = null

    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    override suspend fun speak(text: String, languageTag: String): SpeakOutcome {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || shutDown) return SpeakOutcome.UNAVAILABLE
        val engine = engine() ?: return SpeakOutcome.UNAVAILABLE
        val locale = localeFor(languageTag)
        val langResult = try {
            engine.setLanguage(locale)
        } catch (t: Throwable) {
            return SpeakOutcome.UNAVAILABLE
        }
        if (langResult == TextToSpeech.LANG_MISSING_DATA ||
            langResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            return SpeakOutcome.NO_VOICE
        }
        // Paragraph-sized utterances: Android caps one utterance at
        // 4,000 chars, and paragraph breaks are the natural pauses.
        val chunks = chunkForSpeech(trimmed)
        if (chunks.isEmpty()) return SpeakOutcome.UNAVAILABLE
        var mode = TextToSpeech.QUEUE_FLUSH
        var queued = 0
        for (chunk in chunks) {
            val utteranceId = "rt-${UUID.randomUUID()}"
            val result = engine.speak(chunk, mode, null, utteranceId)
            if (result == TextToSpeech.SUCCESS) {
                queued++
                lastUtteranceId = utteranceId
                mode = TextToSpeech.QUEUE_ADD
            }
        }
        if (queued == 0) return SpeakOutcome.UNAVAILABLE
        _isSpeaking.value = true
        return SpeakOutcome.STARTED
    }

    override fun stop() {
        try {
            tts?.stop()
        } catch (t: Throwable) {
            // A dying engine must not crash the screen (§10).
        }
        _isSpeaking.value = false
    }

    override fun shutdown() {
        shutDown = true
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (t: Throwable) {
            // Same rule: teardown never crashes.
        }
        tts = null
        _isSpeaking.value = false
    }

    /** The engine, created + awaited on first use. Null = no engine. */
    private suspend fun engine(): TextToSpeech? {
        tts?.let { return it }
        val deferred = CompletableDeferred<Boolean>()
        ready = deferred
        val created = try {
            TextToSpeech(appContext) { status ->
                deferred.complete(status == TextToSpeech.SUCCESS)
            }
        } catch (t: Throwable) {
            return null
        }
        tts = created
        created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId != null && utteranceId == lastUtteranceId) {
                    _isSpeaking.value = false
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _isSpeaking.value = false
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                _isSpeaking.value = false
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                _isSpeaking.value = false
            }
        })
        return if (deferred.await()) created else null
    }

    companion object {
        /** Android's per-utterance cap is 4,000 chars; stay well under. */
        const val MAX_UTTERANCE_CHARS = 3_500

        /**
         * Splits text into speakable chunks: paragraphs first, then
         * hard cuts at the cap. Pure — unit-tested.
         */
        fun chunkForSpeech(text: String): List<String> {
            val out = mutableListOf<String>()
            for (paragraph in text.split("\n\n")) {
                var rest = paragraph.trim()
                while (rest.length > MAX_UTTERANCE_CHARS) {
                    out += rest.substring(0, MAX_UTTERANCE_CHARS)
                    rest = rest.substring(MAX_UTTERANCE_CHARS).trim()
                }
                if (rest.isNotEmpty()) out += rest
            }
            return out
        }

        /**
         * App language code → a TTS locale. App codes are BCP-47-ish
         * already ("zh-CN"); `auto` never reaches here (speech is
         * always of a concrete shown text) but degrades to English.
         */
        fun localeFor(languageTag: String): Locale {
            if (languageTag.isBlank() || languageTag == "auto") return Locale.ENGLISH
            return Locale.forLanguageTag(languageTag) ?: Locale.ENGLISH
        }
    }
}
