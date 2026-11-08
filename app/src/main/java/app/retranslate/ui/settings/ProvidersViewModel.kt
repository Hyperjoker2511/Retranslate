package app.retranslate.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.retranslate.core.error.AppResult
import app.retranslate.core.error.ErrorText
import app.retranslate.core.security.KeyVault
import app.retranslate.data.prefs.ProviderConfigStore
import app.retranslate.providers.Provider
import app.retranslate.providers.ProviderId
import app.retranslate.providers.hub.ProviderHealth
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One row of the Providers & keys screen (§9.3), in plain words. */
data class ProviderRowState(
    val id: ProviderId,
    val name: String,
    /** "Working" / "Resting — busy earlier" / "Needs a key" / "Not set up" / … */
    val statusText: String,
    /** Secondary line: usage counter, instance count, or a setup hint. */
    val detailText: String?,
    val hasKey: Boolean,
    val acceptsKey: Boolean,
    val canEditInstances: Boolean
)

data class ProvidersUiState(
    val rows: List<ProviderRowState> = emptyList(),
    val testingId: ProviderId? = null,
    /** §5.6 (P7): the AI-merge daily soft cap + today's spend. */
    val mergeCap: Int = app.retranslate.data.prefs.MergePrefs.DEFAULT_DAILY_CAP,
    val mergesUsedToday: Int = 0,
    // ---- v1.1.3 (§H): Offline (ML Kit) --------------------------------
    val offlinePacks: List<OfflinePackUi> = emptyList(),
    val offlineWifiOnly: Boolean = true,
    val offlineBusy: Boolean = false,
    /** App language codes ML Kit can serve on this device. */
    val offlineLanguages: List<String> = emptyList(),
    // ---- v1.1.3 (§J): APInex ------------------------------------------
    val apinexAllowance: String? = null,
    val apinexModel: String? = null,
    val apinexModels: List<String> = emptyList(),
    // ---- v1.1.3 (§K): added gateways -----------------------------------
    val gateways: List<app.retranslate.providers.gateway.GatewayConfig> = emptyList(),
    val gatewayFlow: GatewayFlowUi? = null
)

/** v1.1.3 (§H): one downloaded ML Kit pair, in plain names. */
data class OfflinePackUi(
    val sourceTag: String,
    val targetTag: String,
    val label: String
)

/** v1.1.3 (§K): the Add-a-provider flow's steps, held in state. */
data class GatewayFlowUi(
    /** 0 = paste the site, 1 = key + confirm (probe already passed). */
    val step: Int = 0,
    val input: String = "",
    val foundBaseUrl: String? = null,
    val foundProtocol: String? = null,
    val name: String = "",
    val key: String = "",
    val busy: Boolean = false,
    val error: String? = null
)

sealed interface ProvidersUiEvent {
    data class ShowMessage(val text: String) : ProvidersUiEvent
}

/**
 * Providers & keys (PLAN.md §9.3): one row per provider with a plain
 * status, its action buttons, and — on top — the guided Gemini card.
 * Status words come from three honest sources only: KeyVault presence,
 * the §4.4.2 health memory, and the configured instance lists.
 */
