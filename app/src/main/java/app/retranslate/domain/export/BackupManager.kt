package app.retranslate.domain.export

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.core.error.runCatchingAppSuspend
import app.retranslate.data.db.BookDao
import app.retranslate.data.db.BookEntity
import app.retranslate.data.db.ChapterDao
import app.retranslate.data.db.ChapterEntity
import app.retranslate.data.db.KbEntryEntity
import app.retranslate.data.db.KnowledgeBaseDao
import app.retranslate.data.db.KnowledgeBaseEntity
import app.retranslate.data.db.TxRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.encodeToStream
import java.io.InputStream
import java.io.OutputStream

/** One typed settings value inside a backup (§9.3). */
@Serializable
data class BackupSettingValue(val kind: String, val value: String)

/**
 * Where settings live, from the backup's point of view (production:
 * [app.retranslate.data.prefs.DataStoreSettingsBackup]). An interface
 * so backup/restore is JVM-testable — and so the KeyVault stays
 * structurally out of reach: this bridge has no path to keys.
 */
interface SettingsBackupSource {
    suspend fun snapshotSettings(): Map<String, BackupSettingValue>
    suspend fun applySettings(values: Map<String, BackupSettingValue>)
    suspend fun snapshotProviderInstances(): Map<String, List<String>>
    suspend fun applyProviderInstances(instances: Map<String, List<String>>)
}

data class BackupSummary(val books: Int, val chapters: Int, val kbEntries: Int)

data class RestoreSummary(
    val restoredBooks: Int,
    val skippedBooks: Int,
    val restoredChapters: Int,
    val settingsApplied: Boolean
)

/**
 * Backup & restore (§9.3, §14.4): ONE file holding books (metadata +
 * every chapter), Knowledge Bases (every version, so chapters'
 * `kbVersionUsed` stays meaningful) and settings — API keys are
 * never included; they live in the KeyVault, which this code cannot
 * reach.
 *
 * The file is a stream of JSON records (header, settings, then per
 * book: book → chapters → KB versions → entries → bookEnd), written
 * and read one record at a time, so a library of any size backs up
 * and restores without ever being whole in memory (§7.3). Restore is
 * MERGE: a book that already exists (same source-file hash + title,
 * or same title + languages for manual books) is skipped, never
 * overwritten. Each restored book lands completely or not at all:
 * a book counts as landed only when its bookEnd record (with the
 * chapter count) has been read — if the stream breaks before that,
 * the partial book is removed again, while books completed before
 * it stay.
 *
 * Sample files are NOT in the backup (they're re-importable working
 * material, §9.3 names books + KB + settings only); covers aren't
 * either — a restored book shows the placeholder cover.
 */
