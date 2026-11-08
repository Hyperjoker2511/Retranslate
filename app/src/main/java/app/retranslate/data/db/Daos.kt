package app.retranslate.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A book row plus its live chapter counts — the Library shelf's data (§9.3). */
data class BookShelfRow(
    @Embedded val book: BookEntity,
    val totalChapters: Int,
    val translatedChapters: Int
)

/** The chapter list's light projection — never loads chapter text (§12 flow 20). */
data class ChapterListItem(
    val id: Long,
    val number: Int,
    val title: String?,
    val status: String,
    val origin: String,
    val qualityScore: Float?,
    /** v1.1.3: who translated it ("OFFLINE" drives one Improve scope). */
    val translatedByProvider: String? = null,
    /** v1.1.3 (§A3): the durable "Improved with AI" marker, null = never. */
    val improvedAt: Long? = null
)

/**
 * v1.1.3 (§A2/§G): the polish engine's light projection — everything a
 * scope decision needs (status, score, translator, improve marker, KB
 * version), never chapter text.
 */
data class ChapterImproveHead(
    val id: Long,
    val number: Int,
    val status: String,
    val qualityScore: Float?,
    val translatedByProvider: String?,
    val improvedAt: Long?,
    val kbVersionUsed: Int?
)

/** The job engine's light projection: identity + order + state, no text (§7.3). */
data class ChapterHead(
    val id: Long,
    val number: Int,
    val status: String
)

/**
 * P5: one translated chapter's text, paged (§7.3) — the name-warning scan
 * and "Re-apply names" walk a book in slices instead of loading it whole.
 */
data class ChapterTextSlice(
    val id: Long,
    val number: Int,
    val translatedText: String?
)

/**
 * P6: one translated chapter ready for export (§7.5) — the writer streams
 * these page by page from Room, so a 1,500-chapter book is never held in
 * memory to be written out.
 */
data class ChapterExportSlice(
    val id: Long,
    val number: Int,
    val title: String?,
    val translatedText: String?
)

/**
 * P6: the chapter the user was last checking (§9.3 "Continue checking"
 * hero): the most recently touched chapter with a saved reading position.
 */
data class ChapterCheckSlice(
    val id: Long,
    val number: Int,
    val readProgress: Float
)

@Dao
interface BookDao {

    @Query(
        """
        SELECT books.*,
          (SELECT COUNT(*) FROM chapters WHERE chapters.bookId = books.id) AS totalChapters,
          (SELECT COUNT(*) FROM chapters WHERE chapters.bookId = books.id
             AND chapters.status = 'TRANSLATED') AS translatedChapters
        FROM books ORDER BY books.updatedAt DESC
        """
    )
    fun observeShelf(): Flow<List<BookShelfRow>>

