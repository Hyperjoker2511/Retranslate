package app.retranslate.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retranslate.domain.books.GenrePreset
import app.retranslate.providers.Languages
import app.retranslate.providers.QualityMode
import app.retranslate.ui.common.BounceButton
import app.retranslate.ui.common.EmptyState

/**
 * Import review (PLAN.md §7.1): the parsed book is shown — cover,
 * title, chapter count, detected language in plain words — and NOTHING
 * is saved until the user taps "Add to library". Unknown language gets
 * three big buttons (Korean / Chinese / English) plus the full list;
 * a duplicate gets its three-way choice. Never a dead end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportReviewScreen(
    viewModel: ImportReviewViewModel,
    onDone: (Long) -> Unit,
    onOpenExisting: (Long) -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pickingFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ImportReviewUiEvent.Done -> onDone(event.bookId)
                is ImportReviewUiEvent.OpenExisting -> onOpenExisting(event.bookId)
                ImportReviewUiEvent.Back -> onBack()
                is ImportReviewUiEvent.ShowMessage -> snackbar.showSnackbar(event.text)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Check this book") },
                navigationIcon = {
                    IconButton(onClick = viewModel::onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        if (state.missing) {
            EmptyState(
                title = "That import was interrupted",
                message = "The file wasn't saved. Pick it again from the Library.",
                actionLabel = "Back to Library",
                onAction = onBack,
                modifier = Modifier.padding(padding)
            )
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CoverImage(
                    path = state.coverPath,
                    modifier = Modifier.size(width = 72.dp, height = 104.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.formatText, style = MaterialTheme.typography.labelLarge)
                    Text(
                        "${state.chapterCount} chapters",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        formatCharCount(state.totalChars),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::setTitle,
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.author,
                onValueChange = viewModel::setAuthor,
                label = { Text("Author (optional)") },
                modifier = Modifier.fillMaxWidth()
            )

            if (state.needsLanguageChoice || state.sourceLang == null) {
                Text(
                    "What language is this book in?",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "The app couldn't tell. Pick the right one:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BounceButton(onClick = { viewModel.chooseSourceLang("ko") }) { Text("Korean") }
                    BounceButton(onClick = { viewModel.chooseSourceLang("zh-CN") }) { Text("Chinese") }
                    BounceButton(onClick = { viewModel.chooseSourceLang("en") }) { Text("English") }
                }
                TextButton(onClick = { pickingFor = "source" }) {
                    Text("Choose another language")
                }
            } else {
                LanguageRow(
                    label = "Original language",
                    code = state.sourceLang!!,
                    onChange = { pickingFor = "source" }
                )
            }
            LanguageRow(
                label = "Translate to",
                code = state.targetLang,
                onChange = { pickingFor = "target" }
            )
            if (state.sourceLang != null && state.sourceLang == state.targetLang) {
                Text(
                    "This book is already in ${Languages.nameOf(state.targetLang)} — " +
                        "it will be added as a finished (already translated) book.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text("Genre", style = MaterialTheme.typography.titleMedium)
            GenreChips(
                selected = GenrePreset.fromId(state.genreId) ?: GenrePreset.OTHER,
                onSelect = viewModel::setGenre
            )
            Text("Translation quality", style = MaterialTheme.typography.titleMedium)
            QualityChips(
                selected = QualityMode.entries.firstOrNull { it.name == state.qualityMode }
                    ?: QualityMode.BALANCED,
                onSelect = viewModel::setQuality
            )

            state.duplicateTitle?.let { existing ->
                Text(
                    "You already have a book called “$existing” from this same file. " +
                        "Adding will ask you what to do.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(4.dp))
            BounceButton(
                onClick = viewModel::onAddClicked,
                enabled = !state.isCommitting,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isCommitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text("Add to library")
            }
            OutlinedButton(
                onClick = viewModel::onBack,
                enabled = !state.isCommitting,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Cancel") }
            Spacer(Modifier.height(24.dp))
        }
    }

    pickingFor?.let { which ->
        LanguagePickerDialog(
            title = if (which == "source") "Original language" else "Translate to",
            onPick = { language ->
                if (which == "source") viewModel.chooseSourceLang(language.code)
                else viewModel.chooseTargetLang(language.code)
                pickingFor = null
            },
            onDismiss = { pickingFor = null }
        )
    }

    if (state.showDuplicateDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDuplicateDialog,
            title = { Text("You already have this book") },
            text = {
                Text(
                    "“${state.duplicateTitle}” is already in your library from this " +
                        "exact file. Open that one, or import this as a separate copy?"
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::onOpenExisting) { Text("Open the existing book") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = viewModel::onImportAsSeparateCopy) {
                        Text("Import as a separate copy")
                    }
                    TextButton(onClick = viewModel::dismissDuplicateDialog) { Text("Cancel") }
                }
            }
        )
    }

    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            title = { Text("Couldn't add the book") },
            text = { Text(error.message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissError) { Text(error.recoveryLabel) }
            }
        )
    }
}

@Composable
private fun LanguageRow(label: String, code: String, onChange: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(Languages.nameOf(code), style = MaterialTheme.typography.bodyLarge)
        }
        TextButton(onClick = onChange) { Text("Change") }
    }
}
