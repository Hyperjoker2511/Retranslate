package app.retranslate.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retranslate.ui.common.BounceButton
import app.retranslate.ui.common.bounceClickable

/**
 * Key entry for one provider (PLAN.md §9.3): what it costs, where to
 * get the key, a paste field, and **Test & save** — a key that fails
 * its test is shown in words and never saved silently.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyEntryScreen(viewModel: KeyEntryViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val info = viewModel.info
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var confirmRemove by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is KeyEntryUiEvent.ShowMessage -> snackbar.showSnackbar(event.text)
                KeyEntryUiEvent.Saved -> onBack()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${info.displayName} key") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(info.costText, style = MaterialTheme.typography.bodyLarge)
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(info.getKeyUrl))
                    )
                }
            ) { Text("Get a key (opens ${info.displayName}'s site)") }

            OutlinedTextField(
                value = state.secret,
                onValueChange = viewModel::onSecretChanged,
                label = { Text(info.secretLabel) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (info.needsRegion) {
                OutlinedTextField(
                    value = state.region,
                    onValueChange = viewModel::onRegionChanged,
                    label = { Text("Region (e.g. eastus)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            state.errorText?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            // v1.1.2 (Gemini only): the model picker. Automatic is the
            // recommended choice — the lite models do the translating
            // and the stronger ones only step in as a rescue. A pinned
            // model is tried first, and the rescue still applies if
            // the pinned model is ever unavailable.
            if (info.id == app.retranslate.providers.ProviderId.GEMINI) {
                GeminiModelPicker(
                    choice = state.geminiModelChoice,
                    onChoice = viewModel::onGeminiModelChanged
                )
            }
            BounceButton(
                onClick = viewModel::onTestAndSave,
                enabled = !state.isTesting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.isTesting) "Testing…" else "Test & save")
            }
            Text(
                "The key is tested first — if it doesn't work, nothing is saved. " +
                    "Saved keys are encrypted on this phone and never leave it except " +
                    "to ${info.displayName} itself.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.hasSavedKey) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { confirmRemove = true }) {
                    Text("Remove saved key", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove the ${info.displayName} key?") },
            text = {
                Text(
                    "Translations will stop using ${info.displayName} until you add a key again."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    viewModel.onRemoveKey()
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("Keep it") }
            }
        )
    }
}

/**
 * v1.1.2: the Gemini model selector — "Automatic (recommended)" plus
 * every current model with a one-line plain description. Lite models
 * say plainly that they do the translating and are light on the free
 * quota; Flash models say plainly that they are stronger and use more.
 */
@Composable
private fun GeminiModelPicker(
    choice: String?,
    onChoice: (String?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Model",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            "Automatic uses the light models first — they do the " +
                "translating and are light on your free quota. The " +
                "stronger models only step in if a light one is " +
                "unavailable.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        GeminiModelRow(
            title = "Automatic (recommended)",
            description = "Light models first — light on your free quota",
            selected = choice == null,
            onClick = { onChoice(null) }
        )
        app.retranslate.providers.key.GeminiProvider.MODEL_OPTIONS.forEach { option ->
            GeminiModelRow(
                title = option.title,
                description = option.description,
                selected = choice == option.modelId,
                onClick = { onChoice(option.modelId) }
            )
        }
    }
}

@Composable
private fun GeminiModelRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bounceClickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        androidx.compose.material3.RadioButton(
            selected = selected,
            onClick = onClick
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
