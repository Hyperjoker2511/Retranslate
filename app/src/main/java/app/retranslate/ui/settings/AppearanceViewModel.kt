package app.retranslate.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.retranslate.data.prefs.AppearancePrefs
import app.retranslate.data.prefs.AppearanceState
import app.retranslate.data.prefs.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Appearance (PLAN.md §9.3/§9.5, completed in P7): light/dark, the
 * Material You (dynamic colour) toggle, the review screen's own
 * themes, and the "Bouncy animations" switch. The state comes
 * straight from [AppearancePrefs] — every choice applies live
 * (MainActivity and the review screen reflect the same flow) and
 * survives restarts.
 */
class AppearanceViewModel(
    private val prefs: AppearancePrefs
) : ViewModel() {

    val state: StateFlow<AppearanceState> = prefs.state.stateIn(
        viewModelScope, SharingStarted.Eagerly, AppearanceState()
    )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { prefs.setThemeMode(mode) }
    }

    fun setDynamicColor(value: Boolean) {
        viewModelScope.launch { prefs.setDynamicColor(value) }
    }

    fun setReviewTheme(id: String) {
        viewModelScope.launch { prefs.setReviewTheme(id) }
    }

    fun setBouncyAnimations(value: Boolean) {
        viewModelScope.launch { prefs.setBouncyAnimations(value) }
    }
}
