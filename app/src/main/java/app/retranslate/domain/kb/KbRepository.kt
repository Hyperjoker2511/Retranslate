package app.retranslate.domain.kb

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.core.error.runCatchingAppSuspend
import app.retranslate.data.db.BookEntity
import app.retranslate.data.db.ChapterDao
import app.retranslate.data.db.DbEnums
import app.retranslate.data.db.KbEntryEntity
import app.retranslate.data.db.KnowledgeBaseDao
import app.retranslate.data.db.KnowledgeBaseEntity
import app.retranslate.data.db.TxRunner
import app.retranslate.domain.translate.AiPrompts
import app.retranslate.providers.AiContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

/** Everything chapter translation needs from the KB (§6.4). */
data class KbBookContext(
    val context: AiContext,
    /** Null when the book has no KB (the cache key's KB component). */
    val kbVersion: Int?,
    val entries: List<KbEntryEntity>
)

/** A draft plus its lock — transforms below always carry both. */
private typealias DraftWithLock = Pair<KbEntryDraft, Boolean>

/**
 * Owns Knowledge Base reads and writes (PLAN.md §6.3). Versioning is
 * copy-on-write: every study and every manual edit batch writes a NEW
 * [KnowledgeBaseEntity] row (version + 1) holding the full entry set.
 * That makes the version a sound translation-cache component (§5.5) —
 * editing one name invalidates exactly the translations made under
 * the old version — and keeps "which KB produced this chapter"
 * (Chapter.kbVersionUsed) meaningful forever.
 *
 * Locked entries are sacred: a re-study carries them over verbatim,
 * and fresh study results that collide with one are dropped.
 */
