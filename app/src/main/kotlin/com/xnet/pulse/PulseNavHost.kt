package com.xnet.pulse

import androidx.compose.runtime.Composable
import com.xnet.pulse.core.designsystem.PulseTheme
import com.xnet.pulse.feature.chat.ChatScreen

@Composable
fun PulseNavHost() {
  PulseTheme {
    ChatScreen()
  }
}
