package com.xnet.pulse.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xnet.pulse.core.model.*
import com.xnet.pulse.core.network.Orchestrator
import com.xnet.pulse.feature.chat.db.*
import com.xnet.pulse.feature.chat.engine.ToolExecutor
import com.xnet.pulse.feature.chat.engine.VoiceManager
import com.xnet.pulse.feature.chat.engine.AttachmentProcessor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
  private val dao: ChatDao,
  private val orchestrator: Orchestrator,
  private val toolExecutor: ToolExecutor,
  val voiceManager: VoiceManager,
  val attachmentProcessor: AttachmentProcessor,
) : ViewModel() {

  private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
  val messages: StateFlow<List<ChatMessage>> = _messages

  private val _status = MutableStateFlow<String?>(null)
  val status: StateFlow<String?> = _status

  private val _isStreaming = MutableStateFlow(false)
  val isStreaming: StateFlow<Boolean> = _isStreaming

  val conversations = dao.getConversations()

  private var currentConvId: String = UUID.randomUUID().toString()
  private var model = "mistral-4"

  init {
    viewModelScope.launch { dao.insertConversation(ConversationEntity(id = currentConvId)) }
  }

  fun loadConversation(id: String) {
    currentConvId = id
    viewModelScope.launch {
      val msgs = dao.getMessages(id).map { it.toDomain() }
      _messages.value = msgs
    }
  }

  fun newConversation() {
    currentConvId = UUID.randomUUID().toString()
    _messages.value = emptyList()
    viewModelScope.launch {
      dao.insertConversation(ConversationEntity(id = currentConvId))
    }
  }

  fun send(text: String, imagePaths: List<String> = emptyList()) {
    val userMsg = ChatMessage(
      id = UUID.randomUUID().toString(),
      conversationId = currentConvId,
      role = Role.USER,
      content = text,
      imagePaths = imagePaths,
    )
    _messages.value = _messages.value + userMsg
    viewModelScope.launch {
      dao.insertMessage(userMsg.toEntity())
      dao.touchConversation(currentConvId)
      streamResponse()
    }
  }

  private suspend fun streamResponse() {
    _isStreaming.value = true
    val assistantId = UUID.randomUUID().toString()
    val assistantMsg = ChatMessage(id = assistantId, conversationId = currentConvId, role = Role.ASSISTANT, content = "", status = MessageStatus.STREAMING)
    _messages.value = _messages.value + assistantMsg

    val apiMessages = buildApiMessages()
    val tools = toolExecutor.buildToolDefs()
    val content = StringBuilder()
    val reasoning = StringBuilder()

    orchestrator.run(apiMessages, model, tools) { name, args ->
      _status.value = toolStatusLabel(name)
      toolExecutor.execute(name, args)
    }.collect { event ->
      when (event) {
        is StreamEvent.Delta -> {
          content.append(event.text)
          updateAssistant(assistantId, content.toString(), reasoning.toString())
        }
        is StreamEvent.Reasoning -> {
          reasoning.append(event.text)
          updateAssistant(assistantId, content.toString(), reasoning.toString())
        }
        is StreamEvent.ToolCalls -> _status.value = toolStatusLabel(event.calls.firstOrNull()?.name ?: "")
        is StreamEvent.ToolResult -> _status.value = null
        is StreamEvent.Status -> _status.value = event.label
        is StreamEvent.Error -> {
          content.append("\n⚠️ ${event.message}")
          updateAssistant(assistantId, content.toString(), reasoning.toString())
        }
        is StreamEvent.Done -> _status.value = null
      }
    }

    _isStreaming.value = false
    _status.value = null
    val final = _messages.value.find { it.id == assistantId }?.copy(status = MessageStatus.SENT)
    if (final != null) {
      _messages.value = _messages.value.map { if (it.id == assistantId) final else it }
      dao.insertMessage(final.toEntity())
    }
  }

  private fun updateAssistant(id: String, content: String, reasoning: String) {
    _messages.value = _messages.value.map {
      if (it.id == id) it.copy(content = content, reasoning = reasoning) else it
    }
  }

  private fun buildApiMessages(): List<JSONObject> {
    val system = JSONObject().put("role", "system").put("content", "You are AIO Pulse, a helpful AI assistant. Use tools when needed. Be concise and helpful.")
    return listOf(system) + _messages.value.filter { it.role != Role.TOOL }.map { msg ->
      JSONObject().put("role", msg.role.name.lowercase()).put("content", msg.content)
    }
  }

  private fun toolStatusLabel(name: String): String = when (name) {
    "search_web", "search_images" -> "Searching..."
    "fetch_url" -> "Reading..."
    "read_file", "write_file", "list_directory" -> "Working..."
    "get_location" -> "Locating..."
    "read_calendar" -> "Checking schedule..."
    "create_event" -> "Creating event..."
    "set_alarm" -> "Setting alarm..."
    "image_generate" -> "Creating image..."
    "analyze_image" -> "Looking at this..."
    "memory_store", "memory_recall" -> "Remembering..."
    else -> "Working..."
  }

  fun deleteConversation(id: String) { viewModelScope.launch { dao.deleteConversation(id) } }

  private fun MessageEntity.toDomain() = ChatMessage(id, conversationId, Role.valueOf(role.uppercase()), content, reasoning, imagePaths.split(",").filter { it.isNotBlank() }, timestamp, MessageStatus.SENT)
  private fun ChatMessage.toEntity() = MessageEntity(id, conversationId, role.name.lowercase(), content, reasoning, imagePaths.joinToString(","), timestamp, status.name.lowercase())
}
