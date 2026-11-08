package app.retranslate.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.1.1 regression guard for the FluidNavBar pill. The v1.1 bug (pill
 * measured a full screen tall) is impossible in the new bar because
 * the pill's rect comes only from [NavBarGeometry] — these tests pin
 * the invariant those functions promise: for ANY bar size, item
 * count, index, inset, or in-flight animated x, the pill stays fully
 * inside the bar.
 */
class NavBarGeometryTest {

    private val widths = listOf(0, 1, 7, 100, 320, 1080, 1440, 2560)
    private val heights = listOf(0, 1, 48, 168, 192, 224, 2210)
    private val counts = listOf(1, 2, 4, 5)
    private val insets = listOf(0, 2, 12, 18, 60)

    @Test
    fun `pill rect stays inside the bar for every size count index and inset`() {
        for (w in widths) for (h in heights) for (n in counts) {
            for (insetX in insets) for (insetY in insets) {
                val pillW = NavBarGeometry.pillWidthPx(w, n, insetX)
                val pillH = NavBarGeometry.pillHeightPx(h, insetY)
                assertTrue("width $pillW within [0,$w]", pillW in 0..w.coerceAtLeast(0))
                assertTrue("height $pillH within [0,$h]", pillH in 0..h.coerceAtLeast(0))
                val y = NavBarGeometry.pillTargetYPx(h, insetY)
                assertTrue("y $y + height $pillH <= bar $h", y + pillH <= h || h <= 0)
                for (index in -2..(n + 2)) {
                    val x = NavBarGeometry.pillTargetXPx(w, n, index, insetX)
                    assertTrue("x $x >= 0", x >= 0)
                    assertTrue(
                        "x $x + width $pillW <= bar $w (n=$n, index=$index, inset=$insetX)",
                        x + pillW <= w || w <= 0
                    )
                }
            }
        }
    }

    @Test
    fun `animated x is clamped inside the bar even when the spring overshoots`() {
        val barW = 1080
        val pillW = NavBarGeometry.pillWidthPx(barW, 4, 12)
        for (x in listOf(-500f, -1f, 0f, 300f, 1056f, 1080f, 5000f)) {
            val clamped = NavBarGeometry.clampPillXPx(x, barW, pillW)
            assertTrue(clamped >= 0f)
            assertTrue(clamped + pillW <= barW.toFloat())
        }
    }

    @Test
    fun `settled positions are one slot apart and in order`() {
        val barW = 1080
        val inset = 12
        val xs = (0 until 4).map { NavBarGeometry.pillTargetXPx(barW, 4, it, inset) }
        assertEquals(inset, xs.first())
        for (i in 1 until xs.size) {
            assertEquals(barW / 4, xs[i] - xs[i - 1])
        }
    }

    @Test
    fun `the shipped bar shape - 64dp tall - yields a bounded pill at phone densities`() {
        // 64dp bar at density 3.0 = 192px; v1.1 produced a ~2210px pill
        // for this slot. The geometry can only return <= the bar height.
        val barH = 192
        val pillH = NavBarGeometry.pillHeightPx(barH, 18)
        assertEquals(barH - 2 * 18, pillH)
        // And no input height can ever produce a taller pill than the bar.
        for (h in heights) {
            assertTrue(NavBarGeometry.pillHeightPx(h, 18) <= h.coerceAtLeast(0))
        }
    }

    @Test
    fun `unmeasured bar produces no pill`() {
        assertEquals(0, NavBarGeometry.pillWidthPx(0, 4, 12))
        assertEquals(0, NavBarGeometry.pillHeightPx(0, 18))
        assertEquals(0, NavBarGeometry.pillTargetXPx(0, 4, 2, 12))
    }
}
