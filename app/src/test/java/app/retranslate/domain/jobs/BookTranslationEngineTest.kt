package app.retranslate.domain.jobs

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.data.db.BookEntity
import app.retranslate.data.db.ChapterEntity
import app.retranslate.data.db.JobKind
import app.retranslate.data.db.JobRunEntity
import app.retranslate.data.db.JobState
import app.retranslate.domain.books.DirectTxRunner
import app.retranslate.domain.books.FakeBookDao
import app.retranslate.domain.books.FakeCachedTranslationDao
import app.retranslate.domain.books.FakeChapterDao
import app.retranslate.domain.books.FakeJobRunDao
import app.retranslate.domain.translate.ChapterTranslator
import app.retranslate.domain.translate.PartialChapterState
import app.retranslate.domain.translate.RequestPacer
import app.retranslate.domain.translate.TranslationCache
import app.retranslate.providers.FakeClock
import app.retranslate.providers.FakeHealthStore
import app.retranslate.providers.Language
import app.retranslate.providers.Provider
import app.retranslate.providers.ProviderId
import app.retranslate.providers.TranslateRequest
import app.retranslate.providers.Translation
import app.retranslate.providers.hub.ProviderHealth
import app.retranslate.providers.hub.ProviderRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

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

private fun englishify(id: ProviderId): suspend (TranslateRequest) -> AppResult<Translation> =
    { req ->
        val out = req.text.split("\n\n").joinToString("\n\n") { paragraph ->
            val marker = Regex("PART[A-Z]+|CH\\d+").find(paragraph)?.value ?: "TEXT"
            "English translation of $marker, told in full sentences with care."
        }
        AppResult.Ok(Translation(out, id))
    }

/** One wired world: fake DAOs + real translator/engine over scripted providers. */
private class World(vararg providers: ScriptedProvider) {
    val clock = FakeClock(now = 10_000L)
    val bookDao = FakeBookDao()
    val chapterDao = FakeChapterDao()
    val jobRunDao = FakeJobRunDao()
    val router = ProviderRouter(
        providers.toList(),
        ProviderHealth(FakeHealthStore(), clock.lambda),
        nowMs = clock.lambda
    )

    fun translator() = ChapterTranslator(
        router = router,
        chapterDao = chapterDao,
        cache = TranslationCache(FakeCachedTranslationDao()) { clock.now },
        tx = DirectTxRunner,
        pacer = RequestPacer({ 0L }, {}, clock.lambda),
        clock = clock.lambda
    )

    fun engine() = BookTranslationEngine(
        bookDao = bookDao,
        chapterDao = chapterDao,
        jobRunDao = jobRunDao,
        translator = translator(),
        tx = DirectTxRunner,
        clock = clock.lambda
    )

    suspend fun bookWithChapters(count: Int, chapterText: (Int) -> String): Pair<Long, Long> {
        val bookId = bookDao.insert(
            BookEntity(
                titleOriginal = "소설", sourceLang = "ko", targetLang = "en",
                createdAt = 0, updatedAt = 0
            )
        )
        chapterDao.insertAll(
            (1..count).map { n ->
                ChapterEntity(
                    bookId = bookId, number = n, title = "Chapter $n",
                    sourceText = chapterText(n), updatedAt = 0
                )
            }
        )
        val runId = jobRunDao.insert(
            JobRunEntity(
                bookId = bookId, kind = JobKind.TRANSLATE.name,
                state = JobState.QUEUED.name, startedAt = clock.now
            )
        )
        return bookId to runId
    }
}

private fun smallChapter(n: Int) = "CH$n 이것은 $n 번째 장의 본문입니다. 모험이 계속됩니다."

/**
 * PLAN.md §11 P4 milestone + §12.1 integration simulations: whole-book
 * completion, mid-book provider death, kill-and-resume (including
 * mid-chapter), failure isolation, the servers-busy self-pause, and
 * the user's cooperative pause.
 */
class BookTranslationEngineTest {

    @Test
    fun `fifty chapter book completes`() = runTest {
        val provider = ScriptedProvider(ProviderId.GEMINI, "Gemini AI", englishify(ProviderId.GEMINI))
        val world = World(provider)
        val (bookId, runId) = world.bookWithChapters(50, ::smallChapter)
        val result = world.engine().run(bookId, runId)
        assertEquals(EngineResult.Finished(50, 0), result)
        val run = world.jobRunDao.getById(runId)!!
        assertEquals("FINISHED", run.state)
        assertEquals(50, run.totalChapters)
        assertEquals(50, run.doneChapters)
        assertEquals(0, run.failedChapters)
        assertNotNull(run.finishedAt)
        assertTrue(
            world.chapterDao.getChapters(bookId).all { it.status == "TRANSLATED" }
        )
        assertEquals(50, provider.calls.size)
    }

