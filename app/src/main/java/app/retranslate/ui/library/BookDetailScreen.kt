package app.retranslate.ui.library

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import app.retranslate.ui.common.GlassCard
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retranslate.data.db.BookEntity
import app.retranslate.data.db.ChapterListItem
import app.retranslate.providers.Languages
import app.retranslate.ui.common.BounceButton
import app.retranslate.ui.common.EmptyState
import kotlinx.coroutines.launch

/**
 * Book page (PLAN.md §9.3): everything about one book on one screen —
 * header, plain-words status, the big visible action row, languages in
 * the open with Change buttons, and the chapter list with state chips
 * and per-chapter buttons. Nothing primary hides in a menu.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BookDetailScreen(
    viewModel: BookDetailViewModel,
    describeFile: (android.net.Uri) -> app.retranslate.data.files.PickedFile,
    onBack: () -> Unit,
    onLearnFromSamples: () -> Unit = {},
    onOpenKnowledgeBase: () -> Unit = {},
    onOpenChapter: (Long) -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pickingFor by remember { mutableStateOf<String?>(null) }
    var showEditDetails by remember { mutableStateOf(false) }
    var showPasteChapter by remember { mutableStateOf(false) }
    // P6: an export file waiting for Save / Share (§7.5 delivery).
    var delivery by remember { mutableStateOf<ExportDelivery?>(null) }
    val deliveryContext = androidx.compose.ui.platform.LocalContext.current
    val deliveryScope = rememberCoroutineScope()
    val saveExportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val ready = delivery
        delivery = null
        if (uri != null && ready != null) {
            deliveryScope.launch {
                val ok = app.retranslate.data.files.FileDelivery.copyTo(
                    deliveryContext, java.io.File(ready.filePath), uri
                )
                snackbar.showSnackbar(
                    if (ok) {
                        "Export saved."
                    } else {
                        "Couldn't save the file there — try Share instead."
                    }
                )
            }
        }
    }

    // P8: "Add chapters" picks another book file and appends it here.
    val addChaptersLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.addChapters(describeFile(uri))
    }

    // §7.4: job progress needs notifications on Android 13+; asked
    // in context, the moment the user starts a translation job.
    // Declining never blocks the job — the Jobs tab still shows it.
    val context = androidx.compose.ui.platform.LocalContext.current
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* granted or not, the job proceeds; Jobs tab carries progress */ }
    val notificationsGranted = {
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is BookDetailUiEvent.ShowMessage -> snackbar.showSnackbar(event.text)
                BookDetailUiEvent.NavigateBack -> onBack()
                is BookDetailUiEvent.ExportReady -> delivery = ExportDelivery(
                    filePath = event.filePath,
                    fileName = event.fileName,
                    mimeType = event.mimeType,
                    includedChapters = event.includedChapters,
                    skippedChapters = event.skippedChapters
                )
            }
        }
    }

    // P6: flags/undo live in files, not Room flows — refresh on appear.
    LaunchedEffect(Unit) { viewModel.refreshFlagsAndUndo() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.book?.titleOriginal ?: "Book",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
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
            state.notFound -> EmptyState(
                title = "This book isn't here",
                message = "It may have been deleted.",
                actionLabel = "Back to Library",
                onAction = onBack,
                modifier = Modifier.padding(padding)
            )
            else -> BookContent(
                book = state.book!!,
                chapters = state.chapters,
                statusText = state.statusText,
                kbEntryCount = state.kbEntryCount,
                nameWarnings = state.nameWarnings,
                flaggedParagraphs = state.flaggedParagraphs,
                flaggedChapters = state.flaggedChapters,
                undoSnapshot = state.undoSnapshot,
                latestJob = state.latestJob,
                onAddChapters = {
                    addChaptersLauncher.launch(
                        arrayOf(
                            "application/epub+zip", "application/pdf",
                            "text/plain", "text/*", "application/octet-stream"
                        )
                    )
                },
                onPasteChapter = { showPasteChapter = true },
                onLearnFromSamples = onLearnFromSamples,
                onOpenKnowledgeBase = onOpenKnowledgeBase,
                onExportEpub = {
                    viewModel.askExport(app.retranslate.domain.export.ExportFormat.EPUB)
                },
                onExportTxt = {
                    viewModel.askExport(app.retranslate.domain.export.ExportFormat.TXT)
                },
                onOpenChapter = onOpenChapter,
                onUndoChapterChange = viewModel::undoChapterChange,
                onTranslateAll = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !notificationsGranted()
                    ) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    viewModel.translateAll()
                },
                onTranslateChapter = viewModel::translateChapter,
                // v1.1.3 (§A/§G/§H): Improve, Consistency, Offline.
                uiState = state,
                onImproveAll = viewModel::openImproveSheet,
                onImproveChapter = viewModel::improveChapter,
                onRestoreChapter = viewModel::restoreChapterOriginal,
                onConsistencyCheck = viewModel::runConsistencyCheck,
                onConsistencyDismiss = viewModel::dismissConsistency,
                onTranslateOffline = viewModel::translateAllOffline,
                onToggleNeedsImprovement = viewModel::toggleNeedsImprovement,
                onPauseJob = viewModel::pauseJob,
                onResumeJob = viewModel::resumeJob,
                onCancelJob = viewModel::cancelJob,
                onChangeSource = { pickingFor = "source" },
                onChangeTarget = { pickingFor = "target" },
                onPolishToggle = viewModel::setPolishEnabled,
                onEditDetails = { showEditDetails = true },
                onDelete = viewModel::askDelete,
                modifier = Modifier.padding(padding)
            )
        }
    }

    pickingFor?.let { which ->
        val book = state.book
        if (book != null) {
            LanguagePickerDialog(
                title = if (which == "source") "Original language" else "Translate to",
                onPick = { language ->
                    if (which == "source") viewModel.changeSourceLanguage(language.code)
                    else viewModel.changeTargetLanguage(language.code)
                    pickingFor = null
                },
                onDismiss = { pickingFor = null }
            )
        }
    }

    if (showEditDetails) {
        val book = state.book
        if (book != null) {
            EditDetailsDialog(
                book = book,
                onDismiss = { showEditDetails = false },
                onSave = { title, author ->
                    showEditDetails = false
                    viewModel.updateDetails(title, author)
                }
            )
        }
    }

    if (showPasteChapter) {
        val book = state.book
        PasteChapterDialog(
            // v1.1.3 (§C/D9): only a translation book needs the
            // Original-vs-MTL question; a same-language book's paste
            // is a translation by definition.
            askKind = book != null && book.sourceLang != book.targetLang,
            onDismiss = { showPasteChapter = false },
            onSave = { title, text, kind ->
                showPasteChapter = false
                viewModel.pasteChapter(title, text, kind)
            }
        )
    }

    // v1.1.3 (§A2/§G): the bulk-Improve sheet — scope, strength, the
    // honest estimate and the no-key line, all before anything runs.
    if (state.showImproveSheet) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = viewModel::dismissImproveSheet
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Improve chapters with AI",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Which chapters?",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                app.retranslate.domain.translate.PolishScope.entries.forEach { scope ->
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = state.improveScope == scope,
                            onClick = { viewModel.setImproveScope(scope) }
                        )
                        Text(scope.label)
                    }
                }
                Text(
                    "How strong?",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(
                        selected = state.improveStrength ==
                            app.retranslate.domain.translate.ImproveStrength.GENTLE,
                        onClick = {
                            viewModel.setImproveStrength(
                                app.retranslate.domain.translate.ImproveStrength.GENTLE
                            )
                        },
                        label = { Text("Gentle — polish wording") }
                    )
                    androidx.compose.material3.FilterChip(
                        selected = state.improveStrength ==
                            app.retranslate.domain.translate.ImproveStrength.STRONG,
                        onClick = {
                            viewModel.setImproveStrength(
                                app.retranslate.domain.translate.ImproveStrength.STRONG
                            )
                        },
                        label = { Text("Strong — rewrite rough sentences") }
                    )
                }
                Text(
                    "This will improve ${state.improveEstimate} chapters. " +
                        "Each one uses AI calls; if the AI gets busy the job " +
                        "pauses and continues by itself. Every chapter's " +
                        "original text is saved first — Restore original " +
                        "always brings it back.",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!state.aiAvailable) {
                    Text(
                        "No AI key is saved yet — add one under More → " +
                            "Providers & keys (a free Gemini key works). " +
                            "Without a key, nothing can be improved.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::dismissImproveSheet) {
                        Text("Cancel")
                    }
                    BounceButton(
                        onClick = viewModel::startImprove,
                        enabled = state.aiAvailable && state.improveEstimate > 0
                    ) { Text("Start improving") }
                }
            }
        }
    }

    // P8: adding a picked chapter file (streamed import — brief state).
    state.addingChaptersText?.let { stageText ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Adding chapters…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stageText)
                    androidx.compose.material3.LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {}
        )
    }

    if (state.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text("Remove this book?") },
            text = {
                Text(
                    "“${state.book?.titleOriginal}” will be removed from this " +
                        "phone, together with its chapters, translations and " +
                        "learned names. EPUB or backup files you already " +
                        "exported and saved somewhere else are NOT touched. " +
                        "This can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text(
                        "Remove book",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDelete) { Text("Keep book") }
            }
        )
    }

    // P6 pre-export dialog (§7.5): what will be left out, said plainly.
    state.pendingExport?.let { pending ->
        val formatName = if (pending.format ==
            app.retranslate.domain.export.ExportFormat.EPUB
        ) {
            "EPUB"
        } else {
            "TXT"
        }
        AlertDialog(
            onDismissRequest = viewModel::dismissExport,
            title = { Text("Export $formatName") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (pending.skippedChapters > 0) {
                            "${pending.skippedChapters} of ${pending.totalChapters} " +
                                "chapters aren't translated yet — they'll be left out. " +
                                "${pending.translatedChapters} chapters will be exported."
                        } else if (pending.totalChapters == 0) {
                            "This book has no chapters yet."
                        } else {
                            "All ${pending.totalChapters} chapters are translated " +
                                "and will be included."
                        }
                    )
                    if (pending.format ==
                        app.retranslate.domain.export.ExportFormat.EPUB
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Add a “Translated with ReTranslate” note",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            androidx.compose.material3.Switch(
                                checked = state.exportIncludeNote,
                                onCheckedChange = viewModel::setExportIncludeNote
                            )
                        }
                    }
                }
            },
            confirmButton = {
                BounceButton(
                    onClick = viewModel::confirmExport,
                    enabled = pending.translatedChapters > 0
                ) { Text("Export") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissExport) { Text("Cancel") }
            }
        )
    }

    // P6: writing the export file (local I/O — brief, but honest state).
    if (state.exportProgress != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Exporting…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Writing the file, one chapter at a time.")
                    val progress = state.exportProgress
                    if (progress != null) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {}
        )
    }

    // P6: delivery — Save to a place the user picks, or Share (§7.5).
    delivery?.let { ready ->
        AlertDialog(
            onDismissRequest = { delivery = null },
            title = { Text("Export ready") },
            text = {
                Text(
                    "${ready.includedChapters} chapters are in “${ready.fileName}”" +
                        if (ready.skippedChapters > 0) {
                            " (${ready.skippedChapters} untranslated chapters left out)."
                        } else {
                            "."
                        } +
                        " Save it where you want it, or share it to another app."
                )
            },
            confirmButton = {
                BounceButton(onClick = { saveExportLauncher.launch(ready.fileName) }) {
                    Text("Save to device")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        delivery = null
                        val intent = app.retranslate.data.files.FileDelivery.shareIntent(
                            deliveryContext,
                            java.io.File(ready.filePath),
                            ready.mimeType
                        )
                        deliveryContext.startActivity(
                            android.content.Intent.createChooser(intent, "Share export")
                        )
                    }) { Text("Share") }
                    TextButton(onClick = { delivery = null }) { Text("Done") }
                }
            }
        )
    }
}

