package app.retranslate.providers.hub

import app.retranslate.providers.FakeClock
import app.retranslate.providers.FakeHealthStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLAN.md §4.4.2: 3 consecutive failures ⇒ 5-minute cooldown; success resets. */
class ProviderHealthTest {

    private val clock = FakeClock(now = 1_000_000L)
    private val store = FakeHealthStore()
    private val health = ProviderHealth(store, clock.lambda)

    @Test
    fun `cooldown starts exactly at the third consecutive failure`() = runBlocking {
        health.ensureLoaded()
        assertFalse(health.recordFailure("MOZHI"))
        assertFalse(health.isInCooldown("MOZHI"))
        assertFalse(health.recordFailure("MOZHI"))
        assertTrue(health.recordFailure("MOZHI"))
        assertTrue(health.isInCooldown("MOZHI"))
        assertEquals(3, health.consecutiveFailures("MOZHI"))
    }

    @Test
    fun `cooldown expires after five minutes and success fully resets`() = runBlocking {
        health.ensureLoaded()
        repeat(3) { health.recordFailure("LINGVA") }
        assertTrue(health.isInCooldown("LINGVA"))
        clock.now += ProviderHealth.COOLDOWN_MS + 1
        assertFalse(health.isInCooldown("LINGVA"))
        health.recordSuccess("LINGVA")
        assertEquals(0, health.consecutiveFailures("LINGVA"))
        assertEquals(clock.now, health.lastSuccessMs("LINGVA"))
    }

    @Test
    fun `a failure during half-open re-enters cooldown immediately`() = runBlocking {
        health.ensureLoaded()
        repeat(3) { health.recordFailure("MOZHI") }
        clock.now += ProviderHealth.COOLDOWN_MS + 1
        assertTrue(health.recordFailure("MOZHI"))
        assertTrue(health.isInCooldown("MOZHI"))
    }

    @Test
    fun `stamps persist through the store so a restart keeps cooldowns`() = runBlocking {
        health.ensureLoaded()
        repeat(3) { health.recordFailure("MOZHI") }
        val stamp = store.stamps["MOZHI"]
        assertEquals(clock.now + ProviderHealth.COOLDOWN_MS, stamp?.cooldownUntilMs)

        // Simulate an app restart: a fresh ProviderHealth over the same store.
        val revived = ProviderHealth(store, clock.lambda)
        revived.ensureLoaded()
        assertTrue(revived.isInCooldown("MOZHI"))
    }

    @Test
    fun `health order puts available and recently-successful keys first`() = runBlocking {
        health.ensureLoaded()
        clock.now = 10_000L
        health.recordSuccess("B")
        clock.now = 20_000L
        health.recordSuccess("C")
        repeat(3) { health.recordFailure("D") }
        // A never tried, B success @10k, C success @20k, D cooling down.
        val ordered = health.orderKeys(listOf("A", "B", "C", "D"))
        assertEquals(listOf("C", "B", "A", "D"), ordered)
    }
}
