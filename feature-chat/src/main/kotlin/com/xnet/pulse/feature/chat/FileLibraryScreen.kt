package com.xnet.pulse.feature.chat

import android.content.ContentValues
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.xnet.pulse.feature.chat.engine.DirectoryManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class FileItem(val file: File, val category: String, val convId: String)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FileLibraryScreen(onDismiss: () -> Unit) {
  val ctx = LocalContext.current
  var selectedTab by remember { mutableIntStateOf(0) }
  val tabs = listOf("Generated", "Uploads", "Workspace")
  val selected = remember { mutableStateListOf<String>() } // file paths
  var previewFile by remember { mutableStateOf<File?>(null) }

  val files = remember(selectedTab) {
    val category = tabs[selectedTab].lowercase()
    val root = File(ctx.filesDir, "pulse/$category")
    if (!root.exists()) emptyList()
    else root.listFiles()?.flatMap { convDir ->
      if (convDir.isDirectory) convDir.walkTopDown().filter { it.isFile }.map { FileItem(it, category, convDir.name) }.toList()
      else emptyList()
    }?.sortedByDescending { it.file.lastModified() } ?: emptyList()
  }

  val inSelectMode = selected.isNotEmpty()

  fun saveToPhone(items: List<File>) {
    items.forEach { file ->
      val mime = when (file.extension.lowercase()) {
        "png", "jpg", "jpeg", "webp" -> "image/${file.extension}"
        "svg" -> "image/svg+xml"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
      }
      val isImage = mime.startsWith("image/")
      val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, if (isImage) Environment.DIRECTORY_PICTURES + "/Pulse AI" else Environment.DIRECTORY_DOWNLOADS + "/Pulse AI")
      }
      val uri = ctx.contentResolver.insert(
        if (isImage) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
      )
      uri?.let { ctx.contentResolver.openOutputStream(it)?.use { out -> file.inputStream().use { inp -> inp.copyTo(out) } } }
    }
    Toast.makeText(ctx, "Saved ${items.size} file(s)", Toast.LENGTH_SHORT).show()
  }

  fun shareFiles(items: List<File>) {
    val uris = items.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", it) }
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
      type = "*/*"
      putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(intent, "Share files"))
  }

  fun deleteFiles(items: List<File>) {
    items.forEach { it.delete() }
    selected.clear()
  }

  // Preview dialog
  if (previewFile != null) {
    AlertDialog(
      onDismissRequest = { previewFile = null },
      title = { Text(previewFile!!.name, style = MaterialTheme.typography.titleSmall) },
      text = {
        val f = previewFile!!
        if (f.extension.lowercase() in listOf("png", "jpg", "jpeg", "webp", "svg")) {
          coil.compose.AsyncImage(
            model = f,
            contentDescription = f.name,
            modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).clip(RoundedCornerShape(8.dp)),
          )
        } else {
          Text(f.readText().take(3000), style = MaterialTheme.typography.bodySmall)
        }
      },
      confirmButton = { TextButton(onClick = { previewFile = null }) { Text("Close") } },
    )
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(if (inSelectMode) "${selected.size} selected" else "Library") },
        navigationIcon = {
          IconButton(onClick = { if (inSelectMode) selected.clear() else onDismiss() }) {
            Icon(if (inSelectMode) Icons.Default.Close else Icons.Default.ArrowBack, "Back")
          }
        },
        actions = {
          if (inSelectMode) {
            IconButton(onClick = { saveToPhone(selected.map { File(it) }) ; selected.clear() }) { Icon(Icons.Default.Download, "Save") }
            IconButton(onClick = { shareFiles(selected.map { File(it) }) ; selected.clear() }) { Icon(Icons.Default.Share, "Share") }
            IconButton(onClick = { deleteFiles(selected.map { File(it) }) }) { Icon(Icons.Default.Delete, "Delete") }
          }
        },
      )
    },
  ) { padding ->
    Column(Modifier.padding(padding)) {
      TabRow(selectedTabIndex = selectedTab) {
        tabs.forEachIndexed { i, title -> Tab(selected = selectedTab == i, onClick = { selectedTab = i; selected.clear() }, text = { Text(title) }) }
      }

      if (files.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text("No files", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      } else {
        LazyColumn(Modifier.fillMaxSize()) {
          items(files, key = { it.file.absolutePath }) { item ->
            val isSelected = item.file.absolutePath in selected
            val dateStr = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(item.file.lastModified()))
            val icon = when (item.file.extension.lowercase()) {
              "png", "jpg", "jpeg", "webp" -> "🖼️"
              "svg" -> "🎨"
              "html" -> "🌐"
              "py" -> "🐍"
              "md", "txt" -> "📝"
              "css" -> "🎨"
              "json" -> "📋"
              "latex", "tex" -> "📐"
              else -> "📄"
            }

            ListItem(
              headlineContent = { Text("$icon ${item.file.name}") },
              supportingContent = { Text("$dateStr • ${formatSize(item.file.length())}", style = MaterialTheme.typography.bodySmall) },
              trailingContent = {
                if (isSelected) Icon(Icons.Default.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary)
              },
              colors = ListItemDefaults.colors(
                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface
              ),
              modifier = Modifier.combinedClickable(
                onClick = {
                  if (inSelectMode) {
                    if (isSelected) selected.remove(item.file.absolutePath) else selected.add(item.file.absolutePath)
                  } else {
                    previewFile = item.file
                  }
                },
                onLongClick = {
                  if (!isSelected) selected.add(item.file.absolutePath)
                },
              ),
            )
          }
        }
      }
    }
  }
}

private fun formatSize(bytes: Long): String = when {
  bytes < 1024 -> "$bytes B"
  bytes < 1024 * 1024 -> "${bytes / 1024} KB"
  else -> "${"%.1f".format(bytes / 1048576.0)} MB"
}
