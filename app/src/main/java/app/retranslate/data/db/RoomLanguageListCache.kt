package app.retranslate.data.db

import app.retranslate.providers.Language
import app.retranslate.providers.ProviderId
import app.retranslate.providers.hub.CachedLanguageList
import app.retranslate.providers.hub.LanguageListCache

/**
 * Room-backed [LanguageListCache] (PLAN.md §4.6): fetched provider
 * language lists persist in the `provider_languages` table with their
 * fetch time; the owning provider applies the 7-day TTL.
 */
class RoomLanguageListCache(
    private val dao: ProviderLanguageDao
) : LanguageListCache {

    override suspend fun get(providerId: ProviderId): CachedLanguageList? {
        val rows = dao.getForProvider(providerId.name)
        if (rows.isEmpty()) return null
        return CachedLanguageList(
            languages = rows.map { Language(it.code, it.displayName) },
            fetchedAtMs = rows.maxOf { it.fetchedAt }
        )
    }

    override suspend fun put(
        providerId: ProviderId,
        languages: List<Language>,
        fetchedAtMs: Long
    ) {
        dao.deleteForProvider(providerId.name)
        dao.insertAll(
            languages.map {
                ProviderLanguageEntity(
                    providerId = providerId.name,
                    code = it.code,
                    displayName = it.name,
                    fetchedAt = fetchedAtMs
                )
            }
        )
    }
}
