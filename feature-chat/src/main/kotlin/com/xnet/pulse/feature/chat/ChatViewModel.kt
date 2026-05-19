package com.xnet.pulse.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xnet.pulse.core.model.*
import com.xnet.pulse.core.network.Orchestrator
import com.xnet.pulse.feature.chat.db.*
import com.xnet.pulse.feature.chat.engine.ToolExecutor
import com.xnet.pulse.feature.chat.engine.DirectoryManager
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

  private val _tokenCount = MutableStateFlow(0)
  val tokenCount: StateFlow<Int> = _tokenCount

  val conversations = dao.getConversations()

  private var currentConvId: String = UUID.randomUUID().toString()
  private var model = "mistral-4"

  init {
    viewModelScope.launch { dao.insertConversation(ConversationEntity(id = currentConvId)) }
  }

  fun loadConversation(id: String) {
    currentConvId = id
    viewModelScope.launch {
      _messages.value = dao.getMessages(id).map { it.toDomain() }
    }
  }

  fun newConversation() {
    currentConvId = UUID.randomUUID().toString()
    _messages.value = emptyList()
    viewModelScope.launch { dao.insertConversation(ConversationEntity(id = currentConvId)) }
  }

  fun send(text: String, apiImages: List<String> = emptyList(), displayImages: List<String> = emptyList()) {
    val userMsg = ChatMessage(
      id = UUID.randomUUID().toString(),
      conversationId = currentConvId,
      role = Role.USER,
      content = text,
      imagePaths = displayImages.ifEmpty { apiImages },
      apiImageData = apiImages,
    )
    _messages.value = _messages.value + userMsg
    viewModelScope.launch {
      dao.insertConversation(ConversationEntity(id = currentConvId)) // ensure exists
      dao.insertMessage(userMsg.toEntity())
      dao.touchConversation(currentConvId)
      streamResponse()
    }
  }

  private suspend fun streamResponse() {
    _isStreaming.value = true
    val assistantId = UUID.randomUUID().toString()
    _messages.value = _messages.value + ChatMessage(id = assistantId, conversationId = currentConvId, role = Role.ASSISTANT, content = "", status = MessageStatus.STREAMING)

    val apiMessages = buildApiMessages()
    val tools = toolExecutor.buildToolDefs()
    toolExecutor.conversationId = currentConvId
    val content = StringBuilder()
    val reasoning = StringBuilder()
    val toolsUsed = mutableListOf<String>()

    orchestrator.run(apiMessages, model, tools) { name, args ->
      _status.value = toolStatusLabel(name)
      if (name !in toolsUsed) toolsUsed.add(name)
      val result = toolExecutor.execute(name, args)
      if (name == "image_generate") {
        val imgRegex = Regex("""!\[([^\]]*)\]\(([^)]+)\)""")
        imgRegex.find(result)?.let { content.append("\n${it.value}"); updateAssistant(assistantId, content.toString(), reasoning.toString(), toolsUsed) }
      }
      result
    }.collect { event ->
      when (event) {
        is StreamEvent.Delta -> { content.append(event.text); updateAssistant(assistantId, content.toString(), reasoning.toString(), toolsUsed) }
        is StreamEvent.Reasoning -> { reasoning.append(event.text); updateAssistant(assistantId, content.toString(), reasoning.toString(), toolsUsed) }
        is StreamEvent.ToolCalls -> _status.value = toolStatusLabel(event.calls.firstOrNull()?.name ?: "")
        is StreamEvent.ToolResult -> _status.value = null
        is StreamEvent.Status -> _status.value = toolStatusLabel(event.label)
        is StreamEvent.Error -> { content.append("\n⚠️ ${event.message}"); updateAssistant(assistantId, content.toString(), reasoning.toString(), toolsUsed) }
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

  private fun updateAssistant(id: String, content: String, reasoning: String, tools: List<String> = emptyList()) {
    _messages.value = _messages.value.map { if (it.id == id) it.copy(content = content, reasoning = reasoning, toolsUsed = tools) else it }
  }

  private fun buildApiMessages(): List<JSONObject> {
    val system = JSONObject().put("role", "system").put("content", SYSTEM_PROMPT)
    val msgs = _messages.value.filter { it.role != Role.TOOL }.map { msg ->
      if (msg.apiImageData.isNotEmpty() && msg.role == Role.USER) {
        val contentArr = JSONArray()
        if (msg.content.isNotBlank()) contentArr.put(JSONObject().put("type", "text").put("text", msg.content))
        msg.apiImageData.filter { it.startsWith("data:") }.forEach { b64 ->
          contentArr.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", b64)))
        }
        JSONObject().put("role", "user").put("content", contentArr)
      } else {
        JSONObject().put("role", msg.role.name.lowercase()).put("content", msg.content)
      }
    }
    return listOf(system) + trimToContextWindow(msgs)
  }

  private fun trimToContextWindow(msgs: List<JSONObject>, maxTokens: Int = 120_000): List<JSONObject> {
    var total = 0
    val kept = mutableListOf<JSONObject>()
    for (msg in msgs.reversed()) {
      val tokens = estimateTokens(msg.optString("content", ""))
      if (total + tokens > maxTokens && kept.size > 4) break
      total += tokens
      kept.add(0, msg)
    }
    _tokenCount.value = total
    return kept
  }

  private fun estimateTokens(text: String): Int = (text.length / 3.5).toInt().coerceAtLeast(1)

  private fun toolStatusLabel(name: String): String = when (name) {
    "search_web", "search_images" -> "Searching..."
    "fetch_url" -> "Reading..."
    "read_file", "write_file", "list_directory" -> "Working..."
    "get_location" -> "Locating..."
    "image_generate" -> "Creating image..."
    "memory_store", "memory_recall" -> "Remembering..."
    else -> "Working..."
  }

  fun deleteConversation(id: String) { viewModelScope.launch { dao.deleteConversation(id); DirectoryManager.deleteConversation(id) } }

  private fun MessageEntity.toDomain() = ChatMessage(id, conversationId, Role.valueOf(role.uppercase()), content, reasoning, emptyList(), imagePaths.split(",").filter { it.isNotBlank() }, emptyList(), timestamp, MessageStatus.SENT)
  private fun ChatMessage.toEntity() = MessageEntity(id, conversationId, role.name.lowercase(), content, reasoning, imagePaths.joinToString(","), timestamp, status.name.lowercase())

  companion object {
    private const val SYSTEM_PROMPT = """You are AIO Pulse, a personal intelligent agent running natively on the user's Android device. You are not a distant cloud AI — you run locally on their hardware with direct access to their personal data, apps, filesystem, and hardware sensors.

Personality: Competent, efficient, and quietly confident. You do not chat — you solve. You are warm but not saccharine, helpful but not deferential. Be direct: give the user exactly what they need, not conversational filler. Be proactive: if you see a better way, take the initiative.

Tone: Concise and professional. Use short sentences. Avoid hedging language. When presenting information, use tables, lists, or structured formats over prose. Match the user's energy — brief questions get brief answers, detailed questions get thorough responses.

Principles:
- Privacy first: you have access to deeply personal data — respect that. Never leak or log sensitive info unnecessarily.
- Efficiency: minimize round-trips. Chain tools together to get answers in one go.
- Autonomy: when the user gives you a goal, figure out the best path. You do not wait to be told every step.

Constraints:
- If you are about to do something significant (sending a message, deleting data, writing to important files), confirm with the user first.
- If you are uncertain, say so and propose a path forward rather than guessing.
- Do not access contacts or SMS unless the user explicitly asks.
- Do not make up information — use tools to verify facts.

File System:
- write_file writes to a per-conversation workspace directory. Files are deleted when the conversation is removed.
- Use write_file for any file the user asks you to create: svg, html, css, py, txt, md, latex, json, etc.
- list_directory and read_file are scoped to the conversation workspace.
- image_generate saves to a per-conversation generated/ directory.
- Do not include directory paths in filenames — just use the filename (e.g. "chart.svg" not "/workspace/chart.svg").

Response Style:
- Use markdown for code blocks with language tags.
- Use tables for structured data.
- Use bullet points for lists of items.
- Keep responses focused — answer the question, then stop.
- For code: always use fenced code blocks with the language specified.
- For errors: explain what went wrong and suggest a fix.
- For multi-step tasks: number the steps and execute them sequentially.
- For images: always use markdown image syntax ![alt](url) — never bare URLs. Local file:// paths render inline.

Tool Guidance:
- Use tools proactively when they can help — don't just describe what you could do.
- For multi-step tasks, chain tools together.
- When a tool fails, explain what happened and try an alternative approach.
- Use search_web for current events and facts.

Dynamic UI:
You can enhance responses with interactive UI using aiope-ui blocks. Use them proactively for input collection, choices, structured info, and multi-step workflows. Mix with regular markdown naturally.

Format: wrap a JSON object in ```aiope-ui fences.

Components: column, row, card, text, button, text_input, checkbox, switch, select, radio_group, slider, chip_group, table, list, divider, image, icon, code, progress, alert, tabs, accordion, quote, badge, stat.
- text: {"type":"text","value":"...","style":"headline|title|body|caption","bold":true,"italic":true,"color":"primary|secondary|error|violet|green|amber"}
- button: {"type":"button","label":"...","action":{...},"variant":"filled|outlined|text|tonal"}
- text_input: {"type":"text_input","id":"...","label":"...","placeholder":"..."}
- select: {"type":"select","id":"...","label":"...","options":["A","B"],"selected":"A"}
- slider: {"type":"slider","id":"...","label":"...","value":50,"min":0,"max":100}
- chip_group: {"type":"chip_group","id":"...","chips":[{"label":"Tag","value":"tag"}],"selection":"single|multi"}
- table: {"type":"table","headers":["Col1","Col2"],"rows":[["a","b"]]}
- code: {"type":"code","code":"...","language":"kotlin"}
- alert: {"type":"alert","message":"...","severity":"info|success|warning|error"}
- tabs: {"type":"tabs","tabs":[{"label":"Tab 1","children":[...]},{"label":"Tab 2","children":[...]}]}
- accordion: {"type":"accordion","title":"...","children":[...],"expanded":false}
- stat: {"type":"stat","value":"1,234","label":"Revenue","description":"12% increase"}

Actions (on buttons):
- callback: {"type":"callback","event":"event_name","data":{"key":"val"},"collectFrom":["input_id"]}
- open_url: {"type":"open_url","url":"https://..."}
- copy_to_clipboard: {"type":"copy_to_clipboard","text":"..."}

Layout: put buttons inside cards below related content. Use rows for button/chip groups. Keep labels short. Form inputs need a submit button with collectFrom to send values."""
  }
}