@OptIn(ExperimentalSerializationApi::class)
class BackupManager(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val knowledgeBaseDao: KnowledgeBaseDao,
    private val tx: TxRunner,
    private val settings: SettingsBackupSource,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val json = Json { ignoreUnknownKeys = true }

    // -- write ------------------------------------------------------------

    suspend fun writeBackup(
        out: OutputStream,
        onProgress: (fraction: Float?) -> Unit = {}
    ): AppResult<BackupSummary> = runCatchingAppSuspend(
        { AppError.ExportFailed("couldn't write the backup file") }
    ) {
        withContext(Dispatchers.IO) {
            var booksWritten = 0
            var chapters = 0
            var kbEntries = 0
            out.buffered().use { sink ->
                fun record(value: BackupRecord) {
                    json.encodeToStream(BackupRecord.serializer(), value, sink)
                    sink.write('\n'.code)
                }
                record(
                    BackupRecord(
                        type = "header",
                        header = BackupHeader(FORMAT_VERSION, clock())
                    )
                )
                record(
                    BackupRecord(
                        type = "settings",
                        settings = settings.snapshotSettings(),
                        providerInstances = settings.snapshotProviderInstances()
                    )
                )
                val books = bookDao.observeAll().first()
                var bookIndex = 0
                for (book in books) {
                    val key = bookKey(book)
                    var bookChapters = 0
                    record(BackupRecord(type = "book", bookKey = key, book = book.toBackup()))
                    var offset = 0
                    while (true) {
                        val page = chapterDao.getChapterPage(book.id, PAGE_SIZE, offset)
                        if (page.isEmpty()) break
                        for (chapter in page) {
                            record(
                                BackupRecord(
                                    type = "chapter", bookKey = key,
                                    chapter = chapter.toBackup()
                                )
                            )
                            chapters++
                            bookChapters++
                        }
                        if (page.size < PAGE_SIZE) break
                        offset += page.size
                    }
                    for (kb in knowledgeBaseDao.getKbsForBook(book.id)) {
                        record(
                            BackupRecord(
                                type = "kb", bookKey = key,
                                kb = BackupKb(kb.version, kb.createdAt, kb.sourceNote)
                            )
                        )
                        for (entry in knowledgeBaseDao.getEntries(kb.id)) {
                            record(
                                BackupRecord(
                                    type = "kbEntry", bookKey = key,
                                    kbVersion = kb.version,
                                    kbEntry = entry.toBackup()
                                )
                            )
                            kbEntries++
                        }
                    }
                    // The commit marker: restore only trusts this
                    // book once this record (and its count) arrives.
                    record(
                        BackupRecord(
                            type = "bookEnd", bookKey = key,
                            chapterCount = bookChapters
                        )
                    )
                    booksWritten++
                    bookIndex++
                    onProgress(
                        if (books.isNotEmpty()) bookIndex.toFloat() / books.size else null
                    )
                }
            }
            BackupSummary(booksWritten, chapters, kbEntries)
        }
    }

    // -- restore ------------------------------------------------------------

    suspend fun restoreBackup(openStream: () -> InputStream?): AppResult<RestoreSummary> {
        val stream = openStream()
            ?: return AppResult.Err(AppError.BadFile("the backup file couldn't be opened"))
        return runCatchingAppSuspend(
            { e ->
                when {
                    e is BackupFormatException ->
                        AppError.BadFile(e.message ?: "not a backup file")
                    e is kotlinx.serialization.SerializationException ->
                        AppError.BadFile("this file couldn't be read as a backup")
                    else -> AppError.StorageFailed("restoring the backup")
                }
            }
        ) {
            withContext(Dispatchers.IO) {
                stream.buffered().use { input ->
                    restore(input)
                }
            }
        }
    }

    private suspend fun restore(input: InputStream): RestoreSummary {
        var headerSeen = false
        var restoredBooks = 0
        var skippedBooks = 0
        var restoredChapters = 0
        var settingsApplied = false

        // The book currently streaming in. Until its bookEnd record
        // arrives it is UNCOMMITTED: an error from here deletes it
        // again (a half-restored book never stays).
        var openBookId: Long? = null
        var openBookCommitted = false
        var openBookChapters = 0
        var skipping = false
        var currentKbIds = mutableMapOf<Int, Long>() // backup version → new row id
        var chapterBatch = mutableListOf<ChapterEntity>()

        suspend fun flushChapters(bookId: Long) {
            if (chapterBatch.isEmpty()) return
            val batch = chapterBatch.toList()
            chapterBatch = mutableListOf()
            tx.run { chapterDao.insertAll(batch.map { it.copy(bookId = bookId) }) }
            restoredChapters += batch.size
            openBookChapters += batch.size
        }

        try {
            val records = json.decodeToSequence(input, BackupRecord.serializer())
            for (record in records) {
                when (record.type) {
                    "header" -> {
                        val header = record.header
                            ?: throw BackupFormatException("this file isn't a ReTranslate backup")
                        if (header.formatVersion > FORMAT_VERSION) {
                            throw BackupFormatException(
                                "this backup was made by a newer version of the app"
                            )
                        }
                        headerSeen = true
                    }
                    "settings" -> {
                        if (!headerSeen) throw BackupFormatException(
                            "this file isn't a ReTranslate backup"
                        )
                        record.settings?.let { settings.applySettings(it) }
                        record.providerInstances?.let { settings.applyProviderInstances(it) }
                        settingsApplied = true
                    }
                    "book" -> {
                        // A new book record closes the previous book.
                        openBookId?.let { flushChapters(it) }
                        openBookId = null
                        openBookCommitted = false
                        openBookChapters = 0
                        currentKbIds = mutableMapOf()
                        val dto = record.book
                            ?: throw BackupFormatException("the backup has a broken book entry")
                        if (isDuplicate(dto)) {
                            skipping = true
                            skippedBooks++
                        } else {
                            skipping = false
                            tx.run {
                                openBookId = bookDao.insert(dto.toEntity())
                            }
                            restoredBooks++
                        }
                    }
                    "bookEnd" -> {
                        if (!skipping) {
                            val bookId = openBookId ?: throw BackupFormatException(
                                "the backup ends a book that never started"
                            )
                            flushChapters(bookId)
                            val expected = record.chapterCount
                            if (expected != null && expected != openBookChapters) {
                                throw BackupFormatException(
                                    "the backup's chapter count doesn't add up"
                                )
                            }
                            openBookCommitted = true
                        }
                    }
                    "chapter" -> {
                        if (skipping) continue
                        val bookId = openBookId ?: throw BackupFormatException(
                            "the backup has a chapter without its book"
                        )
                        val dto = record.chapter
                            ?: throw BackupFormatException("the backup has a broken chapter")
                        chapterBatch += dto.toEntity(bookId)
                        if (chapterBatch.size >= PAGE_SIZE) flushChapters(bookId)
                    }
                    "kb" -> {
                        if (skipping) continue
                        val bookId = openBookId ?: throw BackupFormatException(
                            "the backup has a Knowledge Base without its book"
                        )
                        flushChapters(bookId)
                        val dto = record.kb
                            ?: throw BackupFormatException("the backup has a broken Knowledge Base")
                        tx.run {
                            currentKbIds[dto.version] = knowledgeBaseDao.insertKb(
                                KnowledgeBaseEntity(
                                    bookId = bookId,
                                    version = dto.version,
                                    createdAt = dto.createdAt,
                                    sourceNote = dto.sourceNote
                                )
                            )
                        }
                    }
                    "kbEntry" -> {
                        if (skipping) continue
                        val kbId = currentKbIds[record.kbVersion]
                            ?: throw BackupFormatException(
                                "the backup has a Knowledge Base entry without its base"
                            )
                        val dto = record.kbEntry
                            ?: throw BackupFormatException("the backup has a broken entry")
                        tx.run {
                            knowledgeBaseDao.insertEntries(listOf(dto.toEntity(kbId)))
                        }
                    }
                    else -> Unit // unknown record kinds are ignored (forward-compat)
                }
            }
            if (!headerSeen) {
                throw BackupFormatException("this file isn't a ReTranslate backup")
            }
            openBookId?.let { flushChapters(it) }
            openBookId = null
        } catch (e: Exception) {
            // Compensation: an UNCOMMITTED book (its bookEnd never
            // arrived) goes away whole; committed books stay (§9.3).
            val uncommitted = openBookId?.takeIf { !openBookCommitted }
            if (uncommitted != null) {
                try {
                    bookDao.deleteById(uncommitted)
                } catch (ignored: Exception) {
                    // Best effort — the typed error below is what matters.
                }
            }
            throw e
        }
        return RestoreSummary(restoredBooks, skippedBooks, restoredChapters, settingsApplied)
    }

    // -- helpers -------------------------------------------------------------

    /** Stable per-book identity within one backup file. */
    private fun bookKey(book: BookEntity): String =
        book.sourceFileHash ?: "t:${book.titleOriginal}:${book.createdAt}"

    private suspend fun isDuplicate(dto: BackupBookDto): Boolean {
        if (dto.sourceFileHash != null) {
            val byHash = bookDao.findByHash(dto.sourceFileHash)
            if (byHash.any { it.titleOriginal == dto.titleOriginal }) return true
        }
        return allBooks().any {
            it.titleOriginal == dto.titleOriginal &&
                it.sourceLang == dto.sourceLang && it.targetLang == dto.targetLang &&
                dto.sourceFileHash == null && it.sourceFileHash == null
        }
    }

    private suspend fun allBooks(): List<BookEntity> =
        bookDao.observeAll().first()

    private class BackupFormatException(message: String) : Exception(message)

    companion object {
        const val FORMAT_VERSION = 1
        const val PAGE_SIZE = 50
        const val FILE_NAME = "retranslate-backup.json"
        const val MIME_TYPE = "application/json"
    }
}

