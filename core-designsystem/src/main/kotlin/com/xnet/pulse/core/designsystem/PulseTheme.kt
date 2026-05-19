package com.xnet.pulse.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// WCAG AA compliant dark theme — contrast ratio ≥ 4.5:1 for text
private val DarkColors = darkColorScheme(
  primary = Color(0xFF4DD0E1),           // cyan 300 on dark bg = 7.4:1
  onPrimary = Color(0xFF003738),
  primaryContainer = Color(0xFF004F50),
  onPrimaryContainer = Color(0xFF97F0FF),
  secondary = Color(0xFFFFCC02),         // amber on dark = 12:1
  onSecondary = Color(0xFF3D2E00),
  background = Color(0xFF0F0F0F),
  onBackground = Color(0xFFE3E3E3),      // 14:1 on #0F0F0F
  surface = Color(0xFF141414),
  onSurface = Color(0xFFE3E3E3),
  surfaceVariant = Color(0xFF1E1E1E),
  onSurfaceVariant = Color(0xFFCACACA),  // 10:1 on #1E1E1E
  surfaceContainerHighest = Color(0xFF2A2A2A),
  outline = Color(0xFF5C5C5C),
  error = Color(0xFFFFB4AB),
  onError = Color(0xFF690005),
)

// WCAG AA compliant light theme — contrast ratio ≥ 4.5:1 for text
private val LightColors = lightColorScheme(
  primary = Color(0xFF006874),           // teal 800 on white = 7.2:1
  onPrimary = Color(0xFFFFFFFF),
  primaryContainer = Color(0xFF97F0FF),
  onPrimaryContainer = Color(0xFF001F24),
  secondary = Color(0xFF7B5800),         // amber 900 on white = 5.9:1
  onSecondary = Color(0xFFFFFFFF),
  background = Color(0xFFFCFCFC),
  onBackground = Color(0xFF1A1A1A),      // 15:1 on white
  surface = Color(0xFFFFFFFF),
  onSurface = Color(0xFF1A1A1A),
  surfaceVariant = Color(0xFFF0F0F0),
  onSurfaceVariant = Color(0xFF3C3C3C),  // 9.7:1 on #F0F0F0
  surfaceContainerHighest = Color(0xFFE6E6E6),
  outline = Color(0xFF757575),
  error = Color(0xFFBA1A1A),
  onError = Color(0xFFFFFFFF),
)

@Composable
fun PulseTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme = when {
    // System theme: use Material You dynamic colors on Android 12+
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
      val ctx = LocalContext.current
      if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    }
    darkTheme -> DarkColors
    else -> LightColors
  }

  MaterialTheme(
    colorScheme = colorScheme,
    content = content,
  )
}