    @Test
    fun `provider dying mid-book cascades to the fallback and finishes`() = runTest {
        val dying = ScriptedProvider(ProviderId.MOZHI, "Mozhi") { req ->
            val n = Regex("CH(\\d+)").find(req.text)?.groupValues?.get(1)?.toInt() ?: 0
            if (n >= 10) {
                AppResult.Err(AppError.ProviderDown("MOZHI", 500, retryable = false))
            } else {
                englishify(ProviderId.MOZHI)(req)
            }
        }
        val fallback = ScriptedProvider(ProviderId.LINGVA, "Lingva", englishify(ProviderId.LINGVA))
        val world = World(dying, fallback)
        val (bookId, runId) = world.bookWithChapters(20, ::smallChapter)
        val result = world.engine().run(bookId, runId)
        assertEquals(EngineResult.Finished(20, 0), result)
        // Chapters 10..20 (11 chapters) came from the fallback.
        assertEquals(11, fallback.calls.size)
        val run = world.jobRunDao.getById(runId)!!
        assertTrue("switches recorded: ${run.switchedProviders}", run.switchedProviders >= 11)
        assertTrue(
            world.chapterDao.getChapters(bookId).all { it.status == "TRANSLATED" }
        )
    }

    @Test
    fun `kill mid-job then a fresh engine resumes at the right chapter`() = runTest {
        var armed = true
        val provider = ScriptedProvider(ProviderId.GEMINI, "Gemini AI") { req ->
            if (armed && req.text.contains("CH25")) {
                // Simulates the process dying mid-request.
                throw CancellationException("process killed")
            }
            englishify(ProviderId.GEMINI)(req)
        }
        val world = World(provider)
        val (bookId, runId) = world.bookWithChapters(50, ::smallChapter)

        try {
            world.engine().run(bookId, runId)
            fail("the kill should propagate")
        } catch (expected: CancellationException) {
            // process "died"
        }
        val afterDeath = world.chapterDao.getChapters(bookId)
        assertEquals(24, afterDeath.count { it.status == "TRANSLATED" })
        assertEquals("PENDING", afterDeath.first { it.number == 25 }.status)
        val runAfterDeath = world.jobRunDao.getById(runId)!!
        assertEquals("RUNNING", runAfterDeath.state) // never falsely "finished"
        assertEquals(24, runAfterDeath.doneChapters)

        armed = false
        provider.calls.clear()
        // A brand-new engine + translator over the same stored state —
        // exactly what a relaunched process builds.
        val result = world.engine().run(bookId, runId)
        assertEquals(EngineResult.Finished(26, 0), result)
        assertEquals(26, provider.calls.size)
        assertTrue(
            provider.calls.none { call ->
                (1..24).any { n -> call.text.contains("CH$n ") || call.text.contains("CH$n\uC740") }
            }
        )
        assertTrue(
            world.chapterDao.getChapters(bookId).all { it.status == "TRANSLATED" }
        )
    }

    @Test
    fun `half-done chapter resumes mid-chapter after a kill`() = runTest {
        var armed = true
        val provider = ScriptedProvider(ProviderId.GEMINI, "Gemini AI") { req ->
            if (armed && req.text.contains("PARTTWO")) {
                throw CancellationException("process killed")
            }
            englishify(ProviderId.GEMINI)(req)
        }
        val world = World(provider)
        val big = (1..3).joinToString("\n\n") { n ->
            "PART${listOf("ONE", "TWO", "THREE")[n - 1]} " + "파하타차 ".repeat(1_100)
        }
        val (bookId, runId) = world.bookWithChapters(1) { big }
        try {
            world.engine().run(bookId, runId)
            fail("the kill should propagate")
        } catch (expected: CancellationException) {
            // process "died" during chunk 2
        }
        val partial = world.chapterDao.getChapters(bookId).single()
        assertEquals("PARTIAL", partial.status)
        assertEquals(
            setOf(0),
            PartialChapterState.decode(partial.partialStateJson)?.done?.keys
        )

        armed = false
        provider.calls.clear()
        val result = world.engine().run(bookId, runId)
        assertEquals(EngineResult.Finished(1, 0), result)
        assertEquals(2, provider.calls.size) // chunks 2 and 3 only
        assertTrue(provider.calls.none { it.text.contains("PARTONE") })
        val row = world.chapterDao.getChapters(bookId).single()
        assertEquals("TRANSLATED", row.status)
        assertNull(row.partialStateJson)
        val text = row.translatedText!!
        assertTrue(text.indexOf("PARTONE") < text.indexOf("PARTTWO"))
        assertTrue(text.indexOf("PARTTWO") < text.indexOf("PARTTHREE"))
    }

