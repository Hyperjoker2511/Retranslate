package app.retranslate.domain.translate

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.data.db.BookEntity
import app.retranslate.data.db.ChapterEntity
import app.retranslate.domain.books.DirectTxRunner
import app.retranslate.domain.books.FakeCachedTranslationDao
import app.retranslate.domain.books.FakeChapterDao
import app.retranslate.providers.AiContext
import app.retranslate.providers.FakeClock
import app.retranslate.providers.FakeHealthStore
import app.retranslate.providers.Language
import app.retranslate.providers.Provider
import app.retranslate.providers.ProviderId
import app.retranslate.providers.QualityMode
import app.retranslate.providers.TranslateRequest
import app.retranslate.providers.Translation
import app.retranslate.providers.hub.ProviderHealth
import app.retranslate.providers.hub.ProviderRouter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A scripted provider: records requests, answers via [behaviour]. */
private class ScriptedProvider(
    override val id: ProviderId,
    override val displayName: String,
    val behaviour: suspend (TranslateRequest) -> AppResult<Translation>
) : Provider {
    override val kind: Provider.Kind = Provider.Kind.KEY_AI
    val calls = mutableListOf<TranslateRequest>()

    override fun supportsPair(source: String, target: String): Boolean = true
    override suspend fun supportedLanguages(): AppResult<List<Language>> =
        AppResult.Ok(emptyList())

    override suspend fun translate(req: TranslateRequest): AppResult<Translation> {
        calls += req
        return behaviour(req)
    }

    override suspend fun testConnection(): AppResult<Unit> = AppResult.Ok(Unit)
}

/** Deterministic fake translation: paragraph count preserved, no Hangul. */
private fun englishify(text: String): String =
    text.split("\n\n").joinToString("\n\n") { paragraph ->
        val marker = Regex("PART[A-Z]+|CH\\d+").find(paragraph)?.value ?: "TEXT"
        "English translation of $marker, told in full sentences with care."
    }

/** PLAN.md §5/§12.1: chunking, cache, verify, partial-resume at chapter level. */
class ChapterTranslatorTest {

    private val clock = FakeClock(now = 1_000L)
    private val chapterDao = FakeChapterDao()
    private val cacheDao = FakeCachedTranslationDao()

    private fun translator(
        provider: ScriptedProvider,
        pacer: RequestPacer = RequestPacer({ 0L }, {}, clock.lambda)
    ): ChapterTranslator {
        val router = ProviderRouter(
            listOf(provider),
            ProviderHealth(FakeHealthStore(), clock.lambda),
            nowMs = clock.lambda
        )
        return ChapterTranslator(
            router = router,
            chapterDao = chapterDao,
            cache = TranslationCache(cacheDao) { clock.now },
            tx = DirectTxRunner,
            pacer = pacer,
            clock = clock.lambda
        )
    }

    private fun book(quality: String = "BALANCED") = BookEntity(
        id = 1, titleOriginal = "소설", sourceLang = "ko", targetLang = "en",
        qualityMode = quality, createdAt = 0, updatedAt = 0
    )

    private suspend fun chapter(number: Int, source: String): ChapterEntity {
        val id = chapterDao.insert(
            ChapterEntity(bookId = 1, number = number, sourceText = source, updatedAt = 0)
        )
        return chapterDao.getChapter(id)!!
    }

    @Test
    fun `chapter translates end to end and lands atomically`() = runTest {
        val provider = ScriptedProvider(ProviderId.GEMINI, "Gemini AI") { req ->
            AppResult.Ok(Translation(englishify(req.text), ProviderId.GEMINI))
        }
        val ch = chapter(1, "CH1 첫 단락입니다.\n\nCH1 둘째 단락입니다.")
        val outcome = translator(provider).translateChapter(book(), ch)
        val value = (outcome as AppResult.Ok).value
        assertEquals(ProviderId.GEMINI, value.providerId)
        assertEquals(1, value.networkChunks)
        assertEquals(0, value.cacheChunks)
        val row = chapterDao.getChapter(ch.id)!!
        assertEquals("TRANSLATED", row.status)
        assertEquals(value.text, row.translatedText)
        assertEquals("GEMINI", row.translatedByProvider)
        assertNotNull(row.qualityScore)
        assertNull(row.partialStateJson)
    }

    @Test
    fun `book quality mode and AI context reach the provider request`() = runTest {
        var seen: TranslateRequest? = null
        val provider = ScriptedProvider(ProviderId.GEMINI, "Gemini AI") { req ->
            seen = req
            AppResult.Ok(Translation(englishify(req.text), ProviderId.GEMINI))
        }
        val ch = chapter(1, "CH1 본문입니다.")
        translator(provider).translateChapter(
            book(quality = "FAST"), ch, context = AiContext("KB BLOCK")
        )
        assertEquals(QualityMode.FAST, seen?.quality)
        assertEquals("KB BLOCK", seen?.context?.systemPrompt)
    }

    @Test
    fun `identical second chapter is served entirely from cache`() = runTest {
        val provider = ScriptedProvider(ProviderId.GEMINI, "Gemini AI") { req ->
            AppResult.Ok(Translation(englishify(req.text), ProviderId.GEMINI))
        }
        val t = translator(provider)
        val big = (1..3).joinToString("\n\n") { n ->
            "PART${listOf("ONE", "TWO", "THREE")[n - 1]} " + "가나다라 ".repeat(1_100)
        }
        val first = t.translateChapter(book(), chapter(1, big)) as AppResult.Ok
        assertEquals(3, first.value.networkChunks)
        assertEquals(3, provider.calls.size)
        val second = t.translateChapter(book(), chapter(2, big)) as AppResult.Ok
        assertEquals(0, second.value.networkChunks)
        assertEquals(3, second.value.cacheChunks)
        assertEquals(3, provider.calls.size) // no new calls at all
        assertEquals(first.value.text, second.value.text)
    }

