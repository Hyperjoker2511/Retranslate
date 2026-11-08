package app.retranslate.core.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLAN.md §9.4: the prompt's exact motion constants + the reduce-motion rule. */
class MotionTokensTest {

    @Test
    fun `press scale and progress tween match the spec`() {
        assertEquals(0.97f, MotionTokens.PRESS_SCALE, 0.0001f)
        assertEquals(300, MotionTokens.PROGRESS_TWEEN_MS)
    }

    @Test
    fun `animations need both system motion and the user setting`() {
        assertTrue(MotionTokens.animationsEnabled(systemAnimatorScale = 1f, userEnabled = true))
        assertFalse(MotionTokens.animationsEnabled(systemAnimatorScale = 0f, userEnabled = true))
        assertFalse(MotionTokens.animationsEnabled(systemAnimatorScale = 1f, userEnabled = false))
        assertFalse(MotionTokens.animationsEnabled(systemAnimatorScale = 0f, userEnabled = false))
    }
}
