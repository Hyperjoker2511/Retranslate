package app.retranslate.domain.books

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import app.retranslate.data.files.BookFiles
import app.retranslate.data.files.TempFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * The import orchestrator (PLAN.md §7.1):
 *
 * ```
 * picked file → streamed copy to staging (with progress + SHA-256)
 *   → format sniff (magic bytes, not extension alone)
 *   → streaming parse to temp chapter files
 *   → ImportPlanner (split/merge/validate)
 *   → language detection (declared → script detector → ask the user)
 *   → ImportSession for the review screen → commit on confirm
 * ```
 *
 * Nothing touches Room until [commit]; a failed or abandoned import
 * cleans its temp directory and leaves the library untouched.
 */
class BookImporter(
    private val tempFiles: TempFiles,
    private val repository: BookRepository,
    private val bookFiles: BookFiles,
    private val epubParser: BookParser = EpubParser(),
    private val txtParser: BookParser = TxtBookParser(),
    private val pdfParser: BookParser = PdfBookParser(PdfBoxTextExtractor()),
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * Stages and parses a picked file. [openStream] is called once, on
     * the IO dispatcher. [sizeBytes] <= 0 means "unknown" (progress
     * becomes indeterminate rather than wrong).
     */
    suspend fun importFile(
        openStream: () -> InputStream?,
        displayName: String,
        sizeBytes: Long,
        onProgress: (ImportProgress) -> Unit
    ): AppResult<ImportSession> = withContext(ioDispatcher) {
        val workDir = tempFiles.newImportDir()
        try {
            onProgress(ImportProgress(ImportStage.COPYING, 0f))
            val staged = File(workDir, "staged.bin")
            val sha256 = copyToStaging(openStream, staged, sizeBytes) { fraction ->
                onProgress(ImportProgress(ImportStage.COPYING, fraction))
            } ?: run {
                tempFiles.cleanup(workDir)
                return@withContext AppResult.Err(
                    AppError.BadFile("the file couldn't be opened")
                )
            }

            val (format, sniffError) = FormatSniffer.sniff(staged, displayName)
            if (sniffError != null) {
                tempFiles.cleanup(workDir)
                return@withContext AppResult.Err(sniffError)
            }

            onProgress(ImportProgress(ImportStage.READING, null))
            val parser = when (format) {
                ImportFormat.EPUB -> epubParser
                ImportFormat.TXT -> txtParser
                ImportFormat.PDF -> pdfParser
            }
            val parsed = when (
                val result = parser.parse(staged, workDir) { fraction ->
                    onProgress(ImportProgress(ImportStage.READING, fraction))
                }
            ) {
                is AppResult.Err -> {
                    tempFiles.cleanup(workDir)
                    return@withContext result
                }
                is AppResult.Ok -> result.value
            }

            onProgress(ImportProgress(ImportStage.CHECKING, null))
            val planned = when (val result = ImportPlanner.plan(parsed, format, workDir)) {
                is AppResult.Err -> {
                    tempFiles.cleanup(workDir)
                    return@withContext result
                }
                is AppResult.Ok -> result.value
            }

            val detectedLang = planned.declaredLang
                ?: LanguageDetector.detect(sampleText(planned))
            val fallbackTitle = displayName.substringBeforeLast('.', displayName)
            val duplicate = repository.findDuplicate(
                sha256,
                planned.title ?: fallbackTitle
            )
            AppResult.Ok(
                ImportSession(
                    workDir = workDir,
                    displayName = displayName,
                    format = format,
                    sha256 = sha256,
                    planned = planned,
                    detectedLang = detectedLang,
                    duplicate = duplicate
                )
            )
        } catch (e: CancellationException) {
            tempFiles.cleanup(workDir)
            throw e
        } catch (e: Exception) {
            tempFiles.cleanup(workDir)
            AppResult.Err(AppError.Unknown(e))
        }
    }

    /** Commits a reviewed session to the library and cleans up staging. */
    suspend fun commit(
        session: ImportSession,
        choices: ImportChoices
    ): AppResult<Long> = withContext(ioDispatcher) {
        try {
            repository.commitImport(session.planned, choices, session.sha256, bookFiles)
        } finally {
            tempFiles.cleanup(session.workDir)
        }
    }

    /** Abandons a session (user backed out of the review screen). */
    fun discard(session: ImportSession) {
        tempFiles.cleanup(session.workDir)
    }

    /** Streamed copy with an 8 KB buffer (§7.3), hashing as it goes. */
    private fun copyToStaging(
        openStream: () -> InputStream?,
        staged: File,
        sizeBytes: Long,
        onFraction: (Float?) -> Unit
    ): String? {
        val input = openStream() ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        try {
            input.buffered().use { source ->
                staged.outputStream().buffered().use { sink ->
                    val buffer = ByteArray(8 * 1024)
                    var n = source.read(buffer)
                    while (n >= 0) {
                        sink.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        copied += n
                        onFraction(
                            if (sizeBytes > 0) (copied.toFloat() / sizeBytes).coerceIn(0f, 1f)
                            else null
                        )
                        n = source.read(buffer)
                    }
                }
            }
        } catch (e: IOException) {
            return null
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Up to ~30k characters from the first chapters, for the detector. */
    private fun sampleText(planned: PlannedBook): String {
        val builder = StringBuilder()
        for (chapter in planned.chapters) {
            if (builder.length >= 30_000) break
            val file = chapter.file
            if (!file.isFile) continue
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4096)
                var read = reader.read(buffer)
                while (read >= 0 && builder.length < 30_000) {
                    builder.append(buffer, 0, read)
                    read = reader.read(buffer)
                }
            }
        }
        return builder.toString()
    }
}
