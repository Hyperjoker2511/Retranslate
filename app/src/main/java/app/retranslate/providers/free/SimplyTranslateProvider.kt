package app.retranslate.providers.free

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.core.network.HttpTransport
import app.retranslate.providers.Language
import app.retranslate.providers.LanguageMapping
import app.retranslate.providers.Languages
import app.retranslate.providers.Provider
import app.retranslate.providers.ProviderId
import app.retranslate.providers.ProviderLanguageMaps
import app.retranslate.providers.TranslateRequest
import app.retranslate.providers.Translation
import app.retranslate.providers.hub.HttpErrors
import app.retranslate.providers.hub.InstanceStore
import app.retranslate.providers.hub.ProviderHealth
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * SimplyTranslate — tertiary free route (PLAN.md §4.2). Same API lineage
 * as Mozhi (verified live in P1):
 *   GET {instance}/api/translate/?engine=google&from=ko&to=en&text=…
 *   200 → {"source_language":"ko","translated_text":"…",…}
 *
 * P1 finding: from the build sandbox the one public instance answered
 * with the correct shape but an EMPTY `translated_text` for every pair
 * and engine tried — its upstream fetch appears blocked for this egress
 * IP, so translation is UNVERIFIED from the sandbox. An empty text is
 * therefore treated as a (non-retryable) provider fault, never shown to
 * the user as a "successful" empty translation — that silent-empty
 * result was the old app's worst bug.
 */
class SimplyTranslateProvider(
    private val transport: HttpTransport,
    health: ProviderHealth,
    instances: InstanceStore,
    nowMs: () -> Long = { System.currentTimeMillis() },
    delayFn: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }
) : MultiInstanceProvider(health, instances, nowMs, delayFn) {

    override val id: ProviderId = ProviderId.SIMPLYTRANSLATE
    override val displayName: String = "SimplyTranslate"
    override val kind: Provider.Kind = Provider.Kind.FREE_WEB

    private val mapping: LanguageMapping = ProviderLanguageMaps.SIMPLYTRANSLATE

    override fun supportsPair(source: String, target: String): Boolean =
        mapping.supportsPair(source, target)

    override suspend fun supportedLanguages(): AppResult<List<Language>> =
        AppResult.Ok(Languages.ALL.filter { mapping.toProvider(it.code, isSource = false) != null })

    override suspend fun translateOnInstance(
        instance: String,
        req: TranslateRequest
    ): AppResult<Translation> {
        val src = mapping.toProvider(req.source, isSource = true)
            ?: return AppResult.Err(AppError.UnsupportedLanguagePair(req.source, req.target))
        val tgt = mapping.toProvider(req.target, isSource = false)
            ?: return AppResult.Err(AppError.UnsupportedLanguagePair(req.source, req.target))
        val base = "$instance/api/translate/".toHttpUrlOrNull()
            ?: return AppResult.Err(AppError.ProviderDown(id.name, retryable = false))
        val url = base.newBuilder()
            .addQueryParameter("engine", ENGINE)
            .addQueryParameter("from", src)
            .addQueryParameter("to", tgt)
            .addQueryParameter("text", req.text)
            .build()
            .toString()

        val response = when (val r = transport.get(url)) {
            is AppResult.Err -> return r
            is AppResult.Ok -> r.value
        }
        if (!response.isSuccessful) {
            return AppResult.Err(
                HttpErrors.forResponse(id.name, response)
                    ?: AppError.ProviderDown(id.name, response.code)
            )
        }
        val parsed = try {
            ProviderJson.decodeFromString<SimplyTranslateResponse>(response.body)
        } catch (t: Throwable) {
            return AppResult.Err(AppError.ProviderDown(id.name, response.code, retryable = false))
        }
        val text = parsed.translatedText
        if (text.isNullOrBlank()) {
            // The silent-empty failure mode: a fault, not a result.
            return AppResult.Err(AppError.ProviderDown(id.name, response.code, retryable = false))
        }
        return AppResult.Ok(Translation(text = text, providerId = id, instance = instance))
    }

    companion object {
        const val ENGINE = "google"
    }
}

@Serializable
data class SimplyTranslateResponse(
    @SerialName("translated_text") val translatedText: String? = null,
    @SerialName("source_language") val sourceLanguage: String? = null
)
