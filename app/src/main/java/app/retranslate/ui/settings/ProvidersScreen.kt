package app.retranslate.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retranslate.providers.ProviderId
import app.retranslate.ui.common.BounceButton

/**
 * Providers & keys (PLAN.md §9.3, arrives P2). Gemini gets the guided
 * card on top — it is this app's main engine — every other provider is
 * a plain row: name, honest status, its buttons. Nothing hides in a
 * menu; Test is a real (bounded) call, never a fake green tick.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(
    viewModel: ProvidersViewModel,
    onBack: () -> Unit,
    onAddKey: (ProviderId) -> Unit,
    onEditInstances: (ProviderId) -> Unit,
    onOllamaSetup: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ProvidersUiEvent.ShowMessage -> snackbar.showSnackbar(event.text)
            }
        }
    }
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Providers & keys") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(state.rows, key = { it.id.name }) { row ->
                if (row.id == ProviderId.GEMINI) {
                    GeminiCard(row, state.testingId == row.id, viewModel, onAddKey)
                } else if (row.id == ProviderId.OLLAMA) {
                    OllamaRow(
                        row, state.testingId == row.id, viewModel, onOllamaSetup
                    )
                } else if (row.id == ProviderId.OFFLINE) {
                    // §H: the Offline row's actions live in the
                    // dedicated section below (packs are managed there).
                    ProviderRow(row, state.testingId == row.id, viewModel, onAddKey, onEditInstances)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ProviderRow(row, state.testingId == row.id, viewModel, onAddKey, onEditInstances)
                        if (row.id == ProviderId.APINEX) {
                            ApinexExtras(state, viewModel)
                        }
                    }
                }
            }
            item {
                OfflineSection(state, viewModel)
            }
            item {
                GatewaysSection(state, viewModel)
            }
            item {
                MergeCapCard(state, viewModel)
            }
            item {
                Text(
                    "If one service is busy, ReTranslate quietly tries the next one. " +
                        "With a Gemini key, Gemini goes first and the free services are the safety net.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }

    // v1.1.3 (§K): the Add-a-provider flow, when open.
    state.gatewayFlow?.let {
        GatewayFlowSheet(state, viewModel)
    }
}

/**
 * The dormant Ollama entry (§4.3, P7): greyed explanation + Set up
 * until configured; a normal testable row afterwards.
 */
@Composable
private fun OllamaRow(
    row: ProviderRowState,
    testing: Boolean,
    viewModel: ProvidersViewModel,
    onOllamaSetup: () -> Unit
) {
    val configured = row.statusText != "Not set up"
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    row.statusText,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            row.detailText?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (configured) {
                    TextButton(
                        onClick = { viewModel.onTestClicked(row.id) },
                        enabled = !testing
                    ) { Text(if (testing) "Testing…" else "Test") }
                }
                TextButton(onClick = onOllamaSetup) {
                    Text(if (configured) "Edit set-up" else "Set up")
                }
            }
        }
    }
}

/**
 * §5.6's cost note + the editable daily cap: merge spends 4 calls
 * per quick translation, so the limit lives where the user manages
 * quotas — here.
 */
@Composable
private fun MergeCapCard(
    state: ProvidersUiState,
    viewModel: ProvidersViewModel
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "AI merge — “Best result” on the Translate tab",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Each merged translation costs 4 calls (3 translations + 1 AI " +
                    "check), so there's a daily limit. Used today: " +
                    "${state.mergesUsedToday} of ${state.mergeCap}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Daily limit",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { viewModel.setMergeCap(state.mergeCap - 10) },
                    enabled = state.mergeCap >
                        app.retranslate.data.prefs.MergePrefs.MIN_DAILY_CAP
                ) { Text("−10") }
                Text(
                    "${state.mergeCap}",
                    style = MaterialTheme.typography.bodyLarge
                )
                TextButton(
                    onClick = { viewModel.setMergeCap(state.mergeCap + 10) },
                    enabled = state.mergeCap <
                        app.retranslate.data.prefs.MergePrefs.MAX_DAILY_CAP
                ) { Text("+10") }
            }
        }
    }
}

