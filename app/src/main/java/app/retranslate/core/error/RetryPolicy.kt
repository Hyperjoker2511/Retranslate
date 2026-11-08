package app.retranslate.core.error

import kotlin.random.Random

/**
 * The one retry policy for the whole app (PLAN.md §3.4).
 *
 * Per provider call: up to [MAX_ATTEMPTS] attempts, backoff 1s → 2s → 4s
 * (±20% jitter), retrying ONLY retryable failures: timeouts / connection
 * errors ([AppError.NoNetwork], [AppError.ProviderDown]), HTTP 5xx (folded
 * into ProviderDown), and HTTP 429 ([AppError.RateLimited], honouring
 * Retry-After capped at [RETRY_AFTER_CAP_MS]).
 *
 * Never retried (fail over to the next provider immediately): auth failures,
 * unsupported pairs, same-language, malformed AI output, and every file /
 * storage class — those do not heal by waiting.
 */
object RetryPolicy {

    const val MAX_ATTEMPTS = 3
    const val BASE_DELAY_MS = 1_000L
    const val RETRY_AFTER_CAP_MS = 30_000L

    fun isRetryable(error: AppError): Boolean = when (error) {
        AppError.NoNetwork -> true
        is AppError.ProviderDown -> error.retryable
        is AppError.RateLimited -> true
        is AppError.AuthFailed -> false
        is AppError.UnsupportedLanguagePair -> false
        is AppError.SameLanguage -> false
        is AppError.AllProvidersFailed -> false
        is AppError.BadFile -> false
        AppError.DrmLocked -> false
        AppError.ScannedPdf -> false
        AppError.StorageFull -> false
        is AppError.AiBadOutput -> false
        is AppError.VerificationFailed -> false
        is AppError.StorageFailed -> false
        is AppError.ExportFailed -> false
        is AppError.Unknown -> false
    }

    /** Backoff before attempt number [nextAttempt] (2 or 3): 1s, then 2s. */
    fun backoffMillis(nextAttempt: Int, jitter: Boolean = true, random: Random = Random): Long {
        require(nextAttempt in 2..MAX_ATTEMPTS) { "nextAttempt must be 2..$MAX_ATTEMPTS" }
        val base = BASE_DELAY_MS shl (nextAttempt - 2)
        if (!jitter) return base
        val spread = (base * 0.2).toLong()
        return base + random.nextLong(-spread, spread + 1)
    }

    /**
     * The delay to observe before the next attempt for [error]: the provider's
     * Retry-After (capped) for rate limits, exponential backoff otherwise.
     */
    fun delayBeforeRetryMillis(error: AppError, nextAttempt: Int, random: Random = Random): Long =
        when (error) {
            is AppError.RateLimited -> error.retryAfterSeconds
                ?.let { minOf(it * 1_000L, RETRY_AFTER_CAP_MS) }
                ?: backoffMillis(nextAttempt, random = random)
            else -> backoffMillis(nextAttempt, random = random)
        }
}
