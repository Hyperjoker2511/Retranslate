package app.retranslate.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room schema v1 (PLAN.md §8.1). Every table the plan names exists from
 * this first schema — features that use a table land in later phases
 * (jobs P4, KB P5, export cache P6), but the schema itself never needs a
 * destructive change to grow into them (§8.3: no destructive migrations).
 *
 * Enum-valued columns are stored as their `name` strings and decoded
 * defensively on read (see [DbEnums]): an unknown stored value coerces to
 * a safe default instead of crashing — §10's defensive-read rule, the one
 * whose absence corrupted the old app's data.
 */

@Entity(tableName = "books", indices = [Index("sourceFileHash")])
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val titleOriginal: String,
    val titleTranslated: String? = null,
    val author: String? = null,
    /** Canonical app language codes (§4.6), validated at the import boundary. */
    val sourceLang: String,
    val targetLang: String,
    /** [app.retranslate.domain.books.GenrePreset] id, "" = none chosen. */
    val genrePresetId: String = "",
    /** [app.retranslate.providers.QualityMode] name. */
    val qualityMode: String = "BALANCED",
    val polishEnabled: Boolean = false,
    /** App-private cover image path (BookFiles), null when the file had none. */
    val coverPath: String? = null,
    /** SHA-256 of the imported file — duplicate detection (§7.1). */
    val sourceFileHash: String? = null,
    val chapterCountCache: Int = 0,
    /**
     * UTF-8 byte size of all chapter text, cached at import (mirrors
     * [chapterCountCache]'s precedent — the book page shows a size, §9.3,
     * and summing 1,500 chapters per render is wasteful).
     */
    val sizeBytesCache: Long = 0,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["bookId", "number"], unique = true), Index("bookId")]
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    /** 1-based chapter number within the book. */
    val number: Int,
    val title: String? = null,
    /**
     * Nullable on purpose (§8.1): an imported already-translated book has
     * translation only. No code path may assume both texts exist.
     */
    val sourceText: String? = null,
    val translatedText: String? = null,
    /** [ChapterOrigin] name. */
    val origin: String = ChapterOrigin.IMPORTED_SOURCE.name,
    /** [ChapterStatus] name. */
    val status: String = ChapterStatus.PENDING.name,
    /** P4: which chunks of a half-done chapter are finished (JSON). */
    val partialStateJson: String? = null,
    /** Local verifier score 0–100 (P4/P5), null until first translation. */
    val qualityScore: Float? = null,
    val translatedByProvider: String? = null,
    val kbVersionUsed: Int? = null,
    /**
     * v1.1.3 (§A): when this chapter was last improved by AI (Improve /
     * polish), null = never improved. The durable "Improved with AI"
     * marker the chapter row and Review header read, and the
     * "Not improved yet" bulk-scope signal (§G) — deliberately NOT
     * folded into [translatedByProvider], which must keep naming the
     * translator (the "Offline-translated" Improve scope reads it).
     */
    val improvedAt: Long? = null,
    /** Reading position for the review screen (P6): 0f–1f + paragraph. */
    val readProgress: Float = 0f,
    val lastReadParagraph: Int = 0,
    val updatedAt: Long
)

@Entity(
    tableName = "knowledge_bases",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId")]
)
data class KnowledgeBaseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    /** Increments on every study / manual edit batch (§6.3). */
    val version: Int,
    val createdAt: Long,
    /** Where this version came from, in words ("Studied 10 sample chapters"). */
    val sourceNote: String = ""
)

@Entity(
    tableName = "kb_entries",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeBaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["kbId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("kbId")]
)
data class KbEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kbId: Long,
    /** [KbCategory] name. */
    val category: String,
    /** The name/term as it appears in the original language, when known. */
    val sourceForm: String? = null,
    /** The canonical rendering every translation must use. */
    val canonicalForm: String,
    /** [KbGender] name — characters only. */
    val gender: String? = null,
    val note: String? = null,
    /** Locked entries are never changed by re-study (§6.3). */
    val locked: Boolean = false,
    /** How often the entry appeared in the studied samples (injection order). */
    val frequency: Int = 0
)

