package com.xnet.pulse.core.network

import com.xnet.pulse.core.model.StreamEvent
import com.xnet.pulse.core.model.ToolCallInfo
import com.xnet.pulse.core.model.ToolDef
import com.xnet.pulse.core.model.ToolResultInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Orchestrator @Inject constructor(
  private val client: PollinationsClient,
) {
  companion object {
    private const val MAX_ROUNDS = 140
    private const val MAX_RESULT_LEN = 16000
    private const val TRIM_AFTER = 3
    private const val TRIM_LEN = 500
    private val PARALLEL_SAFE = setOf(
      "read_file", "list_directory", "fetch_url", "search_web", "search_images",
      "memory_recall", "memory_store", "get_location", "image_generate",
    )
  }

  fun run(
    messages: List<JSONObject>,
    model: String,
    tools: List<ToolDef>,
    executor: suspend (String, Map<String, Any?>) -> String,
  ): Flow<StreamEvent> = flow {
    val raw = messages.toMutableList()
    val toolJson = tools.map { td ->
      JSONObject().put("type", "function").put("function",
        JSONObject().put("name", td.name).put("description", td.description)
          .put("parameters", JSONObject(td.parameters)))
    }

    var lastToolKey = ""
    var sameCount = 0

    for (round in 0 until MAX_ROUNDS) {
      trimToolResults(raw)

      val events = mutableListOf<StreamEvent>()
      client.stream(raw, model, toolJson).collect { ev ->
        events.add(ev)
        emit(ev)
      }

      // Find tool calls
      val tcEvent = events.filterIsInstance<StreamEvent.ToolCalls>().lastOrNull() ?: break

      // Loop detection
      val key = "${tcEvent.calls.firstOrNull()?.name}:${tcEvent.calls.firstOrNull()?.rawArgs}"
      if (key == lastToolKey) { sameCount++; if (sameCount >= 3) { emit(StreamEvent.Done("tool_loop")); return@flow } }
      else { lastToolKey = key; sameCount = 1 }

      // Append assistant message with tool_calls
      raw.add(JSONObject().apply {
        put("role", "assistant")
        put("content", JSONObject.NULL)
        put("tool_calls", JSONArray().apply {
          tcEvent.calls.forEach { c ->
            put(JSONObject().put("id", c.id).put("type", "function")
              .put("function", JSONObject().put("name", c.name).put("arguments", c.rawArgs)))
          }
        })
      })

      // Execute tools — emit status so UI updates before blocking on execution
      emit(StreamEvent.Status(tcEvent.calls.firstOrNull()?.name ?: ""))
      val results = executeCalls(tcEvent.calls, executor)
      for (r in results) {
        emit(StreamEvent.ToolResult(r))
        val content = if (r.result.length > MAX_RESULT_LEN) r.result.take(MAX_RESULT_LEN) + "\n...(truncated)" else r.result
        raw.add(JSONObject().put("role", "tool").put("tool_call_id", r.id).put("content", content))
      }
    }
  }

  private suspend fun executeCalls(
    calls: List<ToolCallInfo>,
    executor: suspend (String, Map<String, Any?>) -> String,
  ): List<ToolResultInfo> {
    if (calls.size == 1) {
      val c = calls[0]
      val result = try { executor(c.name, c.arguments) } catch (e: Exception) { "Error: ${e.message}" }
      return listOf(ToolResultInfo(id = c.id, name = c.name, result = result.ifBlank { "(empty)" }))
    }
    // Multiple calls — run concurrently
    return coroutineScope {
      calls.map { c ->
        async(Dispatchers.IO) {
          val result = try { executor(c.name, c.arguments) } catch (e: Exception) { "Error: ${e.message}" }
          ToolResultInfo(id = c.id, name = c.name, result = result.ifBlank { "(empty)" })
        }
      }.map { it.await() }
    }
  }

  private fun trimToolResults(msgs: MutableList<JSONObject>) {
    val toolIdxs = msgs.indices.filter { msgs[it].optString("role") == "tool" }
    if (toolIdxs.size <= TRIM_AFTER) return
    for (i in toolIdxs.dropLast(TRIM_AFTER)) {
      val content = msgs[i].optString("content", "")
      if (content.length > TRIM_LEN) msgs[i].put("content", content.take(TRIM_LEN) + "...(truncated)")
    }
  }
}
