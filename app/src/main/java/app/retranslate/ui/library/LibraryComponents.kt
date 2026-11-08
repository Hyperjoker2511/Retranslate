package app.retranslate.ui.library

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.retranslate.providers.Language
import app.retranslate.providers.Languages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A book cover from its app-private file (decoded off the main thread).
 * Books without a cover get a plain placeholder panel — never a broken
 * image icon.
 */
@Composable
fun CoverImage(path: String?, modifier: Modifier = Modifier) {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = if (path == null) null else withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
    }
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "Book cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                Icons.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The full canonical language list as a simple dialog (PLAN.md §9.3:
 * words, not flags alone). Used wherever a language can be changed —
 * review screen "another language", book page Change, manual entry.
 */
@Composable
fun LanguagePickerDialog(
    title: String,
    languages: List<Language> = Languages.ALL,
    onPick: (Language) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(languages, key = { it.code }) { language ->
                    TextButton(
                        onClick = { onPick(language) },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Text(
                            language.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** "1,540 chapters · about 3.2 MB of text" style sizes, in plain units. */
fun formatTextSize(bytes: Long): String = when {
    bytes <= 0 -> "No text yet"
    bytes < 1_000 -> "$bytes bytes"
    bytes < 1_000_000 -> "${bytes / 1_000} KB"
    bytes < 10_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    else -> "${bytes / 1_000_000} MB"
}

fun formatCharCount(chars: Int): String = "%,d characters".format(chars)
