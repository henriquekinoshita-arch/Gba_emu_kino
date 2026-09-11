package com.kino.gbaemu.ui.library

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kino.gbaemu.data.CrashLog
import com.kino.gbaemu.data.LibraryRepository
import com.kino.gbaemu.data.RomEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryUiState(
    val roms: List<RomEntry> = emptyList(),
    val isScanning: Boolean = false,
    val hasFolder: Boolean = false,
    /** Breadcrumbs from the last native crash, if any - see [CrashLog]. */
    val crashLog: String? = null,
)

class LibraryViewModel(
    private val appContext: Context,
    private val repository: LibraryRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isScanning = true)
            val roms = withContext(Dispatchers.IO) { repository.rescan() }
            _uiState.value = LibraryUiState(
                roms = roms.sortedByDescending { it.lastPlayedAt },
                isScanning = false,
                hasFolder = repository.watchedFolders().isNotEmpty(),
                crashLog = CrashLog.read(appContext),
            )
        }
    }

    fun onFolderPicked(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.addFolder(uri)
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    fun removeRom(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.removeEntry(id)
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    fun dismissCrashLog() {
        CrashLog.clear(appContext)
        _uiState.value = _uiState.value.copy(crashLog = null)
    }
}
