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
import app.retranslate.providers.key.KeyTestable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * LibreTranslate — user-configured only (PLAN.md §4.2). The public demo
 * instance now requires a paid key (verified LIVE in P2: POSTing without
 * one answers HTTP 400 {"error":"Visit https://portal.libretranslate.com
 * to get an API key"}), so v1 ships NO default instances: the user adds
 * their own instance (e.g. on a home PC) and, for libretranslate.com,
 * their own key — the key, when present, rides along as `api_key`.
 *
 *   POST {instance}/translate
 *   body  {"q":"…","source":"ko","target":"en","format":"text"[,"api_key":"…"]}
 *   200 → {"translatedText":"…"}
 *
 * With no instances configured, the base class answers ProviderDown
 * immediately (no HTTP, no time spent) and the chain routes around it;
 * the Providers screen shows the honest "Not set up" state.
 */
class LibreTranslateProvider(
    private val transport: HttpTransport,
    health: ProviderHealth,
    instances: InstanceStore,
    nowMs: () -> Long = { System.currentTimeMillis() },
    delayFn: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val keyProvider: suspend () -> String? = { null },
    private val usageRecorder: suspend (chars: Int) -> Unit = {}
) : MultiInstanceProvider(health, instances, nowMs, delayFn), KeyTestable {

    override val id: ProviderId = ProviderId.LIBRETRANSLATE
    override val displayName: String = "LibreTranslate"
    override val kind: Provider.Kind = Provider.Kind.KEY_CLASSIC

    private val mapping: LanguageMapping = ProviderLanguageMaps.LIBRETRANSLATE

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
        val key = keyProvider()
        val body = buildJsonObject {
            put("q", req.text)
            put("source", src)
            put("target", tgt)
            put("format", "text")
            if (!key.isNullOrBlank()) put("api_key", key)
        }.toString()
        val response = when (
            val r = transport.post("${instance.trimEnd('/')}/translate", body)
        ) {
            is AppResult.Err -> return r
            is AppResult.Ok -> r.value
        }
        if (!response.isSuccessful) return AppResult.Err(libreError(response))
        val parsed = try {
            ProviderJson.decodeFromString<LibreTranslateResponse>(response.body)
        } catch (t: Throwable) {
            return AppResult.Err(AppError.ProviderDown(id.name, response.code, retryable = false))
        }
        val text = parsed.translatedText
        if (text.isNullOrBlank()) {
            return AppResult.Err(AppError.ProviderDown(id.name, response.code, retryable = false))
        }
        usageRecorder(req.text.length)
        return AppResult.Ok(Translation(text = text, providerId = id, instance = instance))
    }

    /**
     * A LibreTranslate key only has meaning against the public host
     * (self-hosted instances usually need none) — so the candidate key
     * is proven there, with one bounded call that touches neither the
     * vault nor provider health.
     */
    override suspend fun testWithSecret(secret: String): AppResult<Unit> {
        if (secret.isBlank()) return AppResult.Err(AppError.AuthFailed(id.name))
        val body = buildJsonObject {
            put("q", "hello")
            put("source", "en")
            put("target", "es")
            put("format", "text")
            put("api_key", secret)
        }.toString()
        val response = when (
            val r = transport.post("$PUBLIC_HOST/translate", body)
        ) {
            is AppResult.Err -> return r
            is AppResult.Ok -> r.value
        }
        return if (response.isSuccessful) {
            AppResult.Ok(Unit)
        } else {
            AppResult.Err(libreError(response))
        }
    }

    private fun libreError(response: HttpResponse): AppError {        val errorText = try {
            ProviderJson.decodeFromString<LibreTranslateErrorBody>(response.body).error
        } catch (t: Throwable) {
            null
        }
        return when {
            // The live-verified "you need a key" answer from the public host.
            errorText?.contains("API key", ignoreCase = true) == true ->
                AppError.AuthFailed(id.name)
            else -> HttpErrors.forResponse(id.name, response)
                ?: AppError.ProviderDown(id.name, response.code)
        }
    }
}

@Serializable
data class LibreTranslateResponse(val translatedText: String? = null)

@Serializable
data class LibreTranslateErrorBody(val error: String? = null)

private const val PUBLIC_HOST = "https://libretranslate.com"
