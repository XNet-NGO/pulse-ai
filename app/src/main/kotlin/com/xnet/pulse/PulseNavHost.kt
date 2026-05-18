package com.xnet.pulse

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun PulseNavHost() {
  val navController = rememberNavController()
  NavHost(navController = navController, startDestination = "chat") {
    composable("chat") { /* ChatScreen() */ }
    composable("library") { /* LibraryScreen() */ }
    composable("settings") { /* SettingsScreen() */ }
  }
}
