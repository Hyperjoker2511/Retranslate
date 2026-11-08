package app.retranslate.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retranslate.ui.common.BounceButton

/**
 * Instance-list editor (PLAN.md §4.2/§9.3): one address per line, Save,
 * and a reset to the list the app shipped with.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstancesScreen(viewModel: InstancesViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                InstancesUiEvent.Saved -> onBack()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${viewModel.providerName} instances") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "One address per line. ReTranslate tries them in order and " +
                    "automatically rests any that keep failing.",
                style = MaterialTheme.typography.bodyLarge
            )
            OutlinedTextField(
                value = state.text,
                onValueChange = viewModel::onTextChanged,
                label = { Text("Instance addresses") },
                minLines = 5,
                modifier = Modifier.fillMaxWidth()
            )
            state.errorText?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            BounceButton(
                onClick = viewModel::onSave,
                enabled = state.loaded,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save") }
            TextButton(onClick = viewModel::onResetToDefaults) {
                Text("Reset to the shipped list")
            }
        }
    }
}
