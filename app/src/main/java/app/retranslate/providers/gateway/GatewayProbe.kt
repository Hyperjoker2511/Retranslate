package app.retranslate.providers.gateway

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.core.network.HttpTransport
import app.retranslate.providers.free.ProviderJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** The test-drive's verdict (§K): a shape was found, or honestly not. */
sealed interface GatewayProbeResult {
    /**
     * The site answers like a standard AI gateway. [models] is what
     * it listed without a key (often empty — many gateways list
     * models only to key holders; the key step fetches the real list).
     */
    data class Found(
        val baseUrl: String,
        val protocol: GatewayProtocol,
        val models: List<String>
    ) : GatewayProbeResult

    /** Nothing was added. [reason] is one plain line for the user. */
    data class NotFound(val reason: String) : GatewayProbeResult
}

/**
 * The §K test-drive: paste a site, the app checks whether it works
 * as an AI provider BEFORE anything is added. Protocol-based — it
 * probes the two standard shapes (OpenAI-compatible first, then
 * Anthropic-compatible) at the pasted address and at its /v1 root.
 * There is deliberately NO list of known sites: names come and go,
 * the shapes stay.
 */
class GatewayProbe(private val transport: HttpTransport) {

    suspend fun probe(rawInput: String): GatewayProbeResult {
        val candidates = candidateBases(rawInput)
        if (candidates.isEmpty()) {
            return GatewayProbeResult.NotFound(
                "That doesn't look like a website address — paste the site's link."
            )
        }
        var sawNetworkError = false
        for (base in candidates) {
            // OpenAI shape first: GET {base}/models.
            when (val openAi = tryModels(base, GatewayProtocol.OPENAI, key = null)) {
                is ShapeHit.Yes -> return GatewayProbeResult.Found(
                    base, GatewayProtocol.OPENAI, openAi.models
                )
                ShapeHit.NeedsKey -> return GatewayProbeResult.Found(
                    base, GatewayProtocol.OPENAI, emptyList()
                )
                ShapeHit.No -> Unit
                ShapeHit.Unreachable -> sawNetworkError = true
            }
            // Anthropic shape: same path, Anthropic's version header.
            when (val anthropic = tryModels(base, GatewayProtocol.ANTHROPIC, key = null)) {
                is ShapeHit.Yes -> return GatewayProbeResult.Found(
                    base, GatewayProtocol.ANTHROPIC, anthropic.models
                )
                ShapeHit.NeedsKey -> return GatewayProbeResult.Found(
                    base, GatewayProtocol.ANTHROPIC, emptyList()
                )
                ShapeHit.No -> Unit
                ShapeHit.Unreachable -> sawNetworkError = true
            }
        }
        return GatewayProbeResult.NotFound(
            if (sawNetworkError) {
                "Couldn't reach that site — check the link and your connection, then try again."
            } else {
                "That site doesn't answer like an AI provider I can add — nothing was added."
            }
        )
    }

    /**
     * The key step (§K step 2): with the user's key, fetch the model
     * list. A key the site rejects is refused here — never saved.
     */
    suspend fun fetchModelsWithKey(
        baseUrl: String,
        protocol: GatewayProtocol,
        key: String
    ): AppResult<List<String>> {
        if (key.isBlank()) return AppResult.Err(AppError.AuthFailed("CUSTOM"))
        return when (val hit = tryModels(baseUrl, protocol, key)) {
            is ShapeHit.Yes -> AppResult.Ok(hit.models)
            ShapeHit.NeedsKey -> AppResult.Err(AppError.AuthFailed("CUSTOM"))
            ShapeHit.No -> AppResult.Err(
                AppError.ProviderDown("CUSTOM", retryable = false)
            )
            ShapeHit.Unreachable -> AppResult.Err(AppError.NoNetwork)
        }
    }

    private sealed interface ShapeHit {
        data class Yes(val models: List<String>) : ShapeHit
        data object NeedsKey : ShapeHit
        data object No : ShapeHit
        data object Unreachable : ShapeHit
    }

    /** One GET /models against one candidate shape. */
    private suspend fun tryModels(
        baseUrl: String,
        protocol: GatewayProtocol,
        key: String?
    ): ShapeHit {
        val headers = when (protocol) {
            GatewayProtocol.OPENAI ->
                if (key != null) mapOf("Authorization" to "Bearer $key") else emptyMap()
            GatewayProtocol.ANTHROPIC -> buildMap {
                put("anthropic-version", ANTHROPIC_VERSION)
                if (key != null) put("x-api-key", key)
            }
        }
        val response = when (val r = transport.get("$baseUrl/models", headers)) {
            is AppResult.Err -> return ShapeHit.Unreachable
            is AppResult.Ok -> r.value
        }
        return when {
            response.isSuccessful -> {
                val models = parseModelIds(response.body)
                // A 200 that isn't a model list isn't this shape.
                if (models != null) ShapeHit.Yes(models) else ShapeHit.No
            }
            response.code == 401 || response.code == 403 -> ShapeHit.NeedsKey
            else -> ShapeHit.No
        }
    }

    companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"

        /**
         * Candidate API roots for a pasted link: the address itself,
         * plus its /v1 root when the paste didn't already end in a
         * version path. A pasted homepage ("https://apinex.bond")
         * yields ["https://apinex.bond", "https://apinex.bond/v1"];
         * a pasted API root yields just itself.
         */
        fun candidateBases(rawInput: String): List<String> {
            var value = rawInput.trim().trimEnd('/')
            if (value.isEmpty()) return emptyList()
            if (!value.startsWith("http://") && !value.startsWith("https://")) {
                value = "https://$value"
            }
            val host = value.substringAfter("://").substringBefore('/')
            if (host.isBlank() || !host.contains('.')) return emptyList()
            val path = value.substringAfter("://").substringAfter('/', "")
            return if (path.isEmpty() || path == "v1") {
                if (path == "v1") listOf(value) else listOf(value, "$value/v1")
            } else {
                listOf(value)
            }
        }

        /**
         * Model ids out of a /models body: the OpenAI shape
         * ({"data":[{"id":…}]}) and the Anthropic shape (same "data"
         * array) share it. Null when the body isn't that shape at all.
         */
        fun parseModelIds(body: String): List<String>? {
            val root = try {
                ProviderJson.parseToJsonElement(body).jsonObject
            } catch (t: Throwable) {
                return null
            }
            val data = root["data"] as? JsonArray ?: return null
            return data.mapNotNull { element ->
                (element as? JsonObject)?.get("id")
                    ?.let { it as? JsonPrimitive }
                    ?.contentOrNull
            }
        }
    }
}
