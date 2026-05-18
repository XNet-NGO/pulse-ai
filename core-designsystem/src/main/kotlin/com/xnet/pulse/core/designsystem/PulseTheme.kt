package com.xnet.pulse.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
  primary = Color(0xFF00E5FF),
  secondary = Color(0xFFFFB300),
  background = Color.Black,
  surface = Color(0xFF0A0A0A),
  surfaceVariant = Color(0xFF1A1A1A),
  primaryContainer = Color(0xFF003D4D),
  onPrimary = Color.Black,
  onSurface = Color(0xFFE0E0E0),
  onSurfaceVariant = Color(0xFFB0B0B0),
)

private val LightColors = lightColorScheme(
  primary = Color(0xFF00ACC1),
  secondary = Color(0xFFFFA000),
)

@Composable
fun PulseTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = if (darkTheme) DarkColors else LightColors,
    content = content,
  )
}
