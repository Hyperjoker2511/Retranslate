package app.retranslate.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retranslate.core.motion.MotionTokens
import app.retranslate.domain.books.GenrePreset
import app.retranslate.providers.Languages
import app.retranslate.providers.QualityMode
import app.retranslate.ui.common.BounceButton
import app.retranslate.ui.common.BounceCard
import app.retranslate.ui.common.EmptyState
import app.retranslate.ui.common.GlassCard
import app.retranslate.ui.common.StatusPill
import app.retranslate.ui.common.StitchHeader
import app.retranslate.ui.common.StitchPrimaryButton
import app.retranslate.ui.common.ThickProgress
import app.retranslate.ui.theme.StitchAmber
import app.retranslate.ui.theme.StitchAzure
import app.retranslate.ui.theme.StitchMint

/**
 * Library — the start destination (PLAN.md §9.3), laid out EXACTLY as
 * the Stitch design (library-shot.png): compact header (eyebrow
 * "RETRANSLATE" + "Your library"), the Continue Reading hero, ONE
 * "+ Add book" action beside the search pill (v1.1.2 removed the
 * duplicated header pill + FAB the user flagged), stat cards, then
 * "Recent novels". Adding a book opens a chooser sheet with four big
 * labelled rows; file picks run the streaming import with progress
 * as state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenBook: (Long) -> Unit,
    onOpenImportReview: () -> Unit,
    onOpenReview: (bookId: Long, chapterId: Long) -> Unit = { _, _ -> }
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showChooser by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is LibraryUiEvent.ShowMessage -> snackbar.showSnackbar(event.text)
                is LibraryUiEvent.OpenBook -> onOpenBook(event.bookId)
                LibraryUiEvent.OpenImportReview -> onOpenImportReview()
                is LibraryUiEvent.OpenReview ->
                    onOpenReview(event.bookId, event.chapterId)
            }
        }
    }

    val epubPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.onFilePicked(uri) }
    val pdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.onFilePicked(uri) }
    val txtPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.onFilePicked(uri) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            StitchHeader(title = "Your library", eyebrow = "RETRANSLATE")
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.isLoading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }

                    state.books.isEmpty() && state.query.isEmpty() &&
                        state.filter == ShelfFilter.ALL -> EmptyState(
                        title = "No books yet",
                        message = "Add a book file (EPUB, PDF or TXT) and ReTranslate will " +
                            "translate it chapter by chapter.",
                        actionLabel = "Add a book",
                        onAction = { showChooser = true }
                    )

                    else -> ShelfContent(
                        state = state,
                        onQueryChange = viewModel::setQuery,
                        onFilterChange = viewModel::setFilter,
                        onBookClick = viewModel::onBookClicked,
                        onBookLongPress = viewModel::askRemoveBook,
                        onHeroClick = { viewModel.onHeroClicked() },
                        onAddBook = { showChooser = true },
                        onDismissWhatsNew = viewModel::dismissWhatsNew
                    )
                }
            }
        }
    }

    if (showChooser) {
        AddBookSheet(
            onDismiss = { showChooser = false },
            onPickEpub = {
                showChooser = false
                epubPicker.launch(arrayOf("application/epub+zip", "application/octet-stream"))
            },
            onPickPdf = {
                showChooser = false
                pdfPicker.launch(arrayOf("application/pdf"))
            },
            onPickTxt = {
                showChooser = false
                txtPicker.launch(arrayOf("text/plain", "text/*"))
            },
            onManual = {
                showChooser = false
                showManual = true
            }
        )
    }

    if (showManual) {
        ManualBookDialog(
            onDismiss = { showManual = false },
            onCreate = { title, source, target, genre, quality ->
                showManual = false
                viewModel.createManualBook(title, source, target, genre, quality)
            }
        )
    }

    when (val import = state.import) {
        is ImportUiState.Working -> AlertDialog(
            onDismissRequest = { /* import is short; no cancel affordance in P3 */ },
            title = { Text("Adding your book") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(import.stageText)
                    val fraction = import.fraction
                    if (fraction != null) {
                        val animated by animateFloatAsState(
                            targetValue = fraction,
                            animationSpec = MotionTokens.progressTween(),
                            label = "importProgress"
                        )
                        LinearProgressIndicator(
                            progress = { animated },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {}
        )

        is ImportUiState.Failed -> AlertDialog(
            onDismissRequest = viewModel::dismissImportError,
            title = { Text("Couldn't add that book") },
            text = { Text(import.error.message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissImportError) {
                    Text(import.error.recoveryLabel)
                }
            }
        )

        ImportUiState.Idle -> Unit
    }

    // v1.1 (workstream E): long-press on a shelf card asks the SAME
    // removal question as the book page's "Remove book" button — same
    // words, same consequences.
    state.pendingRemoval?.let { target ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRemoveBook,
            title = { Text("Remove this book?") },
            text = {
                Text(
                    "“${target.title}” will be removed from this phone, " +
                        "together with its chapters, translations and " +
                        "learned names. EPUB or backup files you already " +
                        "exported and saved somewhere else are NOT " +
                        "touched. This can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRemoveBook) {
                    Text(
                        "Remove book",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRemoveBook) {
                    Text("Keep book")
                }
            }
        )
    }
}

