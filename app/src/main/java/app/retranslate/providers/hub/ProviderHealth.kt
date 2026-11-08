package app.retranslate.providers.hub

import app.retranslate.providers.ProviderId
import java.util.concurrent.ConcurrentHashMap

/** Persisted health facts for one key (provider id, or `PROVIDER@instance`). */
data class HealthStamp(
    val lastSuccessMs: Long = 0L,
    val cooldownUntilMs: Long = 0L
)

/**
 * Where [ProviderHealth] persists its stamps (§4.4.2: an app restart must
 * not resurrect a dead route into first position). Implemented by
 * `ProviderConfigStore` on device; faked in tests.
 */
interface HealthStore {
    suspend fun loadStamps(): Map<String, HealthStamp>
    suspend fun saveStamp(key: String, stamp: HealthStamp)
}

/** Source of an instance list for one provider (defaults + user edits). */
interface InstanceStore {
    suspend fun instances(id: ProviderId): List<String>
}

/**
 * Circuit breaker + health memory (PLAN.md §4.4.2).
 *
 * Per key (a provider, and each of its instances): [FAILURE_THRESHOLD]
 * consecutive failures ⇒ cooldown of [COOLDOWN_MS]. A success resets the
 * counter and the cooldown. Failure counters live in memory; the stamps
 * (last success, cooldown end) are persisted through [HealthStore].
 * After a cooldown expires the key is tried again (half-open): a success
 * fully resets it, a failure re-enters cooldown immediately.
 */
class ProviderHealth(
    private val store: HealthStore,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val failures = ConcurrentHashMap<String, Int>()
    private val stamps = ConcurrentHashMap<String, HealthStamp>()

    @Volatile
    private var loaded = false

    suspend fun ensureLoaded() {
        if (!loaded) {
            stamps.putAll(store.loadStamps())
            loaded = true
        }
    }

    fun isInCooldown(key: String): Boolean {
        val until = stamps[key]?.cooldownUntilMs ?: 0L
        return until > nowMs()
    }

    fun lastSuccessMs(key: String): Long = stamps[key]?.lastSuccessMs ?: 0L

    fun consecutiveFailures(key: String): Int = failures[key] ?: 0

    suspend fun recordSuccess(key: String) {
        failures.remove(key)
        val stamp = HealthStamp(lastSuccessMs = nowMs(), cooldownUntilMs = 0L)
        stamps[key] = stamp
        store.saveStamp(key, stamp)
    }

    /** Records one failure; returns true if this failure started a cooldown. */
    suspend fun recordFailure(key: String): Boolean {
        val count = (failures[key] ?: 0) + 1
        failures[key] = count
        if (count < FAILURE_THRESHOLD) return false
        val stamp = HealthStamp(
            lastSuccessMs = lastSuccessMs(key),
            cooldownUntilMs = nowMs() + COOLDOWN_MS
        )
        stamps[key] = stamp
        store.saveStamp(key, stamp)
        return true
    }

    /**
     * Health order (§4.4.1): available keys first (not cooling down), then
     * most-recently-successful first; ties and never-tried keys keep their
     * given (static) order. Stable sort.
     */
    fun orderKeys(keys: List<String>): List<String> = keys.sortedWith(
        compareBy<String> { if (isInCooldown(it)) 1 else 0 }
            .thenByDescending { lastSuccessMs(it) }
    )

    companion object {
        const val FAILURE_THRESHOLD = 3
        const val COOLDOWN_MS = 5 * 60_000L

        fun providerKey(id: ProviderId): String = id.name
        fun instanceKey(id: ProviderId, instance: String): String = "${id.name}@$instance"
    }
}
