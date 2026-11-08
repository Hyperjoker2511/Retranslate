package app.retranslate.ui.kb

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retranslate.ui.common.BounceButton
import app.retranslate.ui.common.EmptyState

/**
 * Study setup (PLAN.md §6.1): add sample files (separate files, the
 * user's settled pattern), see each one's language in plain words,
 * choose which sample chapters to study and which book chapters to
 * translate, then Study. When study finishes, the Knowledge Base
 * review is one tap away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudySetupScreen(
    viewModel: StudySetupViewModel,
    onBack: () -> Unit,
    onOpenKnowledgeBase: () -> Unit,
    onSetUpKey: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // v1.1 (sweep): the sample set a "Remove" tap is confirming.
    var removingSet by remember {
        androidx.compose.runtime.mutableStateOf<SampleSetRow?>(null)
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.addSampleFiles(uris) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is StudySetupUiEvent.ShowMessage -> snackbar.showSnackbar(event.text)
                StudySetupUiEvent.OpenKnowledgeBase -> onOpenKnowledgeBase()
                StudySetupUiEvent.SetUpKey -> onSetUpKey()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Learn from samples", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding)) {
                CircularProgressIndicator(Modifier.padding(24.dp))
            }
            state.book == null -> EmptyState(
                title = "This book isn't here",
                message = "It may have been deleted.",
                actionLabel = "Back",
                onAction = onBack,
                modifier = Modifier.padding(padding)
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        "The AI reads your best sample chapters and learns the names, " +
                            "places and terms — then every new chapter uses them, so " +
                            "names stay the same across the whole book.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                if (!state.hasKey) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "Studying needs an AI key",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    "The free Gemini key is the usual way — it takes " +
                                        "about a minute to set up. Without it, you can " +
                                        "still add names by hand on the Knowledge Base screen.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    BounceButton(onClick = viewModel::setUpKey) {
                                        Text("Set up key")
                                    }
                                    OutlinedButton(onClick = viewModel::openKnowledgeBase) {
                                        Text("Knowledge Base")
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Text("Sample files", style = MaterialTheme.typography.titleMedium)
                }
                if (state.sets.isEmpty()) {
                    item {
                        Text(
                            "No samples yet. Add files with chapters you trust — for " +
                                "example the well-translated first chapters of this novel.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                state.sets.forEach { row ->
                    item(key = row.set.id) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    row.set.fileName,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${row.languageText} · ${row.set.chapterCount} chapters" +
                                        if (row.alreadyStudied) " · already studied" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "Study chapters:",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                ChapterRangeRow(
                                    from = row.fromChapter,
                                    to = row.toChapter,
                                    max = row.set.chapterCount,
                                    onChange = { from, to ->
                                        viewModel.setSampleRange(row.set.id, from, to)
                                    }
                                )
                                // v1.1 (sweep): a sample added by mistake
                                // can be taken off again (previously the
                                // list only ever grew).
                                androidx.compose.material3.TextButton(
                                    onClick = { removingSet = row }
                                ) {
                                    Text(
                                        "Remove this sample file",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    BounceButton(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        enabled = !state.importing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (state.importing) "Reading file…" else "Add sample files")
                    }
                }
                if (state.hasBothLanguages) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Text(
                                "You have both the original and the translation — " +
                                    "that's the strongest learning setup: names get paired exactly.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }

                item {
                    Text("Study", style = MaterialTheme.typography.titleMedium)
                }
                item {
                    val run = state.studyRun
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            state.studyActive -> {
                                Text(
                                    "Studying your samples… this can take a minute. " +
                                        "You can leave — it keeps going.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                OutlinedButton(onClick = viewModel::cancelStudy) {
                                    Text("Cancel study")
                                }
                            }
                            run?.state == "FINISHED" -> {
                                Text(
                                    "Study finished — check the names it learned, " +
                                        "fix anything wrong, then translate.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                BounceButton(
                                    onClick = viewModel::openKnowledgeBase,
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Review Knowledge Base") }
                                OutlinedButton(
                                    onClick = viewModel::startStudy,
                                    enabled = state.sets.isNotEmpty(),
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Study again") }
                            }
                            run?.state == "FAILED" -> {
                                Text(
                                    studyFailureText(run.lastErrorClass),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                                BounceButton(
                                    onClick = viewModel::startStudy,
                                    enabled = state.sets.isNotEmpty(),
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Try studying again") }
                            }
                            else -> BounceButton(
                                onClick = viewModel::startStudy,
                                enabled = state.sets.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Study samples") }
                        }
                    }
                }

                item {
                    Text(
                        "Chapters to translate",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChapterRangeRow(
                            from = state.targetFrom,
                            to = state.targetTo,
                            max = state.totalChapters,
                            onChange = viewModel::setTargetRange
                        )
                        Text(
                            "${state.targetCount} chapters in this range still need translating.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        BounceButton(
                            onClick = viewModel::translateTargets,
                            enabled = state.targetCount > 0,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Translate these chapters") }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    removingSet?.let { row ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { removingSet = null },
            title = { Text("Remove this sample file?") },
            text = {
                Text(
                    "“${row.set.fileName}” will be taken off this book's " +
                        "sample list. Names already learned from it stay " +
                        "in the Knowledge Base — you can edit those there."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    removingSet = null
                    viewModel.removeSampleSet(row.set.id)
                }) {
                    Text(
                        "Remove",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { removingSet = null }
                ) { Text("Keep") }
            }
        )
    }
}

/** Two small numeric fields: "from chapter N to chapter M". */
@Composable
private fun ChapterRangeRow(
    from: Int,
    to: Int,
    max: Int,
    onChange: (Int, Int) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text("From", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = from.toString(),
            onValueChange = { raw ->
                raw.filter { it.isDigit() }.take(6).toIntOrNull()?.let { onChange(it, to) }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(92.dp)
        )
        Text("to", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = to.toString(),
            onValueChange = { raw ->
                raw.filter { it.isDigit() }.take(6).toIntOrNull()?.let { onChange(from, it) }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(92.dp)
        )
        Text(
            "of $max",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun studyFailureText(lastErrorClass: String?): String = when (lastErrorClass) {
    "AiBadOutput" ->
        "The AI's answer couldn't be read, even after a retry. Nothing was saved — try again."
    "AuthFailed" ->
        "The Gemini key didn't work. Check it under More → Providers & keys, then try again."
    "RateLimited" ->
        "The AI is busy right now (free-tier limit). Nothing was saved — try again later."
    "AllProvidersFailed", "NoNetwork" ->
        "No connection to the AI right now. Nothing was saved — try again when you're online."
    else ->
        "Studying didn't finish. Nothing was saved — try again."
}
