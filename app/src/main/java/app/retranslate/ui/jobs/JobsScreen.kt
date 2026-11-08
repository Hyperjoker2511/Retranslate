package app.retranslate.ui.jobs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retranslate.core.motion.MotionTokens
import app.retranslate.ui.common.BounceButton
import app.retranslate.ui.common.EmptyState
import app.retranslate.ui.common.GlassCard
import app.retranslate.ui.common.IconTile
import app.retranslate.ui.common.StatusPill
import app.retranslate.ui.common.StitchHeader
import app.retranslate.ui.common.StitchPrimaryButton
import app.retranslate.ui.common.ThickProgress
import app.retranslate.ui.theme.StitchAmber
import app.retranslate.ui.theme.StitchAzure
import app.retranslate.ui.theme.StitchMint

/**
 * Jobs (PLAN.md §9.3), laid out EXACTLY as the Stitch design
 * (jobs-shot.png / jobs-html.html): compact "Jobs" header, a
 * "Background Tasks" row with the "N Active / Done" pill, then glass
 * job cards — status pill top-left, tinted icon tile top-right, big
 * title, subtitle, "Progress" + % with a THICK tinted bar, and the
 * Pause (wide soft pill) / Cancel (red text) actions. Finished cards
 * get the big azure EPUB action, which opens the book — the app's
 * export confirm lives on the book page (there is no Jobs-side
 * exporter, and a button that starts nothing would be a fake).
 */
@Composable
fun JobsScreen(
    onGoToLibrary: () -> Unit,
    onOpenBook: (Long) -> Unit,
    viewModel: JobsViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is JobsUiEvent.ShowMessage -> snackbar.showSnackbar(event.text)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            StitchHeader(title = "Jobs")
            if (state.jobs.isEmpty()) {
                EmptyState(
                    title = "No jobs yet",
                    message = "Translate a book and its job will appear here, " +
                        "with progress you can check any time.",
                    actionLabel = "Go to Library",
                    onAction = onGoToLibrary
                )
            } else {
                val active = state.jobs.count { it.canPause || it.canCancel }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Background Tasks",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Spacer(Modifier.weight(1f))
                            StatusPill(
                                text = "$active Active / Done",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = StitchAzure,
                                showDot = false
                            )
                        }
                    }
                    items(state.jobs, key = { it.runId }) { job ->
                        JobCard(
                            job = job,
                            onOpenBook = { onOpenBook(job.bookId) },
                            onPause = { viewModel.pause(job.bookId) },
                            onResume = { viewModel.resume(job.bookId) },
                            onCancel = { viewModel.cancel(job.bookId) },
                            modifier = Modifier.animateItem(
                                placementSpec = MotionTokens.spatialSpring()
                            )
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}

/** Status → the design's pill colour, bar colour and tile icon. */
private class JobStyle(
    val pillText: String,
    val pillContainer: Color,
    val pillContent: Color,
    val barColor: Color,
    val tileContainer: Color
)

@Composable
private fun jobStyleFor(job: JobSummary): JobStyle {
    val colors = MaterialTheme.colorScheme
    val progress = job.progress ?: 0f
    return when {
        job.stateText.startsWith("Finished") -> JobStyle(
            pillText = "✓ Completed",
            pillContainer = colors.secondaryContainer,
            pillContent = colors.onSecondaryContainer,
            barColor = StitchMint,
            tileContainer = colors.primaryContainer
        )
        job.kindText == "Learning from samples" || job.stateText == "Learning" ->
            JobStyle(
                pillText = if (progress >= 0.85f) "Almost done" else job.stateText,
                pillContainer = colors.secondaryContainer,
                pillContent = colors.onSecondaryContainer,
                barColor = StitchMint,
                tileContainer = colors.secondaryContainer
            )
        job.stateText == "Translating" || job.stateText == "Waiting to start" ->
            JobStyle(
                pillText = if (job.stateText == "Translating") {
                    "Translating…"
                } else {
                    job.stateText
                },
                pillContainer = colors.tertiaryContainer,
                pillContent = colors.onTertiaryContainer,
                barColor = StitchAmber,
                tileContainer = colors.tertiaryContainer
            )
        job.stateText.startsWith("Paused") -> JobStyle(
            pillText = job.stateText,
            pillContainer = colors.tertiaryContainer,
            pillContent = colors.onTertiaryContainer,
            barColor = StitchAmber,
            tileContainer = colors.tertiaryContainer
        )
        else -> JobStyle(
            pillText = job.stateText,
            pillContainer = colors.surfaceContainerHigh,
            pillContent = colors.onSurfaceVariant,
            barColor = StitchAzure,
            tileContainer = colors.primaryContainer
        )
    }
}

@Composable
private fun JobCard(
    job: JobSummary,
    onOpenBook: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val style = jobStyleFor(job)
    val finished = job.stateText.startsWith("Finished")
    GlassCard(modifier = modifier, contentPadding = PaddingValues(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    StatusPill(
                        text = style.pillText,
                        containerColor = style.pillContainer,
                        contentColor = style.pillContent
                    )
                    Text(
                        job.bookTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                    Text(
                        job.detailText.ifBlank { job.kindText },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconTile(containerColor = style.tileContainer) {
                    Icon(
                        imageVector = when {
                            finished -> Icons.Filled.MenuBook
                            job.kindText == "Learning from samples" ->
                                Icons.Filled.AutoAwesome
                            else -> Icons.Filled.Bolt
                        },
                        contentDescription = null,
                        tint = style.barColor,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            if (finished) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = StitchMint,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        "  ${job.kindText} finished",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // The design's big azure EPUB action. It opens the book,
                // where the real export confirm + Save/Share live.
                StitchPrimaryButton(
                    onClick = onOpenBook,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.MenuBook, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Make EPUB",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }
                Text(
                    "Opens the book — export starts there, with its Save / Share choices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                job.progress?.let { progress ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "Progress",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${(progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = style.barColor
                            )
                        }
                        ThickProgress(progress = progress, color = style.barColor)
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (job.canPause) {
                        Button(
                            onClick = onPause,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor =
                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) { Text("Pause", style = MaterialTheme.typography.titleSmall) }
                    }
                    if (job.canResume) {
                        BounceButton(
                            onClick = onResume,
                            modifier = Modifier.weight(1f)
                        ) { Text("Resume") }
                    }
                    if (job.canCancel) {
                        TextButton(onClick = onCancel) {
                            Text(
                                "Cancel",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                    }
                    if (!job.canPause && !job.canResume && !job.canCancel) {
                        Spacer(Modifier.weight(1f))
                    }
                    TextButton(onClick = onOpenBook) { Text("Open book") }
                }
            }
        }
    }
}
