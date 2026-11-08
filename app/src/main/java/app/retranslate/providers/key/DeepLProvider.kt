package app.retranslate.providers.key

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.core.network.HttpResponse
import app.retranslate.core.network.HttpTransport
import app.retranslate.providers.LANG_AUTO
import app.retranslate.providers.Language
import app.retranslate.providers.LanguageMapping
import app.retranslate.providers.Languages
import app.retranslate.providers.Provider
import app.retranslate.providers.ProviderId
import app.retranslate.providers.ProviderLanguageMaps
import app.retranslate.providers.TranslateRequest
import app.retranslate.providers.Translation
import app.retranslate.providers.free.ProviderJson
import app.retranslate.providers.free.SingleEndpointProvider
import app.retranslate.providers.hub.HttpErrors
import app.retranslate.providers.hub.ProviderHealth
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * DeepL — key-based classic provider (PLAN.md §4.3). Free keys end in
 * ":fx" and talk to api-free.deepl.com; all other keys use api.deepl.com.
 *
 *   POST {host}/v2/translate
 *   header  Authorization: DeepL-Auth-Key <key>
 *   body    {"text":["…"], "source_lang":"KO", "target_lang":"EN-US"}
 *           (source_lang omitted = DeepL auto-detects)
 *   200  →  {"translations":[{"detected_source_language":"KO","text":"…"}]}
 *
 * Auth verified LIVE from this sandbox in P2: a bad key answers
 * HTTP 403 {"message":"Authentication failed, provided API key is
 * invalid. …"}. Quota exhaustion has its own status: **456**.
 * The Test button uses GET /v2/usage (the canonical key check; it also
 * returns the account's real character count).
 */
class DeepLProvider(
    private val transport: HttpTransport,
    health: ProviderHealth,
    private val keyProvider: suspend () -> String?,
    private val isConfigured: () -> Boolean,
    nowMs: () -> Long = { System.currentTimeMillis() },
    delayFn: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val usageRecorder: suspend (chars: Int) -> Unit = {}
) : SingleEndpointProvider(health, nowMs, delayFn), KeyTestable {

    override val id: ProviderId = ProviderId.DEEPL
    override val displayName: String = "DeepL"
    override val kind: Provider.Kind = Provider.Kind.KEY_CLASSIC

    private val mapping: LanguageMapping = ProviderLanguageMaps.DEEPL

    override fun supportsPair(source: String, target: String): Boolean =
        isConfigured() && mapping.supportsPair(source, target)

    override suspend fun supportedLanguages(): AppResult<List<Language>> =
        AppResult.Ok(Languages.ALL.filter { mapping.toProvider(it.code, isSource = false) != null })

    override suspend fun callOnce(req: TranslateRequest): AppResult<Translation> =
        callWithKey(req, keyProvider())

    override suspend fun testWithSecret(secret: String): AppResult<Unit> {
        if (secret.isBlank()) return AppResult.Err(AppError.AuthFailed(id.name))
        val response = when (
            val r = transport.get(
                "${hostFor(secret)}/v2/usage",
                mapOf("Authorization" to "DeepL-Auth-Key $secret")
            )
        ) {
            is AppResult.Err -> return r
            is AppResult.Ok -> r.value
        }
        return if (response.isSuccessful) {
            AppResult.Ok(Unit)
        } else {
            AppResult.Err(deepLError(response))
        }
    }

    override suspend fun testConnection(): AppResult<Unit> =
        testWithSecret(keyProvider().orEmpty())

    private suspend fun callWithKey(req: TranslateRequest, key: String?): AppResult<Translation> {
        if (key.isNullOrBlank()) return AppResult.Err(AppError.AuthFailed(id.name))
        val tgt = mapping.toProvider(req.target, isSource = false)?.uppercase()
            ?: return AppResult.Err(AppError.UnsupportedLanguagePair(req.source, req.target))
        val src = if (req.source == LANG_AUTO) {
            null
        } else {
            mapping.toProvider(req.source, isSource = true)?.uppercase()
                ?: return AppResult.Err(AppError.UnsupportedLanguagePair(req.source, req.target))
        }
        val body = buildJsonObject {
            putJsonArray("text") { add(JsonPrimitive(req.text)) }
            if (src != null) put("source_lang", src)
            // DeepL requires a concrete English variant as a target.
            put("target_lang", if (tgt == "EN") "EN-US" else tgt)
        }.toString()
        val response = when (
            val r = transport.post(
                "${hostFor(key)}/v2/translate",
                body,
                mapOf("Authorization" to "DeepL-Auth-Key $key")
            )
        ) {
            is AppResult.Err -> return r
            is AppResult.Ok -> r.value
        }
        if (!response.isSuccessful) return AppResult.Err(deepLError(response))
        val parsed = try {
            ProviderJson.decodeFromString<DeepLTranslateResponse>(response.body)
        } catch (t: Throwable) {
            return AppResult.Err(AppError.ProviderDown(id.name, response.code, retryable = false))
        }
        val first = parsed.translations?.firstOrNull()
        val text = first?.text
        if (text.isNullOrBlank()) {
            return AppResult.Err(AppError.ProviderDown(id.name, response.code, retryable = false))
        }
        usageRecorder(req.text.length)
        return AppResult.Ok(
            Translation(
                text = text,
                providerId = id,
                detectedSource = first.detectedSourceLanguage?.let { detectedAppCode(it) }
            )
        )
    }

    /** DeepL reports detected languages UPPER-CASE ("KO", "ZH"); map back to app codes. */
    private fun detectedAppCode(deepLCode: String): String? = when (deepLCode.uppercase()) {
        "ZH" -> "zh-CN"
        "NB" -> "no"
        else -> mapping.fromProvider(deepLCode.lowercase())
    }

    private fun deepLError(response: HttpResponse): AppError = when (response.code) {
        // DeepL's own quota-exceeded status (§4.3: 500k chars/month free).
        456 -> AppError.RateLimited(id.name, null)
        else -> HttpErrors.forResponse(id.name, response)
            ?: AppError.ProviderDown(id.name, response.code)
    }

    companion object {
        const val HOST_FREE = "https://api-free.deepl.com"
        const val HOST_PRO = "https://api.deepl.com"

        /** Free-plan keys end in ":fx" and belong to the free host. */
        fun hostFor(key: String): String =
            if (key.trim().endsWith(":fx")) HOST_FREE else HOST_PRO
    }
}

@Serializable
data class DeepLTranslateResponse(val translations: List<DeepLTranslation>? = null)

@Serializable
data class DeepLTranslation(
    val text: String? = null,
    @kotlinx.serialization.SerialName("detected_source_language")
    val detectedSourceLanguage: String? = null
)
