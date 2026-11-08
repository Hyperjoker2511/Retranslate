package app.retranslate.providers.key

import app.retranslate.core.error.AppResult
import app.retranslate.core.network.HttpTransport
import app.retranslate.providers.free.ProviderJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * APInex (v1.1.3 §J): a third-party OpenAI-compatible gateway with a
 * promotional free tier (20M tokens/day on its free-category models,
 * as listed by the provider in Oct 2026). It is a NAMED instance of
 * the parameterised [OpenAiProvider] — this file holds only its
 * constants and its allowance check. The app never presents it as a
 * first-party engine: its Providers card says what it is.
 */
object ApinexGateway {
    const val API_BASE = "https://api.apinex.bond/v1"
    const val SITE_URL = "https://apinex.bond"
    const val DISPLAY_NAME = "APInex"

    /** A free-category model the key test can spend one tiny call on. */
    const val DEFAULT_FREE_MODEL = "free/deepseek-v4.1-flash"
}

/**
 * The allowance line for APInex's Providers card (§J): the gateway
 * exposes GET /v1/balance and GET /v1/subscription. Best-effort by
 * design — when neither endpoint answers in a shape we recognise,
 * the card shows NOTHING rather than a guess.
 */
class ApinexAllowance(private val transport: HttpTransport) {

    /** One plain line, or null when the gateway didn't tell us. */
    suspend fun allowanceLine(key: String?): String? {
        if (key.isNullOrBlank()) return null
        val headers = mapOf("Authorization" to "Bearer $key")
        subscriptionLine(headers)?.let { return it }
        return balanceLine(headers)
    }

    private suspend fun subscriptionLine(headers: Map<String, String>): String? {
        val body = getJson("${ApinexGateway.API_BASE}/subscription", headers) ?: return null
        // Token-pool shapes: a remaining/total pair, in any of the
        // field spellings the gateway family uses.
        val remaining = firstLong(
            body,
            "free_tokens_remaining", "tokens_remaining", "remaining_tokens",
            "remaining", "token_balance", "allowance_remaining"
        )
        val total = firstLong(
            body,
            "free_tokens_total", "tokens_total", "daily_free_tokens",
            "token_allowance", "allowance", "daily_allowance"
        )
        if (remaining != null) {
            return if (total != null && total > 0) {
                "Free tokens today: ${formatCount(remaining)} of ${formatCount(total)} left"
            } else {
                "Free tokens today: ${formatCount(remaining)} left"
            }
        }
        val status = (body["status"] as? JsonPrimitive)?.contentOrNull
        return status?.let { "Subscription: $it" }
    }

    private suspend fun balanceLine(headers: Map<String, String>): String? {
        val body = getJson("${ApinexGateway.API_BASE}/balance", headers) ?: return null
        val balance = firstDouble(body, "balance", "amount", "usd_balance")
            ?: return null
        return "Balance: $${"%.2f".format(balance)}"
    }

    private suspend fun getJson(url: String, headers: Map<String, String>): JsonObject? {
        val response = when (val r = transport.get(url, headers)) {
            is AppResult.Err -> return null
            is AppResult.Ok -> r.value
        }
        if (!response.isSuccessful) return null
        return try {
            ProviderJson.parseToJsonElement(response.body).jsonObject
        } catch (t: Throwable) {
            null
        }
    }

    private fun firstLong(body: JsonObject, vararg keys: String): Long? {
        for (key in keys) {
            val value = body[key] as? JsonPrimitive ?: continue
            value.longOrNull?.let { return it }
            value.contentOrNull?.toLongOrNull()?.let { return it }
        }
        return null
    }

    private fun firstDouble(body: JsonObject, vararg keys: String): Double? {
        for (key in keys) {
            val value = body[key] as? JsonPrimitive ?: continue
            value.doubleOrNull?.let { return it }
            value.contentOrNull?.toDoubleOrNull()?.let { return it }
        }
        return null
    }

    private fun formatCount(value: Long): String =
        java.text.NumberFormat.getInstance().format(value)
}
