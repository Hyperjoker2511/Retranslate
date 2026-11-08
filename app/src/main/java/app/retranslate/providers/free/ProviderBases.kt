package app.retranslate.providers.free

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.core.error.RetryPolicy
import app.retranslate.providers.Provider
import app.retranslate.providers.TranslateRequest
import app.retranslate.providers.Translation
import app.retranslate.providers.hub.InstanceStore
import app.retranslate.providers.hub.ProviderHealth
import kotlinx.coroutines.delay

/**
 * Base for providers served by many interchangeable public instances
 * (Mozhi, Lingva, SimplyTranslate).
 *
 * Attempt accounting (PLAN.md §3.4 + guardrail "3 attempts before
 * switching to fallback provider"): ONE translate() performs at most
 * [RetryPolicy.MAX_ATTEMPTS] HTTP calls, rotating across instances in
 * health order — each instance try IS an attempt. A non-retryable fault
 * (bad pair, bot page, malformed body) stops the rotation early: every
 * instance of the same software would answer the same way.
 *
 * Health is recorded per instance per attempt, and once per translate()
 * at provider level (so one bad call cannot trip the provider breaker
 * by itself — §4.4.2 counts consecutive failed CALLS there).
 */
abstract class MultiInstanceProvider(
    protected val health: ProviderHealth,
    protected val instances: InstanceStore,
    protected val nowMs: () -> Long = { System.currentTimeMillis() },
    private val delayFn: suspend (Long) -> Unit = { delay(it) }
) : Provider {

    /** One HTTP attempt against one instance. Never throws. */
    protected abstract suspend fun translateOnInstance(
        instance: String,
        req: TranslateRequest
    ): AppResult<Translation>

    final override suspend fun translate(req: TranslateRequest): AppResult<Translation> {
        health.ensureLoaded()
        val hosts = instances.instances(id)
        val providerKey = ProviderHealth.providerKey(id)
        if (hosts.isEmpty()) {
            health.recordFailure(providerKey)
            return AppResult.Err(AppError.ProviderDown(id.name, retryable = false))
        }
        val orderedKeys = health.orderKeys(hosts.map { ProviderHealth.instanceKey(id, it) })
        val byKey = hosts.associateBy { ProviderHealth.instanceKey(id, it) }

        var attempts = 0
        var lastError: AppError? = null
        for (key in orderedKeys) {
            if (attempts >= RetryPolicy.MAX_ATTEMPTS) break
            if (health.isInCooldown(key)) continue
            if (nowMs() >= req.deadlineMs) break
            val instance = byKey.getValue(key)
            attempts++
            when (val result = translateOnInstance(instance, req)) {
                is AppResult.Ok -> {
                    health.recordSuccess(key)
                    health.recordSuccess(providerKey)
                    return result
                }
                is AppResult.Err -> {
                    health.recordFailure(key)
                    lastError = result.error
                    if (!RetryPolicy.isRetryable(result.error)) break
                    if (attempts < RetryPolicy.MAX_ATTEMPTS && nowMs() < req.deadlineMs) {
                        delayFn(INSTANCE_GAP_MS)
                    }
                }
            }
        }
        health.recordFailure(providerKey)
        return AppResult.Err(lastError ?: AppError.ProviderDown(id.name))
    }

    override suspend fun testConnection(): AppResult<Unit> {
        // A connection test IS one bounded translate of a fixed word.
        val req = TranslateRequest(text = "hello", source = "en", target = "es")
        return translate(req).map { }
    }

    companion object {
        /** Short pause between instance tries — rotation should feel instant. */
        const val INSTANCE_GAP_MS = 250L
    }
}

/**
 * Base for single-endpoint providers (MyMemory): the same endpoint is
 * retried with the full §3.4 exponential backoff, at most
 * [RetryPolicy.MAX_ATTEMPTS] calls per translate().
 */
abstract class SingleEndpointProvider(
    protected val health: ProviderHealth,
    protected val nowMs: () -> Long = { System.currentTimeMillis() },
    private val delayFn: suspend (Long) -> Unit = { delay(it) }
) : Provider {

    /** One HTTP attempt. Never throws. */
    protected abstract suspend fun callOnce(req: TranslateRequest): AppResult<Translation>

    final override suspend fun translate(req: TranslateRequest): AppResult<Translation> {
        health.ensureLoaded()
        val providerKey = ProviderHealth.providerKey(id)
        var lastError: AppError? = null
        for (attempt in 1..RetryPolicy.MAX_ATTEMPTS) {
            if (nowMs() >= req.deadlineMs) break
            when (val result = callOnce(req)) {
                is AppResult.Ok -> {
                    health.recordSuccess(providerKey)
                    return result
                }
                is AppResult.Err -> {
                    lastError = result.error
                    if (!RetryPolicy.isRetryable(result.error)) break
                    if (attempt < RetryPolicy.MAX_ATTEMPTS && nowMs() < req.deadlineMs) {
                        delayFn(RetryPolicy.delayBeforeRetryMillis(result.error, attempt + 1))
                    }
                }
            }
        }
        health.recordFailure(providerKey)
        return AppResult.Err(lastError ?: AppError.ProviderDown(id.name))
    }

    override suspend fun testConnection(): AppResult<Unit> {
        val req = TranslateRequest(text = "hello", source = "en", target = "es")
        return translate(req).map { }
    }
}
