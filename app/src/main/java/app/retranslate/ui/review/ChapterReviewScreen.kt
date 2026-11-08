package app.retranslate.ui.review

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retranslate.ui.common.BounceButton
import app.retranslate.ui.common.EmptyState
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Chapter review (PLAN.md §9.3): the quality-checking screen.
 * Original/Translation segmented toggle with a text crossfade
 * (§9.4), paragraph flags, KB warnings and the quality score in the
 * open, and visible buttons for Polish / Re-translate / Undo.
 * P7 adds Listen/Stop (TTS, lifecycle-safe) and the Aa sheet
 * (review themes + text size, §9.5).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChapterReviewScreen(
    viewModel: ChapterReviewViewModel,
    onBack: () -> Unit,
    onOpenChapter: (Long) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ReviewUiEvent.ShowMessage -> snackbar.showSnackbar(event.text)
                is ReviewUiEvent.OpenChapter -> onOpenChapter(event.chapterId)
            }
        }
    }
    // §9.3: Listen never survives leaving the screen.
    androidx.compose.runtime.DisposableEffect(viewModel) {
        onDispose { viewModel.onScreenClosed() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.chapterTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::openPrevious,
                        enabled = state.prevChapterId != null
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.NavigateBefore,
                            contentDescription = "Previous chapter"
                        )
                    }
                    IconButton(
                        onClick = viewModel::openNext,
                        enabled = state.nextChapterId != null
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.NavigateNext,
                            contentDescription = "Next chapter"
                        )
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
                title = "This chapter isn't here",
                message = "It may have been deleted with its book.",
                actionLabel = "Back",
                onAction = onBack,
                modifier = Modifier.padding(padding)
            )
            else -> ReviewContent(
                state = state,
                appearance = appearance,
                viewModel = viewModel,
                onBack = onBack,
                modifier = Modifier.padding(padding)
            )
        }
    }

    if (state.showRetranslateConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRetranslate,
            title = { Text("Re-translate this chapter?") },
            text = {
                Text(
                    "The current translation is replaced by a fresh one (your " +
                        "Knowledge Base applies). The old text is kept as an " +
                        "undo copy — you can restore it afterwards."
                )
            },
            confirmButton = {
                BounceButton(onClick = viewModel::confirmRetranslate) {
                    Text("Re-translate")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRetranslate) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ReviewContent(
    state: ReviewUiState,
    appearance: app.retranslate.data.prefs.AppearanceState,
    viewModel: ChapterReviewViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val reviewTheme = app.retranslate.ui.theme.ReviewThemes.byId(appearance.reviewThemeId)
    var showAaSheet by remember { mutableStateOf(false) }
    var showToolsSheet by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxSize()) {
        // v1.1.3 (§B): the slim header — toggle, readouts, the ONE
        // primary action (Improve) and Tools. Everything else moved
        // into the Tools sheet; no stack of buttons over the text.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !state.showingOriginal,
                    onClick = viewModel::showTranslation,
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) { Text("Translation") }
                SegmentedButton(
                    selected = state.showingOriginal,
                    onClick = viewModel::showOriginal,
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) { Text("Original") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // v1.1 (sweep): these are readouts, not buttons — the
                // non-interactive InfoChip shape, not dead AssistChips.
                app.retranslate.ui.common.InfoChip(state.statusText)
                state.qualityScore?.let { score ->
                    app.retranslate.ui.common.InfoChip("Quality ${score.toInt()}%")
                }
                if (state.improved) {
                    app.retranslate.ui.common.InfoChip("Improved ✓")
                }
                if (state.flagged.isNotEmpty()) {
                    app.retranslate.ui.common.InfoChip(
                        "${state.flagged.size} flagged"
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.canPolish) {
                    BounceButton(
                        onClick = viewModel::polish,
                        enabled = state.workingText == null
                    ) { Text("Improve with AI") }
                }
                OutlinedButton(onClick = { showToolsSheet = true }) {
                    Text("Tools")
                }
                if (state.isSpeaking) {
                    OutlinedButton(onClick = viewModel::listen) { Text("Stop") }
                }
            }
            state.workingText?.let { working ->
                // §B: a hair-thin progress line, not a stacked block.
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        working,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            state.polishNote?.let { note ->
                // §A2: the outcome STAYS until dismissed or acted on.
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(note, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = viewModel::dismissPolishNote) {
                            Text("Dismiss")
                        }
                    }
                }
            }
            if (state.compareBefore != null && state.compareAfter != null) {
                // §G: before/after compare with the two plain choices.
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Before",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            state.compareBefore!!.take(600),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Improved",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            state.compareAfter!!.take(600),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = viewModel::keepMine) {
                                Text("Keep mine")
                            }
                            BounceButton(onClick = viewModel::keepImproved) {
                                Text("Use improved")
                            }
                        }
                    }
                }
            }
            if (state.kbWarnings.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            "Name warnings",
                            style = MaterialTheme.typography.titleSmall
                        )
                        for (warning in state.kbWarnings) {
                            Text(
                                warning,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
            if (state.flagMode) {
                Text(
                    "Tap a paragraph that looks wrong to flag it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // v1.1.3 (§B): the Tools sheet — everything that isn't the
        // primary Improve action lives here, one tap from the header.
        if (showToolsSheet) {
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { showToolsSheet = false }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Tools",
                        style = MaterialTheme.typography.titleMedium
                    )
                    OutlinedButton(
                        onClick = { showToolsSheet = false; viewModel.listen() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (state.isSpeaking) "Stop listening" else "Listen") }
                    OutlinedButton(
                        onClick = { showToolsSheet = false; showAaSheet = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Reading look (Aa)") }
                    OutlinedButton(
                        onClick = { showToolsSheet = false; viewModel.toggleFlagMode() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (state.flagMode) "Done flagging" else "Flag paragraphs"
                        )
                    }
                    OutlinedButton(
                        onClick = { showToolsSheet = false; viewModel.askRetranslate() },
                        enabled = state.workingText == null && state.hasOriginal,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Re-translate this chapter") }
                    if (state.undoAvailable) {
                        OutlinedButton(
                            onClick = {
                                showToolsSheet = false
                                viewModel.undoChapterChange()
                            },
                            enabled = state.workingText == null,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Undo last change") }
                    }
                    if (state.originalArchived) {
                        OutlinedButton(
                            onClick = {
                                showToolsSheet = false
                                viewModel.restoreOriginal()
                            },
                            enabled = state.workingText == null,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Restore original text") }
                    }
                }
            }
        }

        // §9.5: the reading surface wears the review theme (its own
        // colours, independent of the app theme); the chrome above
        // stays Material.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(reviewTheme.background)
        ) {
            val motionOn = app.retranslate.core.motion.rememberMotionEnabled()
            AnimatedContent(
                targetState = state.showingOriginal,
                // v1.1: the §9.4 fade token (and reduce-motion), like
                // the result crossfade on the Translate screen.
                transitionSpec = {
                    val spec = if (motionOn) {
                        app.retranslate.core.motion.MotionTokens.fadeTween<Float>()
                    } else {
                        androidx.compose.animation.core.tween(0)
                    }
                    fadeIn(spec) togetherWith fadeOut(spec)
                },
                label = "original-translation-crossfade",
                modifier = Modifier.fillMaxSize()
            ) { showingOriginal ->
                val paragraphs =
                    if (showingOriginal) state.originalParagraphs
                    else state.translatedParagraphs
                if (paragraphs.isEmpty()) {
                    // v1.1 (sweep): an empty state always offers the
                    // way out, not just words.
                    EmptyState(
                        title = if (showingOriginal) {
                            "No original text"
                        } else {
                            "Not translated yet"
                        },
                        message = if (showingOriginal) {
                            "This book's original text isn't stored (it may have " +
                                "been removed to save space)."
                        } else {
                            "Translate this chapter from the book page, then check it here."
                        },
                        actionLabel = "Back to the book",
                        onAction = onBack
                    )
                } else {
                    ParagraphList(
                        paragraphs = paragraphs,
                        flagged = if (showingOriginal) emptySet() else state.flagged,
                        flagMode = state.flagMode && !showingOriginal,
                        initialParagraph = state.initialParagraph,
                        onPositionChanged = viewModel::onPositionChanged,
                        onParagraphTapped = viewModel::onParagraphTapped,
                        theme = reviewTheme,
                        textScale = appearance.reviewTextScale
                    )
                }
            }
        }
    }

    // The Aa sheet (§9.3/§9.5): review theme swatches + text size.
    if (showAaSheet) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { showAaSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Reading look",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Theme",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    app.retranslate.ui.theme.ReviewThemes.ALL.forEach { theme ->
                        AssistChip(
                            onClick = { viewModel.setReviewTheme(theme.id) },
                            label = {
                                Text(
                                    if (theme.id == appearance.reviewThemeId) {
                                        "✓ ${theme.displayName}"
                                    } else {
                                        theme.displayName
                                    }
                                )
                            }
                        )
                    }
                }
                Text(
                    "Text size",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = {
                            viewModel.setReviewTextScale(
                                appearance.reviewTextScale - 0.1f
                            )
                        },
                        enabled = appearance.reviewTextScale >
                            app.retranslate.data.prefs.AppearancePrefs.MIN_TEXT_SCALE
                    ) { Text("A−") }
                    Text(
                        "${(appearance.reviewTextScale * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    TextButton(
                        onClick = {
                            viewModel.setReviewTextScale(
                                appearance.reviewTextScale + 0.1f
                            )
                        },
                        enabled = appearance.reviewTextScale <
                            app.retranslate.data.prefs.AppearancePrefs.MAX_TEXT_SCALE
                    ) { Text("A+") }
                }
                TextButton(onClick = { showAaSheet = false }) { Text("Done") }
            }
        }
    }
}

