package app.retranslate.ui.more

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retranslate.core.motion.MotionTokens
import app.retranslate.data.files.FileDelivery
import app.retranslate.data.files.PickedFile
import app.retranslate.domain.export.BackupManager
import app.retranslate.ui.common.BounceButton
import kotlinx.coroutines.launch
import java.io.File

/** A backup file waiting for the user to save or share it. */
private data class PendingBackup(
    val file: File,
    val fileName: String,
    val mimeType: String
)

/**
 * Backup & restore screen (PLAN.md §9.3): what the file holds (and
 * what it never holds — keys), a Back up button, a Restore button,
 * and the §7.5 delivery choices (Save to device / Share) once a
 * backup is written.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    viewModel: BackupViewModel,
    describeFile: (android.net.Uri) -> PickedFile,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<PendingBackup?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupManager.MIME_TYPE)
    ) { uri ->
        val file = pending?.file
        pending = null
        if (uri != null && file != null) {
            scope.launch {
                val ok = FileDelivery.copyTo(context, file, uri)
                snackbar.showSnackbar(
                    if (ok) {
                        "Backup saved."
                    } else {
                        "Couldn't save the file there — try Share instead."
                    }
                )
            }
        }
    }
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val picked = describeFile(uri)
            viewModel.restore(picked.openStream)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is BackupUiEvent.ShowMessage -> snackbar.showSnackbar(event.text)
                is BackupUiEvent.BackupReady -> pending = PendingBackup(
                    file = File(event.filePath),
                    fileName = event.fileName,
                    mimeType = event.mimeType
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & restore") },
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "One file with your books, their chapters, your Knowledge " +
                    "Bases and your settings.",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                "Your API keys are never in the backup — after a restore " +
                    "you add them again under Providers & keys.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            BounceButton(
                onClick = viewModel::createBackup,
                enabled = state.workingText == null,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Back up") }
            OutlinedButton(
                onClick = {
                    pickLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                },
                enabled = state.workingText == null,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Restore from a backup file") }

            state.workingText?.let { working ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(working, style = MaterialTheme.typography.bodyMedium)
                    val fraction = state.progress
                    if (fraction != null) {
                        val animated by animateFloatAsState(
                            targetValue = fraction,
                            animationSpec = MotionTokens.progressTween(),
                            label = "backup-progress"
                        )
                        LinearProgressIndicator(
                            progress = { animated },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            state.lastSummary?.let { summary ->
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    pending?.let { file ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Your backup is ready") },
            text = {
                Text(
                    "Save it somewhere safe, or share it to another app. " +
                        "Anyone with this file can read your books — your " +
                        "API keys are not in it."
                )
            },
            confirmButton = {
                BounceButton(onClick = { saveLauncher.launch(file.fileName) }) {
                    Text("Save to device")
                }
            },
            dismissButton = {
                androidx.compose.foundation.layout.Row {
                    TextButton(onClick = {
                        pending = null
                        val intent = FileDelivery.shareIntent(
                            context, file.file, file.mimeType
                        )
                        context.startActivity(
                            Intent.createChooser(intent, "Share backup")
                        )
                    }) { Text("Share") }
                    TextButton(onClick = { pending = null }) { Text("Done") }
                }
            }
        )
    }
}
