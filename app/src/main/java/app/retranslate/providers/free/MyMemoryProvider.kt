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
import app.retranslate.providers.hub.ProviderHealth
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Providers with a hard per-request text limit expose it via this. */
interface TextLengthLimited {
    val maxTextChars: Int
}

/**
 * MyMemory — short-text last resort (PLAN.md §4.2). Single endpoint:
 *   GET https://api.mymemory.translated.net/get?q=…&langpair=ko|en
 *   200 → {"responseData":{"translatedText":"…"},"responseStatus":"200"}
 *
 * Anonymous quota is ~1,000 words/day PER IP. The router consults the
 * daily budget before offering MyMemory (§4.2), and [usageRecorder]
 * charges each successful call's source words against it. Quota
 * exhaustion arrives either as HTTP 429 or as a 200 whose body carries
 * the quota warning in place of a translation (both verified in P1) —
 * both map to [AppError.RateLimited], and the warning text itself must
 * NEVER be returned as a translation.
 */
class MyMemoryProvider(
    private val transport: HttpTransport,
    health: ProviderHealth,
    nowMs: () -> Long = { System.currentTimeMillis() },
    delayFn: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val usageRecorder: suspend (words: Int) -> Unit = {}
) : SingleEndpointProvider(health, nowMs, delayFn), TextLengthLimited {

    override val id: ProviderId = ProviderId.MYMEMORY
    override val displayName: String = "MyMemory"
    override val kind: Provider.Kind = Provider.Kind.FREE_WEB

    override val maxTextChars: Int = MAX_TEXT_CHARS

    private val mapping: LanguageMapping = ProviderLanguageMaps.MYMEMORY

    override fun supportsPair(source: String, target: String): Boolean =
        mapping.supportsPair(source, target)

    override suspend fun supportedLanguages(): AppResult<List<Language>> =
        AppResult.Ok(Languages.ALL.filter { mapping.toProvider(it.code, isSource = false) != null })

    override suspend fun callOnce(req: TranslateRequest): AppResult<Translation> {
        val src = mapping.toProvider(req.source, isSource = true)
            ?: return AppResult.Err(AppError.UnsupportedLanguagePair(req.source, req.target))
        val tgt = mapping.toProvider(req.target, isSource = false)
            ?: return AppResult.Err(AppError.UnsupportedLanguagePair(req.source, req.target))
        val base = "$ENDPOINT/get".toHttpUrlOrNull()
            ?: return AppResult.Err(AppError.ProviderDown(id.name, retryable = false))
        val url = base.newBuilder()
            .addQueryParameter("q", req.text)
            .addQueryParameter("langpair", "$src|$tgt")
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
            ProviderJson.decodeFromString<MyMemoryResponse>(response.body)
        } catch (t: Throwable) {
            return AppResult.Err(AppError.ProviderDown(id.name, response.code, retryable = false))
        }
        val text = parsed.responseData?.translatedText
        val status = parsed.responseStatus?.toStatusString()
        val quotaHit = parsed.quotaFinished == true ||
            (text?.contains(QUOTA_MARKER, ignoreCase = true) == true) ||
            (parsed.responseDetails?.contains("QUOTA", ignoreCase = true) == true)
        if (quotaHit || status == "429") {
            return AppResult.Err(AppError.RateLimited(id.name, null))
        }
        if (status != null && status != "200") {
            val code = status.toIntOrNull()
            return AppResult.Err(
                AppError.ProviderDown(id.name, code, retryable = code == null || code >= 500)
            )
        }
        if (text.isNullOrBlank()) {
            return AppResult.Err(AppError.ProviderDown(id.name, response.code, retryable = false))
        }
        usageRecorder(req.text.trim().split(Regex("\\s+")).count { it.isNotEmpty() })
        return AppResult.Ok(Translation(text = text, providerId = id))
    }

    companion object {
        const val ENDPOINT = "https://api.mymemory.translated.net"

        /** The get?q= parameter rejects long input; chapters never come here. */
        const val MAX_TEXT_CHARS = 500

        /** Anonymous free budget the router plans against (§4.2). */
        const val DAILY_WORD_BUDGET = 1_000

        const val QUOTA_MARKER = "MYMEMORY WARNING"
    }
}

@Serializable
data class MyMemoryResponse(
    val responseData: MyMemoryResponseData? = null,
    /** Arrives as a JSON string ("200") on some responses, a number on others. */
    val responseStatus: JsonElement? = null,
    val responseDetails: String? = null,
    val quotaFinished: Boolean? = null
)

@Serializable
data class MyMemoryResponseData(val translatedText: String? = null)

private fun JsonElement.toStatusString(): String? =
    (this as? JsonPrimitive)?.contentOrNull?.trim()?.trim('"')
