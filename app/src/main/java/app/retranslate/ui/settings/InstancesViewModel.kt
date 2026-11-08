package app.retranslate.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.retranslate.data.prefs.ProviderConfigStore
import app.retranslate.providers.ProviderId
import app.retranslate.providers.free.DefaultInstances
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class InstancesUiState(
    val text: String = "",
    val loaded: Boolean = false,
    val errorText: String? = null
)

sealed interface InstancesUiEvent {
    data object Saved : InstancesUiEvent
}

/**
 * The instance-list editor for the multi-instance providers (§4.2):
 * one URL per line. Dead instances are demoted automatically by the
 * hub's circuit breakers, so this screen is for adding a better one or
 * resetting to the shipped list — never a required chore.
 */
class InstancesViewModel(
    private val providerId: ProviderId,
    private val configStore: ProviderConfigStore
) : ViewModel() {

    val providerName: String = when (providerId) {
        ProviderId.MOZHI -> "Mozhi"
        ProviderId.LINGVA -> "Lingva"
        ProviderId.SIMPLYTRANSLATE -> "SimplyTranslate"
        ProviderId.LIBRETRANSLATE -> "LibreTranslate"
        else -> providerId.name
    }

    private val _state = MutableStateFlow(InstancesUiState())
    val state: StateFlow<InstancesUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<InstancesUiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<InstancesUiEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            val current = configStore.instances(providerId)
            _state.update { it.copy(text = current.joinToString("\n"), loaded = true) }
        }
    }

    fun onTextChanged(value: String) {
        _state.update { it.copy(text = value, errorText = null) }
    }

    fun onSave() {
        val lines = parse(_state.value.text)
        val bad = lines.firstOrNull { !it.startsWith("http://") && !it.startsWith("https://") }
        if (bad != null) {
            _state.update {
                it.copy(errorText = "“$bad” doesn't look like an address — each line must start with https://")
            }
            return
        }
        viewModelScope.launch {
            configStore.saveInstances(providerId, lines)
            _events.tryEmit(InstancesUiEvent.Saved)
        }
    }

    fun onResetToDefaults() {
        _state.update { it.copy(text = defaults().joinToString("\n"), errorText = null) }
    }

    private fun defaults(): List<String> = when (providerId) {
        ProviderId.MOZHI -> DefaultInstances.MOZHI
        ProviderId.LINGVA -> DefaultInstances.LINGVA
        ProviderId.SIMPLYTRANSLATE -> DefaultInstances.SIMPLYTRANSLATE
        else -> emptyList()
    }

    companion object {
        /** Pure parse rule (unit-tested): lines trimmed, blanks dropped, deduped. */
        fun parse(text: String): List<String> =
            text.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}
