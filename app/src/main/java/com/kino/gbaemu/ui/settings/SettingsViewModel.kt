package com.kino.gbaemu.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kino.gbaemu.core.GbaKey
import com.kino.gbaemu.data.AppSettings
import com.kino.gbaemu.data.ControlSkin
import com.kino.gbaemu.data.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {
    val settings: StateFlow<AppSettings> = repository.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        AppSettings(),
    )

    fun setControlSkin(skin: ControlSkin) = viewModelScope.launch { repository.setControlSkin(skin) }
    fun setControlOpacity(opacity: Float) = viewModelScope.launch { repository.setControlOpacity(opacity) }
    fun setHapticsEnabled(enabled: Boolean) = viewModelScope.launch { repository.setHapticsEnabled(enabled) }
    fun setShowFps(enabled: Boolean) = viewModelScope.launch { repository.setShowFps(enabled) }
    fun setSmoothFiltering(enabled: Boolean) = viewModelScope.launch { repository.setSmoothFiltering(enabled) }
    fun setFastForwardSpeed(speed: Int) = viewModelScope.launch { repository.setFastForwardSpeed(speed) }

    fun setMapping(keyCode: Int, key: GbaKey) = viewModelScope.launch {
        val updated = settings.value.controllerMapping.toMutableMap()
        updated.entries.removeAll { it.value == key }
        updated[keyCode] = key
        repository.setControllerMapping(updated)
    }

    fun resetMapping() = viewModelScope.launch { repository.setControllerMapping(emptyMap()) }
}
