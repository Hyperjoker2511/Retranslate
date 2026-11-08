package app.retranslate.domain.translate

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.data.db.BookEntity
import app.retranslate.data.db.ChapterDao
import app.retranslate.data.db.ChapterEntity
import app.retranslate.data.db.KbEntryEntity
import app.retranslate.data.db.TxRunner
import app.retranslate.domain.kb.KbCompliance
import app.retranslate.domain.kb.KbInjection
import app.retranslate.domain.kb.KbRepository
import app.retranslate.providers.AiContext
import app.retranslate.providers.LANG_AUTO
import app.retranslate.providers.Provider
import app.retranslate.providers.ProviderId
import app.retranslate.providers.QualityMode
import app.retranslate.providers.TranslateRequest
import app.retranslate.providers.key.GeminiProvider

/**
 * The one AI call polish needs (§5.2): a system prompt + the chapter
 * in, the polished chapter out. An interface so the polisher is
 * JVM-testable with a scripted fake — and so polish, like study,
 * NEVER goes through the fallback router: an editor pass answered by
 * a classic MT provider would be a second translation, not polish.
 * Production binds this to the chapter-budget Gemini instance.
 */
interface PolishAi {
    /** True when an AI key exists (polish is impossible without one). */
    fun isConfigured(): Boolean

    suspend fun complete(
        systemPrompt: String,
        userPrompt: String,
        targetLang: String,
        quality: QualityMode
    ): AppResult<String>
}

/** [PolishAi] over the chapter-budget Gemini instance (§4.5). */
class GeminiPolishAi(
    private val gemini: GeminiProvider,
    private val configured: () -> Boolean
) : PolishAi {

    override fun isConfigured(): Boolean = configured()

    override suspend fun complete(
        systemPrompt: String,
        userPrompt: String,
        targetLang: String,
        quality: QualityMode
    ): AppResult<String> = gemini.translate(
        TranslateRequest(
            text = userPrompt,
            source = LANG_AUTO,
            target = targetLang,
            context = AiContext(systemPrompt),
            quality = quality,
            // v1.1.3 (§G): Strong pins the standard tier — Gemini's
            // own quality map is lite-first by design (quota), and
            // Improve's whole point is a visibly stronger rewrite.
            modelHint = if (quality == QualityMode.BEST) {
                GeminiProvider.MODEL_FLASH_38
            } else {
                null
            }
        )
    ).map { it.text }
}

/**
 * [PolishAi] over the first configured KEY_AI chapter provider (P7,
 * §5.2's "a different provider if configured"): Gemini first, then
 * OpenAI, then Claude — the same preference order as the §5.6 merge
 * judge. Lets an OpenAI/Claude-only user polish too; with only a
 * Gemini key it behaves exactly like [GeminiPolishAi].
 */
class ProviderPolishAi(
    private val aiOf: () -> Provider?
) : PolishAi {

    override fun isConfigured(): Boolean = aiOf() != null

    override suspend fun complete(
        systemPrompt: String,
        userPrompt: String,
        targetLang: String,
        quality: QualityMode
    ): AppResult<String> {
        val ai = aiOf()
            ?: return AppResult.Err(AppError.AuthFailed(ProviderId.GEMINI.name))
        return ai.translate(
            TranslateRequest(
                text = userPrompt,
                source = LANG_AUTO,
                target = targetLang,
                context = AiContext(systemPrompt),
                quality = quality,
                // v1.1.3 (§G): Strong + Gemini = the standard tier
                // (see GeminiPolishAi); OpenAI/Claude map BEST onto
                // their own stronger models already.
                modelHint = if (ai.id == ProviderId.GEMINI &&
                    quality == QualityMode.BEST
                ) {
                    GeminiProvider.MODEL_FLASH_38
                } else {
                    null
                }
            )
        ).map { it.text }
    }
}

/** Polish finished one of two honest ways (§5.2). */
sealed interface PolishResult {
    /** The polish passed verification and replaced the chapter text. */
    data object Applied : PolishResult

    /**
     * The polish was REJECTED by the local verifier (it changed a
     * locked name, drifted paragraphs, came back empty…). The
     * chapter keeps its previous text — a rejected polish is a
     * result, not an error.
     */
    data class Rejected(val reason: String) : PolishResult
}

/**
 * "Polish this chapter" (PLAN.md §5.2, review screen): one AI editor
 * pass over one translated chapter — grammar, tone, readability —
 * with the Knowledge Base injected and a hard local rule: a polish
 * that alters any LOCKED KB term is rejected and the original text
 * is kept. The pre-polish chapter is snapshotted first
 * ([ChapterSnapshots]), so an applied polish is undoable exactly
 * like a re-translation. The job-level per-book polish toggle (P7)
 * runs through this same class with [snapshotFirst] off: a job may
 * polish hundreds of chapters, the one-chapter Undo slot is the
 * review screen's tool, and a job's pre-polish text stays
 * reproducible from the §5.5 translation cache.
 */
