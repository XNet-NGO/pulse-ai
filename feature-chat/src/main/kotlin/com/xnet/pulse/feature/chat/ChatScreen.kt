package com.xnet.pulse.feature.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xnet.pulse.core.model.MessageStatus
import com.xnet.pulse.core.model.Role
import com.xnet.pulse.feature.chat.engine.AttachmentProcessor
import com.xnet.pulse.feature.chat.engine.VoiceManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel()) {
  val messages by viewModel.messages.collectAsState()
  val status by viewModel.status.collectAsState()
  val isStreaming by viewModel.isStreaming.collectAsState()
  val conversations by viewModel.conversations.collectAsState(initial = emptyList())
  val listState = rememberLazyListState()
  val scope = rememberCoroutineScope()
  var showDrawer by remember { mutableStateOf(false) }
  var showSettings by remember { mutableStateOf(false) }
  var showLibrary by remember { mutableStateOf(false) }
  val ctx = LocalContext.current
  val prefs = remember { com.xnet.pulse.feature.chat.theme.ThemePrefs(ctx) }
  val showThinking by prefs.showThinking.collectAsState(initial = true)
  var reportMessageId by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(messages.size) {
    if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
  }

  if (reportMessageId != null) {
    AlertDialog(
      onDismissRequest = { reportMessageId = null },
      title = { Text("Report Message") },
      text = { Text("Flag this response as inappropriate or harmful?") },
      confirmButton = { TextButton(onClick = { reportMessageId = null }) { Text("Report") } },
      dismissButton = { TextButton(onClick = { reportMessageId = null }) { Text("Cancel") } },
    )
  }

  if (showDrawer) {
    ModalBottomSheet(onDismissRequest = { showDrawer = false }) {
      Column(Modifier.padding(16.dp)) {
        TextButton(onClick = { viewModel.newConversation(); showDrawer = false }) { Text("+ New Chat") }
        TextButton(onClick = { showDrawer = false; showLibrary = true }) { Text("📁 Library") }
        TextButton(onClick = { showDrawer = false; showSettings = true }) { Text("⚙️ Settings") }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        conversations.forEach { conv ->
          ListItem(
            headlineContent = { Text(conv.title) },
            modifier = Modifier.clickable { viewModel.loadConversation(conv.id); showDrawer = false },
            trailingContent = {
              IconButton(onClick = { viewModel.deleteConversation(conv.id) }) { Icon(Icons.Default.Delete, "Delete") }
            },
          )
        }
      }
    }
  }

  // Settings
  if (showSettings) {
    com.xnet.pulse.feature.chat.theme.ThemeSettingsScreen(onBack = { showSettings = false })
    return
  }
  if (showLibrary) {
    FileLibraryScreen(onDismiss = { showLibrary = false })
    return
  }

  val themeState = com.xnet.pulse.feature.chat.theme.LocalThemeState.current

  Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    com.xnet.pulse.feature.chat.theme.ChatBackground(themeState)
    Column(
      Modifier
        .fillMaxSize()
        .statusBarsPadding()
        .navigationBarsPadding()
        .imePadding()
    ) {
    // Top bar
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = { showDrawer = true }) { Icon(Icons.Default.Menu, "Menu") }
      Text("AIO Pulse", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
    }

    // Messages
    LazyColumn(
      state = listState,
      modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      contentPadding = PaddingValues(vertical = 8.dp),
    ) {
      items(messages, key = { it.id }) { msg ->
        MessageBubble(msg, onReport = if (msg.role == Role.ASSISTANT) {{ reportMessageId = msg.id }} else null, showThinking = showThinking)
      }
    }

    // Status
    AnimatedVisibility(visible = status != null) {
      StatusBar(status ?: "")
    }

    // Compose bar — flush to keyboard, no extra padding
    val ctx = LocalContext.current
    val voiceManager = viewModel.voiceManager
    val isListening by voiceManager.isListening.collectAsState()
    val voiceResult by voiceManager.result.collectAsState()
    var text by remember { mutableStateOf("") }

    // Auto-fill from voice result
    LaunchedEffect(voiceResult) {
      voiceResult?.let { text = it; voiceManager.consumeResult() }
    }

    // File picker
    var pendingImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingApiImages by remember { mutableStateOf<List<String>>(emptyList()) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
      uri?.let {
        val attachment = viewModel.attachmentProcessor.process(it)
        if (attachment != null) {
          if (attachment.type == "image") {
            pendingImages = pendingImages + (attachment.displayPath ?: attachment.content)
            pendingApiImages = pendingApiImages + attachment.content
          } else {
            text = "[File: ${attachment.name}]\n${attachment.content}\n" + text
          }
        }
      }
    }

    Row(
      Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = { filePicker.launch("*/*") }) {
        Icon(Icons.Default.AttachFile, "Attach", tint = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.weight(1f),
        placeholder = { Text("Message") },
        shape = RoundedCornerShape(24.dp),
        maxLines = 4,
        enabled = !isStreaming,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = {
          if (text.isNotBlank() || pendingImages.isNotEmpty()) { viewModel.send(text.trim(), pendingApiImages, pendingImages); text = ""; pendingImages = emptyList(); pendingApiImages = emptyList(); scope.launch { listState.animateScrollToItem(messages.size) } }
        }),
      )
      Spacer(Modifier.width(2.dp))
      if (text.isBlank()) {
        // Mic button with permission request
        val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
          if (granted) voiceManager.startListening()
        }
        IconButton(onClick = {
          if (isListening) voiceManager.stopListening()
          else {
            if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
              voiceManager.startListening()
            } else {
              micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
            }
          }
        }, enabled = !isStreaming) {
          Icon(if (isListening) Icons.Default.MicOff else Icons.Default.Mic, "Voice", tint = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
      } else {
        IconButton(onClick = { viewModel.send(text.trim(), pendingApiImages, pendingImages); text = ""; pendingImages = emptyList(); pendingApiImages = emptyList(); scope.launch { listState.animateScrollToItem(messages.size) } }, enabled = !isStreaming) {
          Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = MaterialTheme.colorScheme.primary)
        }
      }
    }
  } // Column
  } // Box
}

