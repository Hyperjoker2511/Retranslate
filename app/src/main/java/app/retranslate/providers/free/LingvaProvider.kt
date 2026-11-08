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
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Lingva Translate — secondary free route (PLAN.md §4.2). API verified
 * live in P1:
 *   GET {instance}/api/v1/{src}/{tgt}/{text}
 *   200 → {"translation":"…"}
 *   failure → HTTP 500 {"error":"An error occurred while retrieving…"}
 *   GET {instance}/api/v1/languages/source → {"languages":[{code,name}]}
 *
 * From the build sandbox both bundled instances answered the language
 * list but returned the 500 error for translations (their upstream
 * fetch is blocked for this egress IP) — translation is UNVERIFIED from
 * the sandbox; the provider ships behind Mozhi with its breakers armed.
 */
class LingvaProvider(
    private val transport: HttpTransport,
    health: ProviderHealth,
    private val instanceStore: InstanceStore,
    nowMs: () -> Long = { System.currentTimeMillis() },
    delayFn: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    /** P3 (§4.6): persistent language-list cache; null keeps P1 behaviour. */
    private val languageCache: app.retranslate.providers.hub.LanguageListCache? = null
) : MultiInstanceProvider(health, instanceStore, nowMs, delayFn) {

    override val id: ProviderId = ProviderId.LINGVA
    override val displayName: String = "Lingva"
    override val kind: Provider.Kind = Provider.Kind.FREE_WEB

    private val mapping: LanguageMapping = ProviderLanguageMaps.LINGVA

    @Volatile
    private var cachedLanguages: List<Language>? = null

    @Volatile
    private var cachedAtMs: Long = 0L

    override fun supportsPair(source: String, target: String): Boolean =
        mapping.supportsPair(source, target)

    override suspend fun supportedLanguages(): AppResult<List<Language>> {
        val cached = cachedLanguages
        if (cached != null && nowMs() - cachedAtMs < LANGUAGES_TTL_MS) {
            return AppResult.Ok(cached)
        }
        // Persistent layer (§4.6): a list fetched before a process death
        // is still valid — adopt it instead of refetching.
        val persisted = languageCache?.get(id)
        if (persisted != null && persisted.languages.isNotEmpty() &&
            nowMs() - persisted.fetchedAtMs < LANGUAGES_TTL_MS
        ) {
            cachedLanguages = persisted.languages
            cachedAtMs = persisted.fetchedAtMs
            return AppResult.Ok(persisted.languages)
        }
        val hosts = instanceStore.instances(id)
        for (host in hosts) {
            val url = "$host/api/v1/languages/source".toHttpUrlOrNull() ?: continue
            val response = (transport.get(url.toString()) as? AppResult.Ok)?.value ?: continue
            if (!response.isSuccessful) continue
            val parsed = try {
                ProviderJson.decodeFromString<LingvaLanguagesResponse>(response.body)
            } catch (t: Throwable) {
                continue
            }
            val languages = parsed.languages.mapNotNull { dto ->
                mapping.fromProvider(dto.code)?.let { code ->
                    Language(code, Languages.nameOf(code))
                }
            }
            if (languages.isNotEmpty()) {
                cachedLanguages = languages
                cachedAtMs = nowMs()
                languageCache?.put(id, languages, cachedAtMs)
                return AppResult.Ok(languages)
            }
        }
        // No instance answered: fall back to the canonical mapped list.
        return AppResult.Ok(
            Languages.ALL.filter { mapping.toProvider(it.code, isSource = false) != null }
        )
    }

    override suspend fun translateOnInstance(
        instance: String,
        req: TranslateRequest
    ): AppResult<Translation> {
        val src = mapping.toProvider(req.source, isSource = true)
            ?: return AppResult.Err(AppError.UnsupportedLanguagePair(req.source, req.target))
        val tgt = mapping.toProvider(req.target, isSource = false)
            ?: return AppResult.Err(AppError.UnsupportedLanguagePair(req.source, req.target))
        val base = "$instance/api/v1".toHttpUrlOrNull()
            ?: return AppResult.Err(AppError.ProviderDown(id.name, retryable = false))
        val url = base.newBuilder()
            .addPathSegment(src)
            .addPathSegment(tgt)
            .addPathSegment(req.text)
            .build()
            .toString()

        val response = when (val r = transport.get(url)) {
            is AppResult.Err -> return r
            is AppResult.Ok -> r.value
        }
        if (!response.isSuccessful) {
            return AppResult.Err(lingvaError(response))
        }
        val parsed = try {
            ProviderJson.decodeFromString<LingvaTranslateResponse>(response.body)
        } catch (t: Throwable) {
            return AppResult.Err(AppError.ProviderDown(id.name, response.code, retryable = false))
        }
        val text = parsed.translation
        if (text.isNullOrBlank()) {
            return AppResult.Err(AppError.ProviderDown(id.name, response.code, retryable = false))
        }
        return AppResult.Ok(Translation(text = text, providerId = id, instance = instance))
    }

    private fun lingvaError(response: HttpResponse): AppError {
        // Lingva's own JSON error body on 5xx = its upstream fetch failed:
        // a transient provider fault. Anything else (HTML challenge pages)
        // is not healed by waiting.
        val isLingvaJson = response.body.trimStart().startsWith("{")
        return if (response.code in 500..599 && isLingvaJson) {
            AppError.ProviderDown(id.name, response.code, retryable = true)
        } else {
            HttpErrors.forResponse(id.name, response)
                ?: AppError.ProviderDown(id.name, response.code, retryable = false)
        }
    }

    companion object {
        /** 7 days, per §4.6. Persisted via the Room-backed cache (P3). */
        const val LANGUAGES_TTL_MS = 7 * 24 * 60 * 60_000L
    }
}

@Serializable
data class LingvaTranslateResponse(val translation: String? = null)

@Serializable
data class LingvaLanguagesResponse(val languages: List<LingvaLanguageDto> = emptyList())

@Serializable
data class LingvaLanguageDto(val code: String = "", val name: String = "")
