package com.xnet.pulse

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.xnet.pulse.feature.chat.ChatScreen
import com.xnet.pulse.feature.chat.theme.ThemeProvider

@Composable
fun PulseNavHost() {
  ThemeProvider {
    Surface(Modifier.fillMaxSize()) {
      ChatScreen()
    }
  }
}
