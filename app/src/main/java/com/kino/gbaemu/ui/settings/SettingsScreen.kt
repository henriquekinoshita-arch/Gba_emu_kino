package com.kino.gbaemu.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kino.gbaemu.R
import com.kino.gbaemu.data.ControlSkin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onMapController: () -> Unit,
) {
    val settings by viewModel.settings.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SkinPicker(current = settings.controlSkin, onPick = viewModel::setControlSkin)

            SettingRow(label = stringResource(R.string.settings_control_opacity)) {
                Slider(
                    value = settings.controlOpacity,
                    onValueChange = viewModel::setControlOpacity,
                    valueRange = 0.15f..1f,
                )
            }

            SettingSwitchRow(
                label = stringResource(R.string.settings_haptics),
                checked = settings.hapticsEnabled,
                onCheckedChange = viewModel::setHapticsEnabled,
            )
            SettingSwitchRow(
                label = stringResource(R.string.settings_show_fps),
                checked = settings.showFps,
                onCheckedChange = viewModel::setShowFps,
            )
            SettingSwitchRow(
                label = stringResource(R.string.settings_filtering),
                checked = settings.smoothFiltering,
                onCheckedChange = viewModel::setSmoothFiltering,
            )

            SettingRow(label = stringResource(R.string.settings_fast_forward_speed)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    listOf(2, 3, 4, 8).forEach { speed ->
                        FilterChipLike(
                            label = "${speed}x",
                            selected = settings.fastForwardSpeed == speed,
                            onClick = { viewModel.setFastForwardSpeed(speed) },
                        )
                    }
                }
            }

            HorizontalDivider()

            Button(onClick = onMapController, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_controller_mapping))
            }
        }
    }
}

@Composable
private fun FilterChipLike(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun SettingSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkinPicker(current: ControlSkin, onPick: (ControlSkin) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(stringResource(R.string.settings_control_skin), style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = { expanded = true }) {
                Text(current.name)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ControlSkin.entries.forEach { skin ->
                    DropdownMenuItem(
                        text = { Text(skin.name) },
                        onClick = {
                            onPick(skin)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
