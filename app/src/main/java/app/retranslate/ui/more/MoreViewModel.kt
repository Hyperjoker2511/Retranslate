package app.retranslate.ui.more

import androidx.lifecycle.ViewModel
import app.retranslate.BuildConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** One labelled row on the More tab (PLAN.md §9.2): nothing hides in a menu. */
data class MoreRow(
    val id: String,
    val title: String,
    val subtitle: String
)

sealed interface MoreUiEvent {
    data class ShowMessage(val text: String) : MoreUiEvent
}

class MoreViewModel : ViewModel() {

    // v1.1.2: the Stitch design's row order (Appearance first, then
    // Providers & keys). Subtitles stay the app's truthful ones — the
    // design's "Cloud sync & local exports" would promise a cloud
    // sync the app does not have.
    val rows: List<MoreRow> = listOf(
        MoreRow(ID_APPEARANCE, "Appearance", "Fluid Glass, light / dark, animations"),
        MoreRow(
            ID_PROVIDERS, "Providers & keys",
            "Manage API credentials"
        ),
        MoreRow(
            ID_BACKUP, "Backup & restore",
            "One file with your books and settings — keys never included"
        ),
        MoreRow(
            ID_LICENCES, "Licences",
            "Open source libraries"
        ),
        MoreRow(ID_ABOUT, "About", "Version ${BuildConfig.VERSION_NAME} (Fluid Glass)")
    )

    private val _events = MutableSharedFlow<MoreUiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<MoreUiEvent> = _events.asSharedFlow()

    fun onRowClicked(row: MoreRow) {
        // Every row except About navigates (handled in MoreScreen); About
        // is the only row that answers with a message.
        if (row.id == ID_ABOUT) {
            _events.tryEmit(
                MoreUiEvent.ShowMessage(
                    "ReTranslate ${BuildConfig.VERSION_NAME} — a fresh start, built step by step."
                )
            )
        }
    }

    companion object {
        /** The Providers row navigates (P2) instead of showing a message. */
        const val ID_PROVIDERS = "providers"

        /** The Backup row navigates (P6) instead of showing a message. */
        const val ID_BACKUP = "backup"

        /** The Appearance row navigates (P7) instead of showing a message. */
        const val ID_APPEARANCE = "appearance"

        /** The Licences row navigates (P8) instead of showing a message. */
        const val ID_LICENCES = "licences"

        /** The About row shows the version message. */
        const val ID_ABOUT = "about"
    }
}
