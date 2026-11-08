package app.retranslate.providers.gateway

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
import app.retranslate.providers.hub.ProviderHealth
import app.retranslate.providers.key.ClaudeProvider
import app.retranslate.providers.key.OpenAiProvider

/**
 * One chain slot for a user-added gateway (v1.1.3 §K), following the
 * Ollama pattern: the slot sits in the provider lists from startup,
 * inert until its config exists, and resolves the CURRENT config per
 * call — a gateway added mid-session joins every feature without a
 * restart. All slots share ProviderId.CUSTOM (health memory is shared
 * too — one gateway resting cools the CUSTOM key; a documented v1.1.3
 * wart, accepted so gateways need no enum or schema change).
 *
 * Calls delegate to the parameterised OpenAI/Anthropic clients
 * (§J's generalisation), so a gateway gets the same §3.4 retries,
 * error mapping, finish checks and usage recording as a built-in.
 */
class GatewaySlotProvider(
    private val slot: String,
    private val configSource: GatewayConfigSource,
    private val transport: HttpTransport,
    private val health: ProviderHealth,
    /** The slot's key from the KeyVault (never retained here). */
    private val keyProvider: suspend (String) -> String?,
    /** Sync KeyVault presence for the router's pre-filter. */
    private val keyPresent: (String) -> Boolean,
    private val usageRecorder: suspend (chars: Int) -> Unit = {}
) : Provider {

    override val id: ProviderId = ProviderId.CUSTOM

    override val displayName: String
        get() = cachedConfig?.name ?: "Added provider"

    override val kind: Provider.Kind = Provider.Kind.KEY_AI

    @Volatile
    private var cachedConfig: GatewayConfig? = null

    /** Loads the slot's saved config into the sync cache. */
    /** True when this slot has a saved gateway AND its key — the
     * "configured" test the AI pickers and routers need (§K). */
    fun hasConfigAndKey(): Boolean =
        cachedConfig?.let { keyPresent(it.vaultEntry) } == true

    suspend fun refreshConfig() {
        cachedConfig = configSource.gatewayConfig(slot)
    }

    private fun mapping(config: GatewayConfig): LanguageMapping =
        if (config.protocolEnum() == GatewayProtocol.ANTHROPIC) {
            ProviderLanguageMaps.CLAUDE
        } else {
            ProviderLanguageMaps.OPENAI
        }

    override fun supportsPair(source: String, target: String): Boolean {
        val config = cachedConfig ?: return false
        if (!keyPresent(config.vaultEntry)) return false
        return mapping(config).supportsPair(source, target)
    }

    override suspend fun supportedLanguages(): AppResult<List<Language>> {
        val config = cachedConfig
            ?: return AppResult.Ok(Languages.ALL)
        val mapping = mapping(config)
        return AppResult.Ok(
            Languages.ALL.filter { mapping.toProvider(it.code, isSource = false) != null }
        )
    }

    override suspend fun translate(req: TranslateRequest): AppResult<Translation> {
        refreshConfig()
        val client = client()
            ?: return AppResult.Err(AppError.AuthFailed(ProviderId.CUSTOM.name))
        return client.translate(req)
    }

    override suspend fun testConnection(): AppResult<Unit> {
        refreshConfig()
        val config = cachedConfig
            ?: return AppResult.Err(
                AppError.ProviderDown(ProviderId.CUSTOM.name, retryable = false)
            )
        val key = keyProvider(config.vaultEntry)
            ?: return AppResult.Err(AppError.AuthFailed(ProviderId.CUSTOM.name))
        return GatewayProbe(transport).fetchModelsWithKey(
            config.baseUrl, config.protocolEnum(), key
        ).map { }
    }

    /** The parameterised client for the slot's current config. */
    private fun client(): Provider? {
        val config = cachedConfig ?: return null
        val keyOf: suspend () -> String? = { keyProvider(config.vaultEntry) }
        val configured: () -> Boolean = { keyPresent(config.vaultEntry) }
        return if (config.protocolEnum() == GatewayProtocol.ANTHROPIC) {
            ClaudeProvider(
                transport = transport,
                health = health,
                keyProvider = keyOf,
                isConfigured = configured,
                apiBase = config.baseUrl,
                providerId = ProviderId.CUSTOM,
                providerDisplayName = config.name,
                modelPreference = { config.effectiveModel() },
                testViaModelsList = true,
                usageRecorder = usageRecorder
            )
        } else {
            OpenAiProvider(
                transport = transport,
                health = health,
                keyProvider = keyOf,
                isConfigured = configured,
                apiBase = config.baseUrl,
                providerId = ProviderId.CUSTOM,
                providerDisplayName = config.name,
                modelPreference = { config.effectiveModel() },
                testViaModelsList = true,
                usageRecorder = usageRecorder
            )
        }
    }
}
