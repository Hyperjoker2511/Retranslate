package app.retranslate.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import app.retranslate.ui.common.bounceClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retranslate.data.prefs.ThemeMode
import app.retranslate.ui.theme.ReviewThemes

/**
 * Appearance (PLAN.md §9.5, completed in P7): Light / Dark / System,
 * the Material You dynamic-colour toggle, the review theme's home
 * (five themes, independent of the app theme), and "Bouncy
 * animations" — the §9.4 reduce-motion switch. Choices apply
 * immediately; there is nothing to save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    viewModel: AppearanceViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appearance") },
                navigationIcon = {
                    // v1.1 (sweep): the same back arrow as every other
                    // sub-screen (this one used a text "← Back").
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionTitle("Light or dark")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ThemeMode.FOLLOW_SYSTEM to "System",
                    ThemeMode.LIGHT to "Light",
                    ThemeMode.DARK to "Dark"
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = state.themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        label = { Text(label) }
                    )
                }
            }

            SectionTitle("Colours")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Use my phone's colours (Material You)",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "Android 12 and up: the app takes its colours from " +
                            "your wallpaper. Off = the app's own blue glass look.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.dynamicColor,
                    onCheckedChange = viewModel::setDynamicColor
                )
            }

            SectionTitle("Chapter review theme")
            Text(
                "The colours of the checking screen only — a dark app can " +
                    "still check chapters on Paper. You can also change this " +
                    "from the Aa button while reading.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ReviewThemes.ALL.chunked(2).forEach { rowThemes ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    rowThemes.forEach { theme ->
                        val selected = state.reviewThemeId == theme.id
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(MaterialTheme.shapes.medium)
                                // v1.1 (sweep): press-scale, like the
                                // app's other tappable surfaces.
                                .bounceClickable {
                                    viewModel.setReviewTheme(theme.id)
                                }
                                .then(
                                    if (selected) {
                                        Modifier.border(
                                            2.dp,
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.shapes.medium
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(4.dp)
                                    .background(
                                        theme.background,
                                        MaterialTheme.shapes.small
                                    )
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    "Aa",
                                    color = theme.text,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                            Text(
                                theme.displayName,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (selected) {
                                Text("✓", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    if (rowThemes.size == 1) {
                        Box(Modifier.weight(1f))
                    }
                }
            }

            SectionTitle("Motion")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Bouncy animations",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "The springy button and screen motion. Turn it off " +
                            "for calmer, plain fades.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.bouncyAnimations,
                    onCheckedChange = viewModel::setBouncyAnimations
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}