@Composable
private fun GeminiCard(
    row: ProviderRowState,
    testing: Boolean,
    viewModel: ProvidersViewModel,
    onAddKey: (ProviderId) -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Gemini AI — main engine", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                if (row.hasKey) {
                    "Status: ${row.statusText}"
                } else {
                    "Free key — recommended: unlocks book translation with consistent names."
                },
                style = MaterialTheme.typography.bodyMedium
            )
            row.detailText?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BounceButton(onClick = { onAddKey(row.id) }) {
                    Text(if (row.hasKey) "Edit key" else "Add the free key")
                }
                if (row.hasKey) {
                    TextButton(
                        onClick = { viewModel.onTestClicked(row.id) },
                        enabled = !testing
                    ) { Text(if (testing) "Testing…" else "Test") }
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(
    row: ProviderRowState,
    testing: Boolean,
    viewModel: ProvidersViewModel,
    onAddKey: (ProviderId) -> Unit,
    onEditInstances: (ProviderId) -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    row.statusText,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            row.detailText?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { viewModel.onTestClicked(row.id) },
                    enabled = !testing
                ) { Text(if (testing) "Testing…" else "Test") }
                if (row.acceptsKey) {
                    TextButton(onClick = { onAddKey(row.id) }) {
                        Text(if (row.hasKey) "Edit key" else "Add key")
                    }
                }
                if (row.canEditInstances) {
                    TextButton(onClick = { onEditInstances(row.id) }) {
                        Text("Edit instances")
                    }
                }
            }
        }
    }
}

/**
 * v1.1.3 (§J): under the APInex row — the allowance line when the
 * gateway's own endpoints answer, and the model picker fed by the
 * gateway's own model list. Free badges are the PROVIDER's claim,
 * and the card says so.
 */
@Composable
private fun ApinexExtras(state: ProvidersUiState, viewModel: ProvidersViewModel) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "APInex is a separate gateway service — your text passes " +
                    "through their servers, and their free allowance is a " +
                    "promotion that can change or end.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.apinexAllowance?.let { line ->
                Spacer(Modifier.height(6.dp))
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Model",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val current = state.apinexModel
                ?: app.retranslate.providers.key.ApinexGateway.DEFAULT_FREE_MODEL
            Text(
                "Using: $current",
                style = MaterialTheme.typography.bodyMedium
            )
            if (state.apinexModels.isEmpty()) {
                Text(
                    "Add your APInex key (Add key above) to load its model list.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                state.apinexModels.take(12).forEach { model ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { viewModel.setApinexModel(model) }) {
                            Text(if (model == current) "✓ $model" else model)
                        }
                        if (app.retranslate.providers.gateway.GatewayConfig
                                .isListedFree(model)
                        ) {
                            Text(
                                "free — the provider's claim",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * v1.1.3 (§H): the Offline section — downloaded ML Kit packs with
 * Delete (removability), the Wi-Fi-only default, the storage line,
 * and the download form. Everything on this phone, no key.
 */
@Composable
private fun OfflineSection(state: ProvidersUiState, viewModel: ProvidersViewModel) {
    var source by remember { mutableStateOf("ko") }
    var target by remember { mutableStateOf("en") }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Offline — on this phone",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Translates with Google ML Kit, right on the phone — no " +
                    "internet, no key. Quality is plainer than the AI " +
                    "engines; chapters it translates are marked " +
                    "Offline-translated so you can improve them with AI later.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (state.offlinePacks.isEmpty()) {
                    "No language packs downloaded yet."
                } else {
                    "${state.offlinePacks.size} pack(s) · about " +
                        "${state.offlinePacks.size * app.retranslate.providers.local.OfflineTranslateEngine.APPROX_PAIR_SIZE_MB} MB used"
                },
                style = MaterialTheme.typography.bodyMedium
            )
            state.offlinePacks.forEach { pack ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(pack.label, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { viewModel.deleteOfflinePack(pack) },
                        enabled = !state.offlineBusy
                    ) { Text("Delete") }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Download packs on Wi-Fi only")
                    Text(
                        "Packs are about 30 MB each.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.offlineWifiOnly,
                    onCheckedChange = viewModel::setOfflineWifiOnly
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Download a pack",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LanguageMenu(
                    label = "From",
                    selected = source,
                    codes = state.offlineLanguages,
                    onSelect = { source = it },
                    modifier = Modifier.weight(1f)
                )
                LanguageMenu(
                    label = "To",
                    selected = target,
                    codes = state.offlineLanguages,
                    onSelect = { target = it },
                    modifier = Modifier.weight(1f)
                )
            }
            BounceButton(
                onClick = { viewModel.downloadOfflinePack(source, target) },
                enabled = !state.offlineBusy && source != target,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.offlineBusy) "Working…" else "Download pack")
            }
        }
    }
}

