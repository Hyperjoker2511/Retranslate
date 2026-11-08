package app.retranslate.domain.translate

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The anti-429 pacer (PLAN.md §5.5/§7.4): bulk chapter work spaces its
 * provider requests at least [minDelayMs] apart. One instance is
 * shared by every chapter of every job (AppContainer), so the spacing
 * holds across chapter boundaries — the place a per-chapter counter
 * would silently stop pacing. Cache hits never pace: they make no
 * request.
 */
class RequestPacer(
    private val minDelayMs: suspend () -> Long,
    private val delayFn: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val mutex = Mutex()
    private var lastCallAtMs: Long = 0L

    /** Waits until a provider call may start; records the start. */
    suspend fun pace() {
        mutex.withLock {
            val delay = minDelayMs().coerceAtLeast(0L)
            val now = nowMs()
            if (lastCallAtMs != 0L && delay > 0L) {
                val wait = delay - (now - lastCallAtMs)
                if (wait > 0L) delayFn(wait)
            }
            lastCallAtMs = nowMs()
        }
    }
}
