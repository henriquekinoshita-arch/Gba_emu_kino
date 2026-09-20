package com.kino.gbaemu.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kino.gbaemu.R
import com.kino.gbaemu.core.LibretroCore
import com.kino.gbaemu.core.Platform
import com.kino.gbaemu.data.RomEntry
import com.kino.gbaemu.data.RomRepository
import com.kino.gbaemu.engine.EmulatorEngine
import kotlin.math.roundToInt

@Composable
fun GameScreen(
    rom: RomEntry,
    repository: RomRepository,
    nativeLibraryDir: String,
    onExit: () -> Unit,
) {
    val engine = remember { EmulatorEngine(nativeLibraryDir, repository) }
    var loadFailed by remember { mutableStateOf(false) }
    val onExitState = rememberUpdatedState(onExit)

    DisposableEffect(rom) {
        val loaded = engine.load(rom)
        if (loaded) {
            engine.start()
        } else {
            loadFailed = true
        }
        onDispose { engine.shutdown() }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> engine.stop()
                Lifecycle.Event.ON_START -> if (!loadFailed) engine.start()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (loadFailed) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.error_load_rom_failed),
                    style = MaterialTheme.typography.bodyLarge,
                )
                IconButton(onClick = onExitState.value) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = null)
                }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        TopBar(
            onExit = { engine.shutdown(); onExitState.value() },
            onReset = engine::reset,
            onSave = { engine.saveState(0) },
            onLoad = { engine.loadState(0) },
        )

        VideoSurface(
            engine = engine,
            platform = rom.platform,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        OnScreenControls(engine = engine)
    }
}

@Composable
private fun TopBar(onExit: () -> Unit, onReset: () -> Unit, onSave: () -> Unit, onLoad: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onExit) { Icon(Icons.Filled.ArrowBack, contentDescription = null, tint = Color.White) }
        Row {
            IconButton(onClick = onReset) { Icon(Icons.Filled.Refresh, contentDescription = null, tint = Color.White) }
            IconButton(onClick = onSave) { Icon(Icons.Filled.Save, contentDescription = null, tint = Color.White) }
            IconButton(onClick = onLoad) { Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White) }
        }
    }
}

@Composable
private fun VideoSurface(engine: EmulatorEngine, platform: Platform, modifier: Modifier = Modifier) {
    val tick by engine.frameTick.collectAsState()
    val frameWidth by engine.frameWidth.collectAsState()
    val frameHeight by engine.frameHeight.collectAsState()

    val aspect = if (frameHeight > 0) frameWidth.toFloat() / frameHeight.toFloat() else 1f

    val pointerModifier = if (platform == Platform.NDS) {
        Modifier
            .pointerInput(engine) {
                detectDragGestures(
                    onDragStart = { offset -> sendPointer(engine, offset, size, true) },
                    onDrag = { change, _ -> sendPointer(engine, change.position, size, true) },
                    onDragEnd = { engine.setPointer(0, 0, false) },
                    onDragCancel = { engine.setPointer(0, 0, false) },
                )
            }
            .pointerInput(engine) {
                detectTapGestures(
                    onPress = { offset ->
                        sendPointer(engine, offset, size, true)
                        tryAwaitRelease()
                        engine.setPointer(0, 0, false)
                    },
                )
            }
    } else {
        Modifier
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // aspectRatio() alone (no fillMaxSize after it) is what makes this
        // letterbox correctly within the Box: it sizes itself to the largest
        // rect matching `aspect` that fits the Box's incoming constraints.
        Canvas(modifier = Modifier.aspectRatio(aspect).then(pointerModifier)) {
            // `tick` is read only to make this draw scope re-run on every new
            // frame; the pixels themselves live in engine.bitmap, mutated in
            // place by the render thread (see EmulatorEngine.frameTick).
            @Suppress("UNUSED_EXPRESSION") tick
            val bmp = engine.bitmap
            if (bmp != null && frameWidth > 0 && frameHeight > 0) {
                drawImage(
                    image = bmp.asImageBitmap(),
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(frameWidth, frameHeight),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                )
            }
        }
    }
}

private fun sendPointer(engine: EmulatorEngine, offset: Offset, size: IntSize, pressed: Boolean) {
    if (size.width <= 0 || size.height <= 0) return
    val nx = ((offset.x / size.width) * 65535 - 32768).roundToInt().coerceIn(-32768, 32767)
    val ny = ((offset.y / size.height) * 65535 - 32768).roundToInt().coerceIn(-32768, 32767)
    engine.setPointer(nx, ny, pressed)
}

@Composable
private fun OnScreenControls(engine: EmulatorEngine) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DPad(engine)
        ActionButtons(engine)
    }
}

@Composable
private fun DPad(engine: EmulatorEngine) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        HoldButton(engine, LibretroCore.Button.UP, "▲")
        Row {
            HoldButton(engine, LibretroCore.Button.LEFT, "◀")
            Spacer(Modifier.size(48.dp))
            HoldButton(engine, LibretroCore.Button.RIGHT, "▶")
        }
        HoldButton(engine, LibretroCore.Button.DOWN, "▼")
    }
}

@Composable
private fun ActionButtons(engine: EmulatorEngine) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row {
            HoldButton(engine, LibretroCore.Button.X, "X")
            HoldButton(engine, LibretroCore.Button.Y, "Y")
        }
        Row {
            HoldButton(engine, LibretroCore.Button.B, "B")
            HoldButton(engine, LibretroCore.Button.A, "A")
        }
        Row {
            HoldButton(engine, LibretroCore.Button.SELECT, "SEL")
            HoldButton(engine, LibretroCore.Button.START, "START")
        }
    }
}

@Composable
private fun HoldButton(engine: EmulatorEngine, buttonId: Int, label: String) {
    Surface(
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.15f),
        modifier = Modifier
            .padding(4.dp)
            .size(48.dp)
            .pointerInput(buttonId) {
                detectTapGestures(
                    onPress = {
                        engine.setButton(0, buttonId, true)
                        tryAwaitRelease()
                        engine.setButton(0, buttonId, false)
                    },
                )
            },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }
}

