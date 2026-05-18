package com.xnet.pulse.feature.library

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen() {
  val ctx = LocalContext.current
  val root = remember { File(ctx.filesDir, "pulse").also { it.mkdirs() } }
  var currentDir by remember { mutableStateOf(root) }
  val files = remember(currentDir) { currentDir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList() }
  val isRoot = currentDir == root

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(if (isRoot) "Library" else currentDir.name) },
        navigationIcon = {
          if (!isRoot) IconButton(onClick = { currentDir = currentDir.parentFile ?: root }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
          }
        },
      )
    },
  ) { padding ->
    if (files.isEmpty()) {
      Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Text("Empty — the AI will save files here", color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    } else {
      LazyColumn(Modifier.padding(padding)) {
        items(files) { file ->
          ListItem(
            headlineContent = { Text(file.name) },
            supportingContent = { if (!file.isDirectory) Text("${file.length() / 1024} KB") },
            leadingContent = { Icon(if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile, null) },
            trailingContent = {
              if (!file.isDirectory) IconButton(onClick = { shareFile(ctx, file) }) {
                Icon(Icons.Default.Share, "Share")
              }
            },
            modifier = Modifier.clickable {
              if (file.isDirectory) currentDir = file
            },
          )
        }
      }
    }
  }
}

private fun shareFile(ctx: Context, file: File) {
  try {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
      type = "*/*"
      putExtra(Intent.EXTRA_STREAM, uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    ctx.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  } catch (_: Exception) {}
}
