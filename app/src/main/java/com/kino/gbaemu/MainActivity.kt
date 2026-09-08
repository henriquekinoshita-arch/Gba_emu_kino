package com.kino.gbaemu

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kino.gbaemu.data.RomEntry
import com.kino.gbaemu.input.GamepadInputHandler
import com.kino.gbaemu.input.PendingKeyCapture
import com.kino.gbaemu.ui.game.GameActivity
import com.kino.gbaemu.ui.library.LibraryScreen
import com.kino.gbaemu.ui.library.LibraryViewModel
import com.kino.gbaemu.ui.settings.ControllerMappingScreen
import com.kino.gbaemu.ui.settings.SettingsScreen
import com.kino.gbaemu.ui.settings.SettingsViewModel
import com.kino.gbaemu.ui.theme.KinoGBATheme

private const val ROUTE_LIBRARY = "library"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_CONTROLLER_MAPPING = "controller_mapping"

class MainActivity : ComponentActivity() {

    private val libraryViewModel: LibraryViewModel by viewModels {
        viewModelFactory {
            initializer { LibraryViewModel(app.libraryRepository) }
        }
    }

    private val settingsViewModel: SettingsViewModel by viewModels {
        viewModelFactory {
            initializer { SettingsViewModel(app.settingsRepository) }
        }
    }

    private val app: KinoGbaApplication get() = application as KinoGbaApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KinoGBATheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = ROUTE_LIBRARY) {
                    composable(ROUTE_LIBRARY) {
                        LibraryScreen(
                            viewModel = libraryViewModel,
                            onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                            onPlayRom = ::launchGame,
                        )
                    }
                    composable(ROUTE_SETTINGS) {
                        SettingsScreen(
                            viewModel = settingsViewModel,
                            onBack = { navController.popBackStack() },
                            onMapController = { navController.navigate(ROUTE_CONTROLLER_MAPPING) },
                        )
                    }
                    composable(ROUTE_CONTROLLER_MAPPING) {
                        ControllerMappingScreen(
                            viewModel = settingsViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        libraryViewModel.refresh()
    }

    private fun launchGame(rom: RomEntry) {
        app.libraryRepository.markPlayed(rom.id)
        startActivity(
            Intent(this, GameActivity::class.java).putExtra(GameActivity.EXTRA_ROM_ID, rom.id),
        )
    }

    /**
     * Physical-gamepad button presses are only observable via the raw
     * dispatchKeyEvent path (Compose has no equivalent). The controller
     * remapping screen "listens" for the next press through
     * [PendingKeyCapture]; everything else falls through to Compose/system
     * handling as normal.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            GamepadInputHandler.isGamepadSource(event.source) &&
            event.repeatCount == 0
        ) {
            if (PendingKeyCapture.consume(event.keyCode)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