@Composable
private fun MessageBubble(msg: com.xnet.pulse.core.model.ChatMessage, onReport: (() -> Unit)? = null, showThinking: Boolean = true) {
  val isUser = msg.role == Role.USER
  val alignment = if (isUser) Alignment.End else Alignment.Start
  val bgColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
  val shape = RoundedCornerShape(16.dp, 16.dp, if (isUser) 4.dp else 16.dp, if (isUser) 16.dp else 4.dp)

  Column(Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
    Surface(shape = shape, color = bgColor, modifier = Modifier.widthIn(max = if (isUser) 300.dp else 10000.dp).fillMaxWidth(if (isUser) 0.8f else 0.95f)) {
      Column(Modifier.padding(12.dp)) {
        // Render attached images (aiope2 approach: AndroidView for stability)
        if (msg.imagePaths.isNotEmpty()) {
          Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            msg.imagePaths.filter { it.isNotBlank() }.forEach { uri ->
              val imgCtx = LocalContext.current
              val bmp = remember(uri) {
                try {
                  if (uri.startsWith("file://")) {
                    android.graphics.BitmapFactory.decodeFile(uri.removePrefix("file://"))
                  } else if (uri.startsWith("http://") || uri.startsWith("https://")) {
                    java.net.URL(uri).openStream().use { android.graphics.BitmapFactory.decodeStream(it) }
                  } else if (uri.startsWith("content://")) {
                    android.provider.MediaStore.Images.Media.getBitmap(imgCtx.contentResolver, android.net.Uri.parse(uri))
                  } else {
                    android.graphics.BitmapFactory.decodeFile(uri)
                  }
                } catch (_: Exception) { null }
              }
              val isGenerated = uri.startsWith("file://") && uri.contains("/generated/")
              val imgSize = if (isGenerated) 256.dp else 64.dp
              if (bmp != null) {
                androidx.compose.ui.viewinterop.AndroidView(factory = { c ->
                  android.widget.ImageView(c).apply {
                    scaleType = if (isGenerated) android.widget.ImageView.ScaleType.FIT_CENTER else android.widget.ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(bmp)
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                      override fun getOutline(v: android.view.View, o: android.graphics.Outline) {
                        o.setRoundRect(0, 0, v.width, v.height, 24f)
                      }
                    }
                  }
                }, modifier = Modifier.size(imgSize))
              }
            }
          }
        }
        if (showThinking && msg.reasoning.isNotBlank()) {
          val isActive = msg.status == MessageStatus.STREAMING && msg.content.isBlank()
          var expanded by remember { mutableStateOf(isActive) }
          LaunchedEffect(msg.status) { if (msg.status != MessageStatus.STREAMING) expanded = false }
          Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            onClick = { expanded = !expanded },
          ) {
            Column(Modifier.padding(8.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                if (isActive) {
                  val alpha by rememberInfiniteTransition(label = "tp").animateFloat(0.4f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "ta")
                  Text("Thinking", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary.copy(alpha = alpha))
                } else {
                  Text("Thought", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "Toggle", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              AnimatedVisibility(visible = expanded || isActive) {
                val lines = msg.reasoning.lines()
                val display = if (isActive && lines.size > 6) lines.takeLast(6).joinToString("\n") else msg.reasoning
                SelectionContainer {
                  Text(display, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.padding(top = 4.dp).heightIn(max = 120.dp).verticalScroll(rememberScrollState()), lineHeight = 16.sp)
                }
              }
            }
          }
        }
        val content = msg.content.ifBlank { if (msg.status == MessageStatus.STREAMING && msg.reasoning.isBlank()) "..." else "" }
        if (msg.toolsUsed.isNotEmpty()) {
          Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            msg.toolsUsed.forEach { tool ->
              Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)) {
                Text(toolIcon(tool), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.primary)
              }
            }
          }
        }
        when {
          content.isBlank() -> {}
          isUser -> Text(content, style = MaterialTheme.typography.bodyMedium)
          msg.status == MessageStatus.STREAMING -> StreamingText(content)
          else -> {
            val uiPattern = Regex("""```aiope-ui\s*\n([\s\S]*?)```""")
            val uiMatches = uiPattern.findAll(content).toList()
            val mdContent = uiPattern.replace(content, "").trim()
            if (mdContent.isNotBlank()) {
              com.fluid.compose.UniversalMarkdown(content = mdContent, animateStreaming = false, modifier = Modifier.fillMaxWidth(), onImageContent = { url, alt ->
                val resolved = when {
                  url.startsWith("file://") -> java.io.File(url.removePrefix("file://"))
                  url.startsWith("/") -> java.io.File("${LocalContext.current.filesDir}/pulse$url")
                  else -> url
                }
                var failed by remember { mutableStateOf(false) }
                if (!failed) {
                  coil.compose.AsyncImage(
                    model = coil.request.ImageRequest.Builder(LocalContext.current).data(resolved).crossfade(true)
                      .listener(onError = { _, _ -> failed = true }).build(),
                    contentDescription = alt,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                  )
                }
              })
            }
            uiMatches.forEach { match ->
              val json = match.groupValues[1].trim()
              val node = com.xnet.pulse.feature.chat.dynamicui.AiopeUiParser.parse(json)
              if (node != null) {
                com.xnet.pulse.feature.chat.dynamicui.AiopeUiRenderer(node = node, isInteractive = true, onCallback = { _, _ -> })
              }
            }
          }
        }
      }
    }
    if (onReport != null) {
      TextButton(onClick = onReport, modifier = Modifier.padding(start = 4.dp)) {
        Text("Report", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
      }
    }
  }
}

