package app.retranslate.domain.kb

import app.retranslate.data.db.ChapterDao

/** One chapter's consistency facts (§G): violations found, worst first. */
data class ChapterConsistency(
    val chapterId: Long,
    val number: Int,
    val violationCount: Int,
    val distinctNames: Int
)

/** One wrong spelling across the book: how bad, and how widespread. */
data class VariantTotal(
    val canonicalForm: String,
    val variantFound: String,
    val occurrences: Int,
    val chapterCount: Int
)

/** The book-wide Consistency check's answer, in plain countable facts. */
data class ConsistencyReport(
    val chaptersScanned: Int,
    val chaptersWithViolations: Int,
    val totalViolations: Int,
    /** Chapters with violations, most violations first (ties: earliest). */
    val worstChapters: List<ChapterConsistency>,
    /** Wrong spellings, most occurrences first. */
    val variants: List<VariantTotal>,
    /** No Knowledge Base → nothing to check against (said in words by the UI). */
    val hasKnowledgeBase: Boolean
)

/**
 * The book-wide Consistency check (v1.1.3 §G candidate 2 — the free
 * half of the user's Consistency & Repair vision): scan EVERY
 * translated chapter against the book's Knowledge Base with the
 * existing [KbCompliance] engine — no AI calls, no new scoring
 * engine (§G candidate 9's skip) — and answer his first question:
 * "how bad is this book, and where?"
 *
 * Chapters are read in pages (§7.3: a 1,500-chapter book is never
 * held in memory), and the per-chapter violation counts double as
 * the free quality signal for imported chapters whose score is null.
 */
class ConsistencyScanner(
    private val chapterDao: ChapterDao,
    private val kbRepository: KbRepository
) {

    suspend fun scan(bookId: Long): ConsistencyReport {
        val entries = kbRepository.activeEntries(bookId)
        if (entries.isEmpty()) {
            return ConsistencyReport(
                chaptersScanned = 0,
                chaptersWithViolations = 0,
                totalViolations = 0,
                worstChapters = emptyList(),
                variants = emptyList(),
                hasKnowledgeBase = false
            )
        }
        var scanned = 0
        var totalViolations = 0
        val perChapter = mutableListOf<ChapterConsistency>()
        // (canonical, variant) → occurrences + the chapters it appears in
        val variantStats = mutableMapOf<Pair<String, String>, Pair<Int, MutableSet<Long>>>()
        var offset = 0
        while (true) {
            val page = chapterDao.getTranslatedPage(bookId, PAGE_SIZE, offset)
            if (page.isEmpty()) break
            for (slice in page) {
                val text = slice.translatedText ?: continue
                scanned++
                val violations = KbCompliance.findViolations(text, entries)
                if (violations.isEmpty()) continue
                val count = violations.sumOf { it.occurrences }
                totalViolations += count
                perChapter += ChapterConsistency(
                    chapterId = slice.id,
                    number = slice.number,
                    violationCount = count,
                    distinctNames = violations.map { it.entryId }.distinct().size
                )
                for (violation in violations) {
                    val key = violation.canonicalForm to violation.variantFound
                    val (occurrences, chapters) = variantStats[key]
                        ?: (0 to mutableSetOf())
                    chapters += slice.id
                    variantStats[key] = (occurrences + violation.occurrences) to chapters
                }
            }
            if (page.size < PAGE_SIZE) break
            offset += PAGE_SIZE
        }
        return ConsistencyReport(
            chaptersScanned = scanned,
            chaptersWithViolations = perChapter.size,
            totalViolations = totalViolations,
            worstChapters = perChapter.sortedWith(
                compareByDescending<ChapterConsistency> { it.violationCount }
                    .thenBy { it.number }
            ),
            variants = variantStats.entries.map { (key, stats) ->
                VariantTotal(
                    canonicalForm = key.first,
                    variantFound = key.second,
                    occurrences = stats.first,
                    chapterCount = stats.second.size
                )
            }.sortedWith(
                compareByDescending<VariantTotal> { it.occurrences }
                    .thenBy { it.canonicalForm }
            ),
            hasKnowledgeBase = true
        )
    }

    companion object {
        const val PAGE_SIZE = 100
    }
}
