package app.retranslate.core.error

/**
 * A failure as the UI shows it (PLAN.md §3.3): one plain-words message plus
 * the label of the primary recovery action. Produced only by [ErrorText].
 */
data class ErrorUi(
    val message: String,
    val recoveryLabel: String
)

/**
 * The one place [AppError]s become words (PLAN.md §10 catalogue).
 * The `when` is exhaustive on purpose: a new [AppError] subclass cannot
 * ship without a plain-words message here — the compiler enforces it.
 */
object ErrorText {

    fun of(error: AppError): ErrorUi = when (error) {
        AppError.NoNetwork ->
            ErrorUi("No internet connection.", "Try again")

        is AppError.ProviderDown ->
            ErrorUi("That translation service isn't answering right now.", "Try again")

        is AppError.RateLimited ->
            ErrorUi("The translation servers are busy right now.", "Try again")

        is AppError.AuthFailed ->
            ErrorUi("This key didn't work — check it and try again.", "Re-enter key")

        is AppError.UnsupportedLanguagePair ->
            ErrorUi(
                "No provider can translate ${error.source} → ${error.target} yet.",
                "Change language"
            )

        is AppError.SameLanguage ->
            ErrorUi(
                "This is already in ${error.language} — there's nothing to translate.",
                "Change language"
            )

        is AppError.AllProvidersFailed ->
            ErrorUi(
                "Every translation service is unavailable right now. " +
                    "Your text is safe — try again in a little while.",
                "Try again"
            )

        is AppError.BadFile ->
            ErrorUi("This file couldn't be read (${error.reason}).", "Pick a different file")

        AppError.DrmLocked ->
            ErrorUi(
                "This book is locked by its publisher (DRM) and can't be imported.",
                "Pick a different file"
            )

        AppError.ScannedPdf ->
            ErrorUi(
                "This PDF is scanned pictures of pages — the app can't read it yet.",
                "Pick a different file"
            )

        AppError.StorageFull ->
            ErrorUi("Not enough space on the phone for this book.", "Free up space")

        is AppError.AiBadOutput ->
            ErrorUi("The AI's answer was garbled — trying again usually fixes it.", "Try again")

        is AppError.VerificationFailed ->
            ErrorUi("That translation didn't pass a quality check (${error.reason}).", "Retry")

        is AppError.StorageFailed ->
            ErrorUi(
                "Something went wrong saving. Restart the app — your books are kept safe.",
                "Restart app"
            )

        is AppError.ExportFailed ->
            ErrorUi("Couldn't save the file there (${error.reason}).", "Choose a different place")

        is AppError.Unknown ->
            ErrorUi("Something went wrong. Nothing was lost.", "Try again")
    }
}
