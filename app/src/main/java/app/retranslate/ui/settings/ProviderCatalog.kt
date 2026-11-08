package app.retranslate.ui.settings

import app.retranslate.providers.ProviderId

/** What the key-entry screen needs to know about one keyed provider. */
data class KeyedProviderInfo(
    val id: ProviderId,
    val displayName: String,
    /** One plain line: what the key costs / what it unlocks. */
    val costText: String,
    /** Where the user gets a key — opened in the browser from the entry screen. */
    val getKeyUrl: String,
    /** Field label for the secret input. */
    val secretLabel: String,
    /** True for Azure: the vault secret is `key|region`, entered as two fields. */
    val needsRegion: Boolean = false,
    /** Free-tier allowance shown next to the local usage counter (§5.5). */
    val freeLimitNote: String? = null
)

/**
 * The keyed providers (§4.3), in display order. OpenAI/Claude joined
 * with their adapters in P7 (§11) — before that, listing them would
 * have promised a Test button that cannot work yet.
 */
object ProviderCatalog {

    val GEMINI = KeyedProviderInfo(
        id = ProviderId.GEMINI,
        displayName = "Gemini AI",
        costText = "Free key — recommended. Unlocks book translation with " +
            "consistent names, and becomes your main engine.",
        getKeyUrl = "https://aistudio.google.com/apikey",
        secretLabel = "Gemini API key",
        freeLimitNote = "Free key with a generous daily limit"
    )

    val DEEPL = KeyedProviderInfo(
        id = ProviderId.DEEPL,
        displayName = "DeepL",
        costText = "Free key: 500,000 characters a month. Excellent for " +
            "short texts and as a backup engine.",
        getKeyUrl = "https://www.deepl.com/pro-api",
        secretLabel = "DeepL API key",
        freeLimitNote = "Free key: 500,000 characters/month"
    )

    val AZURE = KeyedProviderInfo(
        id = ProviderId.AZURE,
        displayName = "Azure Translator",
        costText = "Free F0 tier: 2,000,000 characters a month. Needs the " +
            "key AND the region from your Azure resource.",
        getKeyUrl = "https://azure.microsoft.com/en-us/products/ai-services/translation",
        secretLabel = "Azure key",
        needsRegion = true,
        freeLimitNote = "Free tier: 2,000,000 characters/month"
    )

    val OPENAI = KeyedProviderInfo(
        id = ProviderId.OPENAI,
        displayName = "OpenAI",
        costText = "Paid key — you pay per use. A strong second opinion: " +
            "it can translate chapters and judge AI merges.",
        getKeyUrl = "https://platform.openai.com/api-keys",
        secretLabel = "OpenAI API key"
    )

    val CLAUDE = KeyedProviderInfo(
        id = ProviderId.CLAUDE,
        displayName = "Claude",
        costText = "Paid key — you pay per use. Careful, natural English: " +
            "it can translate chapters and judge AI merges.",
        getKeyUrl = "https://console.anthropic.com/settings/keys",
        secretLabel = "Claude API key"
    )

    val APINEX = KeyedProviderInfo(
        id = ProviderId.APINEX,
        displayName = "APInex",
        costText = "A separate gateway service (not Google/OpenAI): their " +
            "free models currently come with a large promotional daily " +
            "allowance — promotions can change or end, and your text " +
            "passes through their servers.",
        getKeyUrl = "https://apinex.bond",
        secretLabel = "APInex key (starts with sk-apx)",
        freeLimitNote = "Promotional free-model allowance — see the card"
    )

    val LIBRETRANSLATE = KeyedProviderInfo(
        id = ProviderId.LIBRETRANSLATE,
        displayName = "LibreTranslate",
        costText = "Only for the public libretranslate.com site — your own " +
            "instance (set one up under Edit instances) needs no key.",
        getKeyUrl = "https://portal.libretranslate.com",
        secretLabel = "LibreTranslate API key"
    )

    val KEYED: List<KeyedProviderInfo> =
        listOf(GEMINI, DEEPL, AZURE, OPENAI, CLAUDE, APINEX, LIBRETRANSLATE)

    fun keyed(id: ProviderId): KeyedProviderInfo? = KEYED.firstOrNull { it.id == id }
}
