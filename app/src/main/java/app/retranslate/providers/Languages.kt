package app.retranslate.providers

/**
 * The app's canonical language list (PLAN.md §4.6). Internal codes are
 * Google-style BCP-47 (`zh-CN`, never bare `zh` — Mozhi's Google engine
 * rejects bare `zh`, verified live in P1). Provider-specific code mapping
 * lives in [ProviderLanguageMaps]; the pickers show this list (names, not
 * flags alone, §9.3).
 */
object Languages {

    val AUTO = Language(LANG_AUTO, "Auto detect")

    val ALL: List<Language> = listOf(
        "af" to "Afrikaans", "sq" to "Albanian", "am" to "Amharic",
        "ar" to "Arabic", "hy" to "Armenian", "az" to "Azerbaijani",
        "eu" to "Basque", "be" to "Belarusian", "bn" to "Bengali",
        "bs" to "Bosnian", "bg" to "Bulgarian", "ca" to "Catalan",
        "ceb" to "Cebuano", "ny" to "Chichewa", "zh-CN" to "Chinese (Simplified)",
        "zh-TW" to "Chinese (Traditional)", "co" to "Corsican", "hr" to "Croatian",
        "cs" to "Czech", "da" to "Danish", "nl" to "Dutch",
        "en" to "English", "eo" to "Esperanto", "et" to "Estonian",
        "tl" to "Filipino", "fi" to "Finnish", "fr" to "French",
        "fy" to "Frisian", "gl" to "Galician", "ka" to "Georgian",
        "de" to "German", "el" to "Greek", "gu" to "Gujarati",
        "ht" to "Haitian Creole", "ha" to "Hausa", "haw" to "Hawaiian",
        "he" to "Hebrew", "hi" to "Hindi", "hmn" to "Hmong",
        "hu" to "Hungarian", "is" to "Icelandic", "ig" to "Igbo",
        "id" to "Indonesian", "ga" to "Irish", "it" to "Italian",
        "ja" to "Japanese", "jw" to "Javanese", "kn" to "Kannada",
        "kk" to "Kazakh", "km" to "Khmer", "ko" to "Korean",
        "ku" to "Kurdish", "ky" to "Kyrgyz", "lo" to "Lao",
        "la" to "Latin", "lv" to "Latvian", "lt" to "Lithuanian",
        "lb" to "Luxembourgish", "mk" to "Macedonian", "mg" to "Malagasy",
        "ms" to "Malay", "ml" to "Malayalam", "mt" to "Maltese",
        "mi" to "Maori", "mr" to "Marathi", "mn" to "Mongolian",
        "my" to "Myanmar (Burmese)", "ne" to "Nepali", "no" to "Norwegian",
        "ps" to "Pashto", "fa" to "Persian", "pl" to "Polish",
        "pt" to "Portuguese", "pa" to "Punjabi", "ro" to "Romanian",
        "ru" to "Russian", "sm" to "Samoan", "gd" to "Scots Gaelic",
        "sr" to "Serbian", "st" to "Sesotho", "sn" to "Shona",
        "sd" to "Sindhi", "si" to "Sinhala", "sk" to "Slovak",
        "sl" to "Slovenian", "so" to "Somali", "es" to "Spanish",
        "su" to "Sundanese", "sw" to "Swahili", "sv" to "Swedish",
        "tg" to "Tajik", "ta" to "Tamil", "te" to "Telugu",
        "th" to "Thai", "tr" to "Turkish", "uk" to "Ukrainian",
        "ur" to "Urdu", "uz" to "Uzbek", "vi" to "Vietnamese",
        "cy" to "Welsh", "xh" to "Xhosa", "yi" to "Yiddish",
        "yo" to "Yoruba", "zu" to "Zulu"
    ).map { (code, name) -> Language(code, name) }

    private val byCode: Map<LangCode, Language> = ALL.associateBy { it.code }

    fun isKnown(code: LangCode): Boolean = code in byCode

    fun nameOf(code: LangCode): String =
        if (code == LANG_AUTO) AUTO.name else byCode[code]?.name ?: code
}

/**
 * App code ↔ provider code mapping for one provider (PLAN.md §4.6: the
 * mapping lives next to its provider and is unit-tested).
 *
 * Rules encoded here, all verified against the live APIs in P1:
 *  - `auto` may appear as a SOURCE only, and only where the provider
 *    supports detection (MyMemory does not — it needs a concrete pair).
 *  - `auto` is never a valid target.
 *  - Unknown app codes map to null (= pair unsupported) so the router's
 *    pre-filter can skip the provider instead of erroring at call time.
 */
class LanguageMapping(
    private val renames: Map<LangCode, LangCode> = emptyMap(),
    private val unsupported: Set<LangCode> = emptySet(),
    private val supportsAutoSource: Boolean = true,
    /**
     * When non-null, ONLY these app codes are supported (the keyed
     * classics serve a subset of the canonical list — §4.3/P2). Renames
     * still apply on top; [unsupported] still subtracts.
     */
    private val supportedOnly: Set<LangCode>? = null
) {
    /** App code → the code to send this provider, or null if unsupported. */
    fun toProvider(appCode: LangCode, isSource: Boolean): LangCode? {
        if (appCode == LANG_AUTO) {
            return if (isSource && supportsAutoSource) LANG_AUTO else null
        }
        if (supportedOnly != null && appCode !in supportedOnly) return null
        if (appCode in unsupported) return null
        if (!Languages.isKnown(appCode)) return null
        return renames[appCode] ?: appCode
    }

    /** Provider code → app code (detection results), or null if unknown. */
    fun fromProvider(providerCode: String): LangCode? {
        val code = providerCode.trim()
        if (code.isEmpty()) return null
        val reversed = renames.entries.firstOrNull { it.value == code }?.key
        if (reversed != null) return reversed
        return if (Languages.isKnown(code)) code else null
    }

    fun supportsPair(source: LangCode, target: LangCode): Boolean =
        toProvider(source, isSource = true) != null &&
            toProvider(target, isSource = false) != null
}

