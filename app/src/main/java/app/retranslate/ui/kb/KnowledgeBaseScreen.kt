package app.retranslate.ui.kb

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retranslate.data.db.DbEnums
import app.retranslate.data.db.KbCategory
import app.retranslate.data.db.KbEntryEntity
import app.retranslate.data.db.KbGender
import app.retranslate.domain.kb.KbEntryDraft
import app.retranslate.ui.common.BounceButton
import app.retranslate.ui.common.bounceClickable

/**
 * Knowledge Base review & edit (PLAN.md §6.3): grouped, big, editable
 * rows — tap to fix a name or gender, lock the ones that must never
 * change, add entries by hand, delete with a confirm. Changing a name
 * arms "Re-apply names" (§6.3): preview the affected chapters, apply,
 * undo. "Re-study" returns to the study setup screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeBaseScreen(
    viewModel: KnowledgeBaseViewModel,
    onBack: () -> Unit,
    onLearnFromSamples: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<KbEntryEntity?>(null) }
    var addingNew by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<KbEntryEntity?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is KnowledgeBaseUiEvent.ShowMessage -> snackbar.showSnackbar(event.text)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Knowledge Base", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            state.kb == null && state.entries.isEmpty() -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("No Knowledge Base yet", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Learn from your best sample chapters and the app will keep names " +
                        "and terms consistent across the whole book. You can also add " +
                        "names by hand — those are locked, so studying never changes them.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                BounceButton(
                    onClick = onLearnFromSamples,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Learn from samples") }
                OutlinedButton(
                    onClick = { addingNew = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Add an entry by hand") }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                KnowledgeBaseViewModel.summaryText(state.entries) +
                                    " — check them, fix anything wrong.",
                                style = MaterialTheme.typography.titleMedium
                            )
                            state.kb?.let { kb ->
                                Text(
                                    "${kb.sourceNote} · version ${kb.version}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                BounceButton(onClick = onLearnFromSamples) {
                                    Text("Re-study")
                                }
                                OutlinedButton(onClick = { addingNew = true }) {
                                    Text("Add entry")
                                }
                            }
                        }
                    }
                }

                state.pendingReapply?.let { request ->
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    "Name changed: “${request.oldForm}” → “${request.newForm}”",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                val preview = state.reapplyPreview
                                if (preview == null) {
                                    Text(
                                        "Chapters already translated may still use the " +
                                            "old name. Re-applying fixes them here, for " +
                                            "free — no re-translation needed.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        BounceButton(
                                            onClick = viewModel::previewReapply,
                                            enabled = !state.reapplyBusy
                                        ) { Text("Preview") }
                                        TextButton(onClick = viewModel::dismissReapply) {
                                            Text("Not now")
                                        }
                                    }
                                } else {
                                    Text(
                                        preview.joinToString("\n") { fix ->
                                            "Chapter ${fix.chapterNumber} — " +
                                                "${fix.occurrences} place(s): " +
                                                fix.variantsFound.joinToString(", ")
                                        }.ifBlank { "No chapter uses the old name." },
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        BounceButton(
                                            onClick = viewModel::applyReapply,
                                            enabled = !state.reapplyBusy && preview.isNotEmpty()
                                        ) { Text("Apply to these chapters") }
                                        TextButton(onClick = viewModel::dismissReapply) {
                                            Text("Cancel")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (state.hasUndo) {
                    item {
                        OutlinedButton(
                            onClick = viewModel::undoReapply,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Undo the last rename") }
                    }
                }

                CATEGORY_ORDER.forEach { category ->
                    val group = state.entries.filter { it.category == category.name }
                    if (group.isNotEmpty()) {
                        item(key = "header-$category") {
                            Text(
                                categoryTitle(category),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                        group.forEach { entry ->
                            item(key = "entry-${entry.id}") {
                                EntryRow(
                                    entry = entry,
                                    onClick = { editing = entry },
                                    onToggleLock = { viewModel.toggleLock(entry) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { entry ->
        EntryEditDialog(
            initial = entry,
            onDismiss = { editing = null },
            onSave = { draft, locked ->
                editing = null
                viewModel.editEntry(entry, draft, locked)
            },
            onDelete = {
                editing = null
                deleting = entry
            }
        )
    }
    if (addingNew) {
        EntryEditDialog(
            initial = null,
            onDismiss = { addingNew = false },
            onSave = { draft, _ ->
                addingNew = false
                viewModel.addEntry(draft)
            },
            onDelete = null
        )
    }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove this entry?") },
            text = {
                Text(
                    "“${entry.canonicalForm}” will no longer be enforced in new " +
                        "translations. Chapters already translated keep their text."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    viewModel.deleteEntry(entry)
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Keep") }
            }
        )
    }
}

@Composable
private fun EntryRow(
    entry: KbEntryEntity,
    onClick: () -> Unit,
    onToggleLock: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // v1.1 (sweep): the same press-scale feedback as every
            // other tappable card in the app.
            .bounceClickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entry.canonicalForm, style = MaterialTheme.typography.titleSmall)
                entry.sourceForm?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        "Original: $it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (entry.category == KbCategory.CHARACTER.name) {
                        DbEnums.kbGender(entry.gender)?.let { gender ->
                            // v1.1 (sweep): readout labels, not dead chips.
                            app.retranslate.ui.common.InfoChip(
                                when (gender) {
                                    KbGender.MALE -> "Male"
                                    KbGender.FEMALE -> "Female"
                                    KbGender.UNKNOWN -> "Gender unknown"
                                }
                            )
                        }
                    }
                    if (entry.locked) {
                        app.retranslate.ui.common.InfoChip("Locked")
                    }
                }
                entry.note?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onToggleLock) {
                Icon(
                    if (entry.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = if (entry.locked) "Unlock entry" else "Lock entry"
                )
            }
        }
    }
}

/** Add/edit one entry. [initial] null = adding by hand (§6.2 manual path). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EntryEditDialog(
    initial: KbEntryEntity?,
    onDismiss: () -> Unit,
    onSave: (KbEntryDraft, Boolean) -> Unit,
    onDelete: (() -> Unit)?
) {
    var canonical by remember { mutableStateOf(initial?.canonicalForm ?: "") }
    var sourceForm by remember { mutableStateOf(initial?.sourceForm ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var category by remember {
        mutableStateOf(
            initial?.let { DbEnums.kbCategory(it.category) } ?: KbCategory.CHARACTER
        )
    }
    var gender by remember { mutableStateOf(initial?.let { DbEnums.kbGender(it.gender) }) }
    var locked by remember { mutableStateOf(initial?.locked ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add entry" else "Edit entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CATEGORY_ORDER.forEach { option ->
                        FilterChip(
                            selected = category == option,
                            onClick = { category = option },
                            label = { Text(categoryTitle(option)) }
                        )
                    }
                }
                OutlinedTextField(
                    value = canonical,
                    onValueChange = { canonical = it },
                    label = {
                        Text(if (category == KbCategory.TONE_RULE) "The rule" else "Name / term (as it must appear)")
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (category != KbCategory.TONE_RULE) {
                    OutlinedTextField(
                        value = sourceForm,
                        onValueChange = { sourceForm = it },
                        label = { Text("Original form (optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (category == KbCategory.CHARACTER) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            KbGender.MALE to "Male",
                            KbGender.FEMALE to "Female",
                            KbGender.UNKNOWN to "Unknown"
                        ).forEach { (value, label) ->
                            FilterChip(
                                selected = gender == value,
                                onClick = { gender = value },
                                label = { Text(label) }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Locked — never changed by studying")
                    Switch(checked = locked, onCheckedChange = { locked = it })
                }
            }
        },
        confirmButton = {
            BounceButton(
                enabled = canonical.isNotBlank(),
                onClick = {
                    onSave(
                        KbEntryDraft(
                            category = category,
                            sourceForm = sourceForm.trim().takeIf { it.isNotEmpty() },
                            canonicalForm = canonical.trim(),
                            gender = if (category == KbCategory.CHARACTER) gender else null,
                            note = note.trim().takeIf { it.isNotEmpty() },
                            frequency = initial?.frequency ?: 1
                        ),
                        locked
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

private val CATEGORY_ORDER = listOf(
    KbCategory.CHARACTER, KbCategory.LOCATION, KbCategory.ORGANISATION,
    KbCategory.TECHNIQUE, KbCategory.ITEM, KbCategory.TERM, KbCategory.TONE_RULE
)

private fun categoryTitle(category: KbCategory): String = when (category) {
    KbCategory.CHARACTER -> "Characters"
    KbCategory.LOCATION -> "Places"
    KbCategory.ORGANISATION -> "Organisations"
    KbCategory.TECHNIQUE -> "Techniques"
    KbCategory.ITEM -> "Items"
    KbCategory.TERM -> "Terms"
    KbCategory.TONE_RULE -> "Tone rules"
}
