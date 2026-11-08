package app.retranslate.providers.hub

import app.retranslate.providers.Provider
import app.retranslate.providers.ProviderId

/**
 * Chain tiers (PLAN.md §4.4.1, decision 2026-10-27): the router sorts
 * candidates by tier first, then by health within a tier.
 *
 * With a Gemini key, Gemini is the main engine — tier 0 — for quick
 * text AND books; keyed classics sit directly behind it; the free net
 * (Mozhi → Lingva → SimplyTranslate → LibreTranslate → MyMemory) is
 * the automatic fallback. A Gemini quota/auth failure simply continues
 * down the tiers with the standard "switched provider" snackbar.
 *
 * Without a Gemini key, the free net leads (health order) and keyed
 * classics follow — a DeepL/Azure key still helps, it just isn't the
 * main engine. Unconfigured keyed providers never reach the tier sort
 * at all: their `supportsPair` is false, so the pre-filter drops them.
 */
object ChainOrdering {

    fun tierOf(provider: Provider, geminiKeyPresent: Boolean): Int = when (provider.id) {
        ProviderId.GEMINI -> 0
        ProviderId.DEEPL, ProviderId.AZURE, ProviderId.OPENAI, ProviderId.CLAUDE,
        ProviderId.APINEX, ProviderId.CUSTOM ->
            if (geminiKeyPresent) 1 else 2
        ProviderId.MOZHI, ProviderId.LINGVA, ProviderId.SIMPLYTRANSLATE,
        ProviderId.LIBRETRANSLATE, ProviderId.MYMEMORY ->
            if (geminiKeyPresent) 2 else 0
        ProviderId.OLLAMA -> 3
        // §H: Offline is never IN the automatic chain (never silently
        // substituted — §H rule 4); the tier exists only so the when
        // stays exhaustive if a list ever contains it.
        ProviderId.OFFLINE -> 3
    }
}