/** A compact language dropdown over ML Kit's supported tags. */
@Composable
private fun LanguageMenu(
    label: String,
    selected: String,
    codes: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("$label: ${app.retranslate.providers.Languages.nameOf(selected)}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            codes.forEach { code ->
                DropdownMenuItem(
                    text = { Text(app.retranslate.providers.Languages.nameOf(code)) },
                    onClick = {
                        expanded = false
                        onSelect(code)
                    }
                )
            }
        }
    }
}

/**
 * v1.1.3 (§K): the added gateways — each a full citizen with its
 * model choice and a Remove button — plus the Add-a-provider entry.
 */
@Composable
private fun GatewaysSection(state: ProvidersUiState, viewModel: ProvidersViewModel) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Your added providers",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Add any AI gateway site by pasting its link — the app " +
                    "test-drives it first, and only adds it if it answers " +
                    "like a standard provider (OpenAI- or Anthropic-style). " +
                    "Nothing is added when the check fails.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.gateways.forEach { gateway ->
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            gateway.name,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            "${gateway.baseUrl} · ${gateway.models.size} models · " +
                                "using ${gateway.effectiveModel() ?: "—"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { viewModel.removeGateway(gateway.slot) }) {
                        Text("Remove")
                    }
                }
                gateway.models.take(8).forEach { model ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        TextButton(
                            onClick = { viewModel.setGatewayModel(gateway.slot, model) }
                        ) {
                            Text(
                                if (model == gateway.effectiveModel()) {
                                    "✓ $model"
                                } else {
                                    model
                                }
                            )
                        }
                        if (app.retranslate.providers.gateway.GatewayConfig
                                .isListedFree(model)
                        ) {
                            Text(
                                "free — the provider's claim",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            BounceButton(
                onClick = viewModel::startAddGateway,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Add a provider") }
        }
    }
}

/** v1.1.3 (§K): paste → test-drive → key → added. Two steps, honest. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GatewayFlowSheet(state: ProvidersUiState, viewModel: ProvidersViewModel) {
    val flow = state.gatewayFlow ?: return
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = viewModel::dismissAddGateway
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Add a provider",
                style = MaterialTheme.typography.titleMedium
            )
            if (flow.step == 0) {
                Text(
                    "Paste the gateway's site or API link. The app checks " +
                        "whether it works as a provider — nothing is added " +
                        "yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = flow.input,
                    onValueChange = viewModel::gatewayInputChanged,
                    label = { Text("Site or API link") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                flow.error?.let { error ->
                    Text(
                        error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                BounceButton(
                    onClick = viewModel::probeGateway,
                    enabled = !flow.busy && flow.input.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (flow.busy) "Checking…" else "Test-drive it") }
            } else {
                Text(
                    "It answers like a standard provider at " +
                        "${flow.foundBaseUrl}. Now paste its key — the key " +
                        "is proven live and the model list is fetched " +
                        "before anything is added.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = flow.name,
                    onValueChange = viewModel::gatewayNameChanged,
                    label = { Text("Name for it") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = flow.key,
                    onValueChange = viewModel::gatewayKeyChanged,
                    label = { Text("API key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                flow.error?.let { error ->
                    Text(
                        error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                BounceButton(
                    onClick = viewModel::confirmGateway,
                    enabled = !flow.busy && flow.key.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (flow.busy) "Checking the key…" else "Add this provider") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
