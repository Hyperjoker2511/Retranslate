package app.retranslate.domain.books

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction

/**
 * The §7.1 parse contract: a staged file + a work directory in, a
 * [ParsedBook] of on-disk chapters out. Every implementation streams —
 * no parser ever returns chapter text in memory for a whole book.
 */
interface BookParser {
    fun parse(
        staged: File,
        workDir: File,
        onProgress: (fraction: Float?) -> Unit
    ): AppResult<ParsedBook>
}

/**
 * TXT importer (PLAN.md §7.2): scored charset detection over the file
 * head, then a line-streamed decode into the shared [ChapterSink].
 * Memory stays flat on any file size (§7.3) — the parser holds one line
 * and one paragraph at a time.
 */
class TxtBookParser : BookParser {

    override fun parse(
        staged: File,
        workDir: File,
        onProgress: (fraction: Float?) -> Unit
    ): AppResult<ParsedBook> {
        return try {
            val sample = readSample(staged)
            val charset = CharsetDecoder.detect(sample)
            val sink = ChapterSink(File(workDir, "chapters"))
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            staged.inputStream().buffered().use { input ->
                val reader = InputStreamReader(input, decoder).buffered()
                reader.useLines { lines -> lines.forEach { sink.onLine(it) } }
            }
            onProgress(1f)
            AppResult.Ok(
                ParsedBook(
                    title = null, // the importer falls back to the file name
                    author = null,
                    declaredLang = null,
                    coverFile = null,
                    chapters = sink.finish()
                )
            )
        } catch (e: IOException) {
            AppResult.Err(AppError.BadFile("the text file couldn't be read"))
        } catch (e: Exception) {
            AppResult.Err(AppError.Unknown(e))
        }
    }

    private fun readSample(file: File): ByteArray {
        val size = minOf(CharsetDecoder.SAMPLE_BYTES.toLong(), file.length()).toInt()
        if (size <= 0) return ByteArray(0)
        val buffer = ByteArray(size)
        var read = 0
        file.inputStream().use { input ->
            while (read < size) {
                val n = input.read(buffer, read, size - read)
                if (n < 0) break
                read += n
            }
        }
        return if (read == size) buffer else buffer.copyOf(read)
    }
}
