package app.retranslate.ui.more

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.retranslate.core.error.AppResult
import app.retranslate.core.error.ErrorText
import app.retranslate.data.files.TempFiles
import app.retranslate.domain.export.BackupManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream

data class BackupUiState(
    /** "Backing up…" / "Restoring…" while work runs. */
    val workingText: String? = null,
    val progress: Float? = null,
    /** The last finished action, in words (also shown after delivery). */
    val lastSummary: String? = null
)

sealed interface BackupUiEvent {
    data class ShowMessage(val text: String) : BackupUiEvent

    /** A backup file is written; the screen offers Save / Share. */
    data class BackupReady(
        val filePath: String,
        val fileName: String,
        val mimeType: String
    ) : BackupUiEvent
}

/**
 * Backup & restore (PLAN.md §9.3): create writes the backup to a
 * temp file first (the screen then offers SAF save / Share — the
 * §7.5 delivery pattern); restore streams the picked file through
 * [BackupManager]. Keys are never in the file, and the screen says
 * so before the user shares it anywhere.
 */
class BackupViewModel(
    private val backupManager: BackupManager,
    private val tempFiles: TempFiles
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<BackupUiEvent>(extraBufferCapacity = 2)
    val events: SharedFlow<BackupUiEvent> = _events.asSharedFlow()

    fun createBackup() {
        if (_state.value.workingText != null) return
        viewModelScope.launch {
            _state.update { it.copy(workingText = "Backing up…", progress = 0f) }
            val dir = tempFiles.newExportDir()
            val file = File(dir, BackupManager.FILE_NAME)
            val result = try {
                file.outputStream().buffered().use { out ->
                    backupManager.writeBackup(out) { fraction ->
                        _state.update { it.copy(progress = fraction) }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                tempFiles.cleanup(dir)
                _state.update { it.copy(workingText = null, progress = null) }
                _events.tryEmit(
                    BackupUiEvent.ShowMessage("Couldn't write the backup file.")
                )
                return@launch
            }
            _state.update { it.copy(workingText = null, progress = null) }
            when (result) {
                is AppResult.Ok -> {
                    val summary = result.value
                    _state.update {
                        it.copy(
                            lastSummary = "Backup ready: ${summary.books} book(s), " +
                                "${summary.chapters} chapters, " +
                                "${summary.kbEntries} Knowledge Base entries."
                        )
                    }
                    _events.tryEmit(
                        BackupUiEvent.BackupReady(
                            filePath = file.absolutePath,
                            fileName = BackupManager.FILE_NAME,
                            mimeType = BackupManager.MIME_TYPE
                        )
                    )
                }
                is AppResult.Err -> {
                    tempFiles.cleanup(dir)
                    _events.tryEmit(
                        BackupUiEvent.ShowMessage(ErrorText.of(result.error).message)
                    )
                }
            }
        }
    }

    /** [openStream] comes from the screen's SAF pick (PickedFiles). */
    fun restore(openStream: () -> InputStream?) {
        if (_state.value.workingText != null) return
        viewModelScope.launch {
            _state.update { it.copy(workingText = "Restoring…", progress = null) }
            when (val result = backupManager.restoreBackup(openStream)) {
                is AppResult.Ok -> {
                    val summary = result.value
                    val text = buildString {
                        append("Restored ${summary.restoredBooks} book(s) ")
                        append("(${summary.restoredChapters} chapters).")
                        if (summary.skippedBooks > 0) {
                            append(" ${summary.skippedBooks} book(s) were already ")
                            append("here and were kept as they are.")
                        }
                        if (summary.settingsApplied) {
                            append(" Settings were restored. Your API keys are " +
                                "never in a backup — add them again under " +
                                "Providers & keys.")
                        }
                    }
                    _state.update { it.copy(lastSummary = text) }
                    _events.tryEmit(BackupUiEvent.ShowMessage(text))
                }
                is AppResult.Err -> _events.tryEmit(
                    BackupUiEvent.ShowMessage(ErrorText.of(result.error).message)
                )
            }
            _state.update { it.copy(workingText = null) }
        }
    }
}
