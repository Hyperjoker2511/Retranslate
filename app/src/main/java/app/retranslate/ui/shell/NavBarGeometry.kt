package app.retranslate.ui.shell

/**
 * Pure geometry for the [FluidNavBar] selection pill (v1.1.1).
 *
 * Every output is clamped so the pill's rect stays inside the bar's
 * own measured bounds for ANY bar size, item count, index, inset, or
 * in-flight animated position (springs can overshoot their target).
 * This is the invariant the v1.1 bar violated by sizing the pill from
 * raw layout constraints instead of from bounded geometry: here the
 * pill can never be wider or taller than the bar, never start before
 * the bar's left edge, and never end past its right edge.
 *
 * Plain ints/floats only — no Compose types — so the invariant is
 * unit-testable on the JVM (see NavBarGeometryTest).
 */
object NavBarGeometry {

    /** Width of one tab slot in px; 0 until the bar has been measured. */
    fun slotWidthPx(barWidthPx: Int, itemCount: Int): Int =
        if (barWidthPx <= 0 || itemCount <= 0) 0 else barWidthPx / itemCount

    /** Pill width: one slot minus the horizontal insets, clamped to the bar. */
    fun pillWidthPx(barWidthPx: Int, itemCount: Int, horizontalInsetPx: Int): Int {
        if (barWidthPx <= 0) return 0
        val slot = slotWidthPx(barWidthPx, itemCount)
        return (slot - 2 * horizontalInsetPx).coerceIn(0, barWidthPx)
    }

    /** Pill height: the bar height minus the vertical insets, clamped to the bar. */
    fun pillHeightPx(barHeightPx: Int, verticalInsetPx: Int): Int {
        if (barHeightPx <= 0) return 0
        return (barHeightPx - 2 * verticalInsetPx).coerceIn(0, barHeightPx)
    }

    /**
     * The pill's settled left edge (px) for [index]: the slot start
     * plus the horizontal inset, clamped so the whole pill stays
     * inside the bar even for out-of-range indices.
     */
    fun pillTargetXPx(
        barWidthPx: Int,
        itemCount: Int,
        index: Int,
        horizontalInsetPx: Int
    ): Int {
        if (barWidthPx <= 0 || itemCount <= 0) return 0
        val width = pillWidthPx(barWidthPx, itemCount, horizontalInsetPx)
        val slot = slotWidthPx(barWidthPx, itemCount)
        val raw = slot * index.coerceIn(0, itemCount - 1) + horizontalInsetPx
        return raw.coerceIn(0, (barWidthPx - width).coerceAtLeast(0))
    }

    /** The pill's top edge (px): the vertical inset, clamped inside the bar. */
    fun pillTargetYPx(barHeightPx: Int, verticalInsetPx: Int): Int =
        verticalInsetPx.coerceIn(0, barHeightPx.coerceAtLeast(0))

    /**
     * Clamp an animated (possibly spring-overshooting) x so the pill
     * stays fully inside the bar on every frame of its travel.
     */
    fun clampPillXPx(xPx: Float, barWidthPx: Int, pillWidthPx: Int): Float =
        xPx.coerceIn(0f, (barWidthPx - pillWidthPx).coerceAtLeast(0).toFloat())
}