/** The per-provider mappings for the v1 hub. P2's keyed providers add theirs here. */
object ProviderLanguageMaps {

    /** Mozhi's Google engine speaks Google codes verbatim (zh-CN verified live). */
    val MOZHI = LanguageMapping()

    /** Lingva speaks Google codes verbatim; its list endpoint agrees (verified live). */
    val LINGVA = LanguageMapping()

    /** SimplyTranslate's Google engine: Google codes verbatim. */
    val SIMPLYTRANSLATE = LanguageMapping()

    /** MyMemory needs a concrete source language — no auto-detect pair exists. */
    val MYMEMORY = LanguageMapping(supportsAutoSource = false)

    /** Gemini is an LLM: every canonical language, auto-detect included. */
    val GEMINI = LanguageMapping()

    /** OpenAI (chat completions) is an LLM: every canonical language. (P7) */
    val OPENAI = LanguageMapping()

    /** Claude (Messages API) is an LLM: every canonical language. (P7) */
    val CLAUDE = LanguageMapping()

    /**
     * Ollama serves whatever model the user's PC runs — capabilities
     * vary by model, so the mapping claims the canonical list and a
     * weak model fails honestly at call time (§4.3 dormant entry, P7).
     */
    val OLLAMA = LanguageMapping()

    /**
     * DeepL's API language set (developers.deepl.com, stable v2 list).
     * Codes are upper-cased ISO at the provider; `zh-CN`/`zh-TW` both send
     * as `ZH` (the long-stable value), `no` sends as `NB`, and the target
     * `en` is rewritten to `EN-US` inside the provider (target variants
     * are direction-specific). Source may be omitted → auto-detect.
     */
    val DEEPL = LanguageMapping(
        renames = mapOf("zh-CN" to "ZH", "zh-TW" to "ZH", "no" to "NB"),
        supportedOnly = setOf(
            "ar", "bg", "cs", "da", "de", "el", "en", "es", "et", "fi",
            "fr", "hu", "id", "it", "ja", "ko", "lt", "lv", "no", "nl",
            "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk",
            "zh-CN", "zh-TW"
        )
    )

    /**
     * Azure Translator's long-established set (Microsoft v3 reference),
     * deliberately conservative: a few recently-added Azure languages
     * are omitted rather than risk a false "supported" claim — the free
     * chain still covers those pairs. Renames are Azure's BCP-47
     * spellings (`zh-Hans`, `fil`, `nb`, `sr-Cyrl`, `jv`).
     */
    val AZURE = LanguageMapping(
        renames = mapOf(
            "zh-CN" to "zh-Hans", "zh-TW" to "zh-Hant", "tl" to "fil",
            "no" to "nb", "sr" to "sr-Cyrl", "jw" to "jv"
        ),
        supportedOnly = setOf(
            "af", "am", "ar", "az", "bg", "bn", "bs", "ca", "cs", "cy",
            "da", "de", "el", "en", "es", "et", "eu", "fa", "fi", "tl",
            "fr", "ga", "gu", "he", "hi", "hr", "ht", "ha", "hmn", "hu",
            "hy", "id", "ig", "is", "it", "ja", "jw", "kk", "km", "kn",
            "ko", "ku", "ky", "lo", "lt", "lv", "mg", "mi", "mk", "ml",
            "mn", "mr", "ms", "mt", "my", "no", "ne", "nl", "pa", "pl",
            "ps", "pt", "ro", "ru", "sd", "si", "sk", "sl", "so", "sq",
            "sr", "sv", "sw", "ta", "te", "tg", "th", "tr", "uk", "ur",
            "uz", "vi", "zh-CN", "zh-TW"
        )
    )

    /**
     * LibreTranslate's engine set (~35 languages; `zh` simplified,
     * `zt` traditional). Codes verified against the project's /languages
     * list shape; source "auto" is supported by the API.
     */
    val LIBRETRANSLATE = LanguageMapping(
        renames = mapOf("zh-CN" to "zh", "zh-TW" to "zt"),
        supportedOnly = setOf(
            "ar", "az", "ca", "cs", "da", "de", "el", "en", "eo", "es",
            "fa", "fi", "fr", "ga", "he", "hi", "hu", "id", "it", "ja",
            "ko", "nl", "pl", "pt", "ro", "ru", "sk", "sv", "tr", "uk",
            "vi", "zh-CN", "zh-TW"
        )
    )

    fun forId(id: ProviderId): LanguageMapping? = when (id) {
        ProviderId.MOZHI -> MOZHI
        ProviderId.LINGVA -> LINGVA
        ProviderId.SIMPLYTRANSLATE -> SIMPLYTRANSLATE
        ProviderId.MYMEMORY -> MYMEMORY
        ProviderId.GEMINI -> GEMINI
        ProviderId.OPENAI -> OPENAI
        ProviderId.CLAUDE -> CLAUDE
        ProviderId.OLLAMA -> OLLAMA
        ProviderId.DEEPL -> DEEPL
        ProviderId.AZURE -> AZURE
        ProviderId.LIBRETRANSLATE -> LIBRETRANSLATE
        else -> null
    }
}
