package app.retranslate.domain.translate

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.providers.FakeClock
import app.retranslate.providers.FakeHealthStore
import app.retranslate.providers.Language
import app.retranslate.providers.Provider
import app.retranslate.providers.ProviderId
import app.retranslate.providers.QualityMode
import app.retranslate.providers.TranslateRequest
import app.retranslate.providers.Translation
import app.retranslate.providers.hub.ProviderHealth
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class MergeScriptedProvider(
    override val id: ProviderId,
    override val displayName: String,
    var result: AppResult<Translation>
) : Provider {
    override val kind: Provider.Kind = Provider.Kind.KEY_AI
    val calls = mutableListOf<TranslateRequest>()

    override fun supportsPair(source: String, target: String): Boolean = true
    override suspend fun supportedLanguages(): AppResult<List<Language>> =
        AppResult.Ok(emptyList())

    override suspend fun translate(req: TranslateRequest): AppResult<Translation> {
        calls += req
        return result
    }

    override suspend fun testConnection(): AppResult<Unit> = AppResult.Ok(Unit)
}

/** The §5.6 judge, scripted: a fixed answer, garbage, or a failure. */
private class FakeJudge(
    var answer: AppResult<String>,
    var name: String? = "Gemini"
) : MergeAi {
    var calls = 0
    var lastUserPrompt: String? = null

    override fun judgeName(): String? = name

    override suspend fun complete(
        systemPrompt: String,
        userPrompt: String,
        targetLang: String,
        quality: QualityMode
    ): AppResult<String> {
        calls++
        lastUserPrompt = userPrompt
        return answer
    }
}

/**
 * PLAN.md §5.6 + §11 P7 gate: merge orchestration with scripted
 * candidates — judge success, judge garbage → best-single fallback,
 * judge down → fallback, cap reached (no calls at all), too few
 * candidates, and no judge configured.
 */
class MergeOrchestratorTest {

    private val clock = FakeClock(now = 1_000L)
    private val health = ProviderHealth(FakeHealthStore(), clock.lambda)

    private var mergesUsed = 0
    private var cap = 100
    private var recordCount = 0

    private fun ok(id: ProviderId, text: String) =
        AppResult.Ok(Translation(text, id))

    private fun orchestrator(
        providers: List<MergeScriptedProvider>,
        judge: FakeJudge
    ) = MergeOrchestrator(
        providers = providers,
        health = health,
        tierOf = { provider ->
            when (provider.id) {
                ProviderId.GEMINI -> 0
                ProviderId.DEEPL -> 1
                else -> 2
            }
        },
        judge = judge,
        mergesUsedToday = { mergesUsed },
        dailyCap = { cap },
        recordMergeUsed = { recordCount++; mergesUsed++ },
        nowMs = clock.lambda
    )

    private fun threeProviders(): List<MergeScriptedProvider> = listOf(
        MergeScriptedProvider(ProviderId.GEMINI, "Gemini", ok(ProviderId.GEMINI, "Hello, world.")),
        MergeScriptedProvider(ProviderId.DEEPL, "DeepL", ok(ProviderId.DEEPL, "Hello world!")),
        MergeScriptedProvider(ProviderId.MOZHI, "Mozhi", ok(ProviderId.MOZHI, "Hello, the world."))
    )

    @Test
    fun `judge merges three candidates into one result`() = runTest {
        val judge = FakeJudge(AppResult.Ok("Hello, world — merged and natural."))
        val providers = threeProviders()
        val outcome = orchestrator(providers, judge).merge("안녕, 세상", "ko", "en")

        val merged = outcome as MergeOutcome.Merged
        assertEquals("Hello, world — merged and natural.", merged.text)
        assertEquals(3, merged.candidates.size)
        assertEquals(1, merged.mergesUsedToday)
        // The judge saw all three candidates and the source text.
        assertEquals(1, judge.calls)
        assertTrue(judge.lastUserPrompt!!.contains("Hello world!"))
        assertTrue(judge.lastUserPrompt!!.contains("Hello, the world."))
        assertTrue(judge.lastUserPrompt!!.contains("안녕, 세상"))
        // Exactly one merge charged.
        assertEquals(1, recordCount)
        assertTrue(providers.all { it.calls.size == 1 })
    }

