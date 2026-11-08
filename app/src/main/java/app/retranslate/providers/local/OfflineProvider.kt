package app.retranslate.providers.local

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.providers.LANG_AUTO
import app.retranslate.providers.Language
import app.retranslate.providers.Languages
import app.retranslate.providers.Provider
import app.retranslate.providers.ProviderId
import app.retranslate.providers.TranslateRequest
import app.retranslate.providers.Translation

/**
 * Offline (v1.1.3 §H): Google ML Kit on-device translation as a
 * first-class provider — pickable everywhere (§I), never silently
 * substituted (§H rule 4: it is NOT in the Automatic chain; the app
 * offers it when a picked cloud engine can't be reached, and the
 * user decides).
 *
 * Honest limits, in the open: output is plain machine translation
 * (the intended flow is offline now → Improve with AI when back
 * online), and Auto-detect is impossible on-device — the source
 * language must be picked.
 */
class OfflineProvider(
    private val engine: OfflineTranslateEngine
) : Provider {

    override val id: ProviderId = ProviderId.OFFLINE
    override val displayName: String = "Offline"
    override val kind: Provider.Kind = Provider.Kind.LOCAL

    override fun supportsPair(source: String, target: String): Boolean {
        if (source == LANG_AUTO) return false
        val tags = engine.supportedTags()
        return mlKitTag(source) in tags && mlKitTag(target) in tags
    }

    override suspend fun supportedLanguages(): AppResult<List<Language>> {
        val tags = engine.supportedTags()
        return AppResult.Ok(Languages.ALL.filter { mlKitTag(it.code) in tags })
    }

    override suspend fun translate(req: TranslateRequest): AppResult<Translation> {
        if (req.source == LANG_AUTO) {
            return AppResult.Err(
                AppError.VerificationFailed(
                    "Offline can't detect the language — pick the language your text is in."
                )
            )
        }
        if (req.source == req.target) {
            return AppResult.Err(AppError.SameLanguage(Languages.nameOf(req.target)))
        }
        val sourceTag = mlKitTag(req.source)
        val targetTag = mlKitTag(req.target)
        if (OfflinePair(sourceTag, targetTag) !in engine.downloadedPairs()) {
            return AppResult.Err(
                AppError.VerificationFailed(
                    "That language isn't downloaded for Offline yet — " +
                        "download it under More → Providers & keys."
                )
            )
        }
        return engine.translate(req.text, sourceTag, targetTag).map { text ->
            Translation(text = text, providerId = ProviderId.OFFLINE)
        }
    }

    /** On-device: there is no connection to test — the engine is the test. */
    override suspend fun testConnection(): AppResult<Unit> = AppResult.Ok(Unit)

    companion object {
        /**
         * App code → ML Kit tag: ML Kit's tags are bare language
         * codes ("zh" covers both Chinese scripts — ML Kit ships
         * Simplified only, which the Providers screen says in words).
         */
        fun mlKitTag(code: String): String = code.substringBefore('-')
    }
}