    @Test
    fun `mid-chapter failure persists partial progress and resume skips done chunks`() = runTest {
        var failTwo = true
        val provider = ScriptedProvider(ProviderId.GEMINI, "Gemini AI") { req ->
            if (failTwo && req.text.contains("PARTTWO")) {
                AppResult.Err(AppError.ProviderDown("GEMINI", 500))
            } else {
                AppResult.Ok(Translation(englishify(req.text), ProviderId.GEMINI))
            }
        }
        val t = translator(provider)
        val big = (1..3).joinToString("\n\n") { n ->
            "PART${listOf("ONE", "TWO", "THREE")[n - 1]} " + "마바사아 ".repeat(1_100)
        }
        val ch = chapter(1, big)
        val first = t.translateChapter(book(), ch)
        assertTrue(first is AppResult.Err)
        val partialRow = chapterDao.getChapter(ch.id)!!
        assertEquals("PARTIAL", partialRow.status)
        val state = PartialChapterState.decode(partialRow.partialStateJson)
        assertEquals(setOf(0), state?.done?.keys)

        failTwo = false
        provider.calls.clear()
        val resumed = t.translateChapter(book(), chapterDao.getChapter(ch.id)!!)
        assertTrue(resumed is AppResult.Ok)
        assertEquals(2, provider.calls.size)
        assertTrue(provider.calls.none { it.text.contains("PARTONE") })
        val row = chapterDao.getChapter(ch.id)!!
        assertEquals("TRANSLATED", row.status)
        val text = row.translatedText!!
        assertTrue(text.indexOf("PARTONE") < text.indexOf("PARTTWO"))
        assertTrue(text.indexOf("PARTTWO") < text.indexOf("PARTTHREE"))
    }

    @Test
    fun `verification failure marks the chunk failed, never mangled`() = runTest {
        val provider = ScriptedProvider(ProviderId.GEMINI, "Gemini AI") { req ->
            // The provider echoes the Korean back — a classic MTL failure.
            AppResult.Ok(Translation(req.text, ProviderId.GEMINI))
        }
        val ch = chapter(1, "CH1 이것은 한국어 본문입니다. 번역되어야 합니다.")
        val outcome = translator(provider).translateChapter(book(), ch)
        val error = (outcome as AppResult.Err).error
        assertTrue(error is AppError.VerificationFailed)
        val row = chapterDao.getChapter(ch.id)!!
        assertNull(row.translatedText)
        val state = PartialChapterState.decode(row.partialStateJson)
        assertTrue(state?.done.isNullOrEmpty())
    }

    @Test
    fun `pacing spaces network calls and cache hits skip pacing`() = runTest {
        val waits = mutableListOf<Long>()
        var now = 5_000L
        val pacer = RequestPacer(
            minDelayMs = { 600L },
            delayFn = { waits += it },
            nowMs = { now }
        )
        val provider = ScriptedProvider(ProviderId.GEMINI, "Gemini AI") { req ->
            AppResult.Ok(Translation(englishify(req.text), ProviderId.GEMINI))
        }
        val t = translator(provider, pacer)
        val big = (1..3).joinToString("\n\n") { n ->
            "PART${listOf("ONE", "TWO", "THREE")[n - 1]} " + "자차카타 ".repeat(1_100)
        }
        t.translateChapter(book(), chapter(1, big))
        // 3 network calls: the first never waits, calls 2 and 3 wait 600.
        assertEquals(listOf(600L, 600L), waits)
        waits.clear()
        now = 5_000L
        t.translateChapter(book(), chapter(2, big)) // all cache hits
        assertEquals(emptyList<Long>(), waits)
    }

    @Test
    fun `pause signal persists partial state and propagates`() = runTest {
        val provider = ScriptedProvider(ProviderId.GEMINI, "Gemini AI") { req ->
            AppResult.Ok(Translation(englishify(req.text), ProviderId.GEMINI))
        }
        val ch = chapter(1, "CH1 본문입니다.")
        try {
            translator(provider).translateChapter(book(), ch, shouldStop = { true })
            fail("expected ChapterPausedException")
        } catch (expected: ChapterPausedException) {
            // expected
        }
        val row = chapterDao.getChapter(ch.id)!!
        assertEquals("PARTIAL", row.status)
        assertNotNull(row.partialStateJson)
        assertEquals(0, provider.calls.size)
    }

    @Test
    fun `chapter without original text fails in words`() = runTest {
        val provider = ScriptedProvider(ProviderId.GEMINI, "Gemini AI") { req ->
            AppResult.Ok(Translation(englishify(req.text), ProviderId.GEMINI))
        }
        val id = chapterDao.insert(
            ChapterEntity(
                bookId = 1, number = 1, sourceText = null,
                translatedText = "already english", updatedAt = 0
            )
        )
        val outcome = translator(provider)
            .translateChapter(book(), chapterDao.getChapter(id)!!)
        assertTrue((outcome as AppResult.Err).error is AppError.VerificationFailed)
        assertEquals(0, provider.calls.size)
    }
}
