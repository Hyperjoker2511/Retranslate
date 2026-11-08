package app.retranslate.domain.translate

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.providers.AiContext
import app.retranslate.providers.LANG_AUTO
import app.retranslate.providers.Provider
import app.retranslate.providers.ProviderId
import app.retranslate.providers.QualityMode
import app.retranslate.providers.TranslateRequest
import app.retranslate.providers.hub.ProviderHealth
import app.retranslate.providers.hub.ProviderRouter

/**
 * The one AI call the merge needs (§5.6): the judge. An interface so
 * the orchestrator is JVM-testable with a scripted fake — and so the
 * judge, like study and polish, NEVER goes through the fallback
 * router: a classic MT provider cannot judge translations, it would
 * just translate the judge prompt. Production binds this to the
 * first configured AI provider (Gemini, then OpenAI, then Claude —
 * §5.6 names Gemini the judge, with OpenAI/Claude standing in when
 * they are the AI the user set up).
 */
interface MergeAi {
    /** The judge's display name, or null when no AI key exists. */
    fun judgeName(): String?

    fun isConfigured(): Boolean = judgeName() != null

    suspend fun complete(
        systemPrompt: String,
        userPrompt: String,
        targetLang: String,
        quality: QualityMode
    ): AppResult<String>
}

/** [MergeAi] over the first configured KEY_AI provider. */
class ProviderMergeAi(
    private val judgeOf: () -> Provider?
) : MergeAi {

    override fun judgeName(): String? = judgeOf()?.displayName

    override suspend fun complete(
        systemPrompt: String,
        userPrompt: String,
        targetLang: String,
        quality: QualityMode
    ): AppResult<String> {
        val judge = judgeOf()
            ?: return AppResult.Err(AppError.AuthFailed(ProviderId.GEMINI.name))
        return judge.translate(
            TranslateRequest(
                text = userPrompt,
                source = LANG_AUTO,
                target = targetLang,
                context = AiContext(systemPrompt),
                quality = quality
            )
        ).map { it.text }
    }
}

/** One fetched candidate translation (§5.6), kept for the viewer. */
data class MergeCandidate(
    val providerId: ProviderId,
    val providerName: String,
    val text: String,
    val detectedSource: String? = null
)

/** Why merge didn't run — the ViewModel falls back to the plain chain. */
enum class MergeSkipReason { CAP_REACHED, NO_JUDGE, TOO_FEW_CANDIDATES, ALL_CANDIDATES_FAILED }

sealed interface MergeOutcome {
    /** The judge merged the candidates into one best result (§5.6). */
    data class Merged(
        val text: String,
        val candidates: List<MergeCandidate>,
        /** Merges spent today AFTER this one (the cap line in words). */
        val mergesUsedToday: Int
    ) : MergeOutcome

    /**
     * The judge failed or its output failed the sanity checks: the
     * best single candidate is the result (§5.6 fallback — the user
     * always gets a translation; merge is never a failure point).
     */
    data class BestSingle(
        val candidate: MergeCandidate,
        val candidates: List<MergeCandidate>
    ) : MergeOutcome

    /** Merge could not run at all — use the plain §4.4 chain instead. */
    data class UsePlainChain(val reason: MergeSkipReason) : MergeOutcome
}

/**
 * AI Compare & Merge (PLAN.md §5.6): fetch up to [MAX_CANDIDATES]
 * translations of the same text (Gemini + the best two other routes
 * for the pair, in the router's own tier+health order), let one AI
 * judge merge them into a single best result, and sanity-check the
 * merged text with the §5.2 verifier basics before believing it.
 * Every failure degrades one honest step: judge down or garbage →
 * best single candidate; fewer than two candidates → the caller
 * runs the plain chain. Quick Translate only — book chapters keep
 * their stronger KB pipeline (§5.6).
 *
 * Quota (§5.6): a spent merge is 3 candidate calls + 1 judge call,
 * all charged by the providers' own usage recorders. The daily soft
 * cap is checked BEFORE any call; a merge counts as spent the moment
 * the judge is invoked (its call is the expensive one).
 */
