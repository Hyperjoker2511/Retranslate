package app.retranslate.domain.export

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.core.error.runCatchingAppSuspend
import app.retranslate.data.files.TempFiles
import java.io.File

/**
 * Save-a-result-as-a-file (v1.1.3 §E): one chapter (or one result) as
 * .txt or .epub, reusing the book writers' exact shaping — the TXT
 * layout mirrors [BookExporter]'s, the EPUB is the real [EpubWriter]
 * with a one-chapter book (§G assumption 2: it takes plain data
 * classes, no book entity involved).
 *
 * ASK-FIRST is the caller's law (§E15): this class only writes a
 * TEMP file after the user has confirmed the save sheet; the screen
 * then hands the file to SAF/Share exactly like a book export.
 */
class ResultFileExporter(
    private val tempFiles: TempFiles,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /** The TXT body for one result, shaped like a book export. */
    fun txtFor(title: String, text: String): String = buildString {
        append(title)
        append("\n\n")
        append(EpubWriter.paragraphsOf(text).joinToString("\n\n"))
        append("\n")
    }

    /** Writes the confirmed format to a temp file for SAF/Share. */
    suspend fun writeTempFile(
        title: String,
        author: String?,
        targetLang: String,
        text: String,
        format: ExportFormat
    ): AppResult<ExportOutcome> = runCatchingAppSuspend({
        AppError.ExportFailed("the file couldn't be written")
    }) {
        val dir = tempFiles.newExportDir()
        val fileName = fileNameFor(title, format)
        val file = File(dir, fileName)
        when (format) {
            ExportFormat.TXT -> file.writeText(txtFor(title, text), Charsets.UTF_8)
            ExportFormat.EPUB -> {
                val chapters = listOf(
                    ExportChapter(number = 1, title = title, text = text)
                )
                var index = 0
                file.outputStream().use { out ->
                    EpubWriter().write(
                        meta = ExportBookMeta(
                            title = title,
                            author = author,
                            language = targetLang,
                            includeTranslatorNote = false,
                            modifiedUtc = modifiedUtcNow()
                        ),
                        out = out,
                        nextChapter = {
                            if (index < chapters.size) chapters[index++] else null
                        }
                    )
                }
            }
        }
        ExportOutcome(
            file = file,
            fileName = fileName,
            mimeType = format.mimeType,
            includedChapters = 1,
            skippedChapters = 0,
            totalChapters = 1
        )
    }

    /** "Chapter 12 — The Ambush.txt": the result's first line, cleaned. */
    fun fileNameFor(title: String, format: ExportFormat): String {
        val cleaned = title.trim()
            .replace(UNSAFE_FILENAME_CHARS, " ")
            .replace(WHITESPACE_RUNS, " ")
            .trim()
            .take(60)
            .ifBlank { "Chapter" }
        return "$cleaned.${format.extension}"
    }

    private fun modifiedUtcNow(): String {
        val format = java.text.SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US
        )
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return format.format(java.util.Date(clock()))
    }

    companion object {
        private val UNSAFE_FILENAME_CHARS = Regex("[/\\\\:*?\"<>|]")
        private val WHITESPACE_RUNS = Regex("\\s+")

        /** A save's suggested title: the result's first non-blank line. */
        fun suggestedTitle(text: String, fallback: String = "Chapter"): String =
            text.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?.take(80)
                ?: fallback
    }
}
