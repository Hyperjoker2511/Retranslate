package app.retranslate.core.error

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every AppError subclass has plain words and a recovery action (PLAN §10).
 * If a subclass is added without an ErrorText entry this file still compiles
 * (it lists instances explicitly) — but ErrorText itself stops compiling,
 * which is the real enforcement (§3.3).
 */
class ErrorTextTest {

    private val allErrors: List<AppError> = listOf(
        AppError.NoNetwork,
        AppError.ProviderDown("mozhi", 500),
        AppError.RateLimited("mozhi", 5),
        AppError.AuthFailed("gemini"),
        AppError.UnsupportedLanguagePair("Korean", "Elvish"),
        AppError.SameLanguage("English"),
        AppError.AllProvidersFailed(listOf("mozhi", "lingva")),
        AppError.BadFile("the file is empty"),
        AppError.DrmLocked,
        AppError.ScannedPdf,
        AppError.StorageFull,
        AppError.AiBadOutput("gemini"),
        AppError.VerificationFailed("a name was changed"),
        AppError.StorageFailed("save chapter"),
        AppError.ExportFailed("that folder is read-only"),
        AppError.Unknown(IllegalStateException("x"))
    )

    @Test
    fun `every error class maps to non-blank plain words and a recovery`() {
        allErrors.forEach { error ->
            val ui = ErrorText.of(error)
            assertTrue("blank message for $error", ui.message.isNotBlank())
            assertTrue("blank recovery for $error", ui.recoveryLabel.isNotBlank())
        }
    }

    @Test
    fun `parameterised messages include their parameter`() {
        assertTrue(
            ErrorText.of(AppError.UnsupportedLanguagePair("Korean", "Elvish"))
                .message.contains("Elvish")
        )
        assertTrue(
            ErrorText.of(AppError.BadFile("the file is empty"))
                .message.contains("the file is empty")
        )
    }
}
