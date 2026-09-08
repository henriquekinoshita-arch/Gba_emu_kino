package com.kino.gbaemu.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val KinoPurple = Color(0xFF6C4AB6)
private val KinoPurpleDark = Color(0xFF241B3A)
private val KinoLilac = Color(0xFFB39DDB)
private val KinoAmber = Color(0xFFFFC857)
private val KinoBg = Color(0xFF121018)
private val KinoSurface = Color(0xFF1D1926)
private val KinoOnSurface = Color(0xFFF2EFFA)

private val KinoDarkColors = darkColorScheme(
    primary = KinoPurple,
    onPrimary = KinoOnSurface,
    secondary = KinoAmber,
    onSecondary = KinoPurpleDark,
    tertiary = KinoLilac,
    background = KinoBg,
    onBackground = KinoOnSurface,
    surface = KinoSurface,
    onSurface = KinoOnSurface,
)

@Composable
fun KinoGBATheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KinoDarkColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
