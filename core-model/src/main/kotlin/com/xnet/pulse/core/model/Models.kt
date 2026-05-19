package com.xnet.pulse.core.model

data class Conversation(
  val id: String,
  val title: String = "New Chat",
  val agentName: String = "default",
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
)

data class ChatMessage(
  val id: String,
  val conversationId: String,
  val role: Role,
  val content: String,
  val reasoning: String = "",
  val toolsUsed: List<String> = emptyList(),
  val imagePaths: List<String> = emptyList(),
  val apiImageData: List<String> = emptyList(),
  val timestamp: Long = System.currentTimeMillis(),
  val status: MessageStatus = MessageStatus.SENT,
)

enum class Role { USER, ASSISTANT, SYSTEM, TOOL }
enum class MessageStatus { SENT, QUEUED, FAILED, STREAMING }

data class Memory(
  val key: String,
  val content: String,
  val category: String = "general",
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
)

data class ToolDef(
  val name: String,
  val description: String,
  val parameters: String, // JSON schema string
  val parallelSafe: Boolean = false,
)

data class ToolCallInfo(
  val id: String,
  val name: String,
  val arguments: Map<String, Any?>,
  val rawArgs: String = "",
)

data class ToolResultInfo(
  val id: String,
  val name: String,
  val result: String,
  val isError: Boolean = false,
)

sealed interface StreamEvent {
  data class Delta(val text: String) : StreamEvent
  data class Reasoning(val text: String) : StreamEvent
  data class ToolCalls(val calls: List<ToolCallInfo>) : StreamEvent
  data class ToolResult(val result: ToolResultInfo) : StreamEvent
  data class Error(val message: String) : StreamEvent
  data class Done(val finishReason: String = "stop") : StreamEvent
  data class Status(val label: String) : StreamEvent
}
