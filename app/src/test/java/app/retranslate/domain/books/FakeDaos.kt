package app.retranslate.domain.books

import app.retranslate.data.db.BookDao
import app.retranslate.data.db.BookEntity
import app.retranslate.data.db.BookShelfRow
import app.retranslate.data.db.CachedTranslationDao
import app.retranslate.data.db.CachedTranslationEntity
import app.retranslate.data.db.ChapterDao
import app.retranslate.data.db.ChapterEntity
import app.retranslate.data.db.ChapterListItem
import app.retranslate.data.db.JobRunDao
import app.retranslate.data.db.JobRunEntity
import app.retranslate.data.db.KbEntryEntity
import app.retranslate.data.db.KnowledgeBaseDao
import app.retranslate.data.db.KnowledgeBaseEntity
import app.retranslate.data.db.ProviderLanguageDao
import app.retranslate.data.db.ProviderLanguageEntity
import app.retranslate.data.db.SampleChapterEntity
import app.retranslate.data.db.SampleDao
import app.retranslate.data.db.SampleSetEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** A [app.retranslate.data.db.TxRunner] that runs the block directly (no Room in tests). */
object DirectTxRunner : app.retranslate.data.db.TxRunner {
    override suspend fun <T> run(block: suspend () -> T): T = block()
}

/**
 * In-memory DAO fakes for JVM tests. Room itself needs an Android
 * runtime (SQLite), so DAO SQL is verified by the exported schema test
 * and by these fakes at the repository layer — the limit is stated in
 * the P3 report, not hidden.
 */
class FakeBookDao : BookDao {
    val books = MutableStateFlow<List<BookEntity>>(emptyList())
    private var nextId = 1L

    /** Shelf counts hook (total, translated); tests opt in per book. */
    var shelfCounts: (BookEntity) -> Pair<Int, Int> = { 0 to 0 }

    override fun observeShelf(): Flow<List<BookShelfRow>> =
        books.map { list ->
            list.map {
                val (total, translated) = shelfCounts(it)
                BookShelfRow(it, total, translated)
            }
        }

    override fun observeAll(): Flow<List<BookEntity>> = books
    override fun observeById(id: Long): Flow<BookEntity?> =
        books.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun getById(id: Long): BookEntity? =
        books.value.firstOrNull { it.id == id }

    override suspend fun findByHash(hash: String): List<BookEntity> =
        books.value.filter { it.sourceFileHash == hash }

    override suspend fun insert(book: BookEntity): Long {
        val id = nextId++
        books.value = books.value + book.copy(id = id)
        return id
    }

    override suspend fun update(book: BookEntity) {
        books.value = books.value.map { if (it.id == book.id) book else it }
    }

    override suspend fun deleteById(id: Long) {
        books.value = books.value.filter { it.id != id }
    }
}

class FakeChapterDao : ChapterDao {
    val chapters = mutableListOf<ChapterEntity>()
    val insertBatchSizes = mutableListOf<Int>()
    private var nextId = 1L

    override suspend fun insertAll(new: List<ChapterEntity>): List<Long> {
        insertBatchSizes += new.size
        return new.map { chapter ->
            val id = nextId++
            chapters += chapter.copy(id = id)
            id
        }
    }

    override suspend fun insert(chapter: ChapterEntity): Long {
        val id = nextId++
        chapters += chapter.copy(id = id)
        return id
    }

    override fun observeListItems(bookId: Long): Flow<List<ChapterListItem>> =
        MutableStateFlow(
            chapters.filter { it.bookId == bookId }
                .sortedBy { it.number }
                .map {
                    ChapterListItem(
                        it.id, it.number, it.title, it.status, it.origin, it.qualityScore,
                        translatedByProvider = it.translatedByProvider,
                        improvedAt = it.improvedAt
                    )
                }
        )

    override suspend fun getImproveHeads(
        bookId: Long
    ): List<app.retranslate.data.db.ChapterImproveHead> =
        chapters.filter {
            it.bookId == bookId && it.status == "TRANSLATED" && it.translatedText != null
        }.sortedBy { it.number }.map {
            app.retranslate.data.db.ChapterImproveHead(
                it.id, it.number, it.status, it.qualityScore,
                it.translatedByProvider, it.improvedAt, it.kbVersionUsed
            )
        }

    override suspend fun countImprovable(bookId: Long): Int =
        chapters.count {
            it.bookId == bookId && it.status == "TRANSLATED" && it.translatedText != null
        }

    override fun observeChapter(id: Long): Flow<ChapterEntity?> =
        MutableStateFlow(chapters.firstOrNull { it.id == id })