@Composable
private fun ParagraphList(
    paragraphs: List<String>,
    flagged: Set<Int>,
    flagMode: Boolean,
    initialParagraph: Int,
    onPositionChanged: (Int) -> Unit,
    onParagraphTapped: (Int) -> Unit,
    theme: app.retranslate.ui.theme.ReviewTheme,
    textScale: Float
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialParagraph.coerceIn(
            0, (paragraphs.size - 1).coerceAtLeast(0)
        )
    )
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { onPositionChanged(it) }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(paragraphs, key = { index, _ -> index }) { index, paragraph ->
            ParagraphItem(
                text = paragraph,
                isFlagged = index in flagged,
                clickable = flagMode,
                onClick = { onParagraphTapped(index) },
                theme = theme,
                textScale = textScale
            )
        }
    }
}

@Composable
private fun ParagraphItem(
    text: String,
    isFlagged: Boolean,
    clickable: Boolean,
    onClick: () -> Unit,
    theme: app.retranslate.ui.theme.ReviewTheme,
    textScale: Float
) {
    val kind = paragraphKind(text)
    // §9.5: platform serif for review body text (long-form readability).
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = MaterialTheme.typography.bodyLarge.fontSize * textScale,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
        color = theme.text
    )
    val clickModifier = if (clickable) Modifier.clickable(onClick = onClick) else Modifier
    if (kind == ParagraphKind.SYSTEM_WINDOW) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = theme.systemWindowBackground
            ),
            modifier = Modifier
                .fillMaxWidth()
                .then(clickModifier)
                .then(
                    if (isFlagged) {
                        Modifier.border(
                            2.dp, MaterialTheme.colorScheme.error,
                            MaterialTheme.shapes.medium
                        )
                    } else {
                        Modifier
                    }
                )
        ) {
            Column(Modifier.padding(12.dp)) {
                if (isFlagged) FlagLabel()
                Text(
                    text,
                    style = bodyStyle.copy(color = theme.systemWindowText)
                )
            }
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickModifier)
            .then(
                if (isFlagged) {
                    Modifier.border(
                        2.dp, MaterialTheme.colorScheme.error,
                        MaterialTheme.shapes.small
                    ).padding(8.dp)
                } else {
                    Modifier
                }
            )
    ) {
        if (isFlagged) FlagLabel()
        Text(
            text,
            style = bodyStyle,
            fontStyle = if (kind == ParagraphKind.THOUGHT) {
                FontStyle.Italic
            } else {
                FontStyle.Normal
            }
        )
    }
}

@Composable
private fun FlagLabel() {
    Text(
        "Flagged — looks wrong",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(bottom = 2.dp)
    )
}
