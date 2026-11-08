package app.retranslate.domain.kb

import app.retranslate.data.db.KbEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLAN.md §6.4: the injection block — order, tone rules, 6,000-char cap. */
class KbInjectionTest {

    private var nextId = 0L
    private fun entry(
        canonical: String,
        category: String = "TERM",
        locked: Boolean = false,
        frequency: Int = 1,
        gender: String? = null,
        sourceForm: String? = null,
        note: String? = null
    ) = KbEntryEntity(
        id = ++nextId, kbId = 1, category = category, sourceForm = sourceForm,
        canonicalForm = canonical, gender = gender, note = note,
        locked = locked, frequency = frequency
    )

    @Test
    fun `line format carries gender, original form, note and lock`() {
        val line = KbInjection.lineFor(
            entry(
                "Jin Woo", category = "CHARACTER", gender = "MALE",
                sourceForm = "진우", note = "the protagonist", locked = true
            )
        )
        assertEquals("Jin Woo (male) — original: 진우; the protagonist [locked]", line)
    }

    @Test
    fun `tone rules ride first and are capped at five lines`() {
        val entries = (1..8).map { entry("Rule number $it", category = "TONE_RULE") } +
            entry("Jin Woo", category = "CHARACTER")
        val block = KbInjection.buildBlock(entries)
        val lines = block.lines()
        assertTrue(lines.first().startsWith("Rule: Rule number"))
        // 5 rule lines + 1 "(and 3 more…)" summary + the character line.
        assertEquals(7, lines.size)
        assertTrue(lines.any { it.contains("3 more tone rules") })
        assertEquals("Jin Woo", lines.last())
    }

    @Test
    fun `locked entries sort before frequent ones`() {
        val entries = listOf(
            entry("Popular", frequency = 99),
            entry("Pinned", locked = true, frequency = 1)
        )
        val lines = KbInjection.buildBlock(entries).lines()
        assertEquals("Pinned [locked]", lines.first())
        assertEquals("Popular", lines.last())
    }

    @Test
    fun `block never exceeds the cap and summarises what dropped`() {
        val entries = (1..300).map { n ->
            entry("A fairly long canonical name number $n of the academy", frequency = n)
        }
        val block = KbInjection.buildBlock(entries)
        assertTrue(
            "block was ${block.length} chars",
            block.length <= KbInjection.MAX_BLOCK_CHARS
        )
        assertEquals(6_000, KbInjection.MAX_BLOCK_CHARS)
        assertTrue(block.contains("more minor entries"))
        // The highest-frequency entries survive; the tail is summarised.
        assertTrue(block.contains("number 300"))
        assertTrue(!block.contains("number 1 of"))
    }

    @Test
    fun `empty KB gives an empty block`() {
        assertEquals("", KbInjection.buildBlock(emptyList()))
    }

    @Test
    fun `style rules are part of the tone lines`() {
        val entries = listOf(
            entry(
                "Dialogue uses contractions and sounds spoken, not written.",
                category = "TONE_RULE"
            ),
            entry("Jin Woo", category = "CHARACTER")
        )
        val block = KbInjection.buildBlock(entries)
        assertTrue(block.startsWith("Rule: Dialogue uses contractions"))
    }
}
