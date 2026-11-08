package app.retranslate.providers.hub

import app.retranslate.core.error.AppError
import app.retranslate.core.network.HttpResponse

/**
 * The shared HTTP-status → [AppError] mapping (PLAN.md §3.4/§10).
 * Returns null for 2xx (the provider then parses the body). Providers may
 * refine this with body-sniffing for their API's quirks (Mozhi's plain-text
 * 500s, MyMemory's in-body quota status) before falling back to this.
 */
object HttpErrors {

    fun forResponse(providerId: String, response: HttpResponse): AppError? = when {
        response.isSuccessful -> null
        response.code == 429 ->
            AppError.RateLimited(providerId, parseRetryAfterSeconds(response))
        response.code == 401 || response.code == 403 ->
            AppError.AuthFailed(providerId)
        response.code in 500..599 ->
            AppError.ProviderDown(
                providerId, response.code, retryable = true, detail = "HTTP ${response.code}"
            )
        else ->
            AppError.ProviderDown(
                providerId, response.code, retryable = false, detail = "HTTP ${response.code}"
            )
    }

    /** Retry-After is in seconds when numeric; HTTP-date forms are ignored. */
    fun parseRetryAfterSeconds(response: HttpResponse): Long? =
        response.header("Retry-After")?.trim()?.toLongOrNull()
}
