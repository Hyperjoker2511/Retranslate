package app.retranslate.providers.hub

import app.retranslate.providers.Language
import app.retranslate.providers.LanguageMapping
import app.retranslate.providers.Languages
import app.retranslate.providers.ProviderId
import app.retranslate.providers.ProviderLanguageMaps

/**
 * The language matrix façade (PLAN.md §4.6): what the UI pickers show
 * and what tests assert mappings against. Per-provider pair support
 * itself lives on each provider (`supportsPair`, backed by
 * [ProviderLanguageMaps]); the Room-backed 7-day language-list cache
 * joins here when Room lands in P3 — until then Lingva's list is cached
 * in memory and the other providers serve the canonical list.
 */
object LanguageMatrix {

    /** Languages the pickers offer as a target (union across the hub). */
    fun targetLanguages(): List<Language> = Languages.ALL

    /** Languages the pickers offer as a source (Auto detect first). */
    fun sourceLanguages(): List<Language> = listOf(Languages.AUTO) + Languages.ALL

    fun mappingFor(id: ProviderId): LanguageMapping? = ProviderLanguageMaps.forId(id)
}
