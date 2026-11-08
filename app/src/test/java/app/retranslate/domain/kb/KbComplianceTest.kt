package app.retranslate.domain.kb

import app.retranslate.data.db.KbEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLAN.md §6.5: spacing-variant detection + the re-apply replacement. */
class KbComplianceTest {

    private fun entry(canonical: String, category: String = "CHARACTER", id: Long = 1) =
        KbEntryEntity(
            id = id, kbId = 1, category = category, sourceForm = null,
            canonicalForm = canonical, gender = null, note = null,
            locked = false, frequency = 5
        )

    @Test
    fun `variants are the spacing and hyphen slips`() {
        val variants = KbCompliance.variantsOf("Jin Woo")
        assertTrue("Jinwoo" in variants)
        assertTrue("Jin-Woo" in variants)
        assertTrue("Jin Woo" !in variants)
    }

    @Test
    fun `single word names have no variants`() {
        assertTrue(KbCompliance.variantsOf("Jinwoo").isEmpty())
    }

    @Test
    fun `finds the spaceless slip and counts it`() {
        val violations = KbCompliance.findViolations(
            "Jinwoo drew his sword. Later, Jinwoo smiled.",
            listOf(entry("Jin Woo"))
        )
        assertEquals(1, violations.size)
        assertEquals("Jinwoo", violations[0].variantFound)
        assertEquals(2, violations[0].occurrences)
        assertEquals("Jin Woo", violations[0].canonicalForm)
    }

    @Test
    fun `the correct rendering is not a violation`() {
        val violations = KbCompliance.findViolations(
            "Jin Woo drew his sword and Jin Woo smiled.",
            listOf(entry("Jin Woo"))
        )
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `no false positive inside a longer word`() {
        val violations = KbCompliance.findViolations(
            "The JinwooMart store opened late.", // "Jinwoo" glued to more letters
            listOf(entry("Jin Woo"))
        )
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `tone rules are never checked`() {
        val violations = KbCompliance.findViolations(
            "Anything at all.",
            listOf(entry("Some Rule", category = "TONE_RULE"))
        )
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `replaceVariant rewrites whole words only and counts`() {
        val (text, count) = KbCompliance.replaceVariant(
            "Jinwoo left. JinwooMart stayed. Jinwoo returned.",
            "Jinwoo", "Jin Woo"
        )
        assertEquals(2, count)
        assertEquals("Jin Woo left. JinwooMart stayed. Jin Woo returned.", text)
    }
}
