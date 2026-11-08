package app.retranslate.providers.free

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.core.network.HttpResponse
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
 * Mozhi — the primary free route (PLAN.md §4.2). One API shape verified
 * live in P1:
 *   GET {instance}/api/translate/?engine=google&from=ko&to=en&text=…
 *   200 → {"engine":"google","detected":"ko","translated-text":"hello",…}
 *
 * Mozhi's failure bodies are plain text, not JSON (also verified):
 * "instance has been rate limited", "Target language code invalid",
 * "Engine does not exist or has been disabled" — mapped by body below.
 */
class MozhiProvider(
    private val transport: HttpTransport,
    health: ProviderHealth,
    instances: InstanceStore,
    nowMs: () -> Long = { System.currentTimeMillis() },
    delayFn: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }
) : MultiInstanceProvider(health, instances, nowMs, delayFn) {

    override val id: ProviderId = ProviderId.MOZHI
    override val displayName: String = "Mozhi"
    override val kind: Provider.Kind = Provider.Kind.FREE_WEB

    private val mapping: LanguageMapping = ProviderLanguageMaps.MOZHI

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
            return AppResult.Err(mozhiError(response.code, response.body, req))
        }
        val parsed = try {
            ProviderJson.decodeFromString<MozhiTranslateResponse>(response.body)
        } catch (t: Throwable) {
            // A 200 that isn't Mozhi JSON is a parked/bot-check page: the
            // instance is unusable for API calls, and waiting won't heal it.
            return AppResult.Err(AppError.ProviderDown(id.name, response.code, retryable = false))
        }
        val text = parsed.translatedText
        if (text.isNullOrBlank()) {
            return AppResult.Err(AppError.ProviderDown(id.name, response.code, retryable = false))
        }
        return AppResult.Ok(
            Translation(
                text = text,
                providerId = id,
                detectedSource = parsed.detected?.let { mapping.fromProvider(it) },
                instance = instance
            )
        )
    }

    /** Mozhi's plain-text error bodies, mapped precisely (verified live). */
    private fun mozhiError(code: Int, body: String, req: TranslateRequest): AppError {
        val text = body.trim()
        return when {
            text.contains("rate limited", ignoreCase = true) ->
                AppError.RateLimited(id.name, null)
            text.contains("language code invalid", ignoreCase = true) ->
                AppError.UnsupportedLanguagePair(req.source, req.target)
            text.contains("Engine does not exist", ignoreCase = true) ->
                AppError.ProviderDown(id.name, code, retryable = false)
            else -> HttpErrors.forResponse(id.name, HttpResponse(code, body))
                ?: AppError.ProviderDown(id.name, code)
        }
    }

    companion object {
        /** Verified live on two instances (P1); "libre" also answered. */
        const val ENGINE = "google"
    }
}

@Serializable
data class MozhiTranslateResponse(
    @SerialName("translated-text") val translatedText: String? = null,
    val detected: String? = null
)
