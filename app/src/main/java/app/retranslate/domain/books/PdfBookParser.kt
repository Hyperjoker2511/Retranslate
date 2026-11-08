package app.retranslate.domain.books

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import java.io.File

/**
 * PDF importer (PLAN.md §7.2): extract page-by-page via [PdfTextExtractor]
 * → clean the text (strip running heads/footers by repetition, join
 * hyphenated line breaks) → feed lines to the shared [ChapterSink] so
 * PDFs split into chapters exactly like TXTs.
 *
 * A PDF with no extractable text at all is a scanned document:
 * [AppError.ScannedPdf], an honest refusal — never an empty book.
 */
class PdfBookParser(private val extractor: PdfTextExtractor) : BookParser {

    override fun parse(
        staged: File,
        workDir: File,
        onProgress: (fraction: Float?) -> Unit
    ): AppResult<ParsedBook> {
        val rawFile = File(workDir, "pdf-raw.txt")
        val extraction = when (
            val result = extractor.extract(staged, rawFile) { done, total ->
                onProgress(if (total > 0) done.toFloat() / total * 0.8f else null)
            }
        ) {
            is AppResult.Err -> return result
            is AppResult.Ok -> result.value
        }
        if (extraction.pages <= 0) {
            return AppResult.Err(AppError.BadFile("this PDF has no pages"))
        }
        val cleanedFile = File(workDir, "pdf-clean.txt")
        val textChars = cleanPdfText(rawFile, cleanedFile)
        if (textChars <= 0) {
            return AppResult.Err(AppError.ScannedPdf)
        }
        val sink = ChapterSink(File(workDir, "chapters"))
        cleanedFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { sink.onLine(it) }
        }
        onProgress(1f)
        return AppResult.Ok(
            ParsedBook(
                title = extraction.title,
                author = null,
                declaredLang = null,
                coverFile = null,
                chapters = sink.finish()
            )
        )
    }

    companion object {
        /**
         * Streams [raw] (form-feed-separated pages) to [cleaned] with
         * running heads/footers removed and hyphenation joined; returns
         * the non-whitespace character count. Two passes over the temp
         * file; one page in memory at a time.
         */
        fun cleanPdfText(raw: File, cleaned: File): Int {
            // Pass 1: how often does each line appear as a page's
            // first/last line? Repetition = running head/footer.
            val firstCounts = mutableMapOf<String, Int>()
            val lastCounts = mutableMapOf<String, Int>()
            var pageCount = 0
            forEachPage(raw) { lines ->
                pageCount++
                lines.firstOrNull { it.isNotBlank() }?.trim()?.let {
                    firstCounts[it] = (firstCounts[it] ?: 0) + 1
                }
                lines.lastOrNull { it.isNotBlank() }?.trim()?.let {
                    lastCounts[it] = (lastCounts[it] ?: 0) + 1
                }
            }
            val threshold = if (pageCount >= 4) (pageCount + 1) / 2 else Int.MAX_VALUE
            val heads = firstCounts.filterValues { it >= threshold }.keys
            val feet = lastCounts.filterValues { it >= threshold }.keys
            val pageNumberLine = Regex("^\\d{1,5}$")

            // Pass 2: write cleaned lines.
            var chars = 0
            cleaned.bufferedWriter(Charsets.UTF_8).use { writer ->
                var pendingHyphenLine: String? = null
                fun emit(line: String) {
                    writer.write(line)
                    writer.write("\n")
                    chars += line.count { !it.isWhitespace() }
                }
                forEachPage(raw) { lines ->
                    val body = lines.toMutableList()
                    // Drop the running head (first non-blank) and foot (last non-blank).
                    val firstIdx = body.indexOfFirst { it.isNotBlank() }
                    if (firstIdx >= 0) {
                        val t = body[firstIdx].trim()
                        if (t in heads || pageNumberLine.matches(t)) body[firstIdx] = ""
                    }
                    val lastIdx = body.indexOfLast { it.isNotBlank() }
                    if (lastIdx >= 0 && lastIdx != firstIdx) {
                        val t = body[lastIdx].trim()
                        if (t in feet || pageNumberLine.matches(t)) body[lastIdx] = ""
                    }
                    for (rawLine in body) {
                        val line = rawLine.trimEnd()
                        val pending = pendingHyphenLine
                        if (pending != null) {
                            pendingHyphenLine = null
                            val first = line.trimStart().firstOrNull()
                            if (first != null && first.isLowerCase()) {
                                // "transla-" + "tion" → "translation"
                                emit(pending + line.trimStart())
                                continue
                            }
                            emit(pending)
                        }
                        if (line.endsWith('-') && line.length > 1 &&
                            line[line.lastIndex - 1].isLetter()
                        ) {
                            pendingHyphenLine = line.dropLast(1)
                            continue
                        }
                        emit(line)
                    }
                    emit("") // page break = paragraph boundary for the sink
                }
                pendingHyphenLine?.let { emit(it) }
            }
            return chars
        }

        /** Streams a form-feed-separated text file one page at a time. */
        private fun forEachPage(file: File, block: (List<String>) -> Unit) {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                val page = mutableListOf<String>()
                val current = StringBuilder()
                fun endLine() {
                    page += current.toString()
                    current.setLength(0)
                }
                fun endPage() {
                    endLine()
                    block(page.toList())
                    page.clear()
                }
                var ch = reader.read()
                while (ch >= 0) {
                    when (ch.toChar()) {
                        '\u000C' -> endPage()
                        '\n' -> endLine()
                        '\r' -> Unit
                        else -> current.append(ch.toChar())
                    }
                    ch = reader.read()
                }
                if (page.isNotEmpty() || current.isNotEmpty()) endPage()
            }
        }
    }
}
