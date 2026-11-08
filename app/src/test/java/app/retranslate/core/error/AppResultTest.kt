package app.retranslate.core.error

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AppResultTest {

    @Test
    fun `map transforms Ok and passes Err through`() {
        assertEquals(AppResult.Ok(4), AppResult.Ok(2).map { it * 2 })
        val err: AppResult<Int> = AppResult.Err(AppError.NoNetwork)
        assertEquals(err, err.map { it * 2 })
    }

    @Test
    fun `flatMap chains and short-circuits on Err`() {
        val ok: AppResult<Int> = AppResult.Ok(3)
        assertEquals(AppResult.Ok(6), ok.flatMap { AppResult.Ok(it * 2) })
        val err: AppResult<Int> = AppResult.Err(AppError.DrmLocked)
        assertEquals(err, ok.flatMap { err })
        assertEquals(err, err.flatMap { AppResult.Ok(1) })
    }

    @Test
    fun `fold picks the right branch`() {
        assertEquals("ok", AppResult.Ok(1).fold({ "ok" }, { "err" }))
        assertEquals(
            "err",
            AppResult.Err(AppError.ScannedPdf).fold({ "ok" }, { "err" })
        )
    }

    @Test
    fun `runCatchingApp converts exceptions to Unknown`() {
        val result = runCatchingApp { throw IllegalStateException("boom") }
        val error = result.errorOrNull()
        assertTrue(error is AppError.Unknown)
        assertEquals("IllegalStateException", (error as AppError.Unknown).causeClass)
        assertNull(result.getOrNull())
    }

    @Test
    fun `runCatchingApp never swallows cancellation`() {
        try {
            runCatchingApp { throw CancellationException("cancelled") }
            fail("CancellationException must propagate")
        } catch (expected: CancellationException) {
            // correct
        }
    }

    @Test
    fun `suspend variant converts exceptions too`() = runBlocking {
        val result = runCatchingAppSuspend { throw java.io.IOException("io") }
        assertTrue(result.errorOrNull() is AppError.Unknown)
    }
}
