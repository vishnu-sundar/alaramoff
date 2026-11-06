package dev.alarmoff.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val day = lightColorScheme(
    primary = Color(0xFF1D6555), onPrimary = Color.White,
    primaryContainer = Color(0xFFDAEDE3), onPrimaryContainer = Color(0xFF174E40),
    background = Color(0xFFF6F6F1), onBackground = Color(0xFF172423),
    surface = Color(0xFFFCFCF8), onSurface = Color(0xFF172423),
    surfaceVariant = Color(0xFFE5E9E2), onSurfaceVariant = Color(0xFF4B5B56),
    outlineVariant = Color(0xFFCFD7CE), error = Color(0xFF973B30)
)
private val night = darkColorScheme(
    primary = Color(0xFFA1DABD), onPrimary = Color(0xFF123F32),
    primaryContainer = Color(0xFF20483B), onPrimaryContainer = Color(0xFFC2F1D4),
    background = Color(0xFF111917), onBackground = Color(0xFFE5ECE7),
    surface = Color(0xFF1B2420), onSurface = Color(0xFFE5ECE7),
    surfaceVariant = Color(0xFF29362F), onSurfaceVariant = Color(0xFFB0BEB4),
    outlineVariant = Color(0xFF39483F), error = Color(0xFFFFB2A7)
)

@Composable fun AlarmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) night else day, content = content)
}
