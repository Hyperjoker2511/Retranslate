package app.retranslate.ui.more

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.retranslate.ui.common.GlassCard
import app.retranslate.ui.common.bounceClickable
import app.retranslate.ui.common.IconTile
import app.retranslate.ui.common.StitchHeader
import app.retranslate.ui.theme.StitchAmber
import app.retranslate.ui.theme.StitchAzure
import app.retranslate.ui.theme.StitchMint
import app.retranslate.ui.theme.StitchViolet

/**
 * More (PLAN.md §9.2), laid out EXACTLY as the Stitch design
 * (more-shot.png / more-html.html): a compact "Welcome back / More"
 * header, the premium gradient (azure → violet) Gemini banner card
 * whose text tells the TRUTH about the key (present = "Added – main
 * engine on"; absent = "Not added yet"), then the grouped glass rows
 * with 48dp tinted icon tiles, title, subtitle and chevron.
 *
 * The design's "Your data" row is NOT reproduced: the app has no such
 * destination, and a row that leads nowhere would be a fake control.
 */
@Composable
fun MoreScreen(
    viewModel: MoreViewModel = viewModel(),
    geminiKeyPresent: Boolean = false,
    onProvidersClick: () -> Unit = {},
    onBackupClick: () -> Unit = {},
    onAppearanceClick: () -> Unit = {},
    onLicencesClick: () -> Unit = {}
) {
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is MoreUiEvent.ShowMessage -> snackbar.showSnackbar(event.text)
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
            StitchHeader(title = "More", subtitle = "Welcome back")
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    GeminiBanner(
                        keyPresent = geminiKeyPresent,
                        onProvidersClick = onProvidersClick
                    )
                }
                items(viewModel.rows, key = { it.id }) { row ->
                    MoreRowCard(
                        row = row,
                        onClick = {
                            when (row.id) {
                                MoreViewModel.ID_PROVIDERS -> onProvidersClick()
                                MoreViewModel.ID_BACKUP -> onBackupClick()
                                MoreViewModel.ID_APPEARANCE -> onAppearanceClick()
                                MoreViewModel.ID_LICENCES -> onLicencesClick()
                                else -> viewModel.onRowClicked(row)
                            }
                        }
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

/**
 * The design's banner: gradient azure → violet, a "● Gemini AI key"
 * pill, the state headline, and the white "Providers & keys ›" pill
 * button over "Managed globally".
 */
@Composable
private fun GeminiBanner(keyPresent: Boolean, onProvidersClick: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(listOf(StitchAzure, StitchViolet))
            )
            .padding(24.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Color.White.copy(alpha = 0.20f))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(if (keyPresent) StitchMint else StitchAmber)
                    )
                    Text(
                        "Gemini AI key",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = Color.White
                    )
                }
                Spacer(Modifier.weight(1f))
                if (keyPresent) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(StitchMint.copy(alpha = 0.20f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = StitchMint
                        )
                    }
                }
            }
            Text(
                if (keyPresent) {
                    "Added – main engine on"
                } else {
                    "Not added yet"
                },
                style = MaterialTheme.typography.titleLarge,
                color = Color.White
            )
            Text(
                if (keyPresent) {
                    "Running light models first — stronger models only as backup."
                } else {
                    "Add a free key to make Gemini your main engine."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.80f)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.15f))
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Managed globally",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.70f),
                    modifier = Modifier.weight(1f)
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .bounceClickable(onClick = onProvidersClick)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Providers & keys",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = StitchAzure
                    )
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = StitchAzure,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/** One grouped glass row (design: p-4, tile + title/subtitle + chevron). */
@Composable
private fun MoreRowCard(row: MoreRow, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (tileContainer, tileTint, icon) = when (row.id) {
        MoreViewModel.ID_APPEARANCE ->
            Triple(colors.primaryContainer, StitchAzure, Icons.Filled.Palette)
        MoreViewModel.ID_PROVIDERS ->
            Triple(colors.secondaryContainer, StitchMint, Icons.Filled.VpnKey)
        MoreViewModel.ID_BACKUP ->
            Triple(colors.tertiaryContainer, StitchAmber, Icons.Filled.Backup)
        MoreViewModel.ID_LICENCES ->
            Triple(colors.surfaceContainerHigh, colors.onSurfaceVariant, Icons.Filled.Description)
        else -> Triple(colors.primaryContainer, StitchAzure, Icons.Filled.Info)
    }
    GlassCard(
        onClick = onClick,
        contentPadding = PaddingValues(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconTile(containerColor = tileContainer) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tileTint,
                    modifier = Modifier.size(26.dp)
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
            ) {
                Text(row.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    row.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
