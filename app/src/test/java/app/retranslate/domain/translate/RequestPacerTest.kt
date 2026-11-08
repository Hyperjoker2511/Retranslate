package app.retranslate.domain.translate

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** PLAN.md §5.5/§7.4: request pacing between bulk provider calls. */
class RequestPacerTest {

    @Test
    fun `first call never waits, later calls space by the delay`() = runTest {
        var now = 1_000L
        val waits = mutableListOf<Long>()
        val pacer = RequestPacer(
            minDelayMs = { 600L },
            delayFn = { waits += it; now += it },
            nowMs = { now }
        )
        pacer.pace() // first ever call: nothing to space against
        pacer.pace() // immediately after: must wait the full delay
        pacer.pace() // and again
        assertEquals(listOf(600L, 600L), waits)
    }

    @Test
    fun `no wait when enough time already passed`() = runTest {
        var now = 0L
        val waits = mutableListOf<Long>()
        val pacer = RequestPacer(
            minDelayMs = { 600L },
            delayFn = { waits += it },
            nowMs = { now }
        )
        pacer.pace()
        now += 5_000L // a slow provider call already spaced us out
        pacer.pace()
        assertEquals(emptyList<Long>(), waits)
    }

    @Test
    fun `zero configured delay never waits`() = runTest {
        val waits = mutableListOf<Long>()
        val pacer = RequestPacer(
            minDelayMs = { 0L },
            delayFn = { waits += it },
            nowMs = { 100L }
        )
        pacer.pace()
        pacer.pace()
        assertEquals(emptyList<Long>(), waits)
    }
}
