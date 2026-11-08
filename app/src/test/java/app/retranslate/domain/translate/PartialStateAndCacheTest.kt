package app.retranslate.domain.translate

import app.retranslate.domain.books.FakeCachedTranslationDao
import app.retranslate.data.db.CachedTranslationEntity
import app.retranslate.providers.QualityMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** PLAN.md §5.5/§7.4: partial-state durability + the translation cache. */
class PartialStateAndCacheTest {

    @Test
    fun `partial state round-trips`() {
        val state = PartialChapterState(
            sourceHash = "abc",
            totalChunks = 3,
            done = mapOf(0 to "one", 2 to "three"),
            providers = mapOf(0 to "GEMINI", 2 to "MOZHI")
        )
        assertEquals(state, PartialChapterState.decode(state.encode()))
    }

    @Test
    fun `corrupt partial state decodes as no state`() {
        assertNull(PartialChapterState.decode("{not json"))
        assertNull(PartialChapterState.decode(null))
        assertNull(PartialChapterState.decode(""))
    }

    @Test
    fun `withChunk accumulates without mutating the original`() {
        val start = PartialChapterState("h", 2)
        val next = start.withChunk(0, "text", "GEMINI")
        assertEquals(emptyMap<Int, String>(), start.done)
        assertEquals(mapOf(0 to "text"), next.done)
        assertEquals(mapOf(0 to "GEMINI"), next.providers)
    }

    @Test
    fun `cache hit returns text and provenance`() = runTest {
        val dao = FakeCachedTranslationDao()
        val cache = TranslationCache(dao) { 42L }
        cache.put("원문", "ko", "en", QualityMode.BALANCED, null, "GEMINI", "translation")
        val hit = cache.get("원문", "ko", "en", QualityMode.BALANCED, null)
        assertEquals("translation", hit?.text)
        assertEquals("GEMINI", hit?.providerId)
        // What was stored carries the §5.5 provenance fields.
        val stored: CachedTranslationEntity? =
            dao.getByHash(cache.hash("원문", "ko", "en", QualityMode.BALANCED, null))
        assertEquals("GEMINI", stored?.providerId)
        assertEquals(42L, stored?.createdAt)
    }

    @Test
    fun `cache misses on any key difference`() = runTest {
        val cache = TranslationCache(FakeCachedTranslationDao()) { 0L }
        cache.put("원문", "ko", "en", QualityMode.BALANCED, 1, "GEMINI", "translation")
        assertNull(cache.get("원문", "ko", "en", QualityMode.BALANCED, 2)) // KB changed
        assertNull(cache.get("원문", "ko", "en", QualityMode.FAST, 1)) // quality changed
        assertNull(cache.get("다른 원문", "ko", "en", QualityMode.BALANCED, 1))
        assertNull(cache.get("원문", "ko", "ja", QualityMode.BALANCED, 1))
    }

    @Test
    fun `hash is stable and input-sensitive`() {
        val cache = TranslationCache(FakeCachedTranslationDao()) { 0L }
        val a = cache.hash("text", "ko", "en", QualityMode.BALANCED, null)
        assertEquals(a, cache.hash("text", "ko", "en", QualityMode.BALANCED, null))
        assertNotEquals(a, cache.hash("text2", "ko", "en", QualityMode.BALANCED, null))
        assertEquals(64, a.length) // SHA-256 hex
    }
}
