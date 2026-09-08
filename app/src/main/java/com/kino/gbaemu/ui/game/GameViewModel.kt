package com.kino.gbaemu.ui.game

import android.view.SurfaceHolder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kino.gbaemu.core.EmulatorEngine
import com.kino.gbaemu.core.GbaKey
import com.kino.gbaemu.core.RomLoadResult
import com.kino.gbaemu.data.CheatsRepository
import com.kino.gbaemu.data.ControlSkin
import com.kino.gbaemu.data.LibraryRepository
import com.kino.gbaemu.data.RomEntry
import com.kino.gbaemu.data.SaveStateRepository
import com.kino.gbaemu.data.SaveStateSlot
import com.kino.gbaemu.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GameUiState(
    val rom: RomEntry? = null,
    val loadError: RomLoadResult? = null,
    val fps: Int = 0,
    val isPaused: Boolean = false,
    val isFastForwarding: Boolean = false,
    val showFps: Boolean = false,
    val smoothFiltering: Boolean = false,
    val controlSkin: ControlSkin = ControlSkin.NEON,
    val controlOpacity: Float = 0.55f,
    val hapticsEnabled: Boolean = true,
    val fastForwardSpeed: Int = 3,
)

class GameViewModel(
    private val libraryRepository: LibraryRepository,
    private val settingsRepository: SettingsRepository,
    private val saveStateRepository: SaveStateRepository,
    private val cheatsRepository: CheatsRepository,
) : ViewModel() {

    val engine = EmulatorEngine()

    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    init {
        engine.onFps = { fps -> _uiState.value = _uiState.value.copy(fps = fps) }
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                engine.smoothFiltering = settings.smoothFiltering
                _uiState.value = _uiState.value.copy(
                    showFps = settings.showFps,
                    smoothFiltering = settings.smoothFiltering,
                    controlSkin = settings.controlSkin,
                    controlOpacity = settings.controlOpacity,
                    hapticsEnabled = settings.hapticsEnabled,
                    fastForwardSpeed = settings.fastForwardSpeed,
                )
            }
        }
    }

    fun loadRom(romId: String) {
        viewModelScope.launch {
            val entry = withContext(Dispatchers.IO) { libraryRepository.loadLibrary().find { it.id == romId } }
            if (entry == null) {
                _uiState.value = _uiState.value.copy(loadError = RomLoadResult.CANNOT_OPEN_FILE)
                return@launch
            }
            _uiState.value = _uiState.value.copy(rom = entry)

            val result = withContext(Dispatchers.IO) {
                val (romFile, saveFile) = libraryRepository.importIfNeeded(entry)
                val cheats = cheatsRepository.list(entry.id).filter { it.enabled }.map { it.name to it.code }
                val loadResult = engine.start(romFile.absolutePath, saveFile.absolutePath)
                if (loadResult == RomLoadResult.SUCCESS) {
                    engine.replaceCheats(cheats)
                }
                loadResult
            }
            _uiState.value = _uiState.value.copy(loadError = result.takeIf { it != RomLoadResult.SUCCESS })
        }
    }

    fun attachSurface(holder: SurfaceHolder) = engine.attachSurface(holder)
    fun detachSurface() = engine.detachSurface()

    fun setKey(key: GbaKey, pressed: Boolean) = engine.setKey(key, pressed)

    fun togglePause() {
        val paused = !_uiState.value.isPaused
        if (paused) engine.pause() else engine.resume()
        _uiState.value = _uiState.value.copy(isPaused = paused)
    }

    fun reset() = engine.reset()

    fun setFastForward(active: Boolean) {
        engine.fastForwardMultiplier = if (active) _uiState.value.fastForwardSpeed else 1
        _uiState.value = _uiState.value.copy(isFastForwarding = active)
    }

    fun setRewinding(active: Boolean) = engine.setRewinding(active)

    fun saveStateSlots(): List<SaveStateSlot> {
        val romId = _uiState.value.rom?.id ?: return emptyList()
        return saveStateRepository.listSlots(romId)
    }

    fun saveToSlot(slot: Int) {
        val romId = _uiState.value.rom?.id ?: return
        val bytes = engine.saveStateBytes() ?: return
        val thumbnail = engine.captureThumbnail()
        saveStateRepository.save(romId, slot, bytes, thumbnail)
    }

    fun loadFromSlot(slot: Int): Boolean {
        val romId = _uiState.value.rom?.id ?: return false
        val bytes = saveStateRepository.load(romId, slot) ?: return false
        return engine.loadStateBytes(bytes)
    }

    fun cheatsForCurrentRom() = _uiState.value.rom?.let { cheatsRepository.list(it.id) } ?: emptyList()

    fun addCheat(name: String, code: String) {
        val rom = _uiState.value.rom ?: return
        cheatsRepository.add(rom.id, name, code)
        refreshCheats()
    }

    fun removeCheat(id: String) {
        val rom = _uiState.value.rom ?: return
        cheatsRepository.remove(rom.id, id)
        refreshCheats()
    }

    fun setCheatEnabled(id: String, enabled: Boolean) {
        val rom = _uiState.value.rom ?: return
        cheatsRepository.setEnabled(rom.id, id, enabled)
        refreshCheats()
    }

    private fun refreshCheats() {
        val rom = _uiState.value.rom ?: return
        val cheats = cheatsRepository.list(rom.id).filter { it.enabled }.map { it.name to it.code }
        engine.replaceCheats(cheats)
    }

    override fun onCleared() {
        engine.shutdown()
        super.onCleared()
    }
}
