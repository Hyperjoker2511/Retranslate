package app.retranslate.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.retranslate.core.error.AppResult
import app.retranslate.core.error.ErrorText
import app.retranslate.data.prefs.ProviderConfigStore
import app.retranslate.providers.local.OllamaProvider
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OllamaSetupUiState(
    val baseUrl: String = "",
    val model: String = "",
    /** A setup is saved (the provider stops being dormant). */
    val isConfigured: Boolean = false,
    val isTesting: Boolean = false,
    /** Plain-words validation/test failure — nothing was saved on a bad form. */
    val errorText: String? = null
)

sealed interface OllamaSetupUiEvent {
    data class ShowMessage(val text: String) : OllamaSetupUiEvent
}

/**
 * The dormant Ollama entry's setup (PLAN.md §4.3, P7): the user's PC
 * address + model name. Not a key — nothing here touches the KeyVault.
 * Saving wakes the provider (it joins the chains last); the warning
 * about plain HTTP on the home network is on screen BEFORE saving,
 * exactly as §4.3 requires.
 */
class OllamaSetupViewModel(
    private val configStore: ProviderConfigStore,
    private val ollama: OllamaProvider
) : ViewModel() {

    private val _state = MutableStateFlow(OllamaSetupUiState())
    val state: StateFlow<OllamaSetupUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<OllamaSetupUiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<OllamaSetupUiEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            val config = configStore.ollamaConfig()
            if (config != null) {
                _state.update {
                    it.copy(
                        baseUrl = config.baseUrl,
                        model = config.model,
                        isConfigured = true
                    )
                }
            }
        }
    }

    fun onBaseUrlChanged(value: String) {
        _state.update { it.copy(baseUrl = value, errorText = null) }
    }

    fun onModelChanged(value: String) {
        _state.update { it.copy(model = value, errorText = null) }
    }

    fun onSave() {
        val current = _state.value
        if (!OllamaProvider.isPlausibleBaseUrl(current.baseUrl)) {
            _state.update {
                it.copy(
                    errorText = "That address doesn't look right — it should " +
                        "start with http:// and name your computer, " +
                        "like http://192.168.1.20:11434."
                )
            }
            return
        }
        if (current.model.isBlank()) {
            _state.update {
                it.copy(errorText = "Name the model your computer runs, like llama3.1.")
            }
            return
        }
        viewModelScope.launch {
            configStore.saveOllamaConfig(
                OllamaProvider.normalizeBaseUrl(current.baseUrl),
                current.model.trim()
            )
            ollama.refreshConfig()
            _state.update { it.copy(isConfigured = true, errorText = null) }
            _events.tryEmit(
                OllamaSetupUiEvent.ShowMessage(
                    "Saved. Ollama will be tried after the online services."
                )
            )
        }
    }

    fun onTest() {
        if (_state.value.isTesting) return
        _state.update { it.copy(isTesting = true) }
        viewModelScope.launch {
            // Test what is SAVED; unsaved edits are the Save button's job.
            when (val result = ollama.testConnection()) {
                is AppResult.Ok -> _events.tryEmit(
                    OllamaSetupUiEvent.ShowMessage("Your computer answered — Ollama is reachable.")
                )
                is AppResult.Err -> _events.tryEmit(
                    OllamaSetupUiEvent.ShowMessage(
                        "No answer from your computer: ${ErrorText.of(result.error).message}"
                    )
                )
            }
            _state.update { it.copy(isTesting = false) }
        }
    }

    fun onRemove() {
        viewModelScope.launch {
            configStore.clearOllamaConfig()
            ollama.refreshConfig()
            _state.update {
                it.copy(baseUrl = "", model = "", isConfigured = false, errorText = null)
            }
            _events.tryEmit(
                OllamaSetupUiEvent.ShowMessage("Ollama setup removed — the row is dormant again.")
            )
        }
    }
}
