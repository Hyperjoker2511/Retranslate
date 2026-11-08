package app.retranslate.providers.local

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The user's Ollama setup: PC base URL + model name (§4.3). Neither is a secret. */
data class OllamaConfig(val baseUrl: String, val model: String)

/** Where the Ollama setup lives (ProviderConfigStore in production). */
interface OllamaConfigSource {
    suspend fun ollamaConfig(): OllamaConfig?
}

/**
 * Ollama — the DORMANT local entry (PLAN.md §4.3, P7). Ollama does not
 * run on the phone: it serves from the user's own computer on the same
 * Wi-Fi. Until the user saves a PC address + model in the setup screen
 * this provider is inert — [supportsPair] is false, so no chain ever
 * calls it — and the Providers screen shows the row greyed with the
 * explanation. Once configured it joins the chains LAST (tier 3,
 * §4.4.1): a home PC is a bonus engine, never a dependency.
 *
 *   POST {base}/api/chat
 *   body    {"model":"…","stream":false,
 *            "options":{"temperature":0.3},
 *            "messages":[{"role":"system","content":…},
 *                        {"role":"user","content":…}]}
 *   200  →  {"message":{"role":"assistant","content":"…"},"done":true}
 *   error → {"error":"…"} (404 = unknown model on that PC)
 *
 * Cleartext note (honest, §13): a LAN address is plain HTTP, which
 * Android's network security policy blocks by default. The setup
 * screen says so in words before saving; this provider simply makes
 * the call — whether the platform permits it for the user's host is
 * device configuration, verified on a real phone, not in this sandbox.
 */
class OllamaProvider(
    private val transport: HttpTransport,
    health: ProviderHealth,
    private val configSource: OllamaConfigSource,
    nowMs: () -> Long = { System.currentTimeMillis() },
    delayFn: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val usageRecorder: suspend (chars: Int) -> Unit = {}
) : SingleEndpointProvider(health, nowMs, delayFn) {

    override val id: ProviderId = ProviderId.OLLAMA
    override val displayName: String = "Ollama (your computer)"
    override val kind: Provider.Kind = Provider.Kind.LOCAL

    private val mapping: LanguageMapping = ProviderLanguageMaps.OLLAMA

    /** Synchronous view for the router's pre-filter: the last loaded config. */
    @Volatile
    private var cachedConfig: OllamaConfig? = null

    override fun supportsPair(source: String, target: String): Boolean =
        cachedConfig != null && mapping.supportsPair(source, target)

    override suspend fun supportedLanguages(): AppResult<List<Language>> =
        AppResult.Ok(Languages.ALL)

    /** Loads the saved setup into the sync cache the router reads. */
    suspend fun refreshConfig() {
        cachedConfig = configSource.ollamaConfig()
    }

    override suspend fun callOnce(req: TranslateRequest): AppResult<Translation> {
        refreshConfig()
        val config = cachedConfig
            ?: return AppResult.Err(AppError.ProviderDown(id.name, retryable = false))
        if (!mapping.supportsPair(req.source, req.target)) {
            return AppResult.Err(AppError.UnsupportedLanguagePair(req.source, req.target))
        }
        val response = when (
            val r = transport.post("${config.baseUrl}/api/chat", requestBody(req, config))
        ) {
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
            ProviderJson.decodeFromString<OllamaChatResponse>(response.body)
        } catch (t: Throwable) {
            return AppResult.Err(AppError.AiBadOutput(id.name))
        }
        val text = parsed.message?.content?.trim()
        if (text.isNullOrBlank()) {
            return AppResult.Err(AppError.AiBadOutput(id.name))
        }
        usageRecorder(req.text.length)
        return AppResult.Ok(Translation(text = text, providerId = id))
    }

    /** Reachability only: the PC's model list endpoint (§4.3 Test). */
    override suspend fun testConnection(): AppResult<Unit> {
        refreshConfig()
        val config = cachedConfig
            ?: return AppResult.Err(AppError.ProviderDown(id.name, retryable = false))
        val response = when (val r = transport.get("${config.baseUrl}/api/tags")) {
            is AppResult.Err -> return r
            is AppResult.Ok -> r.value
        }
        return if (response.isSuccessful) {
            AppResult.Ok(Unit)
        } else {
            AppResult.Err(
                HttpErrors.forResponse(id.name, response)
                    ?: AppError.ProviderDown(id.name, response.code)
            )
        }
    }

    private fun requestBody(req: TranslateRequest, config: OllamaConfig): String {
        val system = req.context?.systemPrompt ?: defaultInstruction(req)
        return buildJsonObject {
            put("model", config.model)
            put("stream", false)
            putJsonObject("options") { put("temperature", TEMPERATURE) }
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

    companion object {
        const val TEMPERATURE = 0.3

        /** The setup screen's shape check: an http(s) URL with a host. */
        fun isPlausibleBaseUrl(raw: String): Boolean {
            val value = raw.trim().trimEnd('/')
            if (!value.startsWith("http://") && !value.startsWith("https://")) return false
            val host = value.substringAfter("://").substringBefore('/').substringBefore(':')
            return host.isNotBlank()
        }

        /** Normal form stored and called: no trailing slash. */
        fun normalizeBaseUrl(raw: String): String = raw.trim().trimEnd('/')
    }
}

@Serializable
data class OllamaChatResponse(val message: OllamaMessage? = null, val done: Boolean? = null)

@Serializable
data class OllamaMessage(val role: String? = null, val content: String? = null)