/** An export file waiting for the user's Save / Share choice (§7.5). */
private data class ExportDelivery(
    val filePath: String,
    val fileName: String,
    val mimeType: String,
    val includedChapters: Int,
    val skippedChapters: Int
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookContent(
    book: BookEntity,
    chapters: List<ChapterListItem>,
    statusText: String,
    kbEntryCount: Int,
    nameWarnings: Int,
    flaggedParagraphs: Int,
    flaggedChapters: Int,
    undoSnapshot: app.retranslate.domain.translate.ChapterSnapshot?,
    latestJob: app.retranslate.data.db.JobRunEntity?,
    // v1.1.3 (§A/§G/§H): Improve sheet state, consistency, filters.
    uiState: BookDetailUiState? = null,
    onImproveAll: () -> Unit = {},
    onImproveChapter: (Long) -> Unit = {},
    onRestoreChapter: (Long) -> Unit = {},
    onConsistencyCheck: () -> Unit = {},
    onConsistencyDismiss: () -> Unit = {},
    onTranslateOffline: () -> Unit = {},
    onToggleNeedsImprovement: () -> Unit = {},
    onAddChapters: () -> Unit,
    onPasteChapter: () -> Unit,
    onLearnFromSamples: () -> Unit,
    onOpenKnowledgeBase: () -> Unit,
    onExportEpub: () -> Unit,
    onExportTxt: () -> Unit,
    onOpenChapter: (Long) -> Unit,
    onUndoChapterChange: () -> Unit,
    onTranslateAll: () -> Unit,
    onTranslateChapter: (Long) -> Unit,
    onPauseJob: () -> Unit,
    onResumeJob: () -> Unit,
    onCancelJob: () -> Unit,
    onChangeSource: () -> Unit,
    onChangeTarget: () -> Unit,
    onPolishToggle: (Boolean) -> Unit = {},
    onEditDetails: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var goToText by remember { mutableStateOf("") }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CoverImage(
                    path = book.coverPath,
                    modifier = Modifier
                        .size(width = 72.dp, height = 104.dp)
                        .clip(RoundedCornerShape(16.dp))
                )
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(book.titleOriginal, style = MaterialTheme.typography.titleLarge)
                    book.author?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        formatTextSize(book.sizeBytesCache) + " of text",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onEditDetails) { Text("Edit details") }
                }
            }
        }
        item {
            // v1.1.2: the status block is a design glass card.
            GlassCard(contentPadding = PaddingValues(20.dp)) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(statusText, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (kbEntryCount > 0) {
                            "Knowledge Base: $kbEntryCount names and terms learned"
                        } else {
                            "Knowledge Base: not created yet — learn from sample " +
                                "chapters to keep names consistent"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (nameWarnings > 0) {
                        Text(
                            "$nameWarnings chapter(s) have name warnings — a learned " +
                                "name is spelled differently there. Open the Knowledge " +
                                "Base to fix it with Re-apply names.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (flaggedParagraphs > 0) {
                        Text(
                            "$flaggedParagraphs flagged paragraph(s) in " +
                                "$flaggedChapters chapter(s) — open a chapter and " +
                                "tap Read to check them.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (undoSnapshot != null) {
                        TextButton(onClick = onUndoChapterChange) {
                            Text(
                                "Undo the last chapter change " +
                                    "(chapter ${undoSnapshot.chapterNumber})"
                            )
                        }
                    }
                    latestJob?.let { job ->
                        Text(
                            jobStatusLine(job),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (latestJob?.state) {
                    "QUEUED", "RUNNING" -> {
                        BounceButton(onClick = onPauseJob) { Text("Pause job") }
                        OutlinedButton(onClick = onCancelJob) { Text("Cancel job") }
                    }
                    "PAUSED" -> {
                        BounceButton(onClick = onResumeJob) { Text("Resume job") }
                        OutlinedButton(onClick = onCancelJob) { Text("Cancel job") }
                    }
                    else -> BounceButton(onClick = onTranslateAll) {
                        Text("Translate all untranslated")
                    }
                }
                // v1.1.3 (§A2): bulk Improve — visible with the other
                // primary actions, never buried.
                OutlinedButton(onClick = onImproveAll) {
                    Text("Improve all with AI")
                }
                OutlinedButton(onClick = onTranslateOffline) {
                    Text("Translate offline")
                }
                OutlinedButton(onClick = onConsistencyCheck) {
                    Text("Consistency check")
                }
                OutlinedButton(onClick = onLearnFromSamples) {
                    Text("Learn from samples")
                }
                OutlinedButton(onClick = onOpenKnowledgeBase) {
                    Text("Knowledge Base")
                }
                OutlinedButton(onClick = onExportEpub) { Text("Export EPUB") }
                OutlinedButton(onClick = onExportTxt) { Text("Export TXT") }
                // v1.1 (workstream E): removal lives HERE, with the
                // book's other actions — not at the end of the chapter
                // list where nobody scrolls to. Error-tinted, labelled,
                // and always confirmed by the dialog.
                OutlinedButton(
                    onClick = onDelete,
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Remove book")
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Original language: ${Languages.nameOf(book.sourceLang)}",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    TextButton(onClick = onChangeSource) { Text("Change") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Translate to: ${Languages.nameOf(book.targetLang)}",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    TextButton(onClick = onChangeTarget) { Text("Change") }
                }
                // P7 (§5.2 dual-model): the per-book polish toggle.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Polish each chapter (AI rewrite)",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            "After translating, a second AI pass rewrites each " +
                                "chapter into natural, readable English — checked " +
                                "before it's kept, so a bad rewrite never replaces " +
                                "a good translation. Uses extra AI calls; needs " +
                                "an AI key (Gemini, OpenAI or Claude).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = book.polishEnabled,
                        onCheckedChange = onPolishToggle
                    )
                }
            }
        }
        if (chapters.isEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "No chapters yet",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Add chapter files to this book, or paste a chapter.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BounceButton(onClick = onAddChapters) {
                            Text("Add chapters")
                        }
                        OutlinedButton(onClick = onPasteChapter) {
                            Text("Paste a chapter")
                        }
                    }
                }
            }
        } else {
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = goToText,
                        onValueChange = { goToText = it.filter { c -> c.isDigit() } },
                        label = { Text("Go to chapter") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = {
                        val n = goToText.toIntOrNull()
                        if (n != null) {
                            val index = chapters.indexOfFirst { it.number == n }
                            if (index >= 0) {
                                scope.launch { listState.animateScrollToItem(index + 5) }
                            }
                        }
                    }) { Text("Go") }
                }
            }
            // v1.1.3 (§G): the Consistency check's answer, worst first.
            uiState?.consistency?.let { report ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "Consistency check",
                                style = MaterialTheme.typography.titleSmall
                            )
                            if (!report.hasKnowledgeBase) {
                                Text(
                                    "This book has no Knowledge Base yet — " +
                                        "learn from samples first, then this " +
                                        "check can compare every chapter " +
                                        "against it. Nothing was checked.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            } else if (report.totalViolations == 0) {
                                Text(
                                    "All ${report.chaptersScanned} translated " +
                                        "chapters match the Knowledge Base. " +
                                        "No wrong name spellings found.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            } else {
                                Text(
                                    "${report.totalViolations} wrong spellings in " +
                                        "${report.chaptersWithViolations} of " +
                                        "${report.chaptersScanned} chapters. " +
                                        "Worst chapters:",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                for (worst in report.worstChapters.take(5)) {
                                    Text(
                                        "Chapter ${worst.number} — " +
                                            "${worst.violationCount} wrong spellings",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                for (variant in report.variants.take(5)) {
                                    Text(
                                        "“${variant.variantFound}” should be " +
                                            "“${variant.canonicalForm}” " +
                                            "(${variant.occurrences}× in " +
                                            "${variant.chapterCount} chapters) — " +
                                            "fix all at once in the Knowledge Base.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            TextButton(onClick = onConsistencyDismiss) {
                                Text("Close")
                            }
                        }
                    }
                }
            }
            if (uiState?.consistencyRunning == true) {
                item {
                    Text(
                        "Checking every chapter against the Knowledge Base…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // v1.1.3 (§A3): quality filter for the chapter list.
            item {
                androidx.compose.material3.FilterChip(
                    selected = uiState?.needsImprovementOnly == true,
                    onClick = onToggleNeedsImprovement,
                    label = { Text("Needs improvement only") }
                )
            }
            val shownChapters = if (uiState?.needsImprovementOnly == true) {
                chapters.filter {
                    app.retranslate.domain.translate.QualityLabel.isRough(it.qualityScore)
                }
            } else {
                chapters
            }
            items(shownChapters, key = { it.id }) { chapter ->
                ChapterRow(
                    chapter = chapter,
                    onTranslateChapter = onTranslateChapter,
                    onRead = { onOpenChapter(chapter.id) },
                    onImproveChapter = onImproveChapter,
                    onRestoreChapter = onRestoreChapter,
                    busy = uiState?.busyChapterId == chapter.id
                )
            }
        }
        item {
            // v1.1: the chapter list ends here. ("Delete book" used to
            // hide below this point — it now sits with the book's
            // actions at the top, as "Remove book".)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: ChapterListItem,
    onTranslateChapter: (Long) -> Unit,
    onRead: () -> Unit,
    // v1.1.3 (§A3/§G): per-chapter Improve + Restore Original.
    onImproveChapter: (Long) -> Unit = {},
    onRestoreChapter: (Long) -> Unit = {},
    busy: Boolean = false
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        chapter.title?.takeIf { it.isNotBlank() } ?: "Chapter ${chapter.number}",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        // v1.1 (sweep): status readout — InfoChip, not a
                        // dead AssistChip that invites a tap.
                        app.retranslate.ui.common.InfoChip(
                            chapterStatusText(chapter.status)
                        )
                        chapter.qualityScore?.let { score ->
                            // v1.1.3 (§A3): the number AND its word —
                            // "Rough" is what the Improve scope reads.
                            val word = app.retranslate.domain.translate
                                .QualityLabel.of(score)
                            Text(
                                "Quality ${score.toInt()}%" +
                                    (word?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        if (chapter.translatedByProvider == "OFFLINE") {
                            // §H: the mark the Offline-translated scope reads.
                            app.retranslate.ui.common.InfoChip("Offline")
                        }
                        if (chapter.improvedAt != null) {
                            app.retranslate.ui.common.InfoChip("Improved ✓")
                        }
                    }
                }
                when (chapter.status) {
                    "TRANSLATED" -> TextButton(onClick = onRead) { Text("Read") }
                    "FAILED" -> TextButton(onClick = { onTranslateChapter(chapter.id) }) {
                        Text("Retry")
                    }
                    "PARTIAL" -> TextButton(onClick = { onTranslateChapter(chapter.id) }) {
                        Text("Resume")
                    }
                    else -> TextButton(onClick = { onTranslateChapter(chapter.id) }) {
                        Text("Translate")
                    }
                }
            }
            if (chapter.status == "TRANSLATED") {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { onImproveChapter(chapter.id) },
                        enabled = !busy
                    ) { Text(if (busy) "Improving…" else "Improve with AI") }
                    if (chapter.improvedAt != null) {
                        TextButton(
                            onClick = { onRestoreChapter(chapter.id) },
                            enabled = !busy
                        ) { Text("Restore original") }
                    }
                }
            }
        }
    }
}

private fun chapterStatusText(status: String): String = when (status) {
    "TRANSLATED" -> "Done"
    "PARTIAL" -> "Half-done"
    "FAILED" -> "Failed"
    else -> "Not started"
}

/** The status card's job line (§9.3: "last job: …"), in plain words. */
private fun jobStatusLine(job: app.retranslate.data.db.JobRunEntity): String {
    // v1.1.3 (§A2): an Improve job reports its three durable
    // counters — improved / kept-original / failed — never a blur.
    if (job.kind == "POLISH") {
        return when (job.state) {
            "QUEUED" -> "Improve job: waiting to start"
            "RUNNING" -> "Improve job: improving — ${job.polishApplied} improved, " +
                "${job.polishRejected} kept original, ${job.failedChapters} failed " +
                "of ${job.totalChapters}"
            "PAUSED" -> if (job.lastErrorClass == "RateLimited") {
                "Improve job: paused — the AI is busy; it continues by itself"
            } else {
                "Improve job: paused — ${job.polishApplied} improved so far"
            }
            "FINISHED" -> "Last improve job: ${job.polishApplied} improved, " +
                "${job.polishRejected} kept original" +
                if (job.failedChapters > 0) ", ${job.failedChapters} failed" else ""
            "FAILED" -> "Last improve job: stopped — nothing was improved; " +
                "tap Resume job on the Jobs tab"
            "CANCELLED" -> "Last improve job: cancelled — improved chapters are kept"
            else -> ""
        }
    }
    // §I: a job run with ONE picked engine says which engine.
    val pickNote = job.providerPick?.let { pick ->
        if (pick.startsWith("gw")) {
            " (engine: your added provider)"
        } else {
            " (engine: $pick)"
        }
    } ?: ""
    return when (job.state) {
        "QUEUED" -> "Job: waiting to start"
        "RUNNING" -> "Job: translating — ${job.doneChapters + job.failedChapters} " +
            "of ${job.totalChapters} chapters done$pickNote"
        "PAUSED" -> if (job.lastErrorClass == "RateLimited") {
            "Job: paused — servers busy, it will retry by itself"
        } else {
            "Job: paused"
        }
        "FINISHED" -> "Last job: finished — ${job.doneChapters} chapters translated" +
            (if (job.failedChapters > 0) ", ${job.failedChapters} failed" else "") +
            pickNote
        "FAILED" -> "Last job: stopped — the chapters failed; tap Resume job on the Jobs tab"
        "CANCELLED" -> "Last job: cancelled — finished chapters are kept"
        else -> ""
    }
}

@Composable
private fun PasteChapterDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, app.retranslate.domain.translate.ChapterTextKind) -> Unit,
    // v1.1.3 (§C/D9): ask Original-vs-MTL when the book translates.
    askKind: Boolean = false
) {
    var title by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var kind by remember {
        mutableStateOf(app.retranslate.domain.translate.ChapterTextKind.ORIGINAL)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste a chapter") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Chapter title (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Chapter text") },
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                if (askKind) {
                    Text(
                        "This text is…",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    app.retranslate.domain.translate.ChapterTextKind.entries
                        .forEach { option ->
                            Row(
                                verticalAlignment =
                                    androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                androidx.compose.material3.RadioButton(
                                    selected = kind == option,
                                    onClick = { kind = option }
                                )
                                Text(
                                    when (option) {
                                        app.retranslate.domain.translate
                                            .ChapterTextKind.ORIGINAL ->
                                            "The original — translate it"
                                        app.retranslate.domain.translate
                                            .ChapterTextKind.MTL ->
                                            "Already translated (MTL) — improve it"
                                    }
                                )
                            }
                        }
                }
            }
        },
        confirmButton = {
            BounceButton(
                onClick = { onSave(title, text, kind) },
                enabled = text.isNotBlank()
            ) { Text("Add chapter") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun EditDetailsDialog(
    book: BookEntity,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var title by remember { mutableStateOf(book.titleOriginal) }
    var author by remember { mutableStateOf(book.author ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("Author") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            BounceButton(onClick = { onSave(title, author) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
