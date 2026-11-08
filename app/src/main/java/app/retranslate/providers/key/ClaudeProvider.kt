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
 * Claude — a paid-key AI engine (PLAN.md §4.3, P7; Anthropic Messages
 * API adapter). Same standing as OpenAI: listed, testable, usable for
 * chapters and as an AI merge candidate/judge; never a chain default.
 *
 *   POST https://api.anthropic.com/v1/messages
 *   headers x-api-key: <key>
 *           anthropic-version: 2023-06-01
 *   body    {"model":"claude-sonnet-4-5","max_tokens":8192,
 *            "temperature":0.3,"system":…,
 *            "messages":[{"role":"user","content":…}]}
 *   200  →  {"content":[{"type":"text","text":"…"}],
 *            "stop_reason":"end_turn"}
 *   error → {"type":"error","error":{"type":"authentication_error",
 *           "message":"…"}} — 401 authentication_error /
 *           403 permission_error → AuthFailed (§10 #4); 429
 *           rate_limit_error → RateLimited; 529 overloaded_error and
 *           other 5xx → ProviderDown.
 *
 * A reply cut off at max_tokens (stop_reason "max_tokens") is NOT a
 * translation — half a chapter served as whole would be silent
 * corruption — so it maps to AiBadOutput (§10 #11), never Ok.
 */
class ClaudeProvider(
    private val transport: HttpTransport,
    health: ProviderHealth,
    private val keyProvider: suspend () -> String?,
    private val isConfigured: () -> Boolean,
    nowMs: () -> Long = { System.currentTimeMillis() },
    delayFn: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val usageRecorder: suspend (chars: Int) -> Unit = {},
    /**
     * v1.1.3 (§K): the parameterised base — the same client serves
     * Anthropic itself (the default) and any user-added
     * Anthropic-compatible gateway.
     */
    private val apiBase: String = DEFAULT_API_BASE,
    private val providerId: ProviderId = ProviderId.CLAUDE,
    private val providerDisplayName: String = "Claude",
    /** A model chosen outside the quality map (a gateway's pick). */
    private val modelPreference: suspend () -> String? = { null },
    /** Gateways prove a key against GET /models (§K). */
    private val testViaModelsList: Boolean = false
) : SingleEndpointProvider(health, nowMs, delayFn), KeyTestable {

    override val id: ProviderId get() = providerId
    override val displayName: String get() = providerDisplayName
    override val kind: Provider.Kind = Provider.Kind.KEY_AI

    private val mapping: LanguageMapping = ProviderLanguageMaps.CLAUDE

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
                mapOf("x-api-key" to secret, "anthropic-version" to API_VERSION)
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
                "$apiBase/messages",
                requestBody(req, model),
                mapOf(
                    "x-api-key" to key,
                    "anthropic-version" to API_VERSION
                )
            )
        ) {
            is AppResult.Err -> return r
            is AppResult.Ok -> r.value
        }
        if (!response.isSuccessful) return AppResult.Err(claudeError(response))

        val parsed = try {
            ProviderJson.decodeFromString<ClaudeMessagesResponse>(response.body)
        } catch (t: Throwable) {
            return AppResult.Err(AppError.AiBadOutput(id.name))
        }
        if (parsed.stopReason == "max_tokens" || parsed.stopReason == "refusal") {
            // Truncated or refused: unusable as a translation (§10 #11).
            return AppResult.Err(AppError.AiBadOutput(id.name))
        }
        val text = parsed.content.orEmpty()
            .filter { it.type == "text" }
            .mapNotNull { it.text }
            .joinToString("")
            .trim()
        if (text.isBlank()) {
            return AppResult.Err(AppError.AiBadOutput(id.name))
        }
        usageRecorder(req.text.length)
        return AppResult.Ok(Translation(text = text, providerId = id))
    }

    private fun requestBody(req: TranslateRequest, model: String): String {
        val system = req.context?.systemPrompt ?: defaultInstruction(req)
        return buildJsonObject {
            put("model", model)
            put("max_tokens", MAX_TOKENS)
            put("temperature", TEMPERATURE)
            put("system", system)
            putJsonArray("messages") {
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

    /** Anthropic's error envelope, mapped onto the §10 catalogue. */
    private fun claudeError(response: HttpResponse): AppError {
        val detail = try {
            ProviderJson.decodeFromString<ClaudeErrorBody>(response.body).error
        } catch (t: Throwable) {
            null
        }
        return when {
            response.code == 429 || detail?.type == "rate_limit_error" ->
                AppError.RateLimited(
                    id.name, HttpErrors.parseRetryAfterSeconds(response)
                )
            response.code == 401 || response.code == 403 ||
                detail?.type == "authentication_error" ||
                detail?.type == "permission_error" ->
                AppError.AuthFailed(id.name)
            else -> HttpErrors.forResponse(id.name, response)
                ?: AppError.ProviderDown(id.name, response.code)
        }
    }

    companion object {
        const val BASE_URL = "https://api.anthropic.com"

        /** v1.1.3: the default API root (base + version path). */
        const val DEFAULT_API_BASE = "https://api.anthropic.com/v1"
        const val API_VERSION = "2023-06-01"
        const val MODEL_FAST = "claude-haiku-4-5"
        const val MODEL_STANDARD = "claude-sonnet-4-5"

        /**
         * Output bound: a §5.1 chapter chunk is ≤8,000 source chars
         * (≈4k tokens worst case), so 8,192 output tokens covers every
         * request this app makes without the >8k streaming beta.
         */
        const val MAX_TOKENS = 8_192
        const val TEMPERATURE = 0.3

        /** §5.3: Fast runs Haiku; Balanced/Best run Sonnet. */
        fun modelFor(quality: QualityMode): String = when (quality) {
            QualityMode.FAST -> MODEL_FAST
            QualityMode.BALANCED, QualityMode.BEST -> MODEL_STANDARD
        }
    }
}

@Serializable
data class ClaudeMessagesResponse(
    val content: List<ClaudeContentBlock>? = null,
    @kotlinx.serialization.SerialName("stop_reason") val stopReason: String? = null
)

@Serializable
data class ClaudeContentBlock(
    val type: String? = null,
    val text: String? = null
)

@Serializable
data class ClaudeErrorBody(val error: ClaudeErrorDetail? = null)

@Serializable
data class ClaudeErrorDetail(
    val type: String? = null,
    val message: String? = null
)