    @Query("SELECT * FROM books ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeById(id: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: Long): BookEntity?

    /** Duplicate candidates (§7.1): same content hash; the caller compares titles. */
    @Query("SELECT * FROM books WHERE sourceFileHash = :hash")
    suspend fun findByHash(hash: String): List<BookEntity>

    @Insert
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ChapterDao {

    /** Import commit inserts in batches of 50, one transaction per batch (§7.1). */
    @Insert
    suspend fun insertAll(chapters: List<ChapterEntity>): List<Long>

    @Insert
    suspend fun insert(chapter: ChapterEntity): Long

    @Query(
        "SELECT id, number, title, status, origin, qualityScore, translatedByProvider, " +
            "improvedAt FROM chapters " +
            "WHERE bookId = :bookId ORDER BY number ASC"
    )
    fun observeListItems(bookId: Long): Flow<List<ChapterListItem>>

    /**
     * v1.1.3 (§A2): TRANSLATED chapters a polish/improve pass may work
     * on — identity + scope signals, no text (the engine loads one
     * chapter at a time, §7.3).
     */
    @Query(
        "SELECT id, number, status, qualityScore, translatedByProvider, improvedAt, " +
            "kbVersionUsed FROM chapters WHERE bookId = :bookId " +
            "AND status = 'TRANSLATED' AND translatedText IS NOT NULL " +
            "ORDER BY number ASC"
    )
    suspend fun getImproveHeads(bookId: Long): List<ChapterImproveHead>

    /** v1.1.3 (§G): the pre-flight estimate for a bulk Improve run. */
    @Query(
        "SELECT COUNT(*) FROM chapters WHERE bookId = :bookId " +
            "AND status = 'TRANSLATED' AND translatedText IS NOT NULL"
    )
    suspend fun countImprovable(bookId: Long): Int

    @Query("SELECT * FROM chapters WHERE id = :id")
    fun observeChapter(id: Long): Flow<ChapterEntity?>

    @Query("SELECT * FROM chapters WHERE id = :id")
    suspend fun getChapter(id: Long): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY number ASC")
    suspend fun getChapters(bookId: Long): List<ChapterEntity>

    /** P6 backup: full chapter rows, one page at a time (§7.3). */
    @Query(
        "SELECT * FROM chapters WHERE bookId = :bookId ORDER BY number ASC " +
            "LIMIT :limit OFFSET :offset"
    )
    suspend fun getChapterPage(bookId: Long, limit: Int, offset: Int): List<ChapterEntity>

    /** P4: the engine walks a book by heads and loads one chapter at a time. */
    @Query("SELECT id, number, status FROM chapters WHERE bookId = :bookId ORDER BY number ASC")
    suspend fun getChapterHeads(bookId: Long): List<ChapterHead>

    @Query("SELECT COUNT(*) FROM chapters WHERE bookId = :bookId AND status = :status")
    suspend fun countByStatus(bookId: Long, status: String): Int

    @Query("SELECT COUNT(*) FROM chapters WHERE bookId = :bookId")
    suspend fun countForBook(bookId: Long): Int

    /**
     * P4 job scope (§7.4): chapters a "translate all" job will work on —
     * unfinished and holding original text. A count query so a
     * 1,500-chapter book never loads its text just to size a job.
     */
    @Query(
        "SELECT COUNT(*) FROM chapters WHERE bookId = :bookId " +
            "AND status IN ('PENDING', 'PARTIAL', 'FAILED') AND sourceText IS NOT NULL"
    )
    suspend fun countTranslatable(bookId: Long): Int

    @Update
    suspend fun update(chapter: ChapterEntity)

    /**
     * P5: translated chapters of a book, one page at a time (KB name
     * warnings §6.5 + "Re-apply names" §6.3). Only chapters that HAVE a
     * translation are returned; callers loop until a short page arrives.
     */
    @Query(
        "SELECT id, number, translatedText FROM chapters WHERE bookId = :bookId " +
            "AND translatedText IS NOT NULL ORDER BY number ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getTranslatedPage(bookId: Long, limit: Int, offset: Int): List<ChapterTextSlice>

    /**
     * P6 export (§7.5): translated chapters WITH their titles, paged —
     * same walk as [getTranslatedPage], the export shape.
     */
    @Query(
        "SELECT id, number, title, translatedText FROM chapters WHERE bookId = :bookId " +
            "AND translatedText IS NOT NULL " +
            "ORDER BY number ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getTranslatedExportPage(
        bookId: Long,
        limit: Int,
        offset: Int
    ): List<ChapterExportSlice>

    /**
     * P6 (§9.3): the chapter this book's checking last touched — any
     * saved position counts, newest first.
     */
    @Query(
        "SELECT id, number, readProgress FROM chapters WHERE bookId = :bookId " +
            "AND (readProgress > 0 OR lastReadParagraph > 0) " +
            "ORDER BY updatedAt DESC LIMIT 1"
    )
    suspend fun getLastCheckedChapter(bookId: Long): ChapterCheckSlice?

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)
}

@Dao
interface KnowledgeBaseDao {

    @Insert
    suspend fun insertKb(kb: KnowledgeBaseEntity): Long

    @Insert
    suspend fun insertEntries(entries: List<KbEntryEntity>): List<Long>

    /** The active KB for a book is its highest version (§8.1). */
    @Query("SELECT * FROM knowledge_bases WHERE bookId = :bookId ORDER BY version DESC LIMIT 1")
    suspend fun getActiveKb(bookId: Long): KnowledgeBaseEntity?

    /** P6 backup: every KB version of a book, oldest first. */
    @Query("SELECT * FROM knowledge_bases WHERE bookId = :bookId ORDER BY version ASC")
    suspend fun getKbsForBook(bookId: Long): List<KnowledgeBaseEntity>

    @Query("SELECT * FROM knowledge_bases WHERE bookId = :bookId ORDER BY version DESC LIMIT 1")
    fun observeActiveKb(bookId: Long): Flow<KnowledgeBaseEntity?>

    @Query("SELECT * FROM kb_entries WHERE kbId = :kbId ORDER BY category, canonicalForm")
    fun observeEntries(kbId: Long): Flow<List<KbEntryEntity>>

    @Query("SELECT * FROM kb_entries WHERE kbId = :kbId ORDER BY category, canonicalForm")
    suspend fun getEntries(kbId: Long): List<KbEntryEntity>

    @Query("SELECT COUNT(*) FROM kb_entries WHERE kbId = :kbId")
    suspend fun countEntries(kbId: Long): Int

    @Update
    suspend fun updateEntry(entry: KbEntryEntity)

    @Query("DELETE FROM kb_entries WHERE id = :id")
    suspend fun deleteEntry(id: Long)

    /** v1.1 (book removal): every entry of every KB version the book owns. */
    @Query(
        "DELETE FROM kb_entries WHERE kbId IN " +
            "(SELECT id FROM knowledge_bases WHERE bookId = :bookId)"
    )
    suspend fun deleteEntriesForBook(bookId: Long)

    /** v1.1 (book removal): the book's own KB rows — KBs are per-book here. */
    @Query("DELETE FROM knowledge_bases WHERE bookId = :bookId")
    suspend fun deleteKbsForBook(bookId: Long)
}

@Dao
interface SampleDao {

    @Insert
    suspend fun insertSet(sampleSet: SampleSetEntity): Long

    @Insert
    suspend fun insertChapters(chapters: List<SampleChapterEntity>): List<Long>

    @Query("SELECT * FROM sample_sets WHERE bookId = :bookId ORDER BY id ASC")
    fun observeSets(bookId: Long): Flow<List<SampleSetEntity>>

    /** P5: the study engine reads the sets without collecting a flow. */
    @Query("SELECT * FROM sample_sets WHERE bookId = :bookId ORDER BY id ASC")
    suspend fun getSets(bookId: Long): List<SampleSetEntity>

    @Query("SELECT * FROM sample_chapters WHERE sampleSetId = :setId ORDER BY number ASC")
    suspend fun getChapters(setId: Long): List<SampleChapterEntity>

    @Update
    suspend fun updateSet(sampleSet: SampleSetEntity)

    /** v1.1 (sweep): remove ONE sample set's chapters (setup screen). */
    @Query("DELETE FROM sample_chapters WHERE sampleSetId = :setId")
    suspend fun deleteChaptersForSet(setId: Long)

    /** v1.1 (sweep): remove ONE sample set (setup screen). */
    @Query("DELETE FROM sample_sets WHERE id = :setId")
    suspend fun deleteSetById(setId: Long)

    /** v1.1 (book removal): the book's sample chapters, via their sets. */
    @Query(
        "DELETE FROM sample_chapters WHERE sampleSetId IN " +
            "(SELECT id FROM sample_sets WHERE bookId = :bookId)"
    )
    suspend fun deleteChaptersForBook(bookId: Long)

    /** v1.1 (book removal): the book's sample sets. */
    @Query("DELETE FROM sample_sets WHERE bookId = :bookId")
    suspend fun deleteSetsForBook(bookId: Long)
}

@Dao
interface CachedTranslationDao {

    @Query("SELECT * FROM cached_translations WHERE hash = :hash LIMIT 1")
    suspend fun getByHash(hash: String): CachedTranslationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: CachedTranslationEntity): Long

    @Query("DELETE FROM cached_translations WHERE createdAt < :before")
    suspend fun deleteOlderThan(before: Long)
}

@Dao
interface ProviderLanguageDao {