class KbRepository(
    private val knowledgeBaseDao: KnowledgeBaseDao,
    private val chapterDao: ChapterDao,
    private val tx: TxRunner,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    fun observeActiveKb(bookId: Long): Flow<KnowledgeBaseEntity?> =
        knowledgeBaseDao.observeActiveKb(bookId)

    fun observeEntries(kbId: Long): Flow<List<KbEntryEntity>> =
        knowledgeBaseDao.observeEntries(kbId)

    /** The active version's entries as one flow (book page, KB screen). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeActiveEntries(bookId: Long): Flow<List<KbEntryEntity>> =
        knowledgeBaseDao.observeActiveKb(bookId).flatMapLatest { kb ->
            if (kb == null) kotlinx.coroutines.flow.flowOf(emptyList())
            else knowledgeBaseDao.observeEntries(kb.id)
        }

    suspend fun activeKb(bookId: Long): KnowledgeBaseEntity? =
        knowledgeBaseDao.getActiveKb(bookId)

    suspend fun activeEntries(bookId: Long): List<KbEntryEntity> {
        val kb = knowledgeBaseDao.getActiveKb(bookId) ?: return emptyList()
        return knowledgeBaseDao.getEntries(kb.id)
    }

    /**
     * Lands a study (§6.2): a new version whose entries are the
     * previous version's LOCKED entries (kept verbatim) plus the
     * freshly extracted ones that don't collide with a locked entry.
     */
    suspend fun createFromStudy(
        bookId: Long,
        drafts: List<KbEntryDraft>,
        sourceNote: String
    ): AppResult<KnowledgeBaseEntity> = mutate(bookId, sourceNote) { previous ->
        val locked = previous.filter { it.locked }
        val lockedKeys = locked.map { entryKey(it.category, it.canonicalForm, it.sourceForm) }
        locked.map { it.toDraft() to true } +
            drafts.filter {
                entryKey(it.category.name, it.canonicalForm, it.sourceForm) !in lockedKeys
            }.map { it to false }
    }

    /** A name the user typed by hand — locked by definition (§6.2). */
    suspend fun addManualEntry(
        bookId: Long,
        draft: KbEntryDraft
    ): AppResult<KnowledgeBaseEntity> = mutate(bookId, "Added by hand") { previous ->
        previous.map { it.toDraft() to it.locked } + (draft to true)
    }

    /** Edits one entry's fields (its frequency is preserved). */
    suspend fun editEntry(
        bookId: Long,
        entryId: Long,
        updated: KbEntryDraft,
        locked: Boolean
    ): AppResult<KnowledgeBaseEntity> = mutate(bookId, "Edited by hand") { previous ->
        previous.map { entry ->
            if (entry.id == entryId) {
                updated.copy(frequency = entry.frequency) to locked
            } else {
                entry.toDraft() to entry.locked
            }
        }
    }

    /** The lock toggle (§6.3): "never change this". */
    suspend fun setLocked(
        bookId: Long,
        entryId: Long,
        locked: Boolean
    ): AppResult<KnowledgeBaseEntity> = mutate(bookId, "Edited by hand") { previous ->
        previous.map { entry ->
            entry.toDraft() to (if (entry.id == entryId) locked else entry.locked)
        }
    }

    suspend fun deleteEntry(
        bookId: Long,
        entryId: Long
    ): AppResult<KnowledgeBaseEntity> = mutate(bookId, "Edited by hand") { previous ->
        previous.filter { it.id != entryId }.map { it.toDraft() to it.locked }
    }

    /**
     * The copy-on-write core: reads the active version, transforms its
     * entries, writes version + 1 in one transaction.
     */
    private suspend fun mutate(
        bookId: Long,
        sourceNote: String,
        transform: (List<KbEntryEntity>) -> List<DraftWithLock>
    ): AppResult<KnowledgeBaseEntity> =
        runCatchingAppSuspend({ AppError.StorageFailed("saving the Knowledge Base") }) {
            tx.run {
                val previous = knowledgeBaseDao.getActiveKb(bookId)
                val previousEntries =
                    previous?.let { knowledgeBaseDao.getEntries(it.id) }.orEmpty()
                val newEntries = transform(previousEntries)
                val newVersion = (previous?.version ?: 0) + 1
                val now = clock()
                val kbId = knowledgeBaseDao.insertKb(
                    KnowledgeBaseEntity(
                        bookId = bookId,
                        version = newVersion,
                        createdAt = now,
                        sourceNote = sourceNote
                    )
                )
                knowledgeBaseDao.insertEntries(
                    newEntries.map { (draft, locked) ->
                        KbEntryEntity(
                            kbId = kbId,
                            category = draft.category.name,
                            sourceForm = draft.sourceForm,
                            canonicalForm = draft.canonicalForm,
                            gender = draft.gender?.name,
                            note = draft.note,
                            locked = locked,
                            frequency = draft.frequency
                        )
                    }
                )
                KnowledgeBaseEntity(
                    id = kbId, bookId = bookId, version = newVersion,
                    createdAt = now, sourceNote = sourceNote
                )
            }
        }

    /**
     * The §6.4 injection for one book: the §5.4 system prompt (genre
     * hint + KB block) and the active KB version for the cache key.
     * Always returns a context — a book without a KB still gets its
     * genre-aware prompt; its kbVersion is then null.
     */
    suspend fun buildContext(book: BookEntity): KbBookContext {
        val kb = knowledgeBaseDao.getActiveKb(book.id)
        val entries = kb?.let { knowledgeBaseDao.getEntries(it.id) }.orEmpty()
        val block = if (entries.isEmpty()) null else KbInjection.buildBlock(entries)
        return KbBookContext(
            context = AiContext(
                AiPrompts.chapterSystemPrompt(
                    sourceLang = book.sourceLang,
                    targetLang = book.targetLang,
                    genrePresetId = book.genrePresetId,
                    kbBlock = block
                )
            ),
            kbVersion = kb?.version,
            entries = entries
        )
    }

    /**
     * §6.5's book-page line: how many translated chapters currently
     * break a KB name. Computed by a paged scan (§7.3) — a page at a
     * time, texts discarded as the scan walks.
     */
    suspend fun countChaptersWithNameWarnings(bookId: Long): Int {
        val entries = activeEntries(bookId)
        if (entries.isEmpty()) return 0
        var warnings = 0
        var offset = 0
        while (true) {
            val page = chapterDao.getTranslatedPage(bookId, WARNING_PAGE_SIZE, offset)
            if (page.isEmpty()) break
            warnings += page.count { slice ->
                !slice.translatedText.isNullOrBlank() &&
                    KbCompliance.findViolations(slice.translatedText, entries).isNotEmpty()
            }
            if (page.size < WARNING_PAGE_SIZE) break
            offset += page.size
        }
        return warnings
    }

    private fun entryKey(category: String, canonical: String, sourceForm: String?): String =
        category + "|" + (sourceForm?.trim()?.lowercase() ?: canonical.trim().lowercase())

    private fun KbEntryEntity.toDraft(): KbEntryDraft = KbEntryDraft(
        category = DbEnums.kbCategory(category),
        sourceForm = sourceForm,
        canonicalForm = canonicalForm,
        gender = DbEnums.kbGender(gender),
        note = note,
        frequency = frequency
    )

    companion object {
        const val WARNING_PAGE_SIZE = 100
    }
}
