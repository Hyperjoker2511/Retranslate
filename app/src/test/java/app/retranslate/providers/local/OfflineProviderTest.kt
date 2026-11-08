package app.retranslate.providers.local

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.providers.TranslateRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.1.3 (§H): the Offline provider over a fake ML Kit engine —
 * honest failures (not downloaded, auto-detect) in plain errors,
 * and a clean pass-through when the pack is on the phone.
 */
class OfflineProviderTest {

    private class FakeEngine(
        var pairs: Set<OfflinePair> = emptySet()
    ) : OfflineTranslateEngine {
        override fun supportedTags(): Set<String> = setOf("ko", "zh", "en", "ja")
        override suspend fun downloadedPairs(): Set<OfflinePair> = pairs
        override suspend fun download(
            sourceTag: String, targetTag: String, wifiOnly: Boolean
        ): AppResult<Unit> {
            pairs = pairs + OfflinePair(sourceTag, targetTag)
            return AppResult.Ok(Unit)
        }
        override suspend fun delete(
            sourceTag: String, targetTag: String
        ): AppResult<Unit> {
            pairs = pairs - OfflinePair(sourceTag, targetTag)
            return AppResult.Ok(Unit)
        }
        override suspend fun translate(
            text: String, sourceTag: String, targetTag: String
        ): AppResult<String> = AppResult.Ok("[$sourceTag→$targetTag] $text")
    }

    private fun request(source: String, target: String) = TranslateRequest(
        text = "안녕하세요", source = source, target = target
    )

    @Test
    fun `downloaded pair translates on-device`() = runTest {
        val engine = FakeEngine(setOf(OfflinePair("ko", "en")))
        val result = OfflineProvider(engine).translate(request("ko", "en"))
        assertTrue(result is AppResult.Ok)
        assertEquals("[ko→en] 안녕하세요", (result as AppResult.Ok).value.text)
    }

    @Test
    fun `missing pack fails plainly and points at the download`() = runTest {
        val result = OfflineProvider(FakeEngine()).translate(request("ko", "en"))
        assertTrue(result is AppResult.Err)
        val error = (result as AppResult.Err).error
        assertTrue(error is AppError.VerificationFailed)
        assertTrue(
            (error as AppError.VerificationFailed).reason
                .contains("Providers & keys")
        )
    }

    @Test
    fun `auto source is refused in words - offline cannot detect`() = runTest {
        val engine = FakeEngine(setOf(OfflinePair("ko", "en")))
        val result = OfflineProvider(engine).translate(request("auto", "en"))
        assertTrue(result is AppResult.Err)
        assertTrue((result as AppResult.Err).error is AppError.VerificationFailed)
    }

    @Test
    fun `regional codes map to their ML Kit tag`() {
        assertEquals("zh", OfflineProvider.mlKitTag("zh-CN"))
        assertEquals("ko", OfflineProvider.mlKitTag("ko"))
    }
}
