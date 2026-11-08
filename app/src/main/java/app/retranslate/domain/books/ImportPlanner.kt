package app.retranslate.domain.books

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import java.io.File

/**
 * The ImportPlanner (PLAN.md §7.1/§7.2): applies the split/merge rules
 * and validates everything before it may reach the database — the
 * boundary that keeps bad file data out of Room (lesson: values are
 * normalised HERE, never trusted downstream).
 *
 * Rules:
 *  - EPUB only: spine items under [MERGE_FORWARD_MAX] chars merge
 *    forward into the next chapter (title pages don't become chapters).
 *  - All formats: chapters over [SPLIT_THRESHOLD] split at paragraph
 *    boundaries; no chapter can ever exceed the §7.3 ceiling.
 *  - Empty chapters are dropped; a book with no readable text at all is
 *    a [AppError.BadFile], never an empty book.
 *  - Consecutive duplicate titles are suffixed ("… (2)") — that's how
 *    split continuations are named.
 *  - Title/author trimmed; declared language normalised or null.
 */
object ImportPlanner {

    const val MERGE_FORWARD_MAX = 300
    const val SPLIT_THRESHOLD = 200_000

    fun plan(parsed: ParsedBook, format: ImportFormat, workDir: File): AppResult<PlannedBook> {
        var chapters = parsed.chapters
        if (format == ImportFormat.EPUB) {
            chapters = mergeForwardSmall(chapters, workDir)
        }
        chapters = splitOversized(chapters, workDir)
        chapters = dropEmpty(chapters)
        if (chapters.isEmpty()) {
            return AppResult.Err(AppError.BadFile("the file has no readable text"))
        }
        chapters = suffixDuplicateTitles(chapters)
        return AppResult.Ok(
            PlannedBook(
                title = parsed.title?.trim()?.takeIf { it.isNotEmpty() },
                author = parsed.author?.trim()?.takeIf { it.isNotEmpty() },
                declaredLang = LanguageCodes.normalise(parsed.declaredLang),
                coverFile = parsed.coverFile,
                chapters = chapters
            )
        )
    }

    // -- merge forward (EPUB) ---------------------------------------------

    private fun mergeForwardSmall(chapters: List<ParsedChapter>, workDir: File): List<ParsedChapter> {
        if (chapters.size < 2) return chapters
        val out = mutableListOf<ParsedChapter>()
        var pendingText: String? = null
        var pendingTitles: String? = null
        var seq = 0

        fun writeMerged(text: String, title: String?): ParsedChapter {
            val file = File(workDir, "plan-%06d.txt".format(seq++))
            file.writeText(text, Charsets.UTF_8)
            return ParsedChapter(title = title, file = file, charCount = text.length)
        }

        for (chapter in chapters) {
            if (chapter.charCount in 1 until MERGE_FORWARD_MAX) {
                // Small item: hold its text to prepend to the next chapter.
                val text = readChapterText(chapter)
                pendingText = if (pendingText == null) text else pendingText + "\n\n" + text
                if (pendingTitles == null) pendingTitles = chapter.title
                chapter.file.delete()
                // A long run of tiny items must not grow unboundedly.
                if ((pendingText?.length ?: 0) >= SPLIT_THRESHOLD) {
                    out += writeMerged(pendingText!!, pendingTitles)
                    pendingText = null
                    pendingTitles = null
                }
                continue
            }
            val pending = pendingText
            if (pending != null) {
                val main = readChapterText(chapter)
                out += writeMerged(pending + "\n\n" + main, chapter.title ?: pendingTitles)
                chapter.file.delete()
                pendingText = null
                pendingTitles = null
            } else {
                out += chapter
            }
        }
        val leftover = pendingText
        if (leftover != null) {
            // Trailing small items append to the last real chapter.
            val last = out.lastOrNull()
            if (last != null) {
                val merged = readChapterText(last) + "\n\n" + leftover
                out[out.lastIndex] = writeMerged(merged, last.title)
                last.file.delete()
            } else {
                out += writeMerged(leftover, pendingTitles)
            }
        }
        return out
    }

    // -- split oversized -----------------------------------------------------

    private fun splitOversized(chapters: List<ParsedChapter>, workDir: File): List<ParsedChapter> {
        var seq = 0
        val out = mutableListOf<ParsedChapter>()
        for (chapter in chapters) {
            if (chapter.charCount <= SPLIT_THRESHOLD) {
                out += chapter
                continue
            }
            val text = readChapterText(chapter)
            val paragraphs = text.split("\n\n")
            val piece = StringBuilder()
            fun flushPiece() {
                if (piece.isEmpty()) return
                val file = File(workDir, "plan-%06d.txt".format(seq++))
                val content = piece.toString()
                file.writeText(content, Charsets.UTF_8)
                out += ParsedChapter(title = chapter.title, file = file, charCount = content.length)
                piece.setLength(0)
            }
            for (paragraph in paragraphs) {
                if (paragraph.isEmpty()) continue
                if (piece.isNotEmpty() && piece.length + paragraph.length > SPLIT_THRESHOLD) {
                    flushPiece()
                }
                if (piece.isNotEmpty()) piece.append("\n\n")
                piece.append(paragraph)
                // A single paragraph beyond the threshold stands alone —
                // still one chapter in RAM, the §7.3 allowance.
                if (piece.length >= SPLIT_THRESHOLD) flushPiece()
            }
            flushPiece()
            chapter.file.delete()
        }
        return out
    }

    // -- tidy -----------------------------------------------------------------

    private fun dropEmpty(chapters: List<ParsedChapter>): List<ParsedChapter> =
        chapters.filter { chapter ->
            val keep = chapter.charCount > 0 && chapter.file.isFile &&
                chapter.file.length() > 0
            if (!keep) chapter.file.delete()
            keep
        }

    private fun suffixDuplicateTitles(chapters: List<ParsedChapter>): List<ParsedChapter> {
        val seen = mutableMapOf<String, Int>()
        return chapters.map { chapter ->
            val title = chapter.title?.trim()?.takeIf { it.isNotEmpty() } ?: return@map chapter
            val count = (seen[title] ?: 0) + 1
            seen[title] = count
            if (count == 1) chapter.copy(title = title)
            else chapter.copy(title = "$title ($count)")
        }
    }

    private fun readChapterText(chapter: ParsedChapter): String =
        chapter.file.readText(Charsets.UTF_8)
}
