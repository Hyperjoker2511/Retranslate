package app.retranslate.providers

import app.retranslate.core.error.AppResult

/** Internal language code: BCP-47-ish (`en`, `ko`, `zh-CN`, `zh-TW`, `ja`…). */
typealias LangCode = String

/** Special source-only code: let the provider detect the language. */
const val LANG_AUTO: LangCode = "auto"

/** A language as the UI shows it: internal [code] + plain display [name]. */
data class Language(val code: LangCode, val name: String)

enum class ProviderId {
    MOZHI, LINGVA, SIMPLYTRANSLATE, MYMEMORY, LIBRETRANSLATE, GEMINI, DEEPL, AZURE,
    OPENAI, CLAUDE, OLLAMA,
    /** v1.1.3 (§J): APInex — a named third-party OpenAI-compatible gateway. */
    APINEX,
    /** v1.1.3 (§H): Google ML Kit on-device translation ("Offline"). */
    OFFLINE,
    /**
     * v1.1.3 (§K): a user-added gateway (paste-a-site). All added
     * gateways share this id — a gateway's identity is its stored
     * config slot, not the enum (see GatewaySlotProvider).
     */
    CUSTOM
}

enum class QualityMode { FAST, BALANCED, BEST }

/** Context only AI providers consume (Knowledge Base block etc., §5/§6). */
data class AiContext(val systemPrompt: String)

data class TranslateRequest(
    val text: String,
    val source: LangCode,
    val target: LangCode,
    val context: AiContext? = null,
    val quality: QualityMode = QualityMode.BALANCED,
    /**
     * v1.1.3: a per-call model pin for providers that know the id
     * (Improve's Strong strength pins Gemini's standard tier this
     * way). Providers ignore ids they don't recognise.
     */
    val modelHint: String? = null,
    /**
     * Monotonic deadline (ms, same clock as `System.currentTimeMillis` is
     * NOT used — providers compare against the router-supplied value via
     * their injected clock). Attempts stop starting once it passes; this
     * is what makes the §4.5 whole-chain cap real.
     */
    val deadlineMs: Long = Long.MAX_VALUE
)

data class Translation(
    val text: String,
    val providerId: ProviderId,
    /** Source language the provider detected (only meaningful for `auto`). */
    val detectedSource: LangCode? = null,
    /** Instance host that answered, for multi-instance providers. */
    val instance: String? = null
)

/**
 * The one provider interface (PLAN.md §4.1). Every implementation returns
 * [AppResult] — nothing throws across this boundary.
 */
interface Provider {
    val id: ProviderId

    /** Plain-words name for the "via X" chip and switch snackbar, e.g. "Mozhi". */
    val displayName: String

    val kind: Kind

    enum class Kind { FREE_WEB, KEY_CLASSIC, KEY_AI, LOCAL }

    /** Can this provider translate [source] → [target] at all? (§4.6 pre-filter) */
    fun supportsPair(source: LangCode, target: LangCode): Boolean

    suspend fun supportedLanguages(): AppResult<List<Language>>

    suspend fun translate(req: TranslateRequest): AppResult<Translation>

    suspend fun testConnection(): AppResult<Unit>
}