    override suspend fun getChapter(id: Long): ChapterEntity? =
        chapters.firstOrNull { it.id == id }

    override suspend fun getChapters(bookId: Long): List<ChapterEntity> =
        chapters.filter { it.bookId == bookId }.sortedBy { it.number }

    override suspend fun getChapterPage(
        bookId: Long, limit: Int, offset: Int
    ): List<ChapterEntity> =
        chapters.filter { it.bookId == bookId }
            .sortedBy { it.number }.drop(offset).take(limit)

    override suspend fun countByStatus(bookId: Long, status: String): Int =
        chapters.count { it.bookId == bookId && it.status == status }

    override suspend fun countForBook(bookId: Long): Int = chapters.count { it.bookId == bookId }

    override suspend fun countTranslatable(bookId: Long): Int =
        chapters.count {
            it.bookId == bookId &&
                it.status in setOf("PENDING", "PARTIAL", "FAILED") &&
                it.sourceText != null
        }

    override suspend fun getChapterHeads(bookId: Long): List<app.retranslate.data.db.ChapterHead> =
        chapters.filter { it.bookId == bookId }
            .sortedBy { it.number }
            .map { app.retranslate.data.db.ChapterHead(it.id, it.number, it.status) }

    override suspend fun getTranslatedPage(
        bookId: Long, limit: Int, offset: Int
    ): List<app.retranslate.data.db.ChapterTextSlice> =
        chapters.filter { it.bookId == bookId && it.translatedText != null }
            .sortedBy { it.number }
            .drop(offset).take(limit)
            .map {
                app.retranslate.data.db.ChapterTextSlice(it.id, it.number, it.translatedText)
            }

    override suspend fun getTranslatedExportPage(
        bookId: Long, limit: Int, offset: Int
    ): List<app.retranslate.data.db.ChapterExportSlice> =
        chapters.filter { it.bookId == bookId && it.translatedText != null }
            .sortedBy { it.number }
            .drop(offset).take(limit)
            .map {
                app.retranslate.data.db.ChapterExportSlice(
                    it.id, it.number, it.title, it.translatedText
                )
            }

    override suspend fun getLastCheckedChapter(
        bookId: Long
    ): app.retranslate.data.db.ChapterCheckSlice? =
        chapters.filter {
            it.bookId == bookId && (it.readProgress > 0f || it.lastReadParagraph > 0)
        }.maxByOrNull { it.updatedAt }
            ?.let {
                app.retranslate.data.db.ChapterCheckSlice(it.id, it.number, it.readProgress)
            }

    override suspend fun update(chapter: ChapterEntity) {
        val idx = chapters.indexOfFirst { it.id == chapter.id }
        if (idx >= 0) chapters[idx] = chapter
    }

    override suspend fun deleteForBook(bookId: Long) {
        chapters.removeAll { it.bookId == bookId }
    }
}

class FakeKnowledgeBaseDao : KnowledgeBaseDao {
    val kbs = mutableListOf<KnowledgeBaseEntity>()
    val entries = mutableListOf<KbEntryEntity>()
    private var nextKbId = 1L
    private var nextEntryId = 1L

    override suspend fun insertKb(kb: KnowledgeBaseEntity): Long {
        val id = nextKbId++
        kbs += kb.copy(id = id)
        return id
    }

    override suspend fun insertEntries(new: List<KbEntryEntity>): List<Long> =
        new.map { e ->
            val id = nextEntryId++
            entries += e.copy(id = id)
            id
        }

    override suspend fun getKbsForBook(bookId: Long): List<KnowledgeBaseEntity> =
        kbs.filter { it.bookId == bookId }.sortedBy { it.version }

    override suspend fun getActiveKb(bookId: Long): KnowledgeBaseEntity? =
        kbs.filter { it.bookId == bookId }.maxByOrNull { it.version }

    override fun observeActiveKb(bookId: Long): Flow<KnowledgeBaseEntity?> =
        MutableStateFlow(kbs.filter { it.bookId == bookId }.maxByOrNull { it.version })

    override fun observeEntries(kbId: Long): Flow<List<KbEntryEntity>> =
        MutableStateFlow(entries.filter { it.kbId == kbId })

    override suspend fun getEntries(kbId: Long): List<KbEntryEntity> =
        entries.filter { it.kbId == kbId }

    override suspend fun countEntries(kbId: Long): Int = entries.count { it.kbId == kbId }