    @Query("SELECT * FROM provider_languages WHERE providerId = :providerId ORDER BY code")
    suspend fun getForProvider(providerId: String): List<ProviderLanguageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<ProviderLanguageEntity>)

    @Query("DELETE FROM provider_languages WHERE providerId = :providerId")
    suspend fun deleteForProvider(providerId: String)
}

@Dao
interface JobRunDao {

    @Insert
    suspend fun insert(run: JobRunEntity): Long

    @Update
    suspend fun update(run: JobRunEntity)

    @Query("SELECT * FROM job_runs ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<JobRunEntity>>

    @Query("SELECT * FROM job_runs WHERE bookId = :bookId ORDER BY startedAt DESC")
    fun observeForBook(bookId: Long): Flow<List<JobRunEntity>>

    @Query("SELECT * FROM job_runs WHERE bookId = :bookId ORDER BY startedAt DESC LIMIT 1")
    suspend fun latestForBook(bookId: Long): JobRunEntity?

    /** P4: the engine/scheduler read one run back by id (pause checks, counters). */
    @Query("SELECT * FROM job_runs WHERE id = :id")
    suspend fun getById(id: Long): JobRunEntity?

    /** v1.1 (book removal): the book's whole job history. */
    @Query("DELETE FROM job_runs WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)
}
