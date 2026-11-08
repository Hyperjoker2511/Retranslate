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
import app.retranslate.providers.QualityMode
import app.retranslate.providers.TranslateRequest
import app.retranslate.providers.Translation
import app.retranslate.providers.free.ProviderJson
import app.retranslate.providers.free.SingleEndpointProvider
import app.retranslate.providers.hub.HttpErrors
import app.retranslate.providers.hub.ProviderHealth
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI — a paid-key AI engine (PLAN.md §4.3, P7; chat-completions
 * shape). Listed, testable, usable for chapters and as an AI merge
 * candidate/judge — never a chain default, because it costs per call.
 *
 *   POST https://api.openai.com/v1/chat/completions
 *   header  Authorization: Bearer <key>
 *   body    {"model":"gpt-4o-mini","temperature":0.3,
 *            "messages":[{"role":"system","content":…},
 *                        {"role":"user","content":…}]}
 *   200  →  {"choices":[{"message":{"role":"assistant","content":"…"},
 *                        "finish_reason":"stop"}]}
 *   error → {"error":{"message":"…","type":"…","code":"…"}} —
 *           401 invalid_api_key → AuthFailed (§10 #4); 429 (code
 *           rate_limit_exceeded / insufficient_quota) → RateLimited;
 *           5xx → ProviderDown.
 */
