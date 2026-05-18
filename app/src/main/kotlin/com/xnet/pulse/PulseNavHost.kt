package com.xnet.pulse

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.xnet.pulse.core.designsystem.PulseTheme
import com.xnet.pulse.feature.chat.ChatScreen
import com.xnet.pulse.feature.library.LibraryScreen

@Composable
fun PulseNavHost() {
  PulseTheme {
    val navController = rememberNavController()
    var selected by remember { mutableIntStateOf(0) }

    Scaffold(
      bottomBar = {
        NavigationBar {
          NavigationBarItem(
            selected = selected == 0,
            onClick = { selected = 0; navController.navigate("chat") { popUpTo("chat") { inclusive = true } } },
            icon = { Icon(Icons.Default.Chat, "Chat") },
            label = { Text("Chat") },
          )
          NavigationBarItem(
            selected = selected == 1,
            onClick = { selected = 1; navController.navigate("library") { popUpTo("chat") } },
            icon = { Icon(Icons.Default.Folder, "Library") },
            label = { Text("Library") },
          )
        }
      },
    ) { padding ->
      NavHost(navController, startDestination = "chat", Modifier.padding(padding)) {
        composable("chat") { ChatScreen() }
        composable("library") { LibraryScreen() }
      }
    }
  }
}
