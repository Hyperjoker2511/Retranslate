package app.retranslate.providers.gateway

import kotlinx.serialization.Serializable

/** The two standard gateway shapes §K can auto-add — and no others. */
enum class GatewayProtocol { OPENAI, ANTHROPIC }

/**
 * One user-added gateway (v1.1.3 §K): a site the user pasted, the app
 * test-drove, and the user's key validated. The config is NOT secret
 * (it lives in ProviderConfigStore); the key lives in the KeyVault
 * under [vaultEntry]. A gateway's identity is its [slot] — every
 * gateway shares ProviderId.CUSTOM, so slots are how the app tells
 * them apart.
 */
@Serializable
data class GatewayConfig(
    /** Stable slot key: "gw1", "gw2", … (never reused while in use). */
    val slot: String,
    /** The user's name for it (defaults to the site's host). */
    val name: String,
    /** API root, e.g. "https://api.apinex.bond/v1" — no trailing slash. */
    val baseUrl: String,
    /** [GatewayProtocol] name. */
    val protocol: String,
    /** Model ids the site listed when the key was validated. */
    val models: List<String> = emptyList(),
    /** The model this gateway answers with; null = its first listed. */
    val chosenModel: String? = null
) {
    /** The KeyVault entry holding this gateway's key. */
    val vaultEntry: String get() = "CUSTOM:$slot"

    fun protocolEnum(): GatewayProtocol =
        GatewayProtocol.entries.firstOrNull { it.name == protocol }
            ?: GatewayProtocol.OPENAI

    /** The model a call should use: the pick, else the first listed. */
    fun effectiveModel(): String? = chosenModel ?: models.firstOrNull()

    companion object {
        /** How many gateways the app will hold (one chain slot each). */
        const val MAX_GATEWAYS = 6

        /** Slot keys in order: gw1..gw[ MAX_GATEWAYS ]. */
        fun slotKeys(): List<String> = (1..MAX_GATEWAYS).map { "gw$it" }

        /**
         * Whether the PROVIDER lists this model as free (§K: the
         * badge shows the provider's own claim — "free/…" prefixes
         * (APInex-style) and ":free" suffixes (OpenRouter-style)).
         */
        fun isListedFree(modelId: String): Boolean {
            val id = modelId.trim().lowercase()
            return id.startsWith("free/") || id.endsWith(":free") ||
                id.contains("-free")
        }
    }
}

/** Where gateway configs live (ProviderConfigStore in production). */
interface GatewayConfigSource {
    suspend fun gatewayConfigs(): List<GatewayConfig>
    suspend fun gatewayConfig(slot: String): GatewayConfig? =
        gatewayConfigs().firstOrNull { it.slot == slot }
}
