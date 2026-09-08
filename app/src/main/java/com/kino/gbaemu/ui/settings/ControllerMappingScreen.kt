package com.kino.gbaemu.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kino.gbaemu.R
import com.kino.gbaemu.core.GbaKey
import com.kino.gbaemu.input.DEFAULT_GAMEPAD_MAPPING
import com.kino.gbaemu.input.PendingKeyCapture

private val actionLabelRes = mapOf(
    GbaKey.A to R.string.action_a,
    GbaKey.B to R.string.action_b,
    GbaKey.L to R.string.action_l,
    GbaKey.R to R.string.action_r,
    GbaKey.START to R.string.action_start,
    GbaKey.SELECT to R.string.action_select,
    GbaKey.UP to R.string.action_up,
    GbaKey.DOWN to R.string.action_down,
    GbaKey.LEFT to R.string.action_left,
    GbaKey.RIGHT to R.string.action_right,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControllerMappingScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    var listeningFor by remember { mutableStateOf<GbaKey?>(null) }

    val mapping = settings.controllerMapping.ifEmpty { DEFAULT_GAMEPAD_MAPPING }
    val keyCodeForAction = remember(mapping) { mapping.entries.associate { (code, key) -> key to code } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.controller_mapping_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxWidth().padding(padding).padding(20.dp)) {
            listeningFor?.let { action ->
                Text(
                    text = stringResource(R.string.controller_mapping_press, stringResource(actionLabelRes.getValue(action))),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }

            actionLabelRes.keys.forEach { action ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(actionLabelRes.getValue(action)), style = MaterialTheme.typography.titleSmall)
                    Button(onClick = {
                        listeningFor = action
                        PendingKeyCapture.onKeyDown = { keyCode ->
                            viewModel.setMapping(keyCode, action)
                            listeningFor = null
                        }
                    }) {
                        Text(keyCodeForAction[action]?.let { android.view.KeyEvent.keyCodeToString(it) } ?: "-")
                    }
                }
            }

            TextButton(onClick = {
                viewModel.resetMapping()
                listeningFor = null
                PendingKeyCapture.onKeyDown = null
            }) {
                Text(stringResource(R.string.controller_mapping_reset))
            }
        }
    }
}