class OpenAiProvider(
    private val transport: HttpTransport,
    health: ProviderHealth,
    private val keyProvider: suspend () -> String?,
    private val isConfigured: () -> Boolean,
    nowMs: () -> Long = { System.currentTimeMillis() },
    delayFn: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val usageRecorder: suspend (chars: Int) -> Unit = {},
    /**
     * v1.1.3 (§J/§K): the parameterised base. The same client serves
     * OpenAI itself (the default), the named APInex gateway, and any
     * user-added OpenAI-compatible gateway — only the root, identity
     * and model source differ.
     */
    private val apiBase: String = DEFAULT_API_BASE,
    private val providerId: ProviderId = ProviderId.OPENAI,
    private val providerDisplayName: String = "OpenAI",
    /** A model chosen outside the quality map (a gateway's pick). */
    private val modelPreference: suspend () -> String? = { null },
    /**
     * Gateways prove a key against GET /models instead of spending a
     * completion on the test (§K) — also the only honest test when
     * the gateway's model names are unknown.
     */
    private val testViaModelsList: Boolean = false
) : SingleEndpointProvider(health, nowMs, delayFn), KeyTestable {

    override val id: ProviderId get() = providerId
    override val displayName: String get() = providerDisplayName
    override val kind: Provider.Kind = Provider.Kind.KEY_AI

    private val mapping: LanguageMapping = ProviderLanguageMaps.OPENAI

    override fun supportsPair(source: String, target: String): Boolean =
        isConfigured() && mapping.supportsPair(source, target)

    override suspend fun supportedLanguages(): AppResult<List<Language>> =
        AppResult.Ok(Languages.ALL.filter { mapping.toProvider(it.code, isSource = false) != null })

    override suspend fun callOnce(req: TranslateRequest): AppResult<Translation> =
        callWithKey(req, keyProvider())

    override suspend fun testWithSecret(secret: String): AppResult<Unit> {
        if (testViaModelsList) return testViaModels(secret)
        return callWithKey(
            TranslateRequest(text = "hello", source = "en", target = "es", quality = QualityMode.FAST),
            secret
        ).map { }
    }

    /** §K: a gateway key is valid iff GET /models answers 2xx with it. */
    private suspend fun testViaModels(secret: String): AppResult<Unit> {
        if (secret.isBlank()) return AppResult.Err(AppError.AuthFailed(id.name))
        val response = when (
            val r = transport.get(
                "$apiBase/models",
                mapOf("Authorization" to "Bearer $secret")
            )
        ) {
            is AppResult.Err -> return r
            is AppResult.Ok -> r.value
        }
        return when {
            response.isSuccessful -> AppResult.Ok(Unit)
            response.code == 401 || response.code == 403 ->
                AppResult.Err(AppError.AuthFailed(id.name))
            else -> AppResult.Err(AppError.ProviderDown(id.name, response.code))
        }
    }

    override suspend fun testConnection(): AppResult<Unit> =
        testWithSecret(keyProvider().orEmpty())

    private suspend fun callWithKey(req: TranslateRequest, key: String?): AppResult<Translation> {
        if (key.isNullOrBlank()) return AppResult.Err(AppError.AuthFailed(id.name))
        if (!mapping.supportsPair(req.source, req.target)) {
            return AppResult.Err(AppError.UnsupportedLanguagePair(req.source, req.target))
        }
        val model = modelPreference()?.takeIf { it.isNotBlank() }
            ?: req.modelHint?.takeIf { it.isNotBlank() }
            ?: modelFor(req.quality)
        val response = when (
            val r = transport.post(
                "$apiBase/chat/completions",
                requestBody(req, model),
                mapOf("Authorization" to "Bearer $key")
            )
        ) {
            is AppResult.Err -> return r
            is AppResult.Ok -> r.value
        }
        if (!response.isSuccessful) return AppResult.Err(openAiError(response))

        val parsed = try {
            ProviderJson.decodeFromString<OpenAiChatResponse>(response.body)
        } catch (t: Throwable) {
            // A 200 we cannot parse is the AI answering garbage (§10 #11).
            return AppResult.Err(AppError.AiBadOutput(id.name))
        }
        val choice = parsed.choices?.firstOrNull()
        // v1.1.3 (§A4): a "length" finish = the reply was cut off at
        // the output bound. Never serve half a chapter as whole.
        if (choice?.finishReason == "length") {
            return AppResult.Err(AppError.AiBadOutput(id.name))
        }
        val text = choice?.message?.content?.trim()
        if (text.isNullOrBlank() || choice?.message?.refusal != null) {
            // No choice, empty completion, or an explicit refusal (§10 #11).
            return AppResult.Err(AppError.AiBadOutput(id.name))
        }
        usageRecorder(req.text.length)
        return AppResult.Ok(Translation(text = text, providerId = id))
    }

    private fun requestBody(req: TranslateRequest, model: String): String {
        val system = req.context?.systemPrompt ?: defaultInstruction(req)
        return buildJsonObject {
            put("model", model)
            put("temperature", TEMPERATURE)
            // v1.1.3 (§A4): explicit output bound (see the "length"
            // finish check in callWithKey).
            put("max_completion_tokens", MAX_OUTPUT_TOKENS)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", system)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", req.text)
                })
            }
        }.toString()
    }

    private fun defaultInstruction(req: TranslateRequest): String =
        if (req.source == LANG_AUTO) {
            "You are a translation engine. Detect the language of the user's text and " +
                "translate it into ${Languages.nameOf(req.target)}. " +
                "Output ONLY the translation — no explanations, no quotes, no notes."
        } else {
            "You are a translation engine. Translate the user's text from " +
                "${Languages.nameOf(req.source)} to ${Languages.nameOf(req.target)}. " +
                "Output ONLY the translation — no explanations, no quotes, no notes."
        }

    /** OpenAI's error envelope, mapped onto the §10 catalogue. */
    private fun openAiError(response: HttpResponse): AppError {
        val detail = try {
            ProviderJson.decodeFromString<OpenAiErrorBody>(response.body).error
        } catch (t: Throwable) {
            null
        }
        return when {
            response.code == 429 || detail?.code == "rate_limit_exceeded" ||
                detail?.type == "rate_limit_error" ->
                AppError.RateLimited(
                    id.name, HttpErrors.parseRetryAfterSeconds(response)
                )
            response.code == 401 || response.code == 403 ||
                detail?.code == "invalid_api_key" ->
                AppError.AuthFailed(id.name)
            else -> HttpErrors.forResponse(id.name, response)
                ?: AppError.ProviderDown(id.name, response.code)
        }
    }

    companion object {
        const val BASE_URL = "https://api.openai.com"

        /** v1.1.3: the default API root (base + version path). */
        const val DEFAULT_API_BASE = "https://api.openai.com/v1"
        const val MODEL_FAST = "gpt-4o-mini"
        const val MODEL_STANDARD = "gpt-4o-mini"
        const val MODEL_BEST = "gpt-4o"
        const val TEMPERATURE = 0.3

        /** v1.1.3 (§A4): explicit per-call output ceiling (tokens). */
        const val MAX_OUTPUT_TOKENS = 16_384

        /** Paid engine: mini does everyday work; the big model is Best-only. */
        fun modelFor(quality: QualityMode): String = when (quality) {
            QualityMode.FAST -> MODEL_FAST
            QualityMode.BALANCED -> MODEL_STANDARD
            QualityMode.BEST -> MODEL_BEST
        }
    }
}

@Serializable
data class OpenAiChatResponse(val choices: List<OpenAiChoice>? = null)

@Serializable
data class OpenAiChoice(
    val message: OpenAiMessage? = null,
    @kotlinx.serialization.SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class OpenAiMessage(
    val role: String? = null,
    val content: String? = null,
    val refusal: String? = null
)

@Serializable
data class OpenAiErrorBody(val error: OpenAiErrorDetail? = null)

@Serializable
data class OpenAiErrorDetail(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null
)
