package app.retranslate.providers.hub

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.providers.LANG_AUTO
import app.retranslate.providers.Languages
import app.retranslate.providers.Provider
import app.retranslate.providers.ProviderId
import app.retranslate.providers.TranslateRequest
import app.retranslate.providers.free.TextLengthLimited

/** Quick text (Translate tab) vs chapter work (P4 jobs). */
enum class TranslatePurpose { QUICK_TEXT, CHAPTER }

/** A successful routed translation, with its provenance (§4.4.3). */
data class RoutedTranslation(
    val text: String,
    val via: ProviderId,
    val viaName: String,
    val detectedSource: String?,
    /**
     * Display name of the chain's first provider when a DIFFERENT one
     * answered — the ViewModel turns this into the single "X is busy —
     * switched to Y" snackbar. Null when the first provider answered.
     */
    val switchedFromName: String?,
    val attempted: List<ProviderId>
)

/**
 * The cascading fallback chain (PLAN.md §4.4) — the heart of the app.
 *
 * Construction (§4.4.1): language-matrix pre-filter (a provider that
 * cannot do the pair is never called), short-text-only providers are
 * excluded from chapter work and oversized text, MyMemory is offered
 * only while its daily word budget lasts. Order is health order (§4.4.2
 * breakers + last success), static order breaking ties.
 *
 * Execution: each provider runs its own §3.4 attempt policy (3 attempts
 * max, in the provider base classes); the router moves on at the first
 * failure that survives them. The whole chain is deadline-capped (§4.5:
 * 45s quick text) — after the cap the user gets one plain error, never
 * an endless spinner. If literally every failure was "no network", the
 * error is [AppError.NoNetwork] (airplane mode speaks plainly, §10 #1).
 */
class ProviderRouter(
    private val providers: List<Provider>,
    private val health: ProviderHealth,
    private val myMemoryBudgetAvailable: suspend () -> Boolean = { true },
    /**
     * Chain tier per provider (§4.4.1, P2): lower runs earlier; health
     * order applies WITHIN a tier. The default (all one tier) preserves
     * the P1 free-chain behaviour exactly.
     */
    private val tierOf: (Provider) -> Int = { 0 },
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {

    suspend fun translate(
        text: String,
        source: String,
        target: String,
        purpose: TranslatePurpose = TranslatePurpose.QUICK_TEXT,
        /**
         * P4: the book's quality mode (§5.3) — AI providers pick their
         * model from it. Quick text keeps the BALANCED default.
         */
        quality: app.retranslate.providers.QualityMode =
            app.retranslate.providers.QualityMode.BALANCED,
        /**
         * P4/P5: AI context (Knowledge Base system prompt, §6.4). Only
         * KEY_AI providers consume it; classic providers ignore it (§4.1).
         */
        context: app.retranslate.providers.AiContext? = null,
        /**
         * v1.1 (the user's provider picker): a manually chosen provider
         * for QUICK_TEXT only, attempted FIRST; the chain then continues
         * exactly as without it. Null (and any CHAPTER call) keeps the
         * v1.0 behaviour bit-for-bit. A chosen provider that cannot serve
         * the pair, or is resting in cooldown, is skipped silently — the
         * request never fails because of the preference.
         */
        preferredProvider: ProviderId? = null
    ): AppResult<RoutedTranslation> {
        if (source != LANG_AUTO && source == target) {
            return AppResult.Err(AppError.SameLanguage(Languages.nameOf(target)))
        }
        health.ensureLoaded()

        val myMemoryOk = myMemoryBudgetAvailable()
        val candidates = providers.filter { provider ->
            provider.supportsPair(source, target) &&
                lengthAllowed(provider, text) &&
                (provider.id != ProviderId.MYMEMORY || myMemoryOk)
        }
        if (candidates.isEmpty()) {
            return AppResult.Err(
                AppError.UnsupportedLanguagePair(
                    Languages.nameOf(source),
                    Languages.nameOf(target)
                )
            )
        }
        val chainKeys = candidates.map { ProviderHealth.providerKey(it.id) }
        // Health order first, then a STABLE sort by tier (§4.4.1): tiers
        // reorder whole groups while health order survives inside each.
        var orderedProviders = health.orderKeys(chainKeys)
            .mapNotNull { key -> candidates.firstOrNull { ProviderHealth.providerKey(it.id) == key } }
            .sortedBy { tierOf(it) }
            .filter { !health.isInCooldown(ProviderHealth.providerKey(it.id)) }
        // v1.1 picker: the user's chosen provider leads the QUICK_TEXT
        // chain (a STABLE partition — everyone else keeps chain order).
        // switchedFromName below then truthfully names the chosen
        // provider when a different one ends up answering.
        if (purpose == TranslatePurpose.QUICK_TEXT && preferredProvider != null) {
            orderedProviders = orderedProviders.sortedBy {
                if (it.id == preferredProvider) 0 else 1
            }
        }
        if (orderedProviders.isEmpty()) {
            // Everything that could do this pair is resting (breaker).
            return AppResult.Err(AppError.AllProvidersFailed(candidates.map { it.id.name }))
        }

        val deadline = nowMs() + budgetMs(purpose)
        val request = TranslateRequest(
            text = text,
            source = source,
            target = target,
            context = context,
            quality = quality,
            deadlineMs = deadline
        )
        val attempted = mutableListOf<ProviderId>()
        val errors = mutableListOf<AppError>()
        for (provider in orderedProviders) {
            if (nowMs() >= deadline) break
            attempted += provider.id
            when (val result = provider.translate(request)) {
                is AppResult.Ok -> return AppResult.Ok(
                    RoutedTranslation(
                        text = result.value.text,
                        via = provider.id,
                        viaName = provider.displayName,
                        detectedSource = result.value.detectedSource,
                        switchedFromName = if (provider.id != orderedProviders.first().id) {
                            orderedProviders.first().displayName
                        } else {
                            null
                        },
                        attempted = attempted.toList()
                    )
                )
                is AppResult.Err -> errors += result.error
            }
        }
        if (errors.isNotEmpty() && errors.all { it is AppError.NoNetwork }) {
            return AppResult.Err(AppError.NoNetwork)
        }
        return AppResult.Err(AppError.AllProvidersFailed(attempted.map { it.name }))
    }

    private fun lengthAllowed(provider: Provider, text: String): Boolean =
        (provider as? TextLengthLimited)?.let { text.length <= it.maxTextChars } ?: true

    private fun budgetMs(purpose: TranslatePurpose): Long = when (purpose) {
        TranslatePurpose.QUICK_TEXT -> QUICK_CHAIN_BUDGET_MS
        TranslatePurpose.CHAPTER -> CHAPTER_CHAIN_BUDGET_MS
    }

    companion object {
        /** §4.5: the quick-text chain as a whole never runs longer than this. */
        const val QUICK_CHAIN_BUDGET_MS = 45_000L

        /**
         * Chapter calls get a longer chain budget (free classics answer in
         * seconds when alive; the AI providers' own §4.5 budgets govern
         * their attempts from P2/P4).
         */
        const val CHAPTER_CHAIN_BUDGET_MS = 120_000L
    }
}
