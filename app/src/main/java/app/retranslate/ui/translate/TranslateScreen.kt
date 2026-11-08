package app.retranslate.ui.translate

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retranslate.core.motion.MotionTokens
import app.retranslate.core.motion.rememberMotionEnabled
import app.retranslate.providers.LangCode
import app.retranslate.providers.Language
import app.retranslate.providers.hub.LanguageMatrix
import app.retranslate.ui.common.BounceButton
import app.retranslate.ui.common.GlassCard
import app.retranslate.ui.common.InfoChip
import app.retranslate.ui.common.StatusPill
import app.retranslate.ui.common.StitchHeader
import app.retranslate.ui.common.StitchPrimaryButton
import app.retranslate.ui.common.bounceClickable
import app.retranslate.ui.theme.StitchMint
import kotlinx.coroutines.launch

/**
 * Quick Translate (PLAN.md §9.3), laid out EXACTLY as the Stitch
 * design (translate-shot.png / translate-html.html): compact header,
 * language pills with the round swap button on one glass bar, the big
 * input glass card, the azure Translate button, the "AI Compare &
 * Merge" toggle row, then the result glass card with its via pill and
 * round Listen/Copy actions.
 *
 * Two user-ordered deviations from the design, both deliberate:
 *  1. The provider chip stays at the BOTTOM LEFT (the user's explicit
 *     placement instruction overrides the design, which has no chip).
 *  2. No microphone icon: the app has no speech INPUT (Listen is
 *     speech output), and the design's "0 / 500" count would claim a
 *     cap that does not exist — the card's bottom row shows the real
 *     character count instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslateScreen(
    viewModel: TranslateViewModel,
    onOpenProviderSettings: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val motionOn = rememberMotionEnabled()
    val clipboard = LocalClipboardManager.current

    // v1.1.3 (§E): a written result file waiting for Save / Share.
    var fileDelivery by remember {
        mutableStateOf<TranslateUiEvent.SaveFileReady?>(null)
    }
    val deliveryContext = androidx.compose.ui.platform.LocalContext.current
    val deliveryScope = androidx.compose.runtime.rememberCoroutineScope()
    val saveFileLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val ready = fileDelivery
        fileDelivery = null
        if (uri != null && ready != null) {
            deliveryScope.launch {
                val ok = app.retranslate.data.files.FileDelivery.copyTo(
                    deliveryContext, java.io.File(ready.filePath), uri
                )
                snackbar.showSnackbar(
                    if (ok) "File saved." else "Couldn't save there — try Share instead."
                )
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is TranslateUiEvent.ShowMessage -> snackbar.showSnackbar(event.text)
                TranslateUiEvent.OpenProviderSettings -> onOpenProviderSettings()
                is TranslateUiEvent.SaveFileReady -> fileDelivery = event
            }
        }
    }
    // §9.3: Listen never survives leaving the screen.
    DisposableEffect(viewModel) {
        onDispose { viewModel.onScreenClosed() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            StitchHeader(title = "Translate")
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // v1.1.3 (F21): the two modes, always visible —
                // [Quick text | Chapter]. Chapter mode builds the
                // pasted chapter's own name base first (§C).
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !state.largeTextMode,
                        onClick = { viewModel.onModeSelected(false) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2)
                    ) { Text("Quick text") }
                    SegmentedButton(
                        selected = state.largeTextMode,
                        onClick = { viewModel.onModeSelected(true) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2)
                    ) { Text("Chapter") }
                }
                if (state.largeTextMode) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "A whole chapter",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            "Paste a whole chapter. First, a base of its names " +
                                "and terms is built from the chapter itself, so " +
                                "they stay consistent; then it is translated or " +
                                "improved part by part. Nothing is saved to the " +
                                "Library unless you say so afterwards.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // §C refinement: the two visible choices —
                        // detection pre-selects, the user decides.
                        Text(
                            "This text is…",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            androidx.compose.material3.FilterChip(
                                selected = state.chapterKind ==
                                    app.retranslate.domain.translate.ChapterTextKind.ORIGINAL,
                                onClick = {
                                    viewModel.onChapterKindChosen(
                                        app.retranslate.domain.translate.ChapterTextKind.ORIGINAL
                                    )
                                },
                                label = { Text("Original language") }
                            )
                            androidx.compose.material3.FilterChip(
                                selected = state.chapterKind ==
                                    app.retranslate.domain.translate.ChapterTextKind.MTL,
                                onClick = {
                                    viewModel.onChapterKindChosen(
                                        app.retranslate.domain.translate.ChapterTextKind.MTL
                                    )
                                },
                                label = { Text("MTL — improve it") }
                            )
                        }
                        state.detectedNote?.let { note ->
                            Text(
                                note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                // Language bar (design: one glass bar, p-2, two pills
                // and a round swap button between them).
                GlassCard(contentPadding = PaddingValues(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LanguagePill(
                            selected = state.sourceLang,
                            languages = LanguageMatrix.sourceLanguages(),
                            onSelect = viewModel::onSourceChanged,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .size(40.dp)
                                .clip(RoundedCornerShape(percent = 50))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .bounceClickable(onClick = viewModel::onSwapClicked),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.SwapHoriz,
                                contentDescription = "Swap languages",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        LanguagePill(
                            selected = state.targetLang,
                            languages = LanguageMatrix.targetLanguages(),
                            onSelect = viewModel::onTargetChanged,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                // Input card (design: rounded-3xl glass, p-5, divider,
                // then a bottom row — count at the right).
                GlassCard(contentPadding = PaddingValues(20.dp)) {
                    Column {
                        TextField(
                            value = state.input,
                            onValueChange = viewModel::onInputChanged,
                            placeholder = {
                                Text(
                                    if (state.largeTextMode) {
                                        "Paste the chapter text"
                                    } else {
                                        "Type or paste text..."
                                    }
                                )
                            },
                            isError = state.inputError != null,
                            minLines = if (state.largeTextMode) 12 else 4,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences
                            ),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                errorContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                errorIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // §C: chapters arrive by paste — one tap.
                            TextButton(
                                onClick = {
                                    clipboard.getText()?.text
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let { viewModel.onInputChanged(it) }
                                }
                            ) { Text("Paste from clipboard") }
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${state.input.length} characters",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                state.inputError?.let { error ->
                    Text(
                        error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                // v1.1.3 (§G): the long-paste nudge — a whole chapter
                // deserves the Chapter mode (and its base).
                if (state.showChapterNudge) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "This looks like a whole chapter",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                "Chapter mode builds a name base from the text " +
                                    "first, so names stay consistent — better " +
                                    "results for long pastes.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                app.retranslate.ui.common.BounceButton(
                                    onClick = viewModel::onSwitchToChapterMode
                                ) { Text("Switch to Chapter") }
                                TextButton(onClick = viewModel::onDismissChapterNudge) {
                                    Text("Stay here")
                                }
                            }
                        }
                    }
                }
                // The big azure Translate button (design).
                StitchPrimaryButton(
                    onClick = viewModel::onTranslateClicked,
                    enabled = !state.isTranslating,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        when {
                            state.isTranslating -> "Working…"
                            state.largeTextMode &&
                                state.chapterKind ==
                                app.retranslate.domain.translate.ChapterTextKind.MTL ->
                                "Improve chapter"
                            state.largeTextMode -> "Translate chapter"
                            else -> "Translate"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }
                if (state.isTranslating) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.largeProgressText?.let { progress ->
                            Text(progress, style = MaterialTheme.typography.bodyMedium)
                        }
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                // AI Compare & Merge toggle row (design: glass row,
                // mint dot, mint switch). §5.6: only with an AI key;
                // the cap text stays under the row when it matters.
                if (state.mergeAvailable && !state.largeTextMode) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(RoundedCornerShape(percent = 50))
                                        .background(StitchMint)
                                )
                                Text(
                                    "AI Compare & Merge",
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 12.dp)
                                )
                                Switch(
                                    checked = state.mergeEnabled && !state.mergeCapReached,
                                    onCheckedChange = viewModel::onMergeToggleChanged,
                                    enabled = !state.mergeCapReached,
                                    colors = SwitchDefaults.colors(
                                        checkedTrackColor = StitchMint,
                                        checkedThumbColor = Color.White
                                    )
                                )
                            }
                        }
                        Text(
                            if (state.mergeCapReached) {
                                "Today's merge limit (${state.mergeDailyCap}) is used up — " +
                                    "plain translation still works. The limit resets tomorrow " +
                                    "and can be changed under More → Providers & keys."
                            } else {
                                "Checks a few translations and combines the best parts. " +
                                    "(${state.mergesUsedToday} of ${state.mergeDailyCap} " +
                                    "merges used today)"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // v1.1.3 (F21): the old "Large text / chapter" toggle
                // button is replaced by the [Quick text | Chapter]
                // segmented control at the top of this screen.
                // Result card (design: via pill + round actions on top,
                // big result text, divider, other-versions row).
                GlassCard(contentPadding = PaddingValues(20.dp)) {
                    when {
                        state.errorMessage != null -> Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = state.errorMessage!!,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            // v1.0.1: small technical line so a stubborn
                            // failure names itself ("Details: …").
                            state.errorDetail?.let { detail ->
                                Text(
                                    text = "Details: $detail",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            BounceButton(onClick = viewModel::onTranslateClicked) {
                                Text("Try again")
                            }
                        }
                        else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (state.result != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    state.viaProvider?.let { via ->
                                        StatusPill(
                                            text = "via $via",
                                            containerColor =
                                                MaterialTheme.colorScheme.primaryContainer,
                                            contentColor =
                                                MaterialTheme.colorScheme.primary,
                                            showDot = false
                                        )
                                    }
                                    Spacer(Modifier.weight(1f))
                                    RoundAction(
                                        onClick = viewModel::onListenClicked
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.VolumeUp,
                                            contentDescription =
                                                if (state.isSpeaking) "Stop" else "Listen",
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(Modifier.size(8.dp))
                                    RoundAction(
                                        onClick = {
                                            clipboard.setText(AnnotatedString(state.result!!))
                                        }
                                    ) {
                                        Icon(
                                            Icons.Filled.ContentCopy,
                                            contentDescription = "Copy",
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(Modifier.size(8.dp))
                                    // v1.1.3 (§E): Save as file — the
                                    // format sheet asks first; nothing
                                    // is written before the user confirms.
                                    RoundAction(
                                        onClick = viewModel::onSaveAsFileClicked
                                    ) {
                                        Icon(
                                            Icons.Filled.Save,
                                            contentDescription = "Save as file",
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            // §9.4 text crossfade: result swaps never snap.
                            AnimatedContent(
                                targetState = state.result,
                                transitionSpec = {
                                    val spec = if (motionOn) {
                                        MotionTokens.fadeTween<Float>()
                                    } else {
                                        androidx.compose.animation.core.tween(0)
                                    }
                                    fadeIn(spec) togetherWith fadeOut(spec)
                                },
                                label = "translationResult"
                            ) { result ->
                                Text(
                                    text = result ?: "Your translation will appear here.",
                                    style = if (result == null) {
                                        MaterialTheme.typography.bodyLarge
                                    } else {
                                        // Design: result text ~text-xl.
                                        MaterialTheme.typography.bodyLarge.copy(
                                            fontSize = 20.sp,
                                            lineHeight = 28.sp
                                        )
                                    },
                                    color = if (result == null) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }
                            if (state.result != null && state.mergeCandidates.isNotEmpty()) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Alternative translations available",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = viewModel::onShowVersions) {
                                        Text("See other versions")
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // v1.1.3 (§C/§G): plain-words notes under a chapter result.
                if (state.result != null && state.resultNotes.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (note in state.resultNotes) {
                            Text(
                                note,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                // v1.1.3 (§C): the base ask — the user's exact question,
                // with his exact two answers. Dismissing = just this
                // chapter (the VM's dismiss path does exactly that).
                if (state.baseAskVisible) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "Save this base for future chapters?",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                "This chapter's base has ${state.baseEntryCount} " +
                                    "names and terms. Saving it files the chapter " +
                                    "in your Library as its own book, and the base " +
                                    "applies to that book's future chapters.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                app.retranslate.ui.common.BounceButton(
                                    onClick = viewModel::onSaveBaseChosen
                                ) { Text("Save this base") }
                                OutlinedButton(onClick = viewModel::onJustThisChapter) {
                                    Text("Just this chapter")
                                }
                            }
                        }
                    }
                }
                // v1.1 (the user's provider picker): bottom-LEFT of the
                // translate UI, always visible, always tappable — the
                // user's explicit placement, kept over the design. The
                // label is the current selection — "Automatic" by
                // default, the chosen provider's name after a manual
                // pick, and (in Automatic, after a translation)
                // "via X" naming who ACTUALLY answered.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val chipLabel = when {
                        // v1.1.3 (§I): the current mode is ALWAYS
                        // visible on the chip — a pick names the engine.
                        state.providerPickKey != null ->
                            state.providerChoiceName ?: "Automatic"
                        state.providerChoice != null ->
                            state.providerChoiceName ?: "Automatic"
                        state.viaProvider != null -> "via ${state.viaProvider}"
                        else -> "Automatic"
                    }
                    AssistChip(
                        onClick = viewModel::onProviderChipClicked,
                        label = {
                            AnimatedContent(
                                targetState = chipLabel,
                                transitionSpec = {
                                    val spec = if (motionOn) {
                                        MotionTokens.fadeTween<Float>()
                                    } else {
                                        androidx.compose.animation.core.tween(0)
                                    }
                                    fadeIn(spec) togetherWith fadeOut(spec)
                                },
                                label = "providerChipLabel"
                            ) { label -> Text(label) }
                        },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.ArrowDropDown,
                                contentDescription = "Choose provider"
                            )
                        }
                    )
                    state.detectedLanguage?.let { detected ->
                        // v1.1 (sweep): a readout, not a dead chip.
                        InfoChip("Detected: $detected")
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // §5.6: the raw candidates, read-only — a transparency viewer,
    // not a picker. The merged result stays the result.
    if (state.showVersions) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = viewModel::onDismissVersions
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Other versions",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "The result above already combines the best parts of these.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.mergeCandidates.forEach { candidate ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            candidate.providerName,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            candidate.text,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                TextButton(onClick = viewModel::onDismissVersions) {
                    Text("Close")
                }
            }
        }
    }

    // v1.1: the provider picker. Rows are the SAME ProviderRowStates
    // Providers & keys shows (one builder — ProviderStatus), so names
    // and statuses can never disagree between the two screens.
    if (state.showProviderPicker) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = viewModel::onDismissProviderPicker
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "Choose an engine",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    // v1.1.3 (§I): the rule, in plain words — Automatic
                    // is the only mode where engines take turns.
                    "Automatic tries them in order until one answers — the " +
                        "recommended choice. If you pick one engine, ONLY " +
                        "that engine runs: if it fails, you're told plainly " +
                        "and nothing switches behind your back.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                PickerRow(
                    name = "Automatic (recommended)",
                    statusText = null,
                    detailText = "The app picks what works",
                    selected = state.providerPickKey == null,
                    dimmed = false,
                    onClick = viewModel::onAutomaticChosen
                )
                state.providerOptions.forEach { row ->
                    val needsSetup =
                        app.retranslate.ui.settings.ProviderStatus.needsSetup(row)
                    PickerRow(
                        name = row.name,
                        statusText = row.statusText,
                        detailText = if (needsSetup) {
                            "Needs set-up first — tap to open Providers & keys"
                        } else {
                            row.detailText
                        },
                        selected = state.providerPickKey == row.id.name,
                        dimmed = needsSetup,
                        onClick = { viewModel.onProviderRowTapped(row) }
                    )
                }
                if (state.gatewayOptions.isNotEmpty()) {
                    Text(
                        "Your added providers",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    state.gatewayOptions.forEach { option ->
                        PickerRow(
                            name = option.name,
                            statusText = null,
                            detailText = "Added by link — only this one runs",
                            selected = state.providerPickKey == option.pickKey,
                            dimmed = false,
                            onClick = { viewModel.onGatewayPickChosen(option) }
                        )
                    }
                }
                Spacer(
                    Modifier.padding(bottom = 20.dp)
                )
            }
        }
    }

    // v1.1.3 (§E): the save-as-file format sheet — asked BEFORE any
    // file exists; choosing a format writes the temp file, then the
    // Save / Share dialog below decides where it goes.
    if (state.showSaveSheet) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = viewModel::onDismissSaveSheet
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Save as file",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Pick a format — then you'll choose where it goes " +
                        "(save it, or share it). Nothing is saved before that.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = {
                        viewModel.onSaveFormatChosen(
                            app.retranslate.domain.export.ExportFormat.TXT
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Text file (.txt)") }
                OutlinedButton(
                    onClick = {
                        viewModel.onSaveFormatChosen(
                            app.retranslate.domain.export.ExportFormat.EPUB
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("EPUB book (.epub)") }
                Spacer(Modifier.padding(bottom = 16.dp))
            }
        }
    }

    // v1.1.3 (§E): the written file — Save (SAF) or Share.
    fileDelivery?.let { ready ->
        AlertDialog(
            onDismissRequest = { fileDelivery = null },
            title = { Text("Your file is ready") },
            text = { Text(ready.fileName) },
            confirmButton = {
                app.retranslate.ui.common.BounceButton(
                    onClick = { saveFileLauncher.launch(ready.fileName) }
                ) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            val intent = app.retranslate.data.files.FileDelivery
                                .shareIntent(
                                    deliveryContext,
                                    java.io.File(ready.filePath),
                                    ready.mimeType
                                )
                            deliveryContext.startActivity(
                                android.content.Intent.createChooser(
                                    intent, "Share file"
                                )
                            )
                            fileDelivery = null
                        }
                    ) { Text("Share") }
                    TextButton(onClick = { fileDelivery = null }) { Text("Done") }
                }
            }
        )
    }
}

/** One of the result card's round action buttons (design: 32dp circles). */
@Composable
private fun RoundAction(
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .bounceClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

/** One tappable row of the provider picker sheet (v1.1). */
@Composable
private fun PickerRow(
    name: String,
    statusText: String?,
    detailText: String?,
    selected: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit
) {
    val contentAlpha = if (dimmed) 0.55f else 1f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bounceClickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
            )
            detailText?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                        .copy(alpha = contentAlpha)
                )
            }
        }
        statusText?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha)
            )
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * A language selector pill (design: rounded-xl, Jakarta bold name,
 * small chevron) with the full word-labelled dropdown behind it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePill(
    selected: LangCode,
    languages: List<Language>,
    onSelect: (LangCode) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = languages.firstOrNull { it.code == selected }?.name ?: selected
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                selectedName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            languages.forEach { language ->
                DropdownMenuItem(
                    text = { Text(language.name) },
                    onClick = {
                        expanded = false
                        onSelect(language.code)
                    }
                )
            }
        }
    }
}
