package app.retranslate.domain.translate

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.data.db.BookEntity
import app.retranslate.data.db.ChapterDao
import app.retranslate.data.db.ChapterEntity
import app.retranslate.data.db.ChapterStatus
import app.retranslate.data.db.TxRunner
import app.retranslate.providers.AiContext
import app.retranslate.providers.ProviderId
import app.retranslate.providers.QualityMode
import app.retranslate.providers.hub.ProviderRouter
import app.retranslate.providers.hub.TranslatePurpose
import kotlinx.coroutines.CancellationException

/**
 * Control-flow signal: the owner of the running job asked to pause.
 * A [CancellationException] subclass so it is never swallowed by the
 * §3.3 error wrappers — but it is thrown by OUR code (never by an
 * actual job cancellation), so the catching engine may keep running
 * suspend calls afterwards. The chapter's partial progress is always
 * persisted before this propagates.
 */
class ChapterPausedException : CancellationException("translation paused by owner")

/** A finished chapter: what the engine records and the user reviews. */
data class ChapterOutcome(
    val text: String,
    /** Provider that produced most chunks; null when all came from cache. */
    val providerId: ProviderId?,
    val qualityScore: Float,
    val networkChunks: Int,
    val cacheChunks: Int,
    /** Chunks where the chain had to switch away from its first provider. */
    val switches: Int,
    /** §6.5: KB names this chapter renders wrong (flagged, not altered). */
    val kbWarnings: List<app.retranslate.domain.kb.KbViolation> = emptyList()
)

/**
 * Translates ONE chapter (PLAN.md §5.1/§5.2/§5.5): cleanup → chunk →
 * per-chunk cache/router/verify → byte-exact reassembly → one atomic
 * chapter-row write. Every provider call goes through the P1/P2
 * [ProviderRouter] — this class knows no provider directly — with the
 * book's quality mode and (from P5) the Knowledge Base context.
 *
 * Partial progress is persisted to the chapter row after EVERY chunk
 * (§7.4), so this function is safe to kill at any suspension point:
 * call it again with the reloaded chapter row and it resumes at the
 * first unfinished chunk.
 */
class ChapterTranslator(
    private val router: ProviderRouter,
    private val chapterDao: ChapterDao,
    private val cache: TranslationCache,
    private val tx: TxRunner,
    private val pacer: RequestPacer,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    suspend fun translateChapter(
        book: BookEntity,
        chapter: ChapterEntity,
        context: AiContext? = null,
        kbVersion: Int? = null,
        kbEntries: List<app.retranslate.data.db.KbEntryEntity> = emptyList(),
        shouldStop: suspend () -> Boolean = { false }
    ): AppResult<ChapterOutcome> {
        val rawSource = chapter.sourceText?.takeIf { it.isNotBlank() }
            ?: return AppResult.Err(
                AppError.VerificationFailed("this chapter has no original text")
            )
        val quality = QualityMode.entries.firstOrNull { it.name == book.qualityMode }
            ?: QualityMode.BALANCED
        val cleaned = CleanupRules.clean(rawSource)
        if (cleaned.isBlank()) {
            return AppResult.Err(
                AppError.VerificationFailed("nothing readable was left in this chapter")
            )
        }
        val chunks = TextChunker.chunk(cleaned)
        val sourceHash = cache.hash(cleaned, book.sourceLang, book.targetLang, quality, kbVersion)
        var state = PartialChapterState.decode(chapter.partialStateJson)
            ?.takeIf { it.sourceHash == sourceHash && it.totalChunks == chunks.size }
            ?: PartialChapterState(sourceHash = sourceHash, totalChunks = chunks.size)

        var networkChunks = 0
        var cacheChunks = 0
        var switches = 0

        suspend fun persistPartial() {
            chapterDao.update(
                chapter.copy(
                    status = ChapterStatus.PARTIAL.name,
                    partialStateJson = state.encode(),
                    updatedAt = clock()
                )
            )
        }

        for (index in chunks.indices) {
            if (index in state.done) continue
            if (shouldStop()) {
                persistPartial()
                throw ChapterPausedException()
            }
            val chunk = chunks[index]

            val hit = cache.get(chunk, book.sourceLang, book.targetLang, quality, kbVersion)
            if (hit != null) {
                state = state.withChunk(index, hit.text, hit.providerId)
                cacheChunks++
                persistPartial()
                continue
            }

            pacer.pace()
            when (
                val routed = router.translate(
                    text = chunk,
                    source = book.sourceLang,
                    target = book.targetLang,
                    purpose = TranslatePurpose.CHAPTER,
                    quality = quality,
                    context = context
                )
            ) {
                is AppResult.Err -> {
                    persistPartial()
                    return AppResult.Err(routed.error)
                }
                is AppResult.Ok -> {
                    val value = routed.value
                    val issues = ChapterVerifier.chunkIssues(
                        chunk, value.text, book.sourceLang, book.targetLang
                    )
                    if (issues.isNotEmpty()) {
                        // §10 #12: a chunk that fails the sanity checks
                        // is a FAILED chunk — retried on resume, never
                        // silently mangled into the chapter.
                        persistPartial()
                        return AppResult.Err(AppError.VerificationFailed(issues.first()))
                    }
                    state = state.withChunk(index, value.text, value.via.name)
                    if (value.switchedFromName != null) switches++
                    networkChunks++
                    cache.put(
                        chunk, book.sourceLang, book.targetLang, quality,
                        kbVersion, value.via.name, value.text
                    )
                    persistPartial()
                }
            }
        }

        val assembled = chunks.indices.joinToString(separator = "") { state.done.getValue(it) }
        // §6.5: KB violations flag the chapter (score + warnings on the
        // outcome) — the text itself is never silently rewritten.
        val kbWarnings = if (kbEntries.isEmpty()) {
            emptyList()
        } else {
            ChapterVerifier.kbViolations(assembled, kbEntries)
        }
        val score = (
            ChapterVerifier.qualityScore(cleaned, assembled, book.sourceLang, book.targetLang) -
                ChapterVerifier.kbPenalty(kbWarnings)
            ).coerceIn(0f, 100f)
        val dominant = state.providers.values
            .groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key
            ?.let { name -> ProviderId.entries.firstOrNull { it.name == name } }

        // §7.4 per-chapter atomicity: text + status + provenance land in
        // ONE transaction — a kill can never leave a translated text
        // marked untranslated or vice versa.
        tx.run {
            chapterDao.update(
                chapter.copy(
                    translatedText = assembled,
                    status = ChapterStatus.TRANSLATED.name,
                    partialStateJson = null,
                    qualityScore = score,
                    translatedByProvider = dominant?.name,
                    kbVersionUsed = kbVersion,
                    updatedAt = clock()
                )
            )
        }
        return AppResult.Ok(
            ChapterOutcome(
                text = assembled,
                providerId = dominant,
                qualityScore = score,
                networkChunks = networkChunks,
                cacheChunks = cacheChunks,
                switches = switches,
                kbWarnings = kbWarnings
            )
        )
    }
}
