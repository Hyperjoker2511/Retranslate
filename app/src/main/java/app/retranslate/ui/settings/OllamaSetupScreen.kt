package app.retranslate.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retranslate.ui.common.BounceButton

/**
 * Ollama setup (PLAN.md §4.3, P7): dormancy ends here. The user points
 * the app at the Ollama server on their own PC — free, unlimited,
 * private — and the §4.3 cleartext warning is on screen before Save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OllamaSetupScreen(
    viewModel: OllamaSetupViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is OllamaSetupUiEvent.ShowMessage -> snackbar.showSnackbar(event.text)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ollama — your own computer") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("← Back") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Ollama runs an AI model on your own computer — free, " +
                    "unlimited, and private: your text never leaves your home " +
                    "network. Install Ollama on the computer (ollama.com), " +
                    "pull a model like llama3.1, then enter the computer's " +
                    "address below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // §4.3: the cleartext warning, visible BEFORE anything saves.
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Before you save: your computer's address is on your home " +
                        "network, so the app talks to it over plain " +
                        "(unencrypted) HTTP — Android may block that depending " +
                        "on your phone's settings. Only use an address on your " +
                        "own home Wi-Fi, never a stranger's server.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp)
                )
            }
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = viewModel::onBaseUrlChanged,
                label = { Text("Computer address") },
                placeholder = { Text("http://192.168.1.20:11434") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.model,
                onValueChange = viewModel::onModelChanged,
                label = { Text("Model name") },
                placeholder = { Text("llama3.1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            state.errorText?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BounceButton(onClick = viewModel::onSave) { Text("Save") }
                if (state.isConfigured) {
                    TextButton(
                        onClick = viewModel::onTest,
                        enabled = !state.isTesting
                    ) {
                        Text(if (state.isTesting) "Testing…" else "Test connection")
                    }
                    TextButton(onClick = viewModel::onRemove) { Text("Remove") }
                }
            }
            Text(
                if (state.isConfigured) {
                    "Status: set up — Ollama is tried after the online services."
                } else {
                    "Status: not set up — this stays dormant until you save an address."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
