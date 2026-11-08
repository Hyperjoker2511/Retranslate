package app.retranslate.domain.kb

import app.retranslate.core.error.AppResult
import app.retranslate.data.db.BookEntity
import app.retranslate.data.db.ChapterEntity
import app.retranslate.data.db.JobRunEntity
import app.retranslate.data.db.SampleChapterEntity
import app.retranslate.data.db.SampleSetEntity
import app.retranslate.domain.books.DirectTxRunner
import app.retranslate.domain.books.FakeBookDao
import app.retranslate.domain.books.FakeCachedTranslationDao
import app.retranslate.domain.books.FakeChapterDao
import app.retranslate.domain.books.FakeJobRunDao
import app.retranslate.domain.books.FakeKnowledgeBaseDao
import app.retranslate.domain.books.FakeSampleDao
import app.retranslate.domain.translate.ChapterTranslator
import app.retranslate.domain.translate.RequestPacer
import app.retranslate.domain.translate.TranslationCache
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
import org.junit.Assert.assertTrue
import org.junit.Test

private class ChainStudyAi(private val json: String) : StudyAi {
    override fun isConfigured(): Boolean = true
    override suspend fun complete(
        systemPrompt: String, userPrompt: String, targetLang: String, quality: QualityMode
    ): AppResult<String> = AppResult.Ok(json)
}

private class ChainProvider : Provider {
    override val id: ProviderId = ProviderId.GEMINI
    override val displayName: String = "Gemini AI"
    override val kind: Provider.Kind = Provider.Kind.KEY_AI
    val requests = mutableListOf<TranslateRequest>()

    /** Answers with the KB name spelled the sloppy way ("Jinwoo"). */
    var answer: String = "Jinwoo raised his sword and Jinwoo charged."
    override fun supportsPair(source: String, target: String): Boolean = true
    override suspend fun supportedLanguages(): AppResult<List<Language>> =
        AppResult.Ok(emptyList())

    override suspend fun translate(req: TranslateRequest): AppResult<Translation> {
        requests += req
        return AppResult.Ok(Translation(answer, ProviderId.GEMINI))
    }

    override suspend fun testConnection(): AppResult<Unit> = AppResult.Ok(Unit)
}

/**
 * PLAN.md §12.1 integration: study (scripted fake AI) → KB → chapter
 * translation — the KB block must reach the provider's system prompt,
 * the KB version must land on the chapter (and in its cache key), and
 * a name slip in the result must surface as a warning, not a fix.
 */
class StudyToTranslateChainTest {

    private val clock = FakeClock(now = 2_000L)
    private val bookDao = FakeBookDao()
    private val chapterDao = FakeChapterDao()
    private val sampleDao = FakeSampleDao()
    private val jobRunDao = FakeJobRunDao()
    private val kbRepo = KbRepository(
        FakeKnowledgeBaseDao(), chapterDao, DirectTxRunner
    ) { clock.now }

    private val studyJson = """
        {"entries":[
          {"category":"CHARACTER","sourceForm":"진우","canonicalForm":"Jin Woo",
           "gender":"MALE","note":"referred to as 'he'","frequency":6}
        ]}
    """.trimIndent()

    @Test
    fun `study then translate injects the KB and flags the name slip`() = runTest {
        val bookId = bookDao.insert(
            BookEntity(
                titleOriginal = "소설", sourceLang = "ko", targetLang = "en",
                createdAt = 0, updatedAt = 0
            )
        )
        val book = bookDao.getById(bookId)!!
        val setId = sampleDao.insertSet(
            SampleSetEntity(
                bookId = bookId, fileName = "gold.txt", detectedLang = "en", chapterCount = 1
            )
        )
        sampleDao.insertChapters(
            listOf(
                SampleChapterEntity(
                    sampleSetId = setId, number = 1,
                    text = "Jin Woo raised his sword, calm as ever."
                )
            )
        )
        val runId = jobRunDao.insert(
            JobRunEntity(bookId = bookId, kind = "STUDY", state = "QUEUED", startedAt = 0)
        )
        val study = StudyEngine(bookDao, sampleDao, jobRunDao, kbRepo, ChainStudyAi(studyJson)) {
            clock.now
        }
        val studied = study.run(
            bookId, runId, StudySelection(listOf(StudySelection.SetRange(setId, 1, 1)))
        )
        assertTrue(studied is StudyResult.Finished)

        // Translate a later chapter with the KB context wired in.
        val chapterId = chapterDao.insert(
            ChapterEntity(
                bookId = bookId, number = 500,
                sourceText = "진우가 검을 들었다. 진우가 돌진했다.",
                updatedAt = 0
            )
        )
        val chapter = chapterDao.getChapter(chapterId)!!
        val provider = ChainProvider()
        val router = ProviderRouter(
            listOf(provider), ProviderHealth(FakeHealthStore(), clock.lambda),
            nowMs = clock.lambda
        )
        val translator = ChapterTranslator(
            router = router,
            chapterDao = chapterDao,
            cache = TranslationCache(FakeCachedTranslationDao()) { clock.now },
            tx = DirectTxRunner,
            pacer = RequestPacer({ 0L }, {}, clock.lambda),
            clock = clock.lambda
        )
        val kbContext = kbRepo.buildContext(book)
        val outcome = translator.translateChapter(
            book, chapter,
            context = kbContext.context,
            kbVersion = kbContext.kbVersion,
            kbEntries = kbContext.entries
        )
        val value = (outcome as AppResult.Ok).value

        // The KB block reached the provider, inside the system prompt.
        assertEquals(1, provider.requests.size)
        val systemPrompt = provider.requests[0].context!!.systemPrompt
        assertTrue(systemPrompt.contains("Jin Woo (male)"))
        assertTrue(systemPrompt.contains("original: 진우"))

        // The chapter records which KB version produced it…
        val row = chapterDao.getChapter(chapterId)!!
        assertEquals("TRANSLATED", row.status)
        assertEquals(1, row.kbVersionUsed)

        // …and the provider's "Jinwoo" slip is FLAGGED, never rewritten.
        assertEquals("Jinwoo raised his sword and Jinwoo charged.", value.text)
        assertEquals(1, value.kbWarnings.size)
        assertEquals("Jinwoo", value.kbWarnings[0].variantFound)
        assertEquals(2, value.kbWarnings[0].occurrences)
        // Score reflects the warning (−10 for one distinct name).
        assertTrue(value.qualityScore <= 90f)
        // The book-level warning scan sees the same chapter.
        assertEquals(1, kbRepo.countChaptersWithNameWarnings(bookId))
    }
}