class ChapterPolisher(
    private val chapterDao: ChapterDao,
    private val kbRepository: KbRepository,
    private val snapshots: ChapterSnapshots,
    private val ai: PolishAi,
    private val tx: TxRunner,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * v1.1.3 (§G): the per-chapter original archive. When present,
     * the chapter's pre-improve text is archived BEFORE the AI call
     * (first original wins, never overwritten) — this is what makes
     * "Restore original" true at bulk scale.
     */
    private val archive: ChapterOriginalArchive? = null,
    /** v1.1.3 (§G): the shared pacer — bulk improves are paced too. */
    private val pacer: RequestPacer? = null
) {

    suspend fun polish(
        book: BookEntity,
        chapter: ChapterEntity,
        snapshotFirst: Boolean = true,
        strength: ImproveStrength = ImproveStrength.GENTLE
    ): AppResult<PolishResult> {
        val original = chapter.translatedText?.takeIf { it.isNotBlank() }
            ?: return AppResult.Err(
                AppError.VerificationFailed("this chapter has no translation to polish yet")
            )
        if (!ai.isConfigured()) {
            return AppResult.Err(AppError.AuthFailed(ProviderId.GEMINI.name))
        }
        // Archive BEFORE the AI call (§G): if the chapter's original
        // can't be preserved, the improve never starts — the same
        // no-unundoable-edits rule the snapshot below follows.
        if (archive != null && !archive.archiveIfAbsent(book.id, chapter)) {
            return AppResult.Err(AppError.StorageFailed("saving the chapter's original"))
        }
        // Snapshot BEFORE the AI call: if the undo copy can't be
        // written, the polish never starts (§9.3's rule). Job-level
        // polish (P7) opts out — see the class doc.
        if (snapshotFirst &&
            !snapshots.save(book.id, chapter, ChapterChangeAction.POLISH)
        ) {
            return AppResult.Err(AppError.StorageFailed("saving an undo copy"))
        }
        val entries = kbRepository.activeEntries(book.id)
        // GENTLE keeps the book's own quality mode (v1.1.2 behaviour);
        // STRONG forces the standard tier (§G candidate 5).
        val quality = if (strength == ImproveStrength.STRONG) {
            QualityMode.BEST
        } else {
            QualityMode.entries.firstOrNull { it.name == book.qualityMode }
                ?: QualityMode.BALANCED
        }
        // v1.1.3: the chunked core (D8) — chapters over the old
        // 30,000-char single-shot cap improve block by block, each
        // block verified, instead of being refused outright.
        val coreResult = when (
            val result = PolishCore(ai, pacer).polishText(
                text = original,
                systemPrompt = AiPrompts.polishSystemPrompt(
                    targetLang = book.targetLang,
                    genrePresetId = book.genrePresetId,
                    kbBlock = entries.takeIf { it.isNotEmpty() }
                        ?.let { KbInjection.buildBlock(it) }
                ),
                targetLang = book.targetLang,
                quality = quality,
                lockedEntries = entries.filter { it.locked }
            )
        ) {
            is AppResult.Err -> return result
            is AppResult.Ok -> result.value
        }
        val polished = when (coreResult) {
            is PolishCoreResult.Rejected ->
                return AppResult.Ok(PolishResult.Rejected(coreResult.reason))
            is PolishCoreResult.Applied -> coreResult.text
        }
        // Same score the translator would give this text (§5.2): the
        // review screen's number stays comparable before/after.
        // v1.1.3 (§A3): a source-less (imported MTL) chapter gets the
        // LOCAL score instead of keeping null forever.
        val source = chapter.sourceText
        val score = if (source.isNullOrBlank()) {
            ChapterVerifier.localQualityScore(polished, entries)
        } else {
            val violations = ChapterVerifier.kbViolations(polished, entries)
            (
                ChapterVerifier.qualityScore(source, polished, book.sourceLang, book.targetLang) -
                    ChapterVerifier.kbPenalty(violations)
                ).coerceIn(0f, 100f)
        }
        val kbVersion = kbRepository.activeKb(book.id)?.version
        tx.run {
            chapterDao.update(
                chapter.copy(
                    translatedText = polished,
                    qualityScore = score,
                    // §A3: the durable "Improved with AI" marker +
                    // the KB version this improve ran against (the
                    // NOT_YET_IMPROVED scope reads both). The
                    // translator's name is NOT overwritten — the
                    // Offline-translated scope depends on it (§H).
                    improvedAt = clock(),
                    kbVersionUsed = kbVersion ?: chapter.kbVersionUsed,
                    updatedAt = clock()
                )
            )
        }
        return AppResult.Ok(PolishResult.Applied)
    }

    companion object {
        /**
         * The post-polish verifier (§5.2), pure and testable: empty
         * output, paragraph drift (the shared [ChapterVerifier] rules
         * with source == target, so no script check applies), and the
         * locked-term law — a locked canonical's count may not drop,
         * and no wrong variant of it may appear that wasn't already
         * there.
         */
        fun polishIssues(
            original: String,
            polished: String,
            lockedEntries: List<KbEntryEntity>
        ): List<String> {
            if (polished.isBlank()) return listOf("the polish came back empty")
            val issues = mutableListOf<String>()
            issues += ChapterVerifier.chunkIssues(original, polished, "en", "en")
                .filter { it.contains("paragraphs changed") }
                .map { "the polish changed the chapter's paragraphs ($it)" }
            for (entry in lockedEntries) {
                val canonical = entry.canonicalForm.trim()
                if (canonical.isEmpty()) continue
                val before = KbCompliance.countOccurrences(original, canonical)
                val after = KbCompliance.countOccurrences(polished, canonical)
                if (after < before) {
                    issues += "the polish changed the locked name “$canonical”"
                    continue
                }
                for (variant in KbCompliance.variantsOf(canonical)) {
                    val introduced = KbCompliance.countOccurrences(polished, variant) >
                        KbCompliance.countOccurrences(original, variant)
                    if (introduced) {
                        issues += "the polish misspelled the locked name “$canonical”"
                        break
                    }
                }
            }
            return issues
        }
    }
}
