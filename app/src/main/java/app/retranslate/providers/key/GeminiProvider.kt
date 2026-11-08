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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Session memory for the Gemini model cascade (v1.1.2): the last model
 * that actually answered. Shared by every GeminiProvider instance the
 * container builds (quick-text + chapter-budget), so once one call has
 * paid the "model retired" 404 tax, no later call in the same process
 * pays it again. Volatile: a single reference read/write is all the
 * synchronisation this hint needs.
 */
class GeminiModelSession {
    @Volatile
    var workingModel: String? = null
}

/** One selectable Gemini model on the key screen (v1.1.2). */
data class GeminiModelOption(
    val modelId: String,
    val title: String,
    val description: String,
    val isLite: Boolean
)

/**
 * Gemini — the app's main AI engine when the user adds their free key
 * (PLAN.md §4.3, §4.4.1: Gemini FIRST in the chain when configured).
 *
 * REST shape (Google AI for Developers docs):
 *   POST {base}/v1beta/models/{model}:generateContent
 *   header  x-goog-api-key: <key>
 *   body    {"systemInstruction": {"parts":[{"text": …}]},
 *            "contents": [{"role":"user","parts":[{"text": …}]}],
 *            "generationConfig": {"temperature": 0.3}}
 *   200  →  {"candidates":[{"content":{"parts":[{"text": …}]}}], …}
 *
 * Error shapes verified LIVE from this sandbox in P2 with a probe key:
 * a bad key answers HTTP 400 with status INVALID_ARGUMENT and an
 * ErrorInfo detail whose reason is API_KEY_INVALID — so 400 must be
 * body-sniffed for auth, not blindly mapped (see [geminiError]).
 * Quota answers 429 / RESOURCE_EXHAUSTED, often with a RetryInfo
 * retryDelay in the details.
 *
 * v1.1.2 — the model cascade. In Sept 2026 Google restricted ALL 2.5
 * models to past users: a new key asking for one gets HTTP 404 "This
 * model … is no longer available to new users" (the user's own device
 * showed exactly this — it was never a network problem). So no single
 * model id is ever trusted alone again: every call (key test included)
 * walks [CASCADE_ORDER] — LITE models first, because translation does
 * not need the big models and the lites are light on the user's free
 * quota; the Flash models are only the final rescue — stepping to the
 * next model whenever the current one answers a model-not-available
 * 404. The model that answered is remembered in [GeminiModelSession]
 * and tried first next time. A model the user pinned on the key screen
 * ([modelPreference]) is tried first, but the cascade still rescues
 * the call if the pinned model itself 404s.
 */
