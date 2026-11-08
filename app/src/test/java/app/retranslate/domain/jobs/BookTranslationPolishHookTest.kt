package app.retranslate.domain.jobs

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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class EchoProvider : Provider {
    override val id = ProviderId.GEMINI
    override val displayName = "Echo"
    override val kind = Provider.Kind.KEY_AI
    override fun supportsPair(source: String, target: String): Boolean = true
    override suspend fun supportedLanguages(): AppResult<List<Language>> =
        AppResult.Ok(emptyList())

    override suspend fun translate(req: TranslateRequest): AppResult<Translation> =
        AppResult.Ok(
            Translation(
                req.text.split("\n\n").joinToString("\n\n") {
                    "A clean English rendering of the passage, in full sentences."
                },
                ProviderId.GEMINI
            )
        )

    override suspend fun testConnection(): AppResult<Unit> = AppResult.Ok(Unit)
}

/**
 * PLAN.md §5.2/§11 P7: the job-level dual-model polish toggle, at the
 * P4 engine path. Toggle ON → the hook runs once per freshly
 * translated chapter; OFF → never; a THROWING hook changes nothing —
 * polish is an enhancement, never a failure point.
 */
class BookTranslationPolishHookTest {

    private val clock = FakeClock(now = 10_000L)
    private val bookDao = FakeBookDao()
    private val chapterDao = FakeChapterDao()
    private val jobRunDao = FakeJobRunDao()

    private fun engine(polishHook: (suspend (BookEntity, ChapterEntity) -> Unit)?) =
        BookTranslationEngine(
            bookDao = bookDao,
            chapterDao = chapterDao,
            jobRunDao = jobRunDao,
            translator = ChapterTranslator(
                router = ProviderRouter(
                    listOf(EchoProvider()),
                    ProviderHealth(FakeHealthStore(), clock.lambda),
                    nowMs = clock.lambda
                ),
                chapterDao = chapterDao,
                cache = TranslationCache(FakeCachedTranslationDao()) { clock.now },
                tx = DirectTxRunner,
                pacer = RequestPacer({ 0L }, {}, clock.lambda),
                clock = clock.lambda
            ),
            tx = DirectTxRunner,
            polishHook = polishHook,
            clock = clock.lambda
        )

    private suspend fun seedBook(polishEnabled: Boolean, chapters: Int = 3): Pair<Long, Long> {
        val bookId = bookDao.insert(
            BookEntity(
                titleOriginal = "소설", sourceLang = "ko", targetLang = "en",
                polishEnabled = polishEnabled, createdAt = 0, updatedAt = 0
            )
        )
        chapterDao.insertAll(
            (1..chapters).map { n ->
                ChapterEntity(
                    bookId = bookId, number = n, title = "Chapter $n",
                    sourceText = "이것은 $n 번째 장의 본문입니다. 모험이 계속됩니다.",
                    updatedAt = 0
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

    @Test
    fun `polish on - the hook runs once per translated chapter`() = runTest {
        val polished = mutableListOf<Long>()
        val (bookId, runId) = seedBook(polishEnabled = true)
        val result = engine { _, chapter -> polished += chapter.id }.run(bookId, runId)

        assertEquals(EngineResult.Finished(3, 0), result)
        assertEquals(3, polished.size)
        assertTrue(chapterDao.getChapters(bookId).all { it.status == "TRANSLATED" })
    }

    @Test
    fun `polish off - the hook never runs`() = runTest {
        val polished = mutableListOf<Long>()
        val (bookId, runId) = seedBook(polishEnabled = false)
        val result = engine { _, chapter -> polished += chapter.id }.run(bookId, runId)

        assertEquals(EngineResult.Finished(3, 0), result)
        assertTrue(polished.isEmpty())
    }

    @Test
    fun `a throwing polish hook never fails the job`() = runTest {
        val (bookId, runId) = seedBook(polishEnabled = true)
        val result = engine { _, _ ->
            throw IllegalStateException("the editor exploded")
        }.run(bookId, runId)

        assertEquals(EngineResult.Finished(3, 0), result)
        assertTrue(chapterDao.getChapters(bookId).all { it.status == "TRANSLATED" })
    }

    @Test
    fun `no hook wired - polish on is simply inert`() = runTest {
        val (bookId, runId) = seedBook(polishEnabled = true)
        val result = engine(null).run(bookId, runId)
        assertEquals(EngineResult.Finished(3, 0), result)
    }
}
