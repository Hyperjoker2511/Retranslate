package app.retranslate.providers.hub

import app.retranslate.providers.Language
import app.retranslate.providers.ProviderId
import java.util.concurrent.ConcurrentHashMap

/** A provider's fetched language list with its fetch time (§4.6: 7-day TTL). */
data class CachedLanguageList(
    val languages: List<Language>,
    val fetchedAtMs: Long
)

/**
 * Persistence seam for provider language lists (PLAN.md §4.6). P1 cached
 * Lingva's list in memory only; P3 adds the Room-backed implementation
 * ([app.retranslate.data.db.RoomLanguageListCache]) so the cache
 * survives process death, per the §4.6 design. Providers consult their
 * in-memory copy first and this layer second.
 */
interface LanguageListCache {
    suspend fun get(providerId: ProviderId): CachedLanguageList?
    suspend fun put(providerId: ProviderId, languages: List<Language>, fetchedAtMs: Long)
}

/** Non-persistent implementation — tests and providers without a store. */
class InMemoryLanguageListCache : LanguageListCache {
    private val entries = ConcurrentHashMap<ProviderId, CachedLanguageList>()

    override suspend fun get(providerId: ProviderId): CachedLanguageList? = entries[providerId]

    override suspend fun put(
        providerId: ProviderId,
        languages: List<Language>,
        fetchedAtMs: Long
    ) {
        entries[providerId] = CachedLanguageList(languages, fetchedAtMs)
    }
}
