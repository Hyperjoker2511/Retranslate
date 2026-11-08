package app.retranslate.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.retranslate.providers.ProviderId
import app.retranslate.providers.free.DefaultInstances
import app.retranslate.providers.hub.HealthStamp
import app.retranslate.providers.hub.HealthStore
import app.retranslate.providers.hub.InstanceStore
import app.retranslate.providers.local.OllamaConfig
import app.retranslate.providers.local.OllamaConfigSource
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The ONE owner of non-secret provider configuration (PLAN.md §3.5):
 * instance lists, persisted provider-health stamps (§4.4.2), and
 * MyMemory's daily word-budget counter (§4.2). API keys never live
 * here — they belong to the KeyVault (P2).
 */
class ProviderConfigStore(private val store: DataStore<Preferences>) :
    HealthStore, InstanceStore, OllamaConfigSource,
    app.retranslate.providers.gateway.GatewayConfigSource {

    // ---- Instances ----------------------------------------------------

    override suspend fun instances(id: ProviderId): List<String> {
        val prefs = store.data.first()
        val stored = prefs[instanceKey(id)]
        return stored?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: defaultInstances(id)
    }

    suspend fun saveInstances(id: ProviderId, instances: List<String>) {
        store.edit { prefs ->
            prefs[instanceKey(id)] = instances.joinToString("\n")
        }
    }

    private fun instanceKey(id: ProviderId) = stringPreferencesKey("instances_${id.name}")

    private fun defaultInstances(id: ProviderId): List<String> = when (id) {
        ProviderId.MOZHI -> DefaultInstances.MOZHI
        ProviderId.LINGVA -> DefaultInstances.LINGVA
        ProviderId.SIMPLYTRANSLATE -> DefaultInstances.SIMPLYTRANSLATE
        else -> emptyList()
    }

    // ---- Health stamps (HealthStore) ----------------------------------

    override suspend fun loadStamps(): Map<String, HealthStamp> {
        val raw = store.data.first()[KEY_HEALTH_JSON] ?: return emptyMap()
        return decodeStamps(raw)
    }

    override suspend fun saveStamp(key: String, stamp: HealthStamp) {
        store.edit { prefs ->
            val current = decodeStamps(prefs[KEY_HEALTH_JSON] ?: "{}").toMutableMap()
            current[key] = stamp
            prefs[KEY_HEALTH_JSON] = encodeStamps(current)
        }
    }

    // ---- MyMemory daily budget ----------------------------------------

    /** Words charged against today's anonymous MyMemory budget. */
    suspend fun myMemoryWordsUsedToday(): Int {
        val prefs = store.data.first()
        val today = LocalDate.now().toString()
        return if (prefs[KEY_MYMEMORY_DAY] == today) {
            prefs[KEY_MYMEMORY_WORDS] ?: 0
        } else {
            0
        }
    }

    suspend fun addMyMemoryWords(words: Int) {
        if (words <= 0) return
        val today = LocalDate.now().toString()
        store.edit { prefs ->
            val sameDay = prefs[KEY_MYMEMORY_DAY] == today
            val current = if (sameDay) prefs[KEY_MYMEMORY_WORDS] ?: 0 else 0
            prefs[KEY_MYMEMORY_DAY] = today
            prefs[KEY_MYMEMORY_WORDS] = current + words
        }
    }

    // ---- Per-provider usage counters (§5.5) ----------------------------

    /**
     * Characters charged to [id] in [month] ("yyyy-MM"). Counters are
     * monthly because every keyed free tier resets monthly (Gemini per
     * day in practice, DeepL 500k/month, Azure 2M/month — the screen
     * shows the provider's own limit text next to this count).
     */
    suspend fun usageChars(id: ProviderId, month: String = currentMonth()): Int =
        store.data.first()[usageKey(id, month)] ?: 0

    suspend fun addUsageChars(id: ProviderId, chars: Int, month: String = currentMonth()) {
        if (chars <= 0) return
        store.edit { prefs ->
            prefs[usageKey(id, month)] = (prefs[usageKey(id, month)] ?: 0) + chars
        }
    }

    private fun usageKey(id: ProviderId, month: String) =
        intPreferencesKey("usage_chars_${id.name}_$month")

    private fun currentMonth(): String = YearMonth.now().toString()

    // ---- AI merge daily counter (§5.6, P7) ------------------------------

    /** Merges spent today — the §5.6 soft cap counts these. */
    suspend fun mergesUsedToday(): Int {
        val prefs = store.data.first()
        val today = LocalDate.now().toString()
        return if (prefs[KEY_MERGE_DAY] == today) prefs[KEY_MERGE_COUNT] ?: 0 else 0
    }

    suspend fun addMergeUsed() {
        val today = LocalDate.now().toString()
        store.edit { prefs ->
            val sameDay = prefs[KEY_MERGE_DAY] == today
            val current = if (sameDay) prefs[KEY_MERGE_COUNT] ?: 0 else 0
            prefs[KEY_MERGE_DAY] = today
            prefs[KEY_MERGE_COUNT] = current + 1
        }
    }

    // ---- Ollama setup (§4.3, P7) ------------------------------------------

    /**
     * The dormant local entry's config: the user's PC address + model.
     * Not a secret (it's a LAN address), so it lives here, not in the
     * KeyVault. Null until the user saves the setup screen's form.
     */
    override suspend fun ollamaConfig(): OllamaConfig? {
        val prefs = store.data.first()
        val baseUrl = prefs[KEY_OLLAMA_BASE_URL]?.takeIf { it.isNotBlank() } ?: return null
        val model = prefs[KEY_OLLAMA_MODEL]?.takeIf { it.isNotBlank() } ?: return null
        return OllamaConfig(baseUrl = baseUrl, model = model)
    }

    suspend fun saveOllamaConfig(baseUrl: String, model: String) {
        store.edit { prefs ->
            prefs[KEY_OLLAMA_BASE_URL] = baseUrl
            prefs[KEY_OLLAMA_MODEL] = model
        }
    }

    suspend fun clearOllamaConfig() {
        store.edit { prefs ->
            prefs.remove(KEY_OLLAMA_BASE_URL)
            prefs.remove(KEY_OLLAMA_MODEL)
        }
    }

    // ---- Added gateways (§K, v1.1.3) -----------------------------------

    /**
     * The user's added gateways: configs only — keys live in the
     * KeyVault under each config's vaultEntry. Stored as one JSON
     * list; a corrupt blob degrades to "no gateways", never a crash.
     */
    override suspend fun gatewayConfigs():
        List<app.retranslate.providers.gateway.GatewayConfig> {
        val raw = store.data.first()[KEY_GATEWAYS_JSON] ?: return emptyList()
        return decodeGateways(raw)
    }

    suspend fun saveGatewayConfigs(
        configs: List<app.retranslate.providers.gateway.GatewayConfig>
    ) {
        store.edit { prefs -> prefs[KEY_GATEWAYS_JSON] = encodeGateways(configs) }
    }

    /** The APInex card's chosen model (§J); null = its first free model. */
    suspend fun apinexModel(): String? = store.data.first()[KEY_APINEX_MODEL]

    suspend fun saveApinexModel(model: String?) {
        store.edit { prefs ->
            if (model.isNullOrBlank()) prefs.remove(KEY_APINEX_MODEL)
            else prefs[KEY_APINEX_MODEL] = model
        }
    }

    // ---- Offline (§H, v1.1.3) ------------------------------------------

    /** Language-pack downloads wait for Wi-Fi (the default). */
    suspend fun offlineWifiOnly(): Boolean =
        store.data.first()[KEY_OFFLINE_WIFI_ONLY] ?: true

    suspend fun saveOfflineWifiOnly(wifiOnly: Boolean) {
        store.edit { prefs -> prefs[KEY_OFFLINE_WIFI_ONLY] = wifiOnly }
    }

    companion object {
        private val KEY_GATEWAYS_JSON = stringPreferencesKey("gateway_configs_json")
        private val KEY_APINEX_MODEL = stringPreferencesKey("apinex_model")
        private val KEY_OFFLINE_WIFI_ONLY =
            androidx.datastore.preferences.core.booleanPreferencesKey("offline_wifi_only")
        private val KEY_HEALTH_JSON = stringPreferencesKey("health_stamps_json")
        private val KEY_MYMEMORY_DAY = stringPreferencesKey("mymemory_day")
        private val KEY_MYMEMORY_WORDS = intPreferencesKey("mymemory_words")
        private val KEY_MERGE_DAY = stringPreferencesKey("merge_day")
        private val KEY_MERGE_COUNT = intPreferencesKey("merge_count")
        private val KEY_OLLAMA_BASE_URL = stringPreferencesKey("ollama_base_url")
        private val KEY_OLLAMA_MODEL = stringPreferencesKey("ollama_model")

        private val json = Json { ignoreUnknownKeys = true }

        /** Stamps JSON shape: {"MOZHI@https://…": {"s": 123, "c": 456}, …} */
        fun encodeStamps(stamps: Map<String, HealthStamp>): String {
            val obj = buildJsonObject {
                stamps.forEach { (key, stamp) ->
                    put(key, buildJsonObject {
                        put("s", stamp.lastSuccessMs)
                        put("c", stamp.cooldownUntilMs)
                    })
                }
            }
            return obj.toString()
        }

        /** §K: gateway configs as one JSON list. */
        fun encodeGateways(
            configs: List<app.retranslate.providers.gateway.GatewayConfig>
        ): String = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(
                app.retranslate.providers.gateway.GatewayConfig.serializer()
            ),
            configs
        )

        /** Defensive decode: a corrupt blob degrades to empty, never a crash. */
        fun decodeGateways(
            raw: String
        ): List<app.retranslate.providers.gateway.GatewayConfig> = try {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(
                    app.retranslate.providers.gateway.GatewayConfig.serializer()
                ),
                raw
            )
        } catch (t: Throwable) {
            emptyList()
        }

        /** Defensive decode: a corrupt blob degrades to empty, never a crash. */
        fun decodeStamps(raw: String): Map<String, HealthStamp> = try {
            val root: JsonObject = json.parseToJsonElement(raw).jsonObject
            root.entries.mapNotNull { (key, value) ->
                val obj = value as? JsonObject ?: return@mapNotNull null
                val s = (obj["s"] as? JsonPrimitive)?.longOrNull ?: 0L
                val c = (obj["c"] as? JsonPrimitive)?.longOrNull ?: 0L
                key to HealthStamp(lastSuccessMs = s, cooldownUntilMs = c)
            }.toMap()
        } catch (t: Throwable) {
            emptyMap()
        }
    }
}
