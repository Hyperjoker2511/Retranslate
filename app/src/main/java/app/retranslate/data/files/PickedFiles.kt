package app.retranslate.data.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream

/** A file the user picked via SAF, ready to hand to the importer (§7.1). */
data class PickedFile(
    val displayName: String,
    /** Bytes, or -1 when the provider doesn't report a size. */
    val sizeBytes: Long,
    val openStream: () -> InputStream?
)

/**
 * Reads SAF picker results (name + size from the document provider).
 * The only Android-facing piece of the import pipeline; everything
 * downstream of [PickedFile] is plain streams and files.
 */
class PickedFiles(private val context: Context) {

    fun describe(uri: Uri): PickedFile {
        var name = "book"
        var size = -1L
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) {
                        cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { name = it }
                    }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        size = cursor.getLong(sizeIdx)
                    }
                }
            }
        } catch (e: Exception) {
            // A provider that won't describe itself: the importer still
            // works from the stream alone (name falls back, size unknown).
        }
        val resolver = context.contentResolver
        return PickedFile(
            displayName = name,
            sizeBytes = size,
            openStream = {
                try {
                    resolver.openInputStream(uri)
                } catch (e: Exception) {
                    null
                }
            }
        )
    }
}
