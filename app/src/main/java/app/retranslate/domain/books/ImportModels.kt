package app.retranslate.domain.books

import app.retranslate.data.db.BookEntity
import java.io.File

/** The three importable formats (PLAN.md §7). Sniffed by magic bytes + extension. */
enum class ImportFormat { EPUB, TXT, PDF }

/**
 * One parsed chapter on disk (PLAN.md §7.1): parsers write chapter text
 * to temp files — UTF-8, paragraphs separated by a blank line — so no
 * stage ever holds a whole book in memory. [charCount] is the text
 * length, counted while streaming.
 */
data class ParsedChapter(
    val title: String?,
    val file: File,
    val charCount: Int
)

/** A parser's raw output, before the [ImportPlanner] applies its rules. */
data class ParsedBook(
    val title: String?,
    val author: String?,
    /** Language as the file declares it (EPUB metadata), unnormalised. */
    val declaredLang: String?,
    val coverFile: File?,
    val chapters: List<ParsedChapter>
)

/** The planner's validated output — the only shape allowed near the DB (§7.1). */
data class PlannedBook(
    val title: String?,
    val author: String?,
    /** Normalised canonical code (§4.6), or null if the file's claim was unusable. */
    val declaredLang: String?,
    val coverFile: File?,
    val chapters: List<ParsedChapter>
) {
    val totalChars: Int get() = chapters.sumOf { it.charCount }
}

/** Import stages, surfaced to the UI as a progress state (§3.2, §7.1). */
enum class ImportStage { COPYING, READING, CHECKING }

data class ImportProgress(
    val stage: ImportStage,
    /** 0f–1f when known (copy bytes, PDF pages), null when indeterminate. */
    val fraction: Float?
)

/**
 * One in-flight import: everything the review screen needs, held between
 * "file picked" and "Add to library" (§7.1). Lives in temp storage; if
 * the process dies mid-import the session is simply gone and the user
 * picks the file again — nothing half-written ever reaches Room.
 */
data class ImportSession(
    val workDir: File,
    val displayName: String,
    val format: ImportFormat,
    /** SHA-256 of the staged file — duplicate detection (§7.1). */
    val sha256: String,
    val planned: PlannedBook,
    /** Final detected language (declared → detector → null = ask the user). */
    val detectedLang: String?,
    /** Set when the same file (hash + title) already exists in the library. */
    val duplicate: BookEntity?
)

/** What the user confirmed on the review screen — commit's only input. */
data class ImportChoices(
    val title: String,
    val author: String?,
    val sourceLang: String,
    val targetLang: String,
    val genrePresetId: String,
    val qualityMode: String,
    val importAsSeparateCopy: Boolean = false
)

/**
 * Genre presets (PLAN.md §9.3 plain names; §5.4 prompt hints). The hint
 * text feeds AI prompt construction in P4/P5 — stored per book from
 * import day one so no migration is ever needed for it.
 */
enum class GenrePreset(
    val id: String,
    val displayName: String,
    val promptHint: String
) {
    CULTIVATION(
        "cultivation", "Cultivation / Martial arts",
        "Cultivation novel: keep realm/rank names consistent, use 'Senior/Junior' " +
            "for martial-family address, preserve technique names as proper nouns."
    ),
    GAME_SYSTEM(
        "game_system", "Game-like system",
        "System novel: keep [system window] text in brackets exactly formatted, " +
            "stats and skill names as proper nouns."
    ),
    HISTORICAL(
        "historical", "Palace / Historical",
        "Historical/palace novel: formal court address (Your Majesty, this servant), " +
            "titles of nobility kept consistent."
    ),
    MODERN_ROMANCE(
        "modern_romance", "Modern / Romance",
        "Modern novel: natural contemporary dialogue, keep company and brand names as-is."
    ),
    OTHER("other", "Other", "");

    companion object {
        fun fromId(id: String?): GenrePreset? = entries.firstOrNull { it.id == id }
    }
}
