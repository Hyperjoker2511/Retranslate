package app.retranslate.providers.free

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.core.network.HttpResponse
import app.retranslate.providers.FakeClock
import app.retranslate.providers.FakeHealthStore
import app.retranslate.providers.FakeTransport
import app.retranslate.providers.TranslateRequest
import app.retranslate.providers.hub.ProviderHealth
import app.retranslate.providers.hub.ProviderRouter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MyMemory parsing + quota handling. Fixtures mirror the live API,
 * including the quota-exhausted body recorded in P1: HTTP 429 with the
 * warning text sitting in translatedText — it must NEVER reach the
 * user as a "translation".
 */
class MyMemoryProviderTest {

    private val clock = FakeClock(now = 0L)
    private val health = ProviderHealth(FakeHealthStore(), clock.lambda)

    private fun provider(
        transport: FakeTransport,
        usage: MutableList<Int>? = null
    ) = MyMemoryProvider(
        transport = transport,
        health = health,
        nowMs = clock.lambda,
        delayFn = {},
        usageRecorder = { words -> usage?.add(words) }
    )

    private val req = TranslateRequest(text = "hello world", source = "en", target = "es")

    @Test
    fun `parses a translation and charges the word budget`() = runTest {
        val usage = mutableListOf<Int>()
        val transport = FakeTransport().on(
            { true },
            AppResult.Ok(
                HttpResponse(
                    200,
                    """{"responseData":{"translatedText":"Hola mundo","match":1},"responseStatus":"200","quotaFinished":false}"""
                )
            )
        )
        val result = provider(transport, usage).translate(req)
        assertEquals("Hola mundo", (result as AppResult.Ok).value.text)
        assertEquals(listOf(2), usage)
        assertTrue(transport.requestedUrls.single().contains("langpair=en%7Ces"))
    }

    @Test
    fun `HTTP 429 is a rate limit - the warning text is not a translation`() = runTest {
        val transport = FakeTransport().on(
            { true },
            AppResult.Ok(
                HttpResponse(
                    429,
                    """{"responseData":{"translatedText":"MYMEMORY WARNING: YOU USED ALL AVAILABLE FREE TRANSLATIONS FOR TODAY."},"responseStatus":"429"}"""
                )
            )
        )
        val result = provider(transport).translate(req)
        val error = (result as AppResult.Err).error
        assertTrue(error is AppError.RateLimited)
    }

    @Test
    fun `quota warning inside a 200 body is also a rate limit`() = runTest {
        val transport = FakeTransport().on(
            { true },
            AppResult.Ok(
                HttpResponse(
                    200,
                    """{"responseData":{"translatedText":"MYMEMORY WARNING: YOU USED ALL AVAILABLE FREE TRANSLATIONS FOR TODAY."},"responseStatus":"403","responseDetails":"QUOTA FINISHED","quotaFinished":true}"""
                )
            )
        )
        val result = provider(transport).translate(req)
        assertTrue((result as AppResult.Err).error is AppError.RateLimited)
    }

    @Test
    fun `auto source is unsupported - MyMemory needs a concrete pair`() = runTest {
        val p = provider(FakeTransport())
        assertTrue(!p.supportsPair("auto", "en"))
        assertTrue(p.supportsPair("ko", "en"))
    }

    @Test
    fun `long text is excluded by the router before any HTTP call`() = runTest {
        val transport = FakeTransport()
        val router = ProviderRouter(
            providers = listOf(provider(transport)),
            health = health,
            nowMs = clock.lambda
        )
        val longText = "word ".repeat(150) // 750 chars > 500-char limit
        val result = router.translate(longText, "ko", "en")
        assertTrue((result as AppResult.Err).error is AppError.UnsupportedLanguagePair)
        assertEquals(0, transport.requestedUrls.size)
    }
}