@Entity(
    tableName = "sample_sets",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId")]
)
data class SampleSetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val fileName: String,
    val detectedLang: String,
    val chapterCount: Int = 0,
    /** Set once this set has been folded into a Knowledge Base version (P5). */
    val studiedInKbVersion: Int? = null
)

@Entity(
    tableName = "sample_chapters",
    foreignKeys = [
        ForeignKey(
            entity = SampleSetEntity::class,
            parentColumns = ["id"],
            childColumns = ["sampleSetId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sampleSetId")]
)
data class SampleChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sampleSetId: Long,
    val number: Int,
    val text: String
)

@Entity(tableName = "cached_translations", indices = [Index(value = ["hash"], unique = true)])
data class CachedTranslationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** SHA-256 over source text + pair + provider + model + KB version (§5.5). */
    val hash: String,
    val sourceLang: String,
    val targetLang: String,
    val providerId: String,
    val model: String? = null,
    val kbVersion: Int? = null,
    val resultText: String,
    val createdAt: Long
)

/** §4.6: a provider's fetched language list, cached 7 days. */
@Entity(tableName = "provider_languages", primaryKeys = ["providerId", "code"])
data class ProviderLanguageEntity(
    val providerId: String,
    val code: String,
    val displayName: String,
    val fetchedAt: Long
)

@Entity(
    tableName = "job_runs",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId")]
)
data class JobRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    /** [JobKind] name. */
    val kind: String,
    /** [JobState] name. */
    val state: String,
    val totalChapters: Int = 0,
    val doneChapters: Int = 0,
    val failedChapters: Int = 0,
    val switchedProviders: Int = 0,
    /**
     * v1.1.3 (§A2): POLISH runs only — chapters the verifier ACCEPTED.
     * Polish outcomes are never discarded again: applied / rejected /
     * failed are three separate, durable counters.
     */
    val polishApplied: Int = 0,
    /** v1.1.3 (§A2): POLISH runs only — chapters the verifier rejected (text kept). */
    val polishRejected: Int = 0,
    /**
     * v1.1.3 (§G): a POLISH run's scope ([PolishScope] name) and
     * strength ([ImproveStrength] name), stored so Resume after a
     * quota pause — even after the app was closed — continues the
     * SAME job instead of guessing. Null on non-polish runs.
     */
    val polishScope: String? = null,
    val polishStrength: String? = null,
    /**
     * v1.1.3 (§I): a TRANSLATE run's engine pick — a ProviderId name
     * (or a gateway slot key) when the user picked one engine for the
     * job; null = Automatic (the cascade). The worker resolves it.
     */
    val providerPick: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
    /** Error class name only (§10) — never content. */
    val lastErrorClass: String? = null
)

/** String-stored enums with defensive decode (§10 defensive reads). */
object DbEnums {

    fun chapterStatus(stored: String?): ChapterStatus =
        ChapterStatus.entries.firstOrNull { it.name == stored } ?: ChapterStatus.PENDING

    fun chapterOrigin(stored: String?): ChapterOrigin =
        ChapterOrigin.entries.firstOrNull { it.name == stored } ?: ChapterOrigin.IMPORTED_SOURCE

    fun kbCategory(stored: String?): KbCategory =
        KbCategory.entries.firstOrNull { it.name == stored } ?: KbCategory.TERM

    fun kbGender(stored: String?): KbGender? =
        stored?.let { g -> KbGender.entries.firstOrNull { it.name == g } }

    fun jobKind(stored: String?): JobKind =
        JobKind.entries.firstOrNull { it.name == stored } ?: JobKind.TRANSLATE

    fun jobState(stored: String?): JobState =
        JobState.entries.firstOrNull { it.name == stored } ?: JobState.QUEUED
}

enum class ChapterStatus { PENDING, PARTIAL, TRANSLATED, FAILED }

enum class ChapterOrigin { APP_TRANSLATED, IMPORTED_SOURCE, IMPORTED_TRANSLATION }

enum class KbCategory { CHARACTER, LOCATION, ORGANISATION, TECHNIQUE, ITEM, TERM, TONE_RULE }

enum class KbGender { MALE, FEMALE, UNKNOWN }

enum class JobKind { TRANSLATE, STUDY, EXPORT, POLISH }

enum class JobState { QUEUED, RUNNING, PAUSED, FINISHED, FAILED, CANCELLED }