class ProvidersViewModel(
    private val keyVault: KeyVault,
    private val configStore: ProviderConfigStore,
    private val health: ProviderHealth,
    private val providers: Map<ProviderId, Provider>,
    /** P7 (§5.6): the merge cap lives here (§9.3 notes the cost in this screen). */
    private val mergePrefs: app.retranslate.data.prefs.MergePrefs? = null,
    /** v1.1.3 (§H): downloaded ML Kit pack count for the Offline row. */
    private val offlinePackCount: suspend () -> Int = { 0 },
    /** v1.1.3 (§H): the on-device engine itself (packs live here). */
    private val offlineEngine: app.retranslate.providers.local.OfflineTranslateEngine? = null,
    /** v1.1.3 (§K): the gateway test-drive probe. */
    private val gatewayProbe: app.retranslate.providers.gateway.GatewayProbe? = null,
    /** v1.1.3 (§J): APInex's allowance line, when its endpoints answer. */
    private val apinexAllowanceLine: (suspend () -> String?)? = null,
    /** v1.1.3 (§K): refresh the slot providers after add/remove. */
    private val onGatewaysChanged: (suspend () -> Unit)? = null
) : ViewModel() {

    private val _state = MutableStateFlow(ProvidersUiState())
    val state: StateFlow<ProvidersUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ProvidersUiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<ProvidersUiEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch { keyVault.presentKeys.collect { refresh() } }
        mergePrefs?.let { prefs ->
            viewModelScope.launch {
                prefs.dailyCap.collect { cap ->
                    _state.update { it.copy(mergeCap = cap) }
                }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    rows = buildRows(),
                    mergesUsedToday = configStore.mergesUsedToday()
                )
            }
        }
        refreshV113()
    }

    // ---- v1.1.3 (§H/§J/§K) state ---------------------------------------

    private fun refreshV113() {
        viewModelScope.launch {
            val engine = offlineEngine
            val packs = engine?.downloadedPairs().orEmpty().map { pair ->
                OfflinePackUi(
                    sourceTag = pair.source,
                    targetTag = pair.target,
                    label = "${app.retranslate.providers.Languages.nameOf(pair.source)} → " +
                        app.retranslate.providers.Languages.nameOf(pair.target)
                )
            }.sortedBy { it.label }
            val languages = engine?.supportedTags().orEmpty()
                .map { tag -> tag }
                .sorted()
            _state.update {
                it.copy(
                    offlinePacks = packs,
                    offlineWifiOnly = configStore.offlineWifiOnly(),
                    offlineLanguages = languages,
                    gateways = configStore.gatewayConfigs(),
                    apinexModel = configStore.apinexModel()
                )
            }
        }
        viewModelScope.launch {
            val line = apinexAllowanceLine?.invoke()
            _state.update { it.copy(apinexAllowance = line) }
        }
        viewModelScope.launch { refreshApinexModels() }
    }

    /** §J: the model list comes from the gateway itself (key = proof). */
    private suspend fun refreshApinexModels() {
        val probe = gatewayProbe ?: return
        val key = keyVault.get(ProviderId.APINEX.name) ?: return
        val models = probe.fetchModelsWithKey(
            app.retranslate.providers.key.ApinexGateway.API_BASE,
            app.retranslate.providers.gateway.GatewayProtocol.OPENAI,
            key
        )
        if (models is AppResult.Ok) {
            _state.update { it.copy(apinexModels = models.value) }
        }
    }

    /** §J: the user's model choice for APInex (null = the free default). */
    fun setApinexModel(model: String?) {
        viewModelScope.launch {
            configStore.saveApinexModel(model)
            _state.update { it.copy(apinexModel = model) }
            _events.tryEmit(
                ProvidersUiEvent.ShowMessage(
                    if (model == null) {
                        "APInex will use its free default model."
                    } else {
                        "APInex will use $model."
                    }
                )
            )
        }
    }

    // ---- v1.1.3 (§H): Offline packs --------------------------------------

    fun downloadOfflinePack(sourceCode: String, targetCode: String) {
        val engine = offlineEngine ?: return
        if (_state.value.offlineBusy) return
        viewModelScope.launch {
            _state.update { it.copy(offlineBusy = true) }
            try {
                val sourceTag = sourceCode.substringBefore('-')
                val targetTag = targetCode.substringBefore('-')
                when (
                    val result = engine.download(
                        sourceTag, targetTag, _state.value.offlineWifiOnly
                    )
                ) {
                    is AppResult.Ok -> _events.tryEmit(
                        ProvidersUiEvent.ShowMessage(
                            "Downloaded: ${app.retranslate.providers.Languages.nameOf(sourceCode)} → " +
                                "${app.retranslate.providers.Languages.nameOf(targetCode)} " +
                                "(about ${app.retranslate.providers.local.OfflineTranslateEngine.APPROX_PAIR_SIZE_MB} MB)."
                        )
                    )
                    is AppResult.Err -> _events.tryEmit(
                        ProvidersUiEvent.ShowMessage(ErrorText.of(result.error).message)
                    )
                }
            } finally {
                _state.update { it.copy(offlineBusy = false) }
                refreshV113()
                refresh()
            }
        }
    }

    fun deleteOfflinePack(pack: OfflinePackUi) {
        val engine = offlineEngine ?: return
        if (_state.value.offlineBusy) return
        viewModelScope.launch {
            _state.update { it.copy(offlineBusy = true) }
            try {
                when (val result = engine.delete(pack.sourceTag, pack.targetTag)) {
                    is AppResult.Ok -> _events.tryEmit(
                        ProvidersUiEvent.ShowMessage("Removed: ${pack.label}.")
                    )
                    is AppResult.Err -> _events.tryEmit(
                        ProvidersUiEvent.ShowMessage(ErrorText.of(result.error).message)
                    )
                }
            } finally {
                _state.update { it.copy(offlineBusy = false) }
                refreshV113()
                refresh()
            }
        }
    }

    fun setOfflineWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch {
            configStore.saveOfflineWifiOnly(wifiOnly)
            _state.update { it.copy(offlineWifiOnly = wifiOnly) }
        }
    }

    // ---- v1.1.3 (§K): Add a provider by link ------------------------------

    fun startAddGateway() {
        _state.update { it.copy(gatewayFlow = GatewayFlowUi()) }
    }

    fun dismissAddGateway() {
        _state.update { it.copy(gatewayFlow = null) }
    }

    fun gatewayInputChanged(input: String) {
        _state.update {
            it.copy(gatewayFlow = it.gatewayFlow?.copy(input = input, error = null))
        }
    }

    fun gatewayKeyChanged(key: String) {
        _state.update {
            it.copy(gatewayFlow = it.gatewayFlow?.copy(key = key, error = null))
        }
    }

    fun gatewayNameChanged(name: String) {
        _state.update {
            it.copy(gatewayFlow = it.gatewayFlow?.copy(name = name))
        }
    }

    /** Step 1: test-drive the pasted site. Nothing is added on failure. */
    fun probeGateway() {
        val probe = gatewayProbe ?: return
        val flow = _state.value.gatewayFlow ?: return
        if (flow.input.isBlank() || flow.busy) return
        viewModelScope.launch {
            _state.update {
                it.copy(gatewayFlow = it.gatewayFlow?.copy(busy = true, error = null))
            }
            when (val result = probe.probe(flow.input)) {
                is app.retranslate.providers.gateway.GatewayProbeResult.Found -> {
                    val host = runCatching {
                        java.net.URI(result.baseUrl).host
                    }.getOrNull() ?: result.baseUrl
                    _state.update {
                        it.copy(
                            gatewayFlow = it.gatewayFlow?.copy(
                                step = 1,
                                busy = false,
                                foundBaseUrl = result.baseUrl,
                                foundProtocol = result.protocol.name,
                                name = it.gatewayFlow?.name?.ifBlank { host } ?: host
                            )
                        )
                    }
                }
                is app.retranslate.providers.gateway.GatewayProbeResult.NotFound ->
                    _state.update {
                        it.copy(
                            gatewayFlow = it.gatewayFlow?.copy(
                                busy = false,
                                error = result.reason + " Nothing was added."
                            )
                        )
                    }
            }
        }
    }

    /** Step 2: the key is proven live, models fetched — then it's added. */
    fun confirmGateway() {
        val probe = gatewayProbe ?: return
        val flow = _state.value.gatewayFlow ?: return
        val baseUrl = flow.foundBaseUrl ?: return
        val protocol = runCatching {
            app.retranslate.providers.gateway.GatewayProtocol.valueOf(
                flow.foundProtocol ?: "OPENAI"
            )
        }.getOrNull() ?: return
        if (flow.key.isBlank() || flow.busy) return
        viewModelScope.launch {
            _state.update {
                it.copy(gatewayFlow = it.gatewayFlow?.copy(busy = true, error = null))
            }
            when (val fetched = probe.fetchModelsWithKey(baseUrl, protocol, flow.key)) {
                is AppResult.Ok -> {
                    val existing = configStore.gatewayConfigs()
                    val usedSlots = existing.map { it.slot }.toSet()
                    val slot = app.retranslate.providers.gateway.GatewayConfig
                        .slotKeys().firstOrNull { it !in usedSlots }
                    if (slot == null) {
                        _state.update {
                            it.copy(
                                gatewayFlow = it.gatewayFlow?.copy(
                                    busy = false,
                                    error = "All provider slots are full — remove " +
                                        "one above first. Nothing was added."
                                )
                            )
                        }
                        return@launch
                    }
                    val config = app.retranslate.providers.gateway.GatewayConfig(
                        slot = slot,
                        name = flow.name.ifBlank { baseUrl },
                        baseUrl = baseUrl,
                        protocol = protocol.name,
                        models = fetched.value
                    )
                    keyVault.put(config.vaultEntry, flow.key)
                    configStore.saveGatewayConfigs(existing + config)
                    onGatewaysChanged?.invoke()
                    _state.update {
                        it.copy(gatewayFlow = null, gateways = existing + config)
                    }
                    refresh()
                    _events.tryEmit(
                        ProvidersUiEvent.ShowMessage(
                            "Added ${config.name} — ${fetched.value.size} models. " +
                                "It's now pickable everywhere an engine is chosen."
                        )
                    )
                }
                is AppResult.Err -> _state.update {
                    it.copy(
                        gatewayFlow = it.gatewayFlow?.copy(
                            busy = false,
                            error = "That key didn't work: " +
                                ErrorText.of(fetched.error).message +
                                " Nothing was added."
                        )
                    )
                }
            }
        }
    }

    /** §K removability: out it goes, key and all, with the list updated. */
    fun removeGateway(slot: String) {
        viewModelScope.launch {
            val existing = configStore.gatewayConfigs()
            val removed = existing.firstOrNull { it.slot == slot } ?: return@launch
            keyVault.remove(removed.vaultEntry)
            configStore.saveGatewayConfigs(existing - removed)
            onGatewaysChanged?.invoke()
            _state.update { it.copy(gateways = existing - removed) }
            refresh()
            _events.tryEmit(
                ProvidersUiEvent.ShowMessage("Removed ${removed.name} and its key.")
            )
        }
    }

    /** §K: which model an added gateway answers with. */
    fun setGatewayModel(slot: String, model: String?) {
        viewModelScope.launch {
            val existing = configStore.gatewayConfigs()
            val updated = existing.map { config ->
                if (config.slot == slot) config.copy(chosenModel = model) else config
            }
            configStore.saveGatewayConfigs(updated)
            onGatewaysChanged?.invoke()
            _state.update { it.copy(gateways = updated) }
        }
    }

    /** §5.6: the cap is the user's choice — the stepper writes it live. */
    fun setMergeCap(value: Int) {
        val prefs = mergePrefs ?: return
        viewModelScope.launch { prefs.setDailyCap(value) }
    }

    fun onTestClicked(id: ProviderId) {
        val provider = providers[id] ?: return
        if (id == ProviderId.LIBRETRANSLATE) {
            viewModelScope.launch {
                if (configStore.instances(id).isEmpty()) {
                    _events.tryEmit(
                        ProvidersUiEvent.ShowMessage(
                            "Add an instance first — tap Edit instances."
                        )
                    )
                } else {
                    runTest(id, provider)
                }
            }
            return
        }
        viewModelScope.launch { runTest(id, provider) }
    }

    private suspend fun runTest(id: ProviderId, provider: Provider) {
        _state.update { it.copy(testingId = id) }
        val name = provider.displayName
        when (val result = provider.testConnection()) {
            is AppResult.Ok ->
                _events.tryEmit(ProvidersUiEvent.ShowMessage("$name is working."))
            is AppResult.Err ->
                _events.tryEmit(
                    ProvidersUiEvent.ShowMessage(
                        "$name didn't answer: ${ErrorText.of(result.error).message}" +
                            (result.error.detail?.let { "\nDetails: $it" } ?: "")
                    )
                )
        }
        _state.update { it.copy(testingId = null, rows = buildRows()) }
    }

    private suspend fun buildRows(): List<ProviderRowState> =
        ProviderStatus.buildRows(
            keyVault, configStore, health, providers,
            offlinePackCount = offlinePackCount
        )

    companion object {
        /** Delegates to [ProviderStatus] — the one row source (v1.1). */
        val DISPLAY_ORDER: List<ProviderId> = ProviderStatus.DISPLAY_ORDER

        val INSTANCE_EDITABLE: Set<ProviderId> = ProviderStatus.INSTANCE_EDITABLE
    }
}