@Composable
private fun StatusBar(label: String) {
  val alpha by rememberInfiniteTransition(label = "pulse").animateFloat(
    initialValue = 0.4f, targetValue = 1f,
    animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha",
  )
  Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.alpha(alpha))
  }
}

/** Lightweight pre-renderer for streaming — no remember() so it updates every recomposition */
@Composable
private fun StreamingText(content: String) {
  val codeBg = Color(0xFF1E1E1E)
  val inlineBg = Color(0xFF2D2D2D)

  // Don't render images during streaming — they'll render once message is complete
  val imgPattern = remember { Regex("""!\[([^\]]*)\]\(([^)]+)\)""") }
  val textContent = imgPattern.replace(content, "[\u200B]") // placeholder

  val annotated = buildAnnotatedString {
    var i = 0
    while (i < textContent.length) {
      when {
        textContent.startsWith("```", i) -> {
          val end = textContent.indexOf("```", i + 3)
          val block = if (end != -1) textContent.substring(i + 3, end) else textContent.substring(i + 3)
          val code = block.substringAfter('\n', block)
          withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, background = codeBg)) { append(code) }
          i = if (end != -1) end + 3 else textContent.length
        }
        textContent[i] == '`' -> {
          val end = textContent.indexOf('`', i + 1)
          if (end != -1) { withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = inlineBg)) { append(textContent.substring(i + 1, end)) }; i = end + 1 }
          else { append('`'); i++ }
        }
        textContent.startsWith("**", i) -> {
          val end = textContent.indexOf("**", i + 2)
          if (end != -1) { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(textContent.substring(i + 2, end)) }; i = end + 2 }
          else { append("**"); i += 2 }
        }
        textContent.startsWith("### ", i) -> { i += 4 }
        textContent.startsWith("## ", i) -> { i += 3 }
        textContent.startsWith("# ", i) -> { i += 2 }
        textContent.startsWith("- ", i) -> { append("• "); i += 2 }
        else -> { append(textContent[i]); i++ }
      }
    }
  }
  Text(annotated, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun SettingsSheet() {
  val ctx = LocalContext.current
  val prefs = remember { com.xnet.pulse.feature.chat.theme.ThemePrefs(ctx) }
  val scope = rememberCoroutineScope()
  val themeMode by prefs.themeMode.collectAsState(initial = "dark")
  var autoRead by remember { mutableStateOf(false) }
  val showThinking by prefs.showThinking.collectAsState(initial = true)

  Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Settings", style = MaterialTheme.typography.titleLarge)
    HorizontalDivider()
    Text("Theme", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      listOf("light", "dark", "system", "custom").forEach { mode ->
        FilterChip(
          selected = themeMode == mode,
          onClick = { scope.launch { prefs.set(com.xnet.pulse.feature.chat.theme.ThemePrefs.THEME_MODE, mode) } },
          label = { Text(mode.replaceFirstChar { it.uppercase() }) },
        )
      }
    }
    HorizontalDivider()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text("Auto-read responses", Modifier.weight(1f))
      Switch(checked = autoRead, onCheckedChange = { autoRead = it })
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text("Show thinking", Modifier.weight(1f))
      Switch(checked = showThinking, onCheckedChange = { scope.launch { prefs.set(com.xnet.pulse.feature.chat.theme.ThemePrefs.SHOW_THINKING, it) } })
    }
    HorizontalDivider()
    Text("AIO Pulse v1.0.0\nBy XNet NGO", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(16.dp))
  }
}

private fun toolIcon(name: String): String = when (name) {
  "search_web" -> "Search"
  "search_images" -> "Images"
  "fetch_url" -> "Fetch"
  "write_file" -> "Write"
  "read_file" -> "Read"
  "list_directory" -> "Files"
  "get_location" -> "Location"
  "image_generate" -> "Generate"
  "memory_store" -> "Remember"
  "memory_recall" -> "Recall"
  "open_intent" -> "Open"
  else -> name
}