// -- backup record shapes ---------------------------------------------------

@Serializable
data class BackupHeader(val formatVersion: Int, val createdAt: Long)

@Serializable
data class BackupBookDto(
    val titleOriginal: String,
    val titleTranslated: String? = null,
    val author: String? = null,
    val sourceLang: String,
    val targetLang: String,
    val genrePresetId: String = "",
    val qualityMode: String = "BALANCED",
    val polishEnabled: Boolean = false,
    val sourceFileHash: String? = null,
    val chapterCountCache: Int = 0,
    val sizeBytesCache: Long = 0,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class BackupChapterDto(
    val number: Int,
    val title: String? = null,
    val sourceText: String? = null,
    val translatedText: String? = null,
    val origin: String,
    val status: String,
    val partialStateJson: String? = null,
    val qualityScore: Float? = null,
    val translatedByProvider: String? = null,
    val kbVersionUsed: Int? = null,
    val readProgress: Float = 0f,
    val lastReadParagraph: Int = 0,
    val updatedAt: Long
)

@Serializable
data class BackupKb(val version: Int, val createdAt: Long, val sourceNote: String = "")

@Serializable
data class BackupKbEntryDto(
    val category: String,
    val sourceForm: String? = null,
    val canonicalForm: String,
    val gender: String? = null,
    val note: String? = null,
    val locked: Boolean = false,
    val frequency: Int = 0
)

/** One line of the backup file. Exactly one payload is set per type. */
@Serializable
data class BackupRecord(
    val type: String,
    val header: BackupHeader? = null,
    val settings: Map<String, BackupSettingValue>? = null,
    val providerInstances: Map<String, List<String>>? = null,
    val bookKey: String? = null,
    val book: BackupBookDto? = null,
    val chapter: BackupChapterDto? = null,
    /** Set on the "bookEnd" commit record: the book's chapter count. */
    val chapterCount: Int? = null,
    val kb: BackupKb? = null,
    val kbVersion: Int? = null,
    val kbEntry: BackupKbEntryDto? = null
)

// -- entity ↔ DTO --------------------------------------------------------------

private fun BookEntity.toBackup() = BackupBookDto(
    titleOriginal = titleOriginal,
    titleTranslated = titleTranslated,
    author = author,
    sourceLang = sourceLang,
    targetLang = targetLang,
    genrePresetId = genrePresetId,
    qualityMode = qualityMode,
    polishEnabled = polishEnabled,
    sourceFileHash = sourceFileHash,
    chapterCountCache = chapterCountCache,
    sizeBytesCache = sizeBytesCache,
    createdAt = createdAt,
    updatedAt = updatedAt
)

private fun BackupBookDto.toEntity() = BookEntity(
    titleOriginal = titleOriginal,
    titleTranslated = titleTranslated,
    author = author,
    sourceLang = sourceLang,
    targetLang = targetLang,
    genrePresetId = genrePresetId,
    qualityMode = qualityMode,
    polishEnabled = polishEnabled,
    coverPath = null, // covers are files, not backup content (§9.3)
    sourceFileHash = sourceFileHash,
    chapterCountCache = chapterCountCache,
    sizeBytesCache = sizeBytesCache,
    createdAt = createdAt,
    updatedAt = updatedAt
)

private fun ChapterEntity.toBackup() = BackupChapterDto(
    number = number,
    title = title,
    sourceText = sourceText,
    translatedText = translatedText,
    origin = origin,
    status = status,
    partialStateJson = partialStateJson,
    qualityScore = qualityScore,
    translatedByProvider = translatedByProvider,
    kbVersionUsed = kbVersionUsed,
    readProgress = readProgress,
    lastReadParagraph = lastReadParagraph,
    updatedAt = updatedAt
)

private fun BackupChapterDto.toEntity(bookId: Long) = ChapterEntity(
    bookId = bookId,
    number = number,
    title = title,
    sourceText = sourceText,
    translatedText = translatedText,
    origin = origin,
    status = status,
    partialStateJson = partialStateJson,
    qualityScore = qualityScore,
    translatedByProvider = translatedByProvider,
    kbVersionUsed = kbVersionUsed,
    readProgress = readProgress,
    lastReadParagraph = lastReadParagraph,
    updatedAt = updatedAt
)

private fun KbEntryEntity.toBackup() = BackupKbEntryDto(
    category = category,
    sourceForm = sourceForm,
    canonicalForm = canonicalForm,
    gender = gender,
    note = note,
    locked = locked,
    frequency = frequency
)

private fun BackupKbEntryDto.toEntity(kbId: Long) = KbEntryEntity(
    kbId = kbId,
    category = category,
    sourceForm = sourceForm,
    canonicalForm = canonicalForm,
    gender = gender,
    note = note,
    locked = locked,
    frequency = frequency
)