    @Test
    fun `judge garbage falls back to the best single candidate`() = runTest {
        // Leftover source script in the "merged" text = sanity-check
        // failure (long enough to trip the verifier's leftover gate).
        val source = "안녕하세요, 세상입니다. 이것은 시험 문장입니다."
        val judge = FakeJudge(AppResult.Ok(source))
        val outcome = orchestrator(threeProviders(), judge).merge(source, "ko", "en")

        val single = outcome as MergeOutcome.BestSingle
        assertEquals(ProviderId.GEMINI, single.candidate.providerId)
        assertEquals("Hello, world.", single.candidate.text)
        assertEquals(3, single.candidates.size)
        // The judge WAS invoked, so the merge counts as spent.
        assertEquals(1, recordCount)
    }

    @Test
    fun `judge down falls back to the best single candidate`() = runTest {
        val judge = FakeJudge(
            AppResult.Err(AppError.RateLimited("GEMINI", null))
        )
        val outcome = orchestrator(threeProviders(), judge).merge("안녕, 세상", "ko", "en")

        val single = outcome as MergeOutcome.BestSingle
        assertEquals("Hello, world.", single.candidate.text)
        assertEquals(1, recordCount)
    }

    @Test
    fun `cap reached runs nothing and reports the cap`() = runTest {
        mergesUsed = 100
        cap = 100
        val judge = FakeJudge(AppResult.Ok("anything"))
        val providers = threeProviders()
        val outcome = orchestrator(providers, judge).merge("안녕, 세상", "ko", "en")

        assertEquals(
            MergeOutcome.UsePlainChain(MergeSkipReason.CAP_REACHED), outcome
        )
        assertEquals(0, judge.calls)
        assertEquals(0, recordCount)
        assertTrue(providers.all { it.calls.isEmpty() })
    }

    @Test
    fun `no judge configured skips before any call`() = runTest {
        val judge = FakeJudge(AppResult.Ok("anything"), name = null)
        val providers = threeProviders()
        val outcome = orchestrator(providers, judge).merge("안녕, 세상", "ko", "en")

        assertEquals(MergeOutcome.UsePlainChain(MergeSkipReason.NO_JUDGE), outcome)
        assertTrue(providers.all { it.calls.isEmpty() })
        assertEquals(0, recordCount)
    }

    @Test
    fun `fewer than two candidates skips to the plain chain`() = runTest {
        val providers = listOf(
            MergeScriptedProvider(ProviderId.GEMINI, "Gemini", ok(ProviderId.GEMINI, "Hello, world.")),
            MergeScriptedProvider(
                ProviderId.DEEPL, "DeepL",
                AppResult.Err(AppError.ProviderDown("DEEPL", 503))
            ),
            MergeScriptedProvider(
                ProviderId.MOZHI, "Mozhi",
                AppResult.Err(AppError.NoNetwork)
            )
        )
        val judge = FakeJudge(AppResult.Ok("merged"))
        val outcome = orchestrator(providers, judge).merge("안녕, 세상", "ko", "en")

        assertEquals(
            MergeOutcome.UsePlainChain(MergeSkipReason.TOO_FEW_CANDIDATES), outcome
        )
        // The judge is the spend — it never ran, so nothing was charged.
        assertEquals(0, judge.calls)
        assertEquals(0, recordCount)
    }

    @Test
    fun `two candidates are enough to merge`() = runTest {
        val providers = listOf(
            MergeScriptedProvider(ProviderId.GEMINI, "Gemini", ok(ProviderId.GEMINI, "Hello, world.")),
            MergeScriptedProvider(
                ProviderId.DEEPL, "DeepL",
                AppResult.Err(AppError.ProviderDown("DEEPL", 503))
            ),
            MergeScriptedProvider(ProviderId.MOZHI, "Mozhi", ok(ProviderId.MOZHI, "Hello, the world."))
        )
        val judge = FakeJudge(AppResult.Ok("Hello, world, naturally."))
        val outcome = orchestrator(providers, judge).merge("안녕, 세상", "ko", "en")

        val merged = outcome as MergeOutcome.Merged
        assertEquals(2, merged.candidates.size)
        assertEquals("Hello, world, naturally.", merged.text)
    }
}
