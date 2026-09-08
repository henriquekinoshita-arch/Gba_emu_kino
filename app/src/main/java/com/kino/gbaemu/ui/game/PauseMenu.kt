package com.kino.gbaemu.ui.game

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kino.gbaemu.R
import com.kino.gbaemu.data.CheatCode
import com.kino.gbaemu.data.SaveStateSlot

@Composable
fun PauseMenu(
    onResume: () -> Unit,
    onReset: () -> Unit,
    onOpenSaveStates: () -> Unit,
    onOpenCheats: () -> Unit,
    onExit: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onResume,
        confirmButton = {},
        title = { Text(stringResource(R.string.menu_settings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MenuAction(stringResource(R.string.menu_resume), onResume)
                MenuAction(stringResource(R.string.menu_reset), onReset)
                MenuAction(stringResource(R.string.menu_save_state) + " / " + stringResource(R.string.menu_load_state), onOpenSaveStates)
                MenuAction(stringResource(R.string.menu_cheats), onOpenCheats)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                MenuAction(stringResource(R.string.menu_exit_to_library), onExit)
            }
        },
    )
}

@Composable
private fun MenuAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun SaveStateDialog(
    slots: List<SaveStateSlot>,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
    onLoad: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
        title = { Text(stringResource(R.string.menu_save_state)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(slots) { slot ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        slot.thumbnailPath?.let { path ->
                            val bitmap = remember(path) { android.graphics.BitmapFactory.decodeFile(path) }
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .aspectRatio(1.5f)
                                        .clip(RoundedCornerShape(4.dp)),
                                )
                            }
                        }
                        Column {
                            Text(stringResource(R.string.save_slot, slot.index + 1))
                            Text(
                                text = if (slot.exists) java.text.DateFormat.getDateTimeInstance().format(slot.timestamp) else stringResource(R.string.save_slot_empty),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Row {
                            TextButton(onClick = { onSave(slot.index) }) { Text(stringResource(R.string.menu_save_state)) }
                            if (slot.exists) {
                                TextButton(onClick = { onLoad(slot.index) }) { Text(stringResource(R.string.menu_load_state)) }
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
fun CheatsDialog(
    cheats: List<CheatCode>,
    onDismiss: () -> Unit,
    onAdd: (name: String, code: String) -> Unit,
    onRemove: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
        title = { Text(stringResource(R.string.cheats_title)) },
        text = {
            Column {
                if (cheats.isEmpty()) {
                    Text(stringResource(R.string.cheats_empty), style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(cheats) { cheat ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Checkbox(checked = cheat.enabled, onCheckedChange = { onToggle(cheat.id, it) })
                                Text(cheat.name, modifier = Modifier.padding(top = 12.dp))
                                TextButton(onClick = { onRemove(cheat.id) }) { Text("✕") }
                            }
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.cheats_name_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(stringResource(R.string.cheats_code_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        if (name.isNotBlank() && code.isNotBlank()) {
                            onAdd(name, code)
                            name = ""
                            code = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.cheats_add))
                }
            }
        },
    )
}
