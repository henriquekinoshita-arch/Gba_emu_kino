package com.kino.gbaemu.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kino.gbaemu.R
import com.kino.gbaemu.core.Platform
import com.kino.gbaemu.data.RomEntry
import com.kino.gbaemu.data.RomRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(
    repository: RomRepository,
    onRomSelected: (RomEntry) -> Unit,
) {
    var roms by remember { mutableStateOf(repository.listRoms()) }
    var importing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            withContext(Dispatchers.IO) {
                repository.importFromTree(uri)
            }
            roms = repository.listRoms()
            importing = false
        }
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.library_add_folder)) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = { pickFolder.launch(null) },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = stringResource(R.string.library_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(16.dp),
            )

            when {
                importing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.library_scanning), Modifier.padding(top = 8.dp))
                    }
                }

                roms.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.library_empty),
                        modifier = Modifier.padding(32.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

                else -> LazyColumn(
                    contentPadding = PaddingValues(bottom = 96.dp),
                    verticalArrangement = Arrangement.Top,
                ) {
                    items(roms, key = { it.id }) { rom ->
                        ListItem(
                            headlineContent = { Text(rom.displayName) },
                            supportingContent = {
                                Text(
                                    when (rom.platform) {
                                        Platform.GBA -> stringResource(R.string.platform_gba)
                                        Platform.NDS -> stringResource(R.string.platform_nds)
                                    }
                                )
                            },
                            leadingContent = { Icon(Icons.Filled.SportsEsports, contentDescription = null) },
                            trailingContent = {
                                IconButton(onClick = {
                                    repository.deleteRom(rom)
                                    roms = repository.listRoms()
                                }) {
                                    Icon(Icons.Filled.Delete, contentDescription = null)
                                }
                            },
                            modifier = Modifier
                                .clickable { onRomSelected(rom) }
                                .padding(horizontal = 4.dp),
                        )
                        androidx.compose.material3.HorizontalDivider()
                    }
                }
            }

            Text(
                text = stringResource(R.string.library_legal_notice),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
