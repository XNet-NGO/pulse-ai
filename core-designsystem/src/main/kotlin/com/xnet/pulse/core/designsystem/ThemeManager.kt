package com.xnet.pulse.core.designsystem

import android.content.Context
import androidx.compose.runtime.compositionLocalOf

enum class ThemeMode { SYSTEM, LIGHT, DARK, CUSTOM }

object ThemeManager {
  private const val PREFS = "pulse_theme"
  private const val KEY = "mode"

  fun get(ctx: Context): ThemeMode {
    val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    return try { ThemeMode.valueOf(prefs.getString(KEY, "SYSTEM") ?: "SYSTEM") } catch (_: Exception) { ThemeMode.SYSTEM }
  }

  fun set(ctx: Context, mode: ThemeMode) {
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode.name).apply()
  }
}

val LocalThemeMode = compositionLocalOf { ThemeMode.SYSTEM }
