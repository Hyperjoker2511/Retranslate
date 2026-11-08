package app.retranslate.domain.books

import app.retranslate.core.error.AppError
import java.io.File
import java.util.zip.ZipFile

/**
 * Format sniffing (PLAN.md §7.1): magic bytes first, extension as the
 * cross-check — a renamed file can't smuggle the wrong parser in.
 * Returns null when the file is a valid candidate for [TxtBookParser].
 */
object FormatSniffer {

    fun sniff(staged: File, displayName: String): Pair<ImportFormat, AppError?> {
        if (staged.length() == 0L) {
            return ImportFormat.TXT to AppError.BadFile("the file is empty")
        }
        val extension = displayName.substringAfterLast('.', "").lowercase()
        val head = ByteArray(8)
        var read = 0
        staged.inputStream().use { input ->
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
        }
        val isPdf = read >= 5 && String(head, 0, 5, Charsets.US_ASCII) == "%PDF-"
        val isZip = read >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            head[2] == 3.toByte() && head[3] == 4.toByte()

        if (isPdf) return ImportFormat.PDF to null
        if (extension == "pdf") {
            return ImportFormat.PDF to
                AppError.BadFile("this doesn't look like a PDF file")
        }
        if (isZip) {
            // A zip is an EPUB only if it carries the EPUB structure.
            val looksEpub = try {
                ZipFile(staged).use { zip ->
                    zip.getEntry("META-INF/container.xml") != null ||
                        zip.getEntry("mimetype") != null
                }
            } catch (e: Exception) {
                false
            }
            return if (looksEpub) ImportFormat.EPUB to null
            else ImportFormat.EPUB to AppError.BadFile("this is a ZIP file, not an EPUB book")
        }
        if (extension == "epub") {
            return ImportFormat.EPUB to
                AppError.BadFile("this doesn't look like an EPUB file")
        }
        return ImportFormat.TXT to null
    }
}
