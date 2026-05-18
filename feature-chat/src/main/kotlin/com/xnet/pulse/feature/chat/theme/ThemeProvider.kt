package com.xnet.pulse.feature.chat.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

data class ThemeState(
  val mode: String = "dark",
  val isDark: Boolean = true,
  val useBackground: Boolean = false,
  val backgroundUri: String? = null,
  val backgroundMediaType: String = "image",
  val backgroundOpacity: Float = 0.3f,
  val videoMuted: Boolean = true,
  val videoLoop: Boolean = true,
  val videoRotation: Int = 0,
  val userBubbleColor: Color? = null,
  val aiBubbleColor: Color? = null,
  val userTextColor: Color? = null,
  val aiTextColor: Color? = null,
  val useCustomBubbles: Boolean = false,
  val userBubbleOpacity: Float = 1f,
  val aiBubbleOpacity: Float = 1f,
  val showThinking: Boolean = true,
  val uiOpacity: Float = 1f,
)

val LocalThemeState = compositionLocalOf { ThemeState() }

@Composable
fun PulseThemeProvider(content: @Composable () -> Unit) {
  val ctx = LocalContext.current
  val prefs = remember { ThemePrefs(ctx) }

  val mode = prefs.themeMode.collectAsState(initial = "dark").value
  val sysDark = isSystemInDarkTheme()
  val isDark = when (mode) { "light" -> false; "dark" -> true; else -> sysDark }

  val useCustomColors = prefs.useCustomColors.collectAsState(initial = false).value
  val primaryColor = prefs.primaryColor.collectAsState(initial = null).value?.let { Color(it) }
  val secondaryColor = prefs.secondaryColor.collectAsState(initial = null).value?.let { Color(it) }

  val baseScheme = if (isDark) darkColorScheme(
    primary = Color(0xFF00E5FF), secondary = Color(0xFFFFB300),
    background = Color.Black, surface = Color(0xFF0A0A0A), surfaceVariant = Color(0xFF1A1A1A),
    primaryContainer = Color(0xFF003D4D), onPrimary = Color.Black,
    onSurface = Color(0xFFE0E0E0), onSurfaceVariant = Color(0xFFB0B0B0),
  ) else lightColorScheme(primary = Color(0xFF00ACC1), secondary = Color(0xFFFFA000))

  val colorScheme = if (useCustomColors && primaryColor != null) baseScheme.copy(
    primary = primaryColor, secondary = secondaryColor ?: primaryColor,
    primaryContainer = primaryColor.copy(alpha = 0.3f),
  ) else baseScheme

  val useUiColor = prefs.useUiColor.collectAsState(initial = false).value
  val uiColor = prefs.uiColor.collectAsState(initial = null).value?.let { Color(it) }
  val useCustomText = prefs.useCustomText.collectAsState(initial = false).value
  val pText = prefs.primaryTextColor.collectAsState(initial = null).value?.let { Color(it) }
  val sText = prefs.secondaryTextColor.collectAsState(initial = null).value?.let { Color(it) }

  var finalScheme = colorScheme
  if (useUiColor && uiColor != null) finalScheme = finalScheme.copy(surface = uiColor, background = uiColor, surfaceVariant = uiColor.copy(alpha = 0.7f))
  if (useCustomText && pText != null) finalScheme = finalScheme.copy(onSurface = pText, onBackground = pText, onSurfaceVariant = sText ?: pText.copy(alpha = 0.7f))

  val state = ThemeState(
    mode = mode, isDark = isDark,
    useBackground = prefs.useBackground.collectAsState(initial = false).value,
    backgroundUri = prefs.backgroundUri.collectAsState(initial = null).value,
    backgroundMediaType = prefs.backgroundMediaType.collectAsState(initial = "image").value,
    backgroundOpacity = prefs.backgroundOpacity.collectAsState(initial = 0.3f).value,
    videoMuted = prefs.videoMuted.collectAsState(initial = true).value,
    videoLoop = prefs.videoLoop.collectAsState(initial = true).value,
    videoRotation = prefs.videoRotation.collectAsState(initial = 0).value,
    userBubbleColor = prefs.userBubbleColor.collectAsState(initial = null).value?.let { Color(it) },
    aiBubbleColor = prefs.aiBubbleColor.collectAsState(initial = null).value?.let { Color(it) },
    userTextColor = prefs.userTextColor.collectAsState(initial = null).value?.let { Color(it) },
    aiTextColor = prefs.aiTextColor.collectAsState(initial = null).value?.let { Color(it) },
    useCustomBubbles = prefs.useCustomBubbles.collectAsState(initial = false).value,
    userBubbleOpacity = prefs.userBubbleOpacity.collectAsState(initial = 1f).value,
    aiBubbleOpacity = prefs.aiBubbleOpacity.collectAsState(initial = 1f).value,
    showThinking = prefs.showThinking.collectAsState(initial = true).value,
    uiOpacity = prefs.uiOpacity.collectAsState(initial = 1f).value,
  )

  CompositionLocalProvider(LocalThemeState provides state) {
    MaterialTheme(colorScheme = finalScheme, content = content)
  }
}
