package com.kino.gbaemu.ui.game

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kino.gbaemu.KinoGbaApplication
import com.kino.gbaemu.R
import com.kino.gbaemu.input.GamepadInputHandler
import com.kino.gbaemu.ui.theme.KinoGBATheme

class GameActivity : ComponentActivity() {

    private val app: KinoGbaApplication get() = application as KinoGbaApplication

    private val viewModel: GameViewModel by viewModels {
        viewModelFactory {
            initializer {
                GameViewModel(
                    app.libraryRepository,
                    app.settingsRepository,
                    app.saveStateRepository,
                    app.cheatsRepository,
                )
            }
        }
    }

    private lateinit var gamepadHandler: GamepadInputHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()

        gamepadHandler = GamepadInputHandler(mapOf()) { key, pressed -> viewModel.setKey(key, pressed) }

        val romId = intent.getStringExtra(EXTRA_ROM_ID)
        if (romId != null) {
            viewModel.loadRom(romId)
        }

        setContent {
            KinoGBATheme {
                val settings by app.settingsRepository.settings.collectAsState(initial = null)
                LaunchedEffect(settings) {
                    settings?.let { gamepadHandler.updateMapping(it.controllerMapping) }
                }
                GameScreen(viewModel = viewModel, onExit = { finish() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.engine.resume()
    }

    override fun onPause() {
        viewModel.engine.pause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (GamepadInputHandler.isGamepadSource(event.source) && gamepadHandler.handleKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (gamepadHandler.handleMotionEvent(event)) {
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    companion object {
        const val EXTRA_ROM_ID = "extra_rom_id"
    }
}

@Composable
private fun GameScreen(viewModel: GameViewModel, onExit: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    var showMenu by remember { mutableStateOf(false) }
    var showSaveStates by remember { mutableStateOf(false) }
    var showCheats by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.addCallback(
                            object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    viewModel.attachSurface(holder)
                                }

                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    viewModel.detachSurface()
                                }
                            },
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            if (uiState.loadError != null) {
                Text(
                    text = stringResource(R.string.error_load_rom_failed),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            OnScreenControls(
                skin = uiState.controlSkin,
                opacity = uiState.controlOpacity,
                hapticsEnabled = uiState.hapticsEnabled,
                onKey = viewModel::setKey,
                onFastForwardPress = viewModel::setFastForward,
                onRewindPress = viewModel::setRewinding,
                onMenu = { showMenu = true },
                modifier = Modifier.fillMaxSize(),
            )

            if (uiState.showFps) {
                Text(
                    text = "${uiState.fps} fps",
                    color = Color.Green,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                )
            }
        }
    }

    if (showMenu) {
        PauseMenu(
            onResume = { showMenu = false },
            onReset = {
                viewModel.reset()
                showMenu = false
            },
            onOpenSaveStates = {
                showMenu = false
                showSaveStates = true
            },
            onOpenCheats = {
                showMenu = false
                showCheats = true
            },
            onExit = {
                showMenu = false
                onExit()
            },
        )
    }

    if (showSaveStates) {
        SaveStateDialog(
            slots = viewModel.saveStateSlots(),
            onDismiss = { showSaveStates = false },
            onSave = { slot ->
                viewModel.saveToSlot(slot)
                showSaveStates = false
            },
            onLoad = { slot ->
                viewModel.loadFromSlot(slot)
                showSaveStates = false
            },
        )
    }

    if (showCheats) {
        CheatsDialog(
            cheats = viewModel.cheatsForCurrentRom(),
            onDismiss = { showCheats = false },
            onAdd = viewModel::addCheat,
            onRemove = viewModel::removeCheat,
            onToggle = viewModel::setCheatEnabled,
        )
    }
}
