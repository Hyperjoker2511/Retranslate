package app.retranslate.domain.books

import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.io.IOException

/** What a PDF text extraction produced: the raw text file + metadata. */
data class PdfExtraction(
    val pages: Int,
    val title: String?
)

/**
 * The PDF extraction boundary (§7.2). Behind an interface so the
 * pipeline (cleaning, splitting, honest errors) is JVM-testable with a
 * fake extractor; the real implementation is PDFBox-Android.
 *
 * Implementations write the extracted text to [outText] page by page,
 * pages separated by a form-feed character, and never return page text
 * in memory beyond one page.
 */
interface PdfTextExtractor {
    fun extract(
        pdf: File,
        outText: File,
        onProgress: (pagesDone: Int, pagesTotal: Int) -> Unit
    ): AppResult<PdfExtraction>
}

/**
 * PDFBox-Android implementation: the document is loaded with
 * temp-file-only memory settings (§7.3 — a 100 MB PDF must not sit in
 * RAM), then stripped one page at a time (the plan's 50-page-range rule
 * is the ceiling; per-page stripping is what the running-head detection
 * in [PdfBookParser] needs, and it bounds memory to a single page).
 */
class PdfBoxTextExtractor : PdfTextExtractor {

    override fun extract(
        pdf: File,
        outText: File,
        onProgress: (Int, Int) -> Unit
    ): AppResult<PdfExtraction> {
        return try {
            val document = PDDocument.load(pdf, MemoryUsageSetting.setupTempFileOnly())
            document.use { doc ->
                if (doc.isEncrypted) {
                    return AppResult.Err(AppError.BadFile("this PDF is password-protected"))
                }
                val pages = doc.numberOfPages
                val title = doc.documentInformation?.title?.trim()?.takeIf { it.isNotEmpty() }
                outText.bufferedWriter(Charsets.UTF_8).use { writer ->
                    for (page in 0 until pages) {
                        val stripper = PDFTextStripper()
                        stripper.startPage = page + 1
                        stripper.endPage = page + 1
                        writer.write(stripper.getText(doc) ?: "")
                        writer.write("\u000C")
                        onProgress(page + 1, pages)
                    }
                }
                AppResult.Ok(PdfExtraction(pages = pages, title = title))
            }
        } catch (e: InvalidPasswordException) {
            AppResult.Err(AppError.BadFile("this PDF is password-protected"))
        } catch (e: IOException) {
            AppResult.Err(AppError.BadFile("the PDF couldn't be read"))
        } catch (e: Exception) {
            AppResult.Err(AppError.Unknown(e))
        }
    }
}
