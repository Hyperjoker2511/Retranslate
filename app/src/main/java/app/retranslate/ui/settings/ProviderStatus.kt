package app.retranslate.ui.settings

import app.retranslate.core.security.KeyVault
import app.retranslate.data.prefs.ProviderConfigStore
import app.retranslate.providers.Provider
import app.retranslate.providers.ProviderId
import app.retranslate.providers.hub.ProviderHealth
import java.text.NumberFormat

/**
 * The one builder of [ProviderRowState]s (v1.1 extraction): Providers &
 * keys and the Translate screen's provider picker MUST agree on names
 * and statuses, so both read rows from here. Status words come from
 * three honest sources only: KeyVault presence, the §4.4.2 health
 * memory, and the configured instance lists.
 */
object ProviderStatus {

    /** Gemini first (the main engine), keyed classics + AIs, free net, local last. */
    val DISPLAY_ORDER: List<ProviderId> = listOf(
        ProviderId.GEMINI,
        ProviderId.DEEPL,
        ProviderId.AZURE,
        ProviderId.OPENAI,
        ProviderId.CLAUDE,
        ProviderId.APINEX,
        ProviderId.MOZHI,
        ProviderId.LINGVA,
        ProviderId.SIMPLYTRANSLATE,
        ProviderId.LIBRETRANSLATE,
        ProviderId.MYMEMORY,
        ProviderId.OLLAMA,
        ProviderId.OFFLINE
    )

    val INSTANCE_EDITABLE: Set<ProviderId> = setOf(
        ProviderId.MOZHI,
        ProviderId.LINGVA,
        ProviderId.SIMPLYTRANSLATE,
        ProviderId.LIBRETRANSLATE
    )

    suspend fun buildRows(
        keyVault: KeyVault,
        configStore: ProviderConfigStore,
        health: ProviderHealth,
        providers: Map<ProviderId, Provider>,
        /** v1.1.3 (§H): downloaded ML Kit pack count for the Offline row. */
        offlinePackCount: suspend () -> Int = { 0 }
    ): List<ProviderRowState> {
        health.ensureLoaded()
        val offlinePacks = offlinePackCount()
        return DISPLAY_ORDER.map { id ->
            val provider = providers[id]
            val name = provider?.displayName ?: id.name
            val key = ProviderHealth.providerKey(id)
            val resting = health.isInCooldown(key)
            val worked = health.lastSuccessMs(key) > 0L
            val acceptsKey = ProviderCatalog.keyed(id) != null
            val hasKey = keyVault.isPresent(id.name)
            val canEditInstances = id in INSTANCE_EDITABLE
            when {
                // v1.1.3 (§H): the on-device engine — its "setup" is
                // the downloaded language packs, managed in the
                // Providers screen's Offline section.
                id == ProviderId.OFFLINE ->
                    ProviderRowState(
                        id, name,
                        if (offlinePacks > 0) {
                            "$offlinePacks language pack" +
                                if (offlinePacks == 1) "" else "s"
                        } else {
                            "Not set up"
                        },
                        "Translates on this phone with Google ML Kit — no " +
                            "internet, no key. Download a language pack " +
                            "under More → Providers & keys first.",
                        hasKey, acceptsKey, canEditInstances
                    )
                // P7 (§4.3): the dormant local entry — inert and
                // explained until the user connects their computer.
                id == ProviderId.OLLAMA && configStore.ollamaConfig() == null ->
                    ProviderRowState(
                        id, name, "Not set up",
                        "Ollama runs an AI model on your own computer — free, " +
                            "unlimited, private. Set it up to use it; until " +
                            "then it stays off and is never called.",
                        hasKey, acceptsKey, canEditInstances
                    )
                id == ProviderId.LIBRETRANSLATE && configStore.instances(id).isEmpty() ->
                    ProviderRowState(
                        id, name, "Not set up",
                        "Add your own instance to use it — the public site needs a paid key.",
                        hasKey, acceptsKey, canEditInstances
                    )
                acceptsKey && !hasKey ->
                    ProviderRowState(
                        id, name, "Needs a key",
                        ProviderCatalog.keyed(id)?.costText,
                        hasKey, acceptsKey, canEditInstances
                    )
                resting ->
                    ProviderRowState(
                        id, name, "Resting — busy earlier",
                        detailFor(id, keyVault, configStore), hasKey, acceptsKey, canEditInstances
                    )
                worked ->
                    ProviderRowState(
                        id, name, "Working",
                        detailFor(id, keyVault, configStore), hasKey, acceptsKey, canEditInstances
                    )
                acceptsKey ->
                    ProviderRowState(
                        id, name, "Key saved",
                        detailFor(id, keyVault, configStore) ?: "Tap Test to check it.",
                        hasKey, acceptsKey, canEditInstances
                    )
                else ->
                    ProviderRowState(
                        id, name, "On",
                        detailFor(id, keyVault, configStore), hasKey, acceptsKey, canEditInstances
                    )
            }
        }
    }

    /** The §5.5 usage line for keyed providers; instance count for the rest. */
    private suspend fun detailFor(
        id: ProviderId,
        keyVault: KeyVault,
        configStore: ProviderConfigStore
    ): String? {
        if (id == ProviderId.OLLAMA) {
            val config = configStore.ollamaConfig() ?: return null
            return "Your computer: ${config.baseUrl} · model ${config.model}"
        }
        val info = ProviderCatalog.keyed(id)
        if (info != null && keyVault.isPresent(id.name)) {
            val used = configStore.usageChars(id)
            val limit = info.freeLimitNote?.let { " · $it" } ?: ""
            return "≈${NumberFormat.getInstance().format(used)} characters this month$limit"
        }
        if (id in INSTANCE_EDITABLE) {
            val count = configStore.instances(id).size
            return if (count == 0) null else "$count instance${if (count == 1) "" else "s"}"
        }
        return null
    }

    /**
     * The picker's gate (v1.1): a row the user cannot actually use yet —
     * it needs a key that isn't saved, or it was never set up. Tapping
     * such a row routes to Providers & keys instead of selecting it.
     */
    fun needsSetup(row: ProviderRowState): Boolean =
        (row.acceptsKey && !row.hasKey) || row.statusText == "Not set up"
}