@Composable
private fun ShelfContent(
    state: LibraryUiState,
    onQueryChange: (String) -> Unit,
    onFilterChange: (ShelfFilter) -> Unit,
    onBookClick: (Long) -> Unit,
    onBookLongPress: (BookSummary) -> Unit,
    onHeroClick: () -> Unit,
    onAddBook: () -> Unit,
    // v1.1.3 (F24)
    onDismissWhatsNew: () -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ---- v1.1.3 (F24): the one-time What's-new card ----
        if (state.showWhatsNew) {
            item {
                androidx.compose.material3.Card(
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "What's new in 1.1.3",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "• Improve with AI now works on chapters you already " +
                                "have — including imported MTL books. Every " +
                                "chapter's original is saved first, and Restore " +
                                "original always brings it back.\n" +
                                "• Improve a whole book at once (book page → " +
                                "Improve all with AI), with a Consistency check " +
                                "that finds wrong name spellings for free.\n" +
                                "• Pick any engine — including the new Offline " +
                                "(on-phone) engine and APInex — or stay on " +
                                "Automatic. Add almost any AI site yourself " +
                                "under Providers & keys → Add a provider.\n" +
                                "• Paste a chapter on the Translate tab's new " +
                                "Chapter mode: it builds a name base first, " +
                                "then asks if you want to save that base.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        androidx.compose.material3.TextButton(
                            onClick = onDismissWhatsNew
                        ) { Text("Got it") }
                    }
                }
            }
        }
        // ---- Continue Reading hero (the design's first section) ----
        state.heroBook?.let { hero ->
            item {
                val heroChapter = state.heroChapter?.takeIf { it.bookId == hero.id }
                val fraction = when {
                    heroChapter != null && hero.totalCount > 0 ->
                        heroChapter.chapterNumber.toFloat() / hero.totalCount
                    hero.totalCount > 0 ->
                        hero.translatedCount.toFloat() / hero.totalCount
                    else -> 0f
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(percent = 50))
                                .background(StitchAzure)
                        )
                        Text(
                            "CONTINUE READING",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${(fraction * 100).toInt()}% read",
                            style = MaterialTheme.typography.labelLarge,
                            color = StitchAzure
                        )
                    }
                    GlassCard(
                        onClick = onHeroClick,
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            CoverImage(
                                path = hero.coverPath,
                                modifier = Modifier
                                    .size(width = 92.dp, height = 132.dp)
                                    .clip(RoundedCornerShape(16.dp))
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                StatusPill(
                                    text = if (hero.totalCount > 0 &&
                                        hero.translatedCount >= hero.totalCount
                                    ) {
                                        "Ready to read"
                                    } else {
                                        "In progress"
                                    },
                                    containerColor =
                                        MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = StitchAzure
                                )
                                Text(
                                    hero.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                hero.author?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        "By $it",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.MenuBook,
                                        contentDescription = null,
                                        tint = StitchAmber,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        heroChapter?.let {
                                            " Chapter ${it.chapterNumber} of ${hero.totalCount}"
                                        } ?: " ${hero.translatedCount} of ${hero.totalCount} chapters",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        "${(fraction * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = StitchAmber
                                    )
                                }
                                ThickProgress(
                                    progress = fraction,
                                    color = StitchAmber,
                                    height = 6.dp
                                )
                            }
                        }
                        StitchPrimaryButton(
                            onClick = onHeroClick,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 14.dp)
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Continue reading",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
        // ---- Search pill + the ONE Add action (design row) ----
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("Search your books") },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null)
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(percent = 50),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onAddBook,
                    shape = RoundedCornerShape(percent = 50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = StitchAzure
                    ),
                    modifier = Modifier.height(56.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Add book",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
        }
        // ---- Shelf filters (real function; the design has no chips,
        // so they sit quietly under the search row in the same pill style)
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.filter == ShelfFilter.ALL,
                    onClick = { onFilterChange(ShelfFilter.ALL) },
                    label = { Text("All") }
                )
                FilterChip(
                    selected = state.filter == ShelfFilter.READING,
                    onClick = { onFilterChange(ShelfFilter.READING) },
                    label = { Text("Reading") }
                )
                FilterChip(
                    selected = state.filter == ShelfFilter.FINISHED,
                    onClick = { onFilterChange(ShelfFilter.FINISHED) },
                    label = { Text("Finished") }
                )
            }
        }
        // ---- Stat cards (design) — every number is real library data ----
        if (state.books.isNotEmpty()) {
            item {
                val totalChapters = state.books.sumOf { it.translatedCount }
                val finished = state.books.count {
                    it.totalCount > 0 && it.translatedCount >= it.totalCount
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(
                        label = "IN LIBRARY",
                        value = "${state.books.size} Novels",
                        valueColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        label = "TRANSLATED",
                        value = if (totalChapters >= 1000) {
                            "%.1fk Ch.".format(totalChapters / 1000.0)
                        } else {
                            "$totalChapters Ch."
                        },
                        valueColor = StitchAzure,
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        label = "FINISHED",
                        value = "$finished Books",
                        valueColor = StitchAmber,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Recent novels",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        "  ${state.books.size} books",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (state.books.isEmpty()) {
            item {
                Text(
                    "No books match. Try a different search or filter.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp)
                )
            }
        } else {
            items(state.books, key = { it.id }) { book ->
                BookCard(
                    book = book,
                    onClick = { onBookClick(book.id) },
                    onLongClick = { onBookLongPress(book) },
                    modifier = Modifier.animateItem(
                        placementSpec = MotionTokens.spatialSpring()
                    )
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** One of the design's three library stat cards. */
@Composable
private fun StatCard(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    GlassCard(modifier = modifier, contentPadding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = valueColor,
                maxLines = 1
            )
        }
    }
}

/** Book status → the design's pill + bar colours. */
private data class BookStatusStyle(
    val pillText: String,
    val barColor: Color
)

@Composable
private fun BookCard(
    book: BookSummary,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val percent = if (book.totalCount > 0) {
        book.translatedCount * 100 / book.totalCount
    } else {
        0
    }
    val style = when {
        book.totalCount > 0 && book.translatedCount >= book.totalCount ->
            BookStatusStyle("Ready to read", StitchAzure)
        book.translatedCount > 0 ->
            BookStatusStyle("Translated $percent%", StitchMint)
        else -> BookStatusStyle("Not started", StitchAmber)
    }
    val pillContainer = when (style.barColor) {
        StitchAzure -> MaterialTheme.colorScheme.primaryContainer
        StitchMint -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val pillContent = when (style.barColor) {
        StitchAzure -> StitchAzure
        StitchMint -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onTertiaryContainer
    }
    GlassCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        contentPadding = PaddingValues(16.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CoverImage(
                path = book.coverPath,
                modifier = Modifier
                    .size(width = 64.dp, height = 92.dp)
                    .clip(RoundedCornerShape(14.dp))
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                StatusPill(
                    text = style.pillText,
                    containerColor = pillContainer,
                    contentColor = pillContent
                )
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (book.totalCount > 0) {
                        "${book.translatedCount} / ${book.totalCount} chapters"
                    } else {
                        book.progressText
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (book.totalCount > 0) {
                    ThickProgress(
                        progress = book.translatedCount.toFloat() / book.totalCount,
                        color = style.barColor,
                        height = 6.dp
                    )
                }
            }
        }
    }
}

/** The §9.3 chooser: four big rows, icon + two lines each. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddBookSheet(
    onDismiss: () -> Unit,
    onPickEpub: () -> Unit,
    onPickPdf: () -> Unit,
    onPickTxt: () -> Unit,
    onManual: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Add a book",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            ChooserRow(
                title = "EPUB file",
                subtitle = "An ebook in EPUB format",
                onClick = onPickEpub
            )
            ChooserRow(
                title = "PDF file",
                subtitle = "A PDF with real text (not scanned pictures)",
                onClick = onPickPdf
            )
            ChooserRow(
                title = "Text file",
                subtitle = "A plain .txt novel file",
                onClick = onPickTxt
            )
            ChooserRow(
                title = "Type details myself",
                subtitle = "Start an empty book and add chapters later",
                onClick = onManual
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ChooserRow(title: String, subtitle: String, onClick: () -> Unit) {
    BounceCard(onClick = onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Manual book entry (§9.3 "Type details myself"). */
@Composable
private fun ManualBookDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String, GenrePreset, QualityMode) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("ko") }
    var target by remember { mutableStateOf("en") }
    var genre by remember { mutableStateOf(GenrePreset.OTHER) }
    var quality by remember { mutableStateOf(QualityMode.BALANCED) }
    var pickingFor by remember { mutableStateOf<String?>(null) } // "source" | "target"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New book") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Book title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { pickingFor = "source" }) {
                        Text("Original: ${Languages.nameOf(source)}")
                    }
                    TextButton(onClick = { pickingFor = "target" }) {
                        Text("Translate to: ${Languages.nameOf(target)}")
                    }
                }
                Text("Genre", style = MaterialTheme.typography.labelLarge)
                GenreChips(selected = genre, onSelect = { genre = it })
                Text("Translation quality", style = MaterialTheme.typography.labelLarge)
                QualityChips(selected = quality, onSelect = { quality = it })
            }
        },
        confirmButton = {
            BounceButton(
                onClick = { onCreate(title, source, target, genre, quality) }
            ) { Text("Create book") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    pickingFor?.let { which ->
        LanguagePickerDialog(
            title = if (which == "source") "Original language" else "Translate to",
            onPick = { language ->
                if (which == "source") source = language.code else target = language.code
                pickingFor = null
            },
            onDismiss = { pickingFor = null }
        )
    }
}

@Composable
internal fun GenreChips(selected: GenrePreset, onSelect: (GenrePreset) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GenrePreset.entries.chunked(2).forEach { rowGenres ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowGenres.forEach { genre ->
                    FilterChip(
                        selected = genre == selected,
                        onClick = { onSelect(genre) },
                        label = { Text(genre.displayName) }
                    )
                }
            }
        }
    }
}

@Composable
internal fun QualityChips(selected: QualityMode, onSelect: (QualityMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QualityMode.entries.forEach { mode ->
            FilterChip(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                label = {
                    Text(
                        when (mode) {
                            QualityMode.FAST -> "Fast"
                            QualityMode.BALANCED -> "Balanced"
                            QualityMode.BEST -> "Best"
                        }
                    )
                }
            )
        }
    }
}
