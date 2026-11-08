package app.retranslate.core.error

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PLAN.md §3.4: a ProviderDown may carry retryable=false for faults
 * waiting cannot heal (HTTP 4xx, malformed body, bot-check page).
 * The P0 default (no flag) stays retryable.
 */
class RetryPolicyProviderDownTest {

    @Test
    fun `provider down defaults to retryable`() {
        assertTrue(RetryPolicy.isRetryable(AppError.ProviderDown("mozhi", 500)))
        assertTrue(RetryPolicy.isRetryable(AppError.ProviderDown("mozhi")))
    }

    @Test
    fun `provider down flagged non-retryable fails over immediately`() {
        assertFalse(
            RetryPolicy.isRetryable(AppError.ProviderDown("mozhi", 400, retryable = false))
        )
        assertFalse(
            RetryPolicy.isRetryable(AppError.ProviderDown("lingva", 200, retryable = false))
        )
    }
}
