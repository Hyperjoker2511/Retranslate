package app.retranslate.core.error

/**
 * Every failure class in the §10 resilience catalogue, as a type.
 * The UI never sees a raw exception: ViewModels map these through
 * [ErrorText] — and that mapper is an exhaustive `when`, so adding a new
 * subclass here without a plain-words message is a compile error
 * (PLAN.md §3.3).
 *
 * Fields deliberately carry ids / class names / counts only — never API keys
 * and never user text — so errors are always safe to log and to show in a
 * "Copy details" crash note (§10 row 16).
 */
sealed interface AppError {

    /**
     * Optional short technical detail for diagnostics (v1.0.1): an
     * exception class name + message, or "HTTP <code>" plus a provider's
     * own error snippet. Only [ProviderDown] and [AuthFailed] carry one.
     * Never contains API keys, headers or user text, so it is always safe
     * to show: UI surfaces render it as a small secondary "Details:" line
     * under the plain-words message, which [ErrorText] leaves unchanged.
     */
    val detail: String? get() = null

    /** §10 #1 — no connectivity / airplane mode. */
    data object NoNetwork : AppError

    /** §10 #2 — provider timed out, 5xx, or returned unparseable output. */
    data class ProviderDown(
        val providerId: String,
        val httpCode: Int? = null,
        /**
         * False for faults waiting cannot heal (HTTP 4xx, malformed body,
         * bot-check page): [RetryPolicy] must not retry them (§3.4).
         * Defaults to true so existing constructions keep §3.4 behaviour.
         */
        val retryable: Boolean = true,
        /** Technical detail for the "Details:" line (see [AppError.detail]). */
        override val detail: String? = null
    ) : AppError

    /** §10 #3 — HTTP 429. [retryAfterSeconds] honoured up to the §3.4 cap. */
    data class RateLimited(
        val providerId: String,
        val retryAfterSeconds: Long? = null
    ) : AppError

    /** §10 #4 — HTTP 401/403: the user's key for this provider is wrong/missing. */
    data class AuthFailed(
        val providerId: String,
        /** Technical detail for the "Details:" line (see [AppError.detail]). */
        override val detail: String? = null
    ) : AppError

    /** §10 #5 — no configured provider supports this language pair. */
    data class UnsupportedLanguagePair(
        val source: String,
        val target: String
    ) : AppError

    /** §10 #6 — source == target; there is nothing to translate. */
    data class SameLanguage(val language: String) : AppError

    /** Every provider in the fallback chain failed. [attempted] = provider ids, in order. */
    data class AllProvidersFailed(val attempted: List<String>) : AppError

    /** §10 #7 — corrupt / empty / wrong-format import file. [reason] is one plain line. */
    data class BadFile(val reason: String) : AppError

    /** §10 #8 — EPUB is DRM-locked (encryption.xml present). */
    data object DrmLocked : AppError

    /** §10 #9 — PDF has no text layer (scanned images). */
    data object ScannedPdf : AppError

    /** §10 #10 — not enough free storage on the device. */
    data object StorageFull : AppError

    /** §10 #11 — the AI answered with malformed output (e.g. unparseable JSON). */
    data class AiBadOutput(val providerId: String) : AppError

    /**
     * §10 #12 — a translation failed the local sanity checks (paragraph-count
     * drift, leftover source script, broken KB term). [reason] is one plain line.
     */
    data class VerificationFailed(val reason: String) : AppError

    /** §10 #14 — a database / local-storage write failed. */
    data class StorageFailed(val operation: String) : AppError

    /** §10 #15 — export could not be written to the chosen destination. */
    data class ExportFailed(val reason: String) : AppError

    /** Anything not yet classified. Carries the exception class name only. */
    data class Unknown(val cause: Throwable) : AppError {
        val causeClass: String get() = cause.javaClass.simpleName
    }
}
