package app.retranslate.data.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Delivering a finished file to the user (§7.5): the two ways out of
 * the app are the SAF save dialog (the user picks where it lands)
 * and the Android Share sheet. Both stream from the temp file —
 * nothing is copied into memory. The Share path goes through a
 * FileProvider (manifest, authority [AUTHORITY]) so no file:// URI
 * ever leaves the app.
 */
object FileDelivery {

    const val AUTHORITY = "app.retranslate.files"

    /** A shareable content:// URI for a temp/cache file. */
    fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, AUTHORITY, file)

    /** The ACTION_SEND intent for [file]; wrap in a chooser to show it. */
    fun shareIntent(context: Context, file: File, mimeType: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, shareUri(context, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /**
     * Copies [file] into the SAF destination the user picked.
     * Returns false on any I/O failure — the caller surfaces
     * §10 #15 ("Couldn't save the file there") and the temp file
     * stays available for Share instead.
     */
    suspend fun copyTo(context: Context, file: File, destination: Uri): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val out = context.contentResolver.openOutputStream(destination)
                    ?: return@withContext false
                out.use { sink ->
                    file.inputStream().buffered().use { source ->
                        source.copyTo(sink, 64 * 1024)
                    }
                }
                true
            } catch (e: Exception) {
                false
            }
        }
}
