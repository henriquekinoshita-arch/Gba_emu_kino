package com.kino.gbaemu.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.kino.gbaemu.core.GbaKey
import com.kino.gbaemu.data.ControlSkin

private data class SkinColors(val fill: Color, val border: Color, val label: Color)

private fun colorsFor(skin: ControlSkin): SkinColors = when (skin) {
    ControlSkin.CLASSIC -> SkinColors(Color(0xFF4A4A4A), Color(0xFF2A2A2A), Color.White)
    ControlSkin.NEON -> SkinColors(Color(0xFF6C4AB6), Color(0xFFFFC857), Color.White)
    ControlSkin.MONO -> SkinColors(Color(0xFF202020), Color.White, Color.White)
}

@Composable
fun OnScreenControls(
    skin: ControlSkin,
    opacity: Float,
    hapticsEnabled: Boolean,
    onKey: (GbaKey, Boolean) -> Unit,
    onFastForwardPress: (Boolean) -> Unit,
    onRewindPress: (Boolean) -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = colorsFor(skin)
    Box(modifier = modifier.fillMaxSize()) {
        DPad(
            colors = colors,
            opacity = opacity,
            hapticsEnabled = hapticsEnabled,
            onKey = onKey,
            modifier = Modifier.align(Alignment.BottomStart).padding(24.dp),
        )

        ActionButtons(
            colors = colors,
            opacity = opacity,
            hapticsEnabled = hapticsEnabled,
            onKey = onKey,
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
        )

        RoundGlyphButton(
            label = "L",
            colors = colors,
            opacity = opacity,
            hapticsEnabled = hapticsEnabled,
            onPress = { onKey(GbaKey.L, it) },
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
        )
        RoundGlyphButton(
            label = "R",
            colors = colors,
            opacity = opacity,
            hapticsEnabled = hapticsEnabled,
            onPress = { onKey(GbaKey.R, it) },
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
        )

        RoundGlyphButton(
            label = "≡",
            colors = colors,
            opacity = opacity,
            hapticsEnabled = false,
            onPress = { pressed -> if (pressed) onMenu() },
            modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
            size = 40.dp,
        )

        Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)) {
            SmallPillButton(
                label = "SELECT",
                colors = colors,
                opacity = opacity,
                hapticsEnabled = hapticsEnabled,
                onPress = { onKey(GbaKey.SELECT, it) },
                modifier = Modifier.absoluteOffset(x = (-48).dp),
            )
            SmallPillButton(
                label = "START",
                colors = colors,
                opacity = opacity,
                hapticsEnabled = hapticsEnabled,
                onPress = { onKey(GbaKey.START, it) },
                modifier = Modifier.absoluteOffset(x = 48.dp),
            )
        }

        SmallPillButton(
            label = "FF",
            colors = colors,
            opacity = opacity,
            hapticsEnabled = hapticsEnabled,
            onPress = onFastForwardPress,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
        )
        SmallPillButton(
            label = "REW",
            colors = colors,
            opacity = opacity,
            hapticsEnabled = hapticsEnabled,
            onPress = onRewindPress,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
        )
    }
}

@Composable
private fun DPad(
    colors: SkinColors,
    opacity: Float,
    hapticsEnabled: Boolean,
    onKey: (GbaKey, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cell = 44.dp
    Box(modifier = modifier.size(cell * 3)) {
        DPadCell(colors, opacity, hapticsEnabled, { onKey(GbaKey.UP, it) }, Modifier.align(Alignment.TopCenter).size(cell))
        DPadCell(colors, opacity, hapticsEnabled, { onKey(GbaKey.DOWN, it) }, Modifier.align(Alignment.BottomCenter).size(cell))
        DPadCell(colors, opacity, hapticsEnabled, { onKey(GbaKey.LEFT, it) }, Modifier.align(Alignment.CenterStart).size(cell))
        DPadCell(colors, opacity, hapticsEnabled, { onKey(GbaKey.RIGHT, it) }, Modifier.align(Alignment.CenterEnd).size(cell))
        Box(modifier = Modifier.align(Alignment.Center).size(cell).background(colors.fill.copy(alpha = opacity), RoundedCornerShape(6.dp)))
    }
}

@Composable
private fun DPadCell(
    colors: SkinColors,
    opacity: Float,
    hapticsEnabled: Boolean,
    onPress: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.fill.copy(alpha = opacity), RoundedCornerShape(6.dp))
            .pressable(hapticsEnabled, haptics, onPress),
    )
}

@Composable
private fun ActionButtons(
    colors: SkinColors,
    opacity: Float,
    hapticsEnabled: Boolean,
    onKey: (GbaKey, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.size(140.dp)) {
        RoundGlyphButton(
            label = "B",
            colors = colors,
            opacity = opacity,
            hapticsEnabled = hapticsEnabled,
            onPress = { onKey(GbaKey.B, it) },
            modifier = Modifier.align(Alignment.BottomStart),
            size = 60.dp,
        )
        RoundGlyphButton(
            label = "A",
            colors = colors,
            opacity = opacity,
            hapticsEnabled = hapticsEnabled,
            onPress = { onKey(GbaKey.A, it) },
            modifier = Modifier.align(Alignment.TopEnd),
            size = 60.dp,
        )
    }
}

@Composable
private fun RoundGlyphButton(
    label: String,
    colors: SkinColors,
    opacity: Float,
    hapticsEnabled: Boolean,
    onPress: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 52.dp,
) {
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.fill.copy(alpha = if (pressed) (opacity + 0.25f).coerceAtMost(1f) else opacity), CircleShape)
            .pressable(hapticsEnabled, haptics) {
                pressed = it
                onPress(it)
            },
    ) {
        Text(text = label, color = colors.label)
    }
}

@Composable
private fun SmallPillButton(
    label: String,
    colors: SkinColors,
    opacity: Float,
    hapticsEnabled: Boolean,
    onPress: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(colors.fill.copy(alpha = opacity), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .pressable(hapticsEnabled, haptics, onPress),
    ) {
        Text(text = label, color = colors.label, style = MaterialTheme.typography.labelSmall)
    }
}

private fun Modifier.pressable(
    hapticsEnabled: Boolean,
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onPress: (Boolean) -> Unit,
): Modifier = this.pointerInput(Unit) {
    detectTapGestures(
        onPress = {
            if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onPress(true)
            try {
                tryAwaitRelease()
            } finally {
                onPress(false)
            }
        },
    )
}
