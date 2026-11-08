package app.retranslate.core.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLAN.md §3.4: 3 attempts, 1s → 2s → 4s backoff, retry retryable only. */
class RetryPolicyTest {

    @Test
    fun `backoff doubles without jitter`() {
        assertEquals(1_000L, RetryPolicy.backoffMillis(2, jitter = false))
        assertEquals(2_000L, RetryPolicy.backoffMillis(3, jitter = false))
    }

    @Test
    fun `backoff jitter stays within twenty percent`() {
        repeat(200) {
            val v2 = RetryPolicy.backoffMillis(2)
            assertTrue(v2 in 800..1_200)
            val v3 = RetryPolicy.backoffMillis(3)
            assertTrue(v3 in 1_600..2_400)
        }
    }

    @Test
    fun `retryable classes match the plan`() {
        assertTrue(RetryPolicy.isRetryable(AppError.NoNetwork))
        assertTrue(RetryPolicy.isRetryable(AppError.ProviderDown("mozhi", 500)))
        assertTrue(RetryPolicy.isRetryable(AppError.RateLimited("mozhi", null)))
    }

    @Test
    fun `non-retryable classes fail over immediately`() {
        assertFalse(RetryPolicy.isRetryable(AppError.AuthFailed("gemini")))
        assertFalse(RetryPolicy.isRetryable(AppError.UnsupportedLanguagePair("ko", "xx")))
        assertFalse(RetryPolicy.isRetryable(AppError.SameLanguage("English")))
        assertFalse(RetryPolicy.isRetryable(AppError.AiBadOutput("gemini")))
        assertFalse(RetryPolicy.isRetryable(AppError.BadFile("empty")))
        assertFalse(RetryPolicy.isRetryable(AppError.DrmLocked))
        assertFalse(RetryPolicy.isRetryable(AppError.ScannedPdf))
        assertFalse(RetryPolicy.isRetryable(AppError.StorageFull))
        assertFalse(RetryPolicy.isRetryable(AppError.AllProvidersFailed(listOf("mozhi"))))
    }

    @Test
    fun `rate limit honours retry-after up to the cap`() {
        val under = AppError.RateLimited("mozhi", retryAfterSeconds = 5)
        assertEquals(5_000L, RetryPolicy.delayBeforeRetryMillis(under, 2))
        val over = AppError.RateLimited("mozhi", retryAfterSeconds = 600)
        assertEquals(
            RetryPolicy.RETRY_AFTER_CAP_MS,
            RetryPolicy.delayBeforeRetryMillis(over, 2)
        )
        val absent = AppError.RateLimited("mozhi", retryAfterSeconds = null)
        val fallback = RetryPolicy.delayBeforeRetryMillis(absent, 2, random = kotlin.random.Random(0))
        assertTrue("no Retry-After must fall back to attempt-2 backoff band", fallback in 800..1_200)
    }
}
