package app.retranslate.domain.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLAN.md §5.1: the fixed cleanup rule set — junk out, story untouched. */
class CleanupRulesTest {

    @Test
    fun `navigation labels are dropped`() {
        val text = "Chapter content here.\n\nNext Chapter\n\nMore content."
        assertEquals(
            "Chapter content here.\n\nMore content.",
            CleanupRules.clean(text)
        )
        assertTrue(CleanupRules.isJunk("이전 화"))
        assertTrue(CleanupRules.isJunk("下一章"))
        assertTrue(CleanupRules.isJunk("Table of Contents"))
    }

    @Test
    fun `site banners and url watermarks are dropped`() {
        val text = buildString {
            append("The hero drew his sword.\n\n")
            append("Read the latest chapters at mtlnovel.example.com\n\n")
            append("mtlnovel.example.com\n\n")
            append("He struck.")
        }
        val cleaned = CleanupRules.clean(text)
        assertEquals("The hero drew his sword.\n\nHe struck.", cleaned)
    }

    @Test
    fun `translator credit lines are dropped, story mentions stay`() {
        assertTrue(CleanupRules.isJunk("Translator: Night Owl"))
        assertFalse(
            CleanupRules.isJunk(
                "The translator of the ancient tablet stepped forward and " +
                    "began to read the inscription aloud to the court."
            )
        )
    }

    @Test
    fun `system windows are never junk`() {
        val window = "[Skill: Dragon's Breath has been acquired.]"
        assertFalse(CleanupRules.isJunk(window))
        assertEquals(window, CleanupRules.clean(window))
    }

    @Test
    fun `ordinary prose is untouched`() {
        val text = "그는 검을 뽑았다.\n\n다음 순간, 문이 열렸다."
        assertEquals(text, CleanupRules.clean(text))
    }

    @Test
    fun `blank input cleans to blank`() {
        assertEquals("", CleanupRules.clean("   \n\n  "))
    }
}