class MergeOrchestrator(
    private val providers: List<Provider>,
    private val health: ProviderHealth,
    private val tierOf: (Provider) -> Int,
    private val judge: MergeAi,
    private val mergesUsedToday: suspend () -> Int,
    private val dailyCap: suspend () -> Int,
    private val recordMergeUsed: suspend () -> Unit,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {

    suspend fun merge(
        text: String,
        source: String,
        target: String,
        quality: QualityMode = QualityMode.BALANCED
    ): MergeOutcome {
        if (!judge.isConfigured()) return MergeOutcome.UsePlainChain(MergeSkipReason.NO_JUDGE)
        val used = mergesUsedToday()
        if (used >= dailyCap()) return MergeOutcome.UsePlainChain(MergeSkipReason.CAP_REACHED)

        health.ensureLoaded()
        val eligible = providers.filter { provider ->
            provider.supportsPair(source, target) &&
                !health.isInCooldown(ProviderHealth.providerKey(provider.id))
        }
        if (eligible.isEmpty()) {
            return MergeOutcome.UsePlainChain(MergeSkipReason.ALL_CANDIDATES_FAILED)
        }
        val ordered = health.orderKeys(eligible.map { ProviderHealth.providerKey(it.id) })
            .mapNotNull { key ->
                eligible.firstOrNull { ProviderHealth.providerKey(it.id) == key }
            }
            .sortedBy { tierOf(it) }
            .take(MAX_CANDIDATES)

        // One shared deadline (§4.5 quick-text budget): a slow
        // candidate must not let the set run away.
        val deadline = nowMs() + ProviderRouter.QUICK_CHAIN_BUDGET_MS
        val candidates = mutableListOf<MergeCandidate>()
        for (provider in ordered) {
            if (nowMs() >= deadline) break
            val result = provider.translate(
                TranslateRequest(
                    text = text,
                    source = source,
                    target = target,
                    quality = quality,
                    deadlineMs = deadline
                )
            )
            if (result is AppResult.Ok) {
                candidates += MergeCandidate(
                    providerId = provider.id,
                    providerName = provider.displayName,
                    text = result.value.text,
                    detectedSource = result.value.detectedSource
                )
            }
        }
        if (candidates.size < MIN_CANDIDATES) {
            return MergeOutcome.UsePlainChain(MergeSkipReason.TOO_FEW_CANDIDATES)
        }

        // The judge call is the spend: count it as it happens.
        recordMergeUsed()
        val judged = judge.complete(
            systemPrompt = AiPrompts.mergeSystemPrompt(),
            userPrompt = AiPrompts.mergeUserPrompt(
                sourceText = text,
                sourceLang = source,
                targetLang = target,
                candidates = candidates.map {
                    MergeCandidateText(providerName = it.providerName, text = it.text)
                }
            ),
            targetLang = target,
            quality = quality
        )
        val mergedText = (judged as? AppResult.Ok)?.value?.trim()
        if (mergedText.isNullOrBlank() ||
            ChapterVerifier.chunkIssues(text, mergedText, source, target).isNotEmpty()
        ) {
            // Judge down, or its answer failed the sanity checks:
            // the chain's first success is the result (§5.6).
            return MergeOutcome.BestSingle(
                candidate = candidates.first(),
                candidates = candidates.toList()
            )
        }
        return MergeOutcome.Merged(
            text = mergedText,
            candidates = candidates.toList(),
            mergesUsedToday = used + 1
        )
    }

    companion object {
        /** Gemini + the best 2 other routes for the pair (§5.6). */
        const val MAX_CANDIDATES = 3

        /** Merge needs at least two versions to be worth a judge. */
        const val MIN_CANDIDATES = 2
    }
}
