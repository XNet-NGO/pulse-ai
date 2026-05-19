package com.xnet.pulse

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.xnet.pulse.core.designsystem.LocalThemeMode
import com.xnet.pulse.core.designsystem.PulseTheme
import com.xnet.pulse.core.designsystem.ThemeManager
import com.xnet.pulse.core.designsystem.ThemeMode
import com.xnet.pulse.feature.chat.ChatScreen

@Composable
fun PulseNavHost() {
  val ctx = LocalContext.current
  var themeMode by remember { mutableStateOf(ThemeManager.get(ctx)) }
  val isDark = when (themeMode) {
    ThemeMode.DARK, ThemeMode.CUSTOM -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
  }

  CompositionLocalProvider(LocalThemeMode provides themeMode) {
    PulseTheme(darkTheme = isDark) {
      ChatScreen(onThemeChange = { mode -> themeMode = mode; ThemeManager.set(ctx, mode) })
    }
  }
}
