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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Azure Translator — key-based classic provider (PLAN.md §4.3; the F0
 * free tier is 2M chars/month). The vault secret is the plan's
 * `key|region` shape — Azure needs BOTH on every call:
 *
 *   POST https://api.cognitive.microsofttranslator.com/translate
 *        ?api-version=3.0&from=ko&to=en      (from omitted = auto-detect)
 *   headers  Ocp-Apim-Subscription-Key: <key>
 *            Ocp-Apim-Subscription-Region: <region>
 *   body     [{"Text":"…"}]
 *   200  →   [{"detectedLanguage":{"language":"ko","score":1.0},
 *              "translations":[{"text":"…","to":"en"}]}]
 *   error →  {"error":{"code":401001,"message":"…"}}
 *
 * Error semantics follow Microsoft's own v3 reference: 401 = the key
 * couldn't be authenticated (§10 #4 → AuthFailed); 403 "often indicates
 * that all free translations provided with a trial subscription are
 * used" — a quota signal, so a quota-worded 403 maps to RateLimited and
 * only a non-quota 403 is treated as an auth failure.
 *
 * Live verification note (P2): the endpoint did not answer at all from
 * this sandbox (connection timeouts through the egress proxy), so the
 * shapes above are from Microsoft's documentation and the fixtures in
 * AzureProviderTest — the success/auth paths are labelled UNVERIFIED
 * live in the P2 report.
 */
class AzureProvider(
    private val transport: HttpTransport,
    health: ProviderHealth,
    private val secretProvider: suspend () -> String?,
    private val isConfigured: () -> Boolean,
    nowMs: () -> Long = { System.currentTimeMillis() },
    delayFn: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val usageRecorder: suspend (chars: Int) -> Unit = {}
) : SingleEndpointProvider(health, nowMs, delayFn), KeyTestable {

    override val id: ProviderId = ProviderId.AZURE
    override val displayName: String = "Azure Translator"
    override val kind: Provider.Kind = Provider.Kind.KEY_CLASSIC

    private val mapping: LanguageMapping = ProviderLanguageMaps.AZURE

    override fun supportsPair(source: String, target: String): Boolean =
        isConfigured() && mapping.supportsPair(source, target)

    override suspend fun supportedLanguages(): AppResult<List<Language>> =
        AppResult.Ok(Languages.ALL.filter { mapping.toProvider(it.code, isSource = false) != null })

    override suspend fun callOnce(req: TranslateRequest): AppResult<Translation> =
        callWithSecret(req, secretProvider())

    override suspend fun testWithSecret(secret: String): AppResult<Unit> =
        callWithSecret(TranslateRequest(text = "hello", source = "en", target = "es"), secret)
            .map { }

    override suspend fun testConnection(): AppResult<Unit> =
        testWithSecret(secretProvider().orEmpty())

    private suspend fun callWithSecret(
        req: TranslateRequest,
        secret: String?
    ): AppResult<Translation> {
        val parsed = parseSecret(secret)
            ?: return AppResult.Err(AppError.AuthFailed(id.name))
        val tgt = mapping.toProvider(req.target, isSource = false)
            ?: return AppResult.Err(AppError.UnsupportedLanguagePair(req.source, req.target))
        val src = if (req.source == LANG_AUTO) {
            null
        } else {
            mapping.toProvider(req.source, isSource = true)
                ?: return AppResult.Err(AppError.UnsupportedLanguagePair(req.source, req.target))
        }
        val base = "$ENDPOINT/translate".toHttpUrlOrNull()
            ?: return AppResult.Err(AppError.ProviderDown(id.name, retryable = false))
        val urlBuilder = base.newBuilder()
            .addQueryParameter("api-version", "3.0")
            .addQueryParameter("to", tgt)
        if (src != null) urlBuilder.addQueryParameter("from", src)
        val body = buildJsonArray {
            add(buildJsonObject { put("Text", req.text) })
        }.toString()
        val response = when (
            val r = transport.post(
                urlBuilder.build().toString(),
                body,
                mapOf(
                    "Ocp-Apim-Subscription-Key" to parsed.key,
                    "Ocp-Apim-Subscription-Region" to parsed.region
                )
            )
        ) {
            is AppResult.Err -> return r
            is AppResult.Ok -> r.value
        }
        if (!response.isSuccessful) return AppResult.Err(azureError(response))
        val parsedBody = try {
            ProviderJson.decodeFromString<List<AzureTranslateItem>>(response.body)
        } catch (t: Throwable) {
            return AppResult.Err(AppError.ProviderDown(id.name, response.code, retryable = false))
        }
        val item = parsedBody.firstOrNull()
        val text = item?.translations?.firstOrNull()?.text
        if (text.isNullOrBlank()) {
            return AppResult.Err(AppError.ProviderDown(id.name, response.code, retryable = false))
        }
        usageRecorder(req.text.length)
        return AppResult.Ok(
            Translation(
                text = text,
                providerId = id,
                detectedSource = item.detectedLanguage?.language?.let { mapping.fromProvider(it) }
            )
        )
    }

    private fun azureError(response: HttpResponse): AppError {
        val message = try {
            ProviderJson.decodeFromString<AzureErrorBody>(response.body).error?.message
        } catch (t: Throwable) {
            null
        }
        return when {
            response.code == 403 &&
                (message?.contains("quota", ignoreCase = true) == true ||
                    message?.contains("free translations", ignoreCase = true) == true) ->
                AppError.RateLimited(id.name, null)
            else -> HttpErrors.forResponse(id.name, response)
                ?: AppError.ProviderDown(id.name, response.code)
        }
    }

    /** The parsed vault secret: an Azure call needs both halves (§4.3). */
    data class Secret(val key: String, val region: String)

    companion object {
        const val ENDPOINT = "https://api.cognitive.microsofttranslator.com"

        /** The vault shape (§4.3): `key|region`; both halves required. */
        fun parseSecret(secret: String?): Secret? {
            val raw = secret?.trim().orEmpty()
            val sep = raw.indexOf('|')
            if (sep <= 0) return null
            val key = raw.substring(0, sep).trim()
            val region = raw.substring(sep + 1).trim()
            return if (key.isNotEmpty() && region.isNotEmpty()) Secret(key, region) else null
        }

        fun assembleSecret(key: String, region: String): String =
            "${key.trim()}|${region.trim()}"
    }
}

@Serializable
data class AzureTranslateItem(
    val detectedLanguage: AzureDetectedLanguage? = null,
    val translations: List<AzureTranslation>? = null
)

@Serializable
data class AzureDetectedLanguage(val language: String? = null, val score: Double? = null)

@Serializable
data class AzureTranslation(val text: String? = null, val to: String? = null)

@Serializable
data class AzureErrorBody(val error: AzureErrorDetail? = null)

@Serializable
data class AzureErrorDetail(val code: Int? = null, val message: String? = null)
