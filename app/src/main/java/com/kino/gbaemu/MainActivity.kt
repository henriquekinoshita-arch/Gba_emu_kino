package com.kino.gbaemu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.kino.gbaemu.data.RomEntry
import com.kino.gbaemu.ui.game.GameScreen
import com.kino.gbaemu.ui.library.LibraryScreen
import com.kino.gbaemu.ui.theme.KinoGBATheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = (application as KinoApplication).romRepository
        val nativeLibraryDir = applicationInfo.nativeLibraryDir

        setContent {
            KinoGBATheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var selectedRom by remember { mutableStateOf<RomEntry?>(null) }
                    val rom = selectedRom
                    if (rom == null) {
                        LibraryScreen(
                            repository = repository,
                            onRomSelected = { selectedRom = it },
                        )
                    } else {
                        GameScreen(
                            rom = rom,
                            repository = repository,
                            nativeLibraryDir = nativeLibraryDir,
                            onExit = { selectedRom = null },
                        )
                    }
                }
            }
        }
    }
}