    @Test
    fun `one bad chapter fails and the rest of the book finishes`() = runTest {
        val provider = ScriptedProvider(ProviderId.GEMINI, "Gemini AI") { req ->
            if (req.text.contains("CH3 ")) {
                AppResult.Err(AppError.ProviderDown("GEMINI", 500))
            } else {
                englishify(ProviderId.GEMINI)(req)
            }
        }
        val world = World(provider)
        val (bookId, runId) = world.bookWithChapters(5, ::smallChapter)
        val result = world.engine().run(bookId, runId)
        assertEquals(EngineResult.Finished(4, 1), result)
        val chapters = world.chapterDao.getChapters(bookId)
        assertEquals("FAILED", chapters.first { it.number == 3 }.status)
        assertEquals(4, chapters.count { it.status == "TRANSLATED" })
        val run = world.jobRunDao.getById(runId)!!
        assertEquals("FINISHED", run.state)
        assertEquals(1, run.failedChapters)
    }

    @Test
    fun `servers-busy storm parks the job instead of burning retries`() = runTest {
        val provider = ScriptedProvider(ProviderId.GEMINI, "Gemini AI") {
            AppResult.Err(AppError.RateLimited("GEMINI", retryAfterSeconds = 60))
        }
        val world = World(provider)
        val (bookId, runId) = world.bookWithChapters(6, ::smallChapter)
        val result = world.engine().run(bookId, runId)
        assertEquals(EngineResult.PausedServersBusy, result)
        // Two consecutive busy chapters tripped the self-pause; the
        // remaining four were never attempted.
        assertEquals(2, provider.calls.size)
        val run = world.jobRunDao.getById(runId)!!
        assertEquals("PAUSED", run.state)
        assertEquals("RateLimited", run.lastErrorClass)
        assertEquals(2, run.failedChapters)
    }

    @Test
    fun `user pause stops the job and the state is never clobbered`() = runTest {
        lateinit var worldRef: World
        var runIdRef = -1L
        val provider = ScriptedProvider(ProviderId.GEMINI, "Gemini AI") { req ->
            if (req.text.contains("CH3 ")) {
                // The user taps Pause (Jobs screen) during chapter 3.
                val run = worldRef.jobRunDao.getById(runIdRef)!!
                worldRef.jobRunDao.update(run.copy(state = "PAUSED"))
            }
            englishify(ProviderId.GEMINI)(req)
        }
        val world = World(provider)
        worldRef = world
        val (bookId, runId) = world.bookWithChapters(5, ::smallChapter)
        runIdRef = runId
        val result = world.engine().run(bookId, runId)
        assertEquals(EngineResult.PausedByUser, result)
        val chapters = world.chapterDao.getChapters(bookId)
        assertEquals(3, chapters.count { it.status == "TRANSLATED" })
        assertEquals(2, chapters.count { it.status == "PENDING" })
        // Engine counter writes after the pause must not resurrect RUNNING.
        assertEquals("PAUSED", world.jobRunDao.getById(runId)!!.state)
    }

    @Test
    fun `single chapter scope runs only that chapter`() = runTest {
        val provider = ScriptedProvider(ProviderId.GEMINI, "Gemini AI", englishify(ProviderId.GEMINI))
        val world = World(provider)
        val (bookId, runId) = world.bookWithChapters(4, ::smallChapter)
        val target = world.chapterDao.getChapters(bookId).first { it.number == 2 }
        val result = world.engine().run(bookId, runId, onlyChapterIds = setOf(target.id))
        assertEquals(EngineResult.Finished(1, 0), result)
        val chapters = world.chapterDao.getChapters(bookId)
        assertEquals("TRANSLATED", chapters.first { it.number == 2 }.status)
        assertEquals(3, chapters.count { it.status == "PENDING" })
        assertEquals(1, world.jobRunDao.getById(runId)!!.totalChapters)
    }
}
