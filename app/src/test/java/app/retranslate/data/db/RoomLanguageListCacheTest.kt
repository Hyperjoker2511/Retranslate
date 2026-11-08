package app.retranslate.data.db

import app.retranslate.domain.books.FakeProviderLanguageDao
import app.retranslate.providers.Language
import app.retranslate.providers.ProviderId
import app.retranslate.providers.free.LingvaProvider
import app.retranslate.providers.hub.InMemoryLanguageListCache
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §4.6's deferred item from P1: the provider language-list cache now
 * persists (Room-backed in production; a fake DAO here). The Lingva
 * half proves a list fetched before a "process death" (a fresh
 * provider instance) is adopted without any network call.
 */
class RoomLanguageListCacheTest {

    @Test
    fun `round trip persists languages with fetch time`() = runTest {
        val dao = FakeProviderLanguageDao()
        val cache = RoomLanguageListCache(dao)
        assertNull(cache.get(ProviderId.LINGVA))
        cache.put(
            ProviderId.LINGVA,
            listOf(Language("ko", "Korean"), Language("en", "English")),
            fetchedAtMs = 42_000L
        )
        val got = cache.get(ProviderId.LINGVA)!!
        assertEquals(42_000L, got.fetchedAtMs)
        assertEquals(listOf("ko", "en"), got.languages.map { it.code })
    }

    @Test
    fun `put replaces the previous list`() = runTest {
        val dao = FakeProviderLanguageDao()
        val cache = RoomLanguageListCache(dao)
        cache.put(ProviderId.LINGVA, listOf(Language("ko", "Korean")), 1L)
        cache.put(ProviderId.LINGVA, listOf(Language("ja", "Japanese")), 2L)
        val got = cache.get(ProviderId.LINGVA)!!
        assertEquals(listOf("ja"), got.languages.map { it.code })
        assertEquals(2L, got.fetchedAtMs)
    }

    @Test
    fun `Lingva adopts a fresh persisted list without network`() = runTest {
        val cache = InMemoryLanguageListCache()
        val persisted = listOf(Language("ko", "Korean"), Language("en", "English"))
        cache.put(ProviderId.LINGVA, persisted, fetchedAtMs = 1_000_000L)

        // Transport that fails the test if touched.
        val explodingTransport = object : app.retranslate.core.network.HttpTransport {
            override suspend fun get(
                url: String,
                headers: Map<String, String>
            ): app.retranslate.core.error.AppResult<app.retranslate.core.network.HttpResponse> =
                throw AssertionError("network must not be called")

            override suspend fun post(
                url: String,
                jsonBody: String,
                headers: Map<String, String>
            ): app.retranslate.core.error.AppResult<app.retranslate.core.network.HttpResponse> =
                throw AssertionError("network must not be called")
        }
        val provider = LingvaProvider(
            transport = explodingTransport,
            health = app.retranslate.providers.hub.ProviderHealth(
                app.retranslate.providers.FakeHealthStore()
            ),
            instanceStore = app.retranslate.providers.FakeInstanceStore(
                mapOf(ProviderId.LINGVA to listOf("https://lingva.example"))
            ),
            nowMs = { 1_000_000L + 60_000L }, // one minute later: still fresh
            languageCache = cache
        )
        val result = provider.supportedLanguages()
        assertTrue(result is app.retranslate.core.error.AppResult.Ok)
        assertEquals(
            persisted,
            (result as app.retranslate.core.error.AppResult.Ok).value
        )
    }
}
