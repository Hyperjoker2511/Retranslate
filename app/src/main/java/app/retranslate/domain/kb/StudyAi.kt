package app.retranslate.domain.kb

import app.retranslate.core.error.AppResult
import app.retranslate.providers.AiContext
import app.retranslate.providers.LANG_AUTO
import app.retranslate.providers.ProviderId
import app.retranslate.providers.QualityMode
import app.retranslate.providers.TranslateRequest
import app.retranslate.providers.key.GeminiProvider

/**
 * The one AI call study needs (§6.2): a system prompt + a user prompt
 * in, the model's raw text out. An interface so the study engine is
 * JVM-testable with a scripted fake — and so the extraction NEVER
 * goes through the fallback router (a classic provider "answering"
 * the extraction prompt with a translation of it would be garbage
 * in a judge's clothing). Production binds this to the P2 Gemini
 * provider directly — the provider plumbing, never raw HTTP.
 */
interface StudyAi {
    /** True when an AI key exists (study is impossible without one). */
    fun isConfigured(): Boolean

    suspend fun complete(
        systemPrompt: String,
        userPrompt: String,
        targetLang: String,
        quality: QualityMode
    ): AppResult<String>
}

/** [StudyAi] over the chapter-budget Gemini instance (§4.5). */
class GeminiStudyAi(
    private val gemini: GeminiProvider,
    private val configured: () -> Boolean
) : StudyAi {

    override fun isConfigured(): Boolean = configured()

    override suspend fun complete(
        systemPrompt: String,
        userPrompt: String,
        targetLang: String,
        quality: QualityMode
    ): AppResult<String> = gemini.translate(
        TranslateRequest(
            text = userPrompt,
            source = LANG_AUTO,
            target = targetLang,
            context = AiContext(systemPrompt),
            quality = quality
        )
    ).map { it.text }

    companion object {
        val PROVIDER_ID: ProviderId = ProviderId.GEMINI
    }
}
