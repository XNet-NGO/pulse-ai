package com.xnet.pulse.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
  var reportMessageId by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(messages.size) {
    if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
  }

  // Report dialog
  if (reportMessageId != null) {
    AlertDialog(
      onDismissRequest = { reportMessageId = null },
      title = { Text("Report Message") },
      text = { Text("Flag this response as inappropriate or harmful?") },
      confirmButton = { TextButton(onClick = { reportMessageId = null /* TODO: log report */ }) { Text("Report") } },
      dismissButton = { TextButton(onClick = { reportMessageId = null }) { Text("Cancel") } },
    )
  }

  // Conversation drawer
  if (showDrawer) {
    ModalBottomSheet(onDismissRequest = { showDrawer = false }) {
      Column(Modifier.padding(16.dp)) {
        TextButton(onClick = { viewModel.newConversation(); showDrawer = false }) { Text("+ New Chat") }
        conversations.forEach { conv ->
          ListItem(
            headlineContent = { Text(conv.title) },
            modifier = Modifier.clickable { viewModel.loadConversation(conv.id); showDrawer = false },
            trailingContent = {
              IconButton(onClick = { viewModel.deleteConversation(conv.id) }) {
                Icon(Icons.Default.Delete, "Delete")
              }
            },
          )
        }
      }
    }
  }

  Column(Modifier.fillMaxSize().imePadding()) {
    // Top bar
    Surface(tonalElevation = 2.dp) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { showDrawer = true }) { Icon(Icons.Default.Menu, "Conversations") }
        Text("AIO Pulse", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        var menuExpanded by remember { mutableStateOf(false) }
        Box {
          IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.MoreVert, "More") }
          DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(text = { Text("Attach file") }, onClick = { menuExpanded = false /* TODO: launch picker */ }, leadingIcon = { Icon(Icons.Default.AttachFile, null) })
            DropdownMenuItem(text = { Text("Library") }, onClick = { menuExpanded = false /* TODO: nav to library */ }, leadingIcon = { Icon(Icons.Default.Folder, null) })
            DropdownMenuItem(text = { Text("New chat") }, onClick = { menuExpanded = false; viewModel.newConversation() }, leadingIcon = { Icon(Icons.Default.Add, null) })
          }
        }
      }
    }

    // Message list
    LazyColumn(
      state = listState,
      modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      contentPadding = PaddingValues(vertical = 12.dp),
    ) {
      items(messages, key = { it.id }) { msg ->
        MessageBubble(msg, onReport = if (msg.role == Role.ASSISTANT) {{ reportMessageId = msg.id }} else null)
      }
    }

    // Status animation
    AnimatedVisibility(visible = status != null) {
      StatusBar(status ?: "")
    }

    // Compose bar (floats above keyboard via imePadding on parent)
    ComposeBar(
      enabled = !isStreaming,
      onSend = { text ->
        viewModel.send(text)
        scope.launch { listState.animateScrollToItem(messages.size) }
      },
    )
  }
}

@Composable
private fun MessageBubble(msg: com.xnet.pulse.core.model.ChatMessage, onReport: (() -> Unit)? = null) {
  val isUser = msg.role == Role.USER
  val alignment = if (isUser) Alignment.End else Alignment.Start
  val bgColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
  val shape = RoundedCornerShape(16.dp, 16.dp, if (isUser) 4.dp else 16.dp, if (isUser) 16.dp else 4.dp)

  Column(Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
    Surface(shape = shape, color = bgColor, modifier = Modifier.widthIn(max = 320.dp)) {
      Column(Modifier.padding(12.dp)) {
        if (msg.reasoning.isNotBlank()) {
          Text(msg.reasoning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
          Spacer(Modifier.height(4.dp))
        }
        val content = msg.content.ifBlank { if (msg.status == MessageStatus.STREAMING) "..." else "" }
        if (!isUser && content.isNotBlank() && msg.status != MessageStatus.STREAMING) {
          com.fluid.compose.UniversalMarkdown(
            content = content,
            animateStreaming = false,
            modifier = Modifier.fillMaxWidth(),
          )
        } else if (!isUser && content.isNotBlank()) {
          // Lightweight pre-render during streaming (like headless streamHtml)
          StreamingText(content)
        } else {
          Text(content, style = MaterialTheme.typography.bodyMedium)
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
  Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.alpha(alpha))
  }
}

@Composable
private fun ComposeBar(enabled: Boolean, onSend: (String) -> Unit, onMic: () -> Unit = {}, isListening: Boolean = false) {
  var text by remember { mutableStateOf("") }

  Surface(tonalElevation = 3.dp) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.weight(1f),
        placeholder = { Text("Message") },
        shape = RoundedCornerShape(24.dp),
        maxLines = 4,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) { onSend(text.trim()); text = "" } }),
      )
      Spacer(Modifier.width(4.dp))
      if (text.isBlank()) {
        IconButton(onClick = onMic, enabled = enabled) {
          Icon(if (isListening) Icons.Default.MicOff else Icons.Default.Mic, contentDescription = "Voice", tint = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
      } else {
        IconButton(onClick = { onSend(text.trim()); text = "" }, enabled = enabled) {
          Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
        }
      }
    }
  }
}

@Composable
private fun StreamingText(content: String) {
  val codeBg = androidx.compose.ui.graphics.Color(0xFF1E1E1E)
  val inlineBg = androidx.compose.ui.graphics.Color(0xFF2D2D2D)
  val annotated = remember(content) {
    buildAnnotatedString {
      var i = 0
      while (i < content.length) {
        when {
          content.startsWith("```", i) -> {
            val end = content.indexOf("```", i + 3)
            val block = if (end != -1) content.substring(i + 3, end) else content.substring(i + 3)
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, background = codeBg)) { append(block.trimStart('\n')) }
            i = if (end != -1) end + 3 else content.length
          }
          content[i] == '`' -> {
            val end = content.indexOf('`', i + 1)
            if (end != -1) { withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = inlineBg)) { append(content.substring(i + 1, end)) }; i = end + 1 }
            else { append('`'); i++ }
          }
          content.startsWith("**", i) -> {
            val end = content.indexOf("**", i + 2)
            if (end != -1) { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(content.substring(i + 2, end)) }; i = end + 2 }
            else { append("**"); i += 2 }
          }
          else -> { append(content[i]); i++ }
        }
      }
    }
  }
  Text(annotated, style = MaterialTheme.typography.bodyMedium)
}
