package app.retranslate.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.retranslate.core.error.AppResult
import app.retranslate.core.error.ErrorText
import app.retranslate.core.security.KeyVault
import app.retranslate.providers.ProviderId
import app.retranslate.providers.key.AzureProvider
import app.retranslate.providers.key.KeyTestable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class KeyEntryUiState(
    val secret: String = "",
    val region: String = "",
    val isTesting: Boolean = false,
    /** Plain-words reason the last Test & save failed (nothing was saved). */
    val errorText: String? = null,
    val hasSavedKey: Boolean = false,
    /** v1.1.2 (Gemini only): the pinned model id; null = Automatic. */
    val geminiModelChoice: String? = null
)

sealed interface KeyEntryUiEvent {
    /** The key tested OK and was saved — leave the screen. */
    data object Saved : KeyEntryUiEvent
    data class ShowMessage(val text: String) : KeyEntryUiEvent
}

/**
 * One provider's key-entry screen (PLAN.md §9.3): paste → **Test & save**.
 * The candidate secret is proven against the provider FIRST (§11
 * milestone: a fake key fails in words and is NEVER saved); only a
 * passing secret reaches the KeyVault.
 */
class KeyEntryViewModel(
    private val providerId: ProviderId,
    private val keyTestable: KeyTestable,
    private val keyVault: KeyVault,
    private val geminiModelPrefs: app.retranslate.data.prefs.GeminiModelPrefs? = null
) : ViewModel() {

    val info: KeyedProviderInfo = ProviderCatalog.keyed(providerId)
        ?: error("Key entry opened for a provider without keys: $providerId")

    private val _state = MutableStateFlow(
        KeyEntryUiState(hasSavedKey = keyVault.isPresent(providerId.name))
    )
    val state: StateFlow<KeyEntryUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<KeyEntryUiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<KeyEntryUiEvent> = _events.asSharedFlow()

    init {
        // Presence arrives asynchronously from storage at process start;
        // keep the Remove button honest whenever it lands.
        viewModelScope.launch {
            keyVault.presentKeys.collect { present ->
                _state.update { it.copy(hasSavedKey = providerId.name in present) }
            }
        }
        // v1.1.2: the Gemini model picker's persisted choice.
        geminiModelPrefs?.let { prefs ->
            viewModelScope.launch {
                prefs.choice.collect { choice ->
                    _state.update { it.copy(geminiModelChoice = choice) }
                }
            }
        }
    }

    /** v1.1.2 (Gemini only): pin a model, or null for Automatic. */
    fun onGeminiModelChanged(modelId: String?) {
        val prefs = geminiModelPrefs ?: return
        _state.update { it.copy(geminiModelChoice = modelId) }
        viewModelScope.launch { prefs.setChoice(modelId) }
    }

    fun onSecretChanged(value: String) {
        _state.update { it.copy(secret = value, errorText = null) }
    }

    fun onRegionChanged(value: String) {
        _state.update { it.copy(region = value, errorText = null) }
    }

    fun onTestAndSave() {
        val current = _state.value
        if (current.isTesting) return
        val secret = assemble(current) ?: run {
            _state.update {
                it.copy(
                    errorText = if (info.needsRegion) {
                        "Enter both the key and the region — Azure needs the two together."
                    } else {
                        "Paste the key first."
                    }
                )
            }
            return
        }
        _state.update { it.copy(isTesting = true, errorText = null) }
        viewModelScope.launch {
            when (val result = keyTestable.testWithSecret(secret)) {
                is AppResult.Ok -> {
                    keyVault.put(providerId.name, secret)
                    _state.update { it.copy(isTesting = false, hasSavedKey = true) }
                    _events.tryEmit(KeyEntryUiEvent.ShowMessage("${info.displayName} key saved."))
                    _events.tryEmit(KeyEntryUiEvent.Saved)
                }
                is AppResult.Err -> {
                    // Nothing is saved on a failed test — the typed
                    // secret stays on screen so the user can fix a typo.
                    // v1.0.1: the technical detail rides on a second line
                    // so a stubborn failure names itself.
                    val detailSuffix = result.error.detail
                        ?.let { "\nDetails: $it" } ?: ""
                    _state.update {
                        it.copy(
                            isTesting = false,
                            errorText = ErrorText.of(result.error).message + detailSuffix
                        )
                    }
                }
            }
        }
    }

    fun onRemoveKey() {
        viewModelScope.launch {
            keyVault.remove(providerId.name)
            _state.update { it.copy(hasSavedKey = false, secret = "", region = "") }
            _events.tryEmit(KeyEntryUiEvent.ShowMessage("${info.displayName} key removed."))
            _events.tryEmit(KeyEntryUiEvent.Saved)
        }
    }

    /** The vault shape for this provider, or null when the form is incomplete. */
    private fun assemble(state: KeyEntryUiState): String? {
        val key = state.secret.trim()
        if (key.isEmpty()) return null
        if (!info.needsRegion) return key
        val region = state.region.trim()
        if (region.isEmpty()) return null
        return AzureProvider.assembleSecret(key, region)
    }
}