    override suspend fun updateEntry(entry: KbEntryEntity) {
        val idx = entries.indexOfFirst { it.id == entry.id }
        if (idx >= 0) entries[idx] = entry
    }

    override suspend fun deleteEntry(id: Long) {
        entries.removeAll { it.id == id }
    }

    override suspend fun deleteEntriesForBook(bookId: Long) {
        val kbIds = kbs.filter { it.bookId == bookId }.map { it.id }.toSet()
        entries.removeAll { it.kbId in kbIds }
    }

    override suspend fun deleteKbsForBook(bookId: Long) {
        kbs.removeAll { it.bookId == bookId }
    }
}

class FakeSampleDao : SampleDao {
    val sets = mutableListOf<SampleSetEntity>()
    val chapters = mutableListOf<SampleChapterEntity>()
    private var nextSetId = 1L
    private var nextChapterId = 1L

    override suspend fun insertSet(sampleSet: SampleSetEntity): Long {
        val id = nextSetId++
        sets += sampleSet.copy(id = id)
        return id
    }

    override suspend fun insertChapters(new: List<SampleChapterEntity>): List<Long> =
        new.map { chapter ->
            val id = nextChapterId++
            chapters += chapter.copy(id = id)
            id
        }

    override fun observeSets(bookId: Long): Flow<List<SampleSetEntity>> =
        MutableStateFlow(sets.filter { it.bookId == bookId })

    override suspend fun getSets(bookId: Long): List<SampleSetEntity> =
        sets.filter { it.bookId == bookId }

    override suspend fun getChapters(setId: Long): List<SampleChapterEntity> =
        chapters.filter { it.sampleSetId == setId }.sortedBy { it.number }

    override suspend fun updateSet(sampleSet: SampleSetEntity) {
        val idx = sets.indexOfFirst { it.id == sampleSet.id }
        if (idx >= 0) sets[idx] = sampleSet
    }

    override suspend fun deleteChaptersForBook(bookId: Long) {
        val setIds = sets.filter { it.bookId == bookId }.map { it.id }.toSet()
        chapters.removeAll { it.sampleSetId in setIds }
    }

    override suspend fun deleteChaptersForSet(setId: Long) {
        chapters.removeAll { it.sampleSetId == setId }
    }

    override suspend fun deleteSetById(setId: Long) {
        sets.removeAll { it.id == setId }
    }

    override suspend fun deleteSetsForBook(bookId: Long) {
        sets.removeAll { it.bookId == bookId }
    }
}

class FakeCachedTranslationDao : CachedTranslationDao {
    private val byHash = mutableMapOf<String, CachedTranslationEntity>()
    override suspend fun getByHash(hash: String): CachedTranslationEntity? = byHash[hash]
    override suspend fun put(entry: CachedTranslationEntity): Long {
        byHash[entry.hash] = entry
        return 1
    }

    override suspend fun deleteOlderThan(before: Long) = Unit
}

class FakeProviderLanguageDao : ProviderLanguageDao {
    val rows = mutableListOf<ProviderLanguageEntity>()

    override suspend fun getForProvider(providerId: String): List<ProviderLanguageEntity> =
        rows.filter { it.providerId == providerId }

    override suspend fun insertAll(new: List<ProviderLanguageEntity>) {
        rows += new
    }

    override suspend fun deleteForProvider(providerId: String) {
        rows.removeAll { it.providerId == providerId }
    }
}

class FakeJobRunDao : JobRunDao {
    val runs = MutableStateFlow<List<JobRunEntity>>(emptyList())
    private var nextId = 1L

    override suspend fun insert(run: JobRunEntity): Long {
        val id = nextId++
        runs.value = runs.value + run.copy(id = id)
        return id
    }

    override suspend fun update(run: JobRunEntity) {
        runs.value = runs.value.map { if (it.id == run.id) run else it }
    }

    override fun observeRecent(limit: Int): Flow<List<JobRunEntity>> =
        runs.map { list -> list.sortedByDescending { it.startedAt }.take(limit) }

    override fun observeForBook(bookId: Long): Flow<List<JobRunEntity>> =
        runs.map { list ->
            list.filter { it.bookId == bookId }.sortedByDescending { it.startedAt }
        }

    override suspend fun latestForBook(bookId: Long): JobRunEntity? =
        runs.value.filter { it.bookId == bookId }.maxByOrNull { it.startedAt }

    override suspend fun getById(id: Long): JobRunEntity? =
        runs.value.firstOrNull { it.id == id }

    override suspend fun deleteForBook(bookId: Long) {
        runs.value = runs.value.filter { it.bookId != bookId }
    }
}
