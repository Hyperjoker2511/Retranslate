package app.retranslate.domain.translate

import app.retranslate.core.error.AppResult
import app.retranslate.providers.FakeClock
import app.retranslate.providers.FakeHealthStore
import app.retranslate.providers.hub.ProviderHealth
import app.retranslate.providers.hub.ProviderRouter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.1.3 (§C): the standalone pasted-chapter flow — the mini-base is
 * built FIRST (here: the free name scan, no AI key), and the MTL path
 * fixes the chapter's own inconsistent name spellings locally, in
 * plain words, without pretending an AI ran.
 */
class ChapterMakeBetterTest {

    private val clock = FakeClock(now = 10_000L)

    private fun makeBetter(): ChapterMakeBetter {
        val router = ProviderRouter(
            providers = emptyList(),
            health = ProviderHealth(FakeHealthStore(), clock.lambda),
            nowMs = clock.lambda
        )
        return ChapterMakeBetter(
            largeText = LargeTextTranslator(router),
            polishAi = null
        )
    }

    private val mtl = "Jin Woo drew his blade. Jin Woo smiled at the elder. " +
        "Jinwoo nodded once. Jin Woo turned away. Jinwoo followed him out."

    @Test
    fun `name scan groups spelling variants under the dominant form`() {
        val drafts = NameFrequencyScan.scan(mtl)
        assertEquals(1, drafts.size)
        assertEquals("Jin Woo", drafts[0].canonicalForm)
        assertEquals(5, drafts[0].frequency)
    }

    @Test
    fun `mtl without a key fixes spellings locally and says so`() = runTest {
        val outcome = makeBetter().makeBetter(
            text = mtl,
            kind = ChapterTextKind.MTL,
            sourceLang = "en",
            targetLang = "en"
        )
        assertTrue(outcome is AppResult.Ok)
        val value = (outcome as AppResult.Ok).value
        assertFalse(value.text.contains("Jinwoo"))
        assertTrue(value.text.contains("Jin Woo nodded once."))
        assertFalse(value.base.fromAi)
        assertTrue(value.notes.any { it.contains("name spelling") })
        assertTrue(value.notes.any { it.contains("AI key") })
    }

    @Test
    fun `fixNameSpellings replaces variants with the canonical form`() {
        val entries = NameFrequencyScan.scan(mtl).map { draft ->
            app.retranslate.data.db.KbEntryEntity(
                kbId = 0, category = draft.category.name,
                sourceForm = null, canonicalForm = draft.canonicalForm,
                gender = null, note = null, locked = true,
                frequency = draft.frequency
            )
        }
        val (fixed, replacements) = ChapterMakeBetter.fixNameSpellings(mtl, entries)
        assertEquals(2, replacements)
        assertFalse(fixed.contains("Jinwoo"))
    }
}