class GeminiProvider(
    private val transport: HttpTransport,
    health: ProviderHealth,
    private val keyProvider: suspend () -> String?,
    private val isConfigured: () -> Boolean,
    nowMs: () -> Long = { System.currentTimeMillis() },
    delayFn: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val usageRecorder: suspend (chars: Int) -> Unit = {},
    private val modelPreference: suspend () -> String? = { null },
    private val session: GeminiModelSession = GeminiModelSession()
) : SingleEndpointProvider(health, nowMs, delayFn), KeyTestable {

    override val id: ProviderId = ProviderId.GEMINI
    override val displayName: String = "Gemini AI"
    override val kind: Provider.Kind = Provider.Kind.KEY_AI

    private val mapping: LanguageMapping = ProviderLanguageMaps.GEMINI

    override fun supportsPair(source: String, target: String): Boolean =
        isConfigured() && mapping.supportsPair(source, target)

    override suspend fun supportedLanguages(): AppResult<List<Language>> =
        AppResult.Ok(Languages.ALL.filter { mapping.toProvider(it.code, isSource = false) != null })

    override suspend fun callOnce(req: TranslateRequest): AppResult<Translation> =
        callWithKey(req, keyProvider())

    override suspend fun testWithSecret(secret: String): AppResult<Unit> =
        callWithKey(
            TranslateRequest(text = "hello", source = "en", target = "es", quality = QualityMode.FAST),
            secret
        ).map { }

    override suspend fun testConnection(): AppResult<Unit> =
        testWithSecret(keyProvider().orEmpty())

    /**
     * One logical call = walk the model cascade until a model answers
     * (or a non-model error — bad key, quota, server down — stops the
     * walk: switching models cannot fix those, so they return at once).
     */
    private suspend fun callWithKey(req: TranslateRequest, key: String?): AppResult<Translation> {
        if (key.isNullOrBlank()) return AppResult.Err(AppError.AuthFailed(id.name))
        if (!mapping.supportsPair(req.source, req.target)) {
            return AppResult.Err(AppError.UnsupportedLanguagePair(req.source, req.target))
        }
        var lastUnavailable: AppError? = null
        // v1.1.3: a per-call model hint (Improve's Strong strength)
        // heads the cascade like a pin — the cascade still rescues
        // the call if the hinted model itself is unavailable.
        val pinned = req.modelHint?.takeIf { it in CASCADE_ORDER }
            ?: modelPreference()
        for (model in cascadeOrder(pinned)) {
            when (val attempt = callWithModel(req, key, model)) {
                is ModelAttempt.Success -> {
                    session.workingModel = model
                    return AppResult.Ok(attempt.translation)
                }
                is ModelAttempt.Unavailable -> {
                    lastUnavailable = attempt.error
                    continue
                }
                is ModelAttempt.Failed -> return AppResult.Err(attempt.error)
            }
        }
        // Every model in the set says it is unavailable to this key.
        return AppResult.Err(lastUnavailable ?: AppError.ProviderDown(id.name))
    }

    /** One model attempt's outcome — the cascade's private vocabulary. */
    private sealed interface ModelAttempt {
        data class Success(val translation: Translation) : ModelAttempt
        data class Failed(val error: AppError) : ModelAttempt

        /** Failed ONLY because this model is unavailable to this key — step on. */
        data class Unavailable(val error: AppError) : ModelAttempt
    }

    /**
     * The cascade order for one call: the user's pinned model first,
     * else the session's remembered working model, then the lite-first
     * [CASCADE_ORDER] with duplicates removed.
     */
    internal fun cascadeOrder(manual: String?): List<String> {
        val head = manual?.takeIf { it in CASCADE_ORDER } ?: session.workingModel
        return listOfNotNull(head) + CASCADE_ORDER.filter { it != head }
    }

    /** A single HTTP attempt against a single [model]. */
    private suspend fun callWithModel(
        req: TranslateRequest,
        key: String,
        model: String
    ): ModelAttempt {
        val url = "$BASE_URL/v1beta/models/$model:generateContent"
        val response = when (
            val r = transport.post(url, requestBody(req), mapOf("x-goog-api-key" to key))
        ) {
            is AppResult.Err -> return ModelAttempt.Failed(r.error)
            is AppResult.Ok -> r.value
        }
        if (!response.isSuccessful) {
            val error = geminiError(response)
            return if (isModelUnavailable(response)) {
                ModelAttempt.Unavailable(error)
            } else {
                ModelAttempt.Failed(error)
            }
        }

        val parsed = try {
            ProviderJson.decodeFromString<GeminiResponse>(response.body)
        } catch (t: Throwable) {
            // A 200 we cannot parse is the AI answering garbage (§10 #11).
            return ModelAttempt.Failed(AppError.AiBadOutput(id.name))
        }
        if (parsed.promptFeedback?.blockReason != null) {
            return ModelAttempt.Failed(AppError.AiBadOutput(id.name))
        }
        // v1.1.3 (§A4): a MAX_TOKENS finish means the reply was CUT
        // OFF — feeding truncated text onward silently corrupted
        // polish verification (paragraph drift → quiet rejection).
        // It is a distinct failure now, never a "successful" answer.
        if (parsed.candidates?.firstOrNull()?.finishReason == "MAX_TOKENS") {
            return ModelAttempt.Failed(AppError.AiBadOutput(id.name))
        }
        val text = parsed.candidates?.firstOrNull()?.content?.parts
            ?.mapNotNull { it.text }?.joinToString("")?.trim()
        if (text.isNullOrBlank()) {
            // No candidate / safety stop / empty generation (§10 #11).
            return ModelAttempt.Failed(AppError.AiBadOutput(id.name))
        }
        usageRecorder(req.text.length)
        return ModelAttempt.Success(Translation(text = text, providerId = id))
    }

    /**
     * Google's model-retirement shape: HTTP 404 whose message says the
     * model is "no longer available", or a NOT_FOUND that names the
     * model. A 404 about anything else is NOT a cascade trigger.
     */
    private fun isModelUnavailable(response: HttpResponse): Boolean {
        if (response.code != 404) return false
        val body = response.body.lowercase()
        return body.contains("no longer available") ||
            (body.contains("not found") && body.contains("model"))
    }

    /** The request body (§5.4: low temperature; output-only contract). */
    private fun requestBody(req: TranslateRequest): String {
        val system = req.context?.systemPrompt ?: defaultInstruction(req)
        return buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", system) }) }
            }
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { add(buildJsonObject { put("text", req.text) }) }
                })
            }
            putJsonObject("generationConfig") {
                put("temperature", TEMPERATURE)
                // v1.1.3 (§A4): an explicit output bound. Without one,
                // long polish replies came back silently truncated
                // (see the finishReason check in callWithModel).
                put("maxOutputTokens", MAX_OUTPUT_TOKENS)
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

    /** Gemini's error envelope, mapped onto the §10 catalogue. */
    private fun geminiError(response: HttpResponse): AppError {
        val errDetail = try {
            ProviderJson.decodeFromString<GeminiErrorBody>(response.body).error
        } catch (t: Throwable) {
            null
        }
        val reasons = errDetail?.details.orEmpty().mapNotNull { it.reason }
        // v1.0.1 diagnostics: "HTTP 400: API key not valid. Please pass a
        // valid API key." for the UI's "Details:" line. Google's error
        // envelope never echoes the caller's key, so this stays safe.
        val httpDetail = buildString {
            append("HTTP ${response.code}")
            val message = errDetail?.message?.trim().orEmpty()
            if (message.isNotEmpty()) append(": ").append(message.take(200))
        }
        return when {
            response.code == 429 || errDetail?.status == "RESOURCE_EXHAUSTED" ->
                AppError.RateLimited(id.name, retryDelaySeconds(response, errDetail))
            response.code == 401 || response.code == 403 ||
                "API_KEY_INVALID" in reasons ||
                errDetail?.message?.contains("API key not valid", ignoreCase = true) == true ->
                AppError.AuthFailed(id.name, httpDetail)
            else -> when (val mapped = HttpErrors.forResponse(id.name, response)) {
                is AppError.ProviderDown -> mapped.copy(detail = httpDetail)
                null -> AppError.ProviderDown(id.name, response.code, detail = httpDetail)
                else -> mapped
            }
        }
    }

    /** Retry-After header first, then the RetryInfo detail ("57s" / "57.5s"). */
    private fun retryDelaySeconds(response: HttpResponse, detail: GeminiErrorDetail?): Long? {
        HttpErrors.parseRetryAfterSeconds(response)?.let { return it }
        val raw = detail?.details
            ?.firstOrNull { it.type?.endsWith("RetryInfo") == true }
            ?.retryDelay
            ?: return null
        return raw.removeSuffix("s").toDoubleOrNull()?.toLong()
    }

    companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com"

        // The current model set (verified Oct 2026): every 2.5 model is
        // restricted to past users, and gemini-3.5-flash is itself
        // deprecated (it silently runs on 3.6) — neither is used here.
        const val MODEL_LITE_35 = "gemini-3.5-flash-lite"
        const val MODEL_LITE_31 = "gemini-3.1-flash-lite"
        const val MODEL_FLASH_38 = "gemini-3.8-flash"
        const val MODEL_FLASH_36 = "gemini-3.6-flash"

        /** The default starting model: the lightest current lite. */
        const val MODEL_FAST = MODEL_LITE_35

        /** The strongest model in the set — a cascade rescue / manual pin, never the default. */
        const val MODEL_STANDARD = MODEL_FLASH_38

        const val TEMPERATURE = 0.3

        /** v1.1.3 (§A4): explicit per-call output ceiling (tokens). */
        const val MAX_OUTPUT_TOKENS = 32_768

        /**
         * LITE-FIRST for ALL Gemini work (quick translate, chapters,
         * polish, study): the lites do the translating — they are light
         * on the free quota — and the Flash models act only as the
         * final rescue if both lites are unavailable.
         */
        val CASCADE_ORDER: List<String> = listOf(
            MODEL_LITE_35, MODEL_LITE_31, MODEL_FLASH_38, MODEL_FLASH_36
        )

        /** The key screen's picker rows, in cascade order. */
        val MODEL_OPTIONS: List<GeminiModelOption> = listOf(
            GeminiModelOption(
                MODEL_LITE_35,
                "Gemini 3.5 Flash-Lite",
                "Does the translating — light on your free quota",
                isLite = true
            ),
            GeminiModelOption(
                MODEL_LITE_31,
                "Gemini 3.1 Flash-Lite",
                "Does the translating — light on your free quota",
                isLite = true
            ),
            GeminiModelOption(
                MODEL_FLASH_38,
                "Gemini 3.8 Flash",
                "Stronger — uses more of your quota",
                isLite = false
            ),
            GeminiModelOption(
                MODEL_FLASH_36,
                "Gemini 3.6 Flash",
                "Stronger — uses more of your quota",
                isLite = false
            )
        )

        /**
         * The model a call STARTS from under Automatic with a cold
         * session: always the lightest lite, whatever the quality —
         * translation does not need the big models.
         */
        fun modelFor(quality: QualityMode): String = MODEL_FAST
    }
}

@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null,
    val promptFeedback: GeminiPromptFeedback? = null
)

@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null,
    val finishReason: String? = null
)

@Serializable
data class GeminiContent(
    val parts: List<GeminiPart>? = null,
    val role: String? = null
)

@Serializable
data class GeminiPart(val text: String? = null)

@Serializable
data class GeminiPromptFeedback(val blockReason: String? = null)

@Serializable
data class GeminiErrorBody(val error: GeminiErrorDetail? = null)

@Serializable
data class GeminiErrorDetail(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null,
    val details: List<GeminiErrorInfo>? = null
)

@Serializable
data class GeminiErrorInfo(
    @SerialName("@type") val type: String? = null,
    val reason: String? = null,
    val retryDelay: String? = null
)
