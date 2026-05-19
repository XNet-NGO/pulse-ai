package com.xnet.pulse.core.network

import com.xnet.pulse.core.model.StreamEvent
import com.xnet.pulse.core.model.ToolCallInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

@Singleton
class PollinationsClient @Inject constructor() {

  companion object {
    private const val BASE_URL = "https://gen.pollinations.ai/v1"
    private const val IMAGE_URL = "https://image.pollinations.ai/prompt"
    private const val API_KEY = "REDACTED_KEY"
    private val JSON_MT = "application/json; charset=utf-8".toMediaType()
    private val REASONING_TAGS = listOf("think", "thinking", "thought")
    private const val MAX_RETRIES = 3

    private val client: OkHttpClient by lazy {
      val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
      tmf.init(null as java.security.KeyStore?)
      val tm = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
      val ssl = SSLContext.getInstance("TLS").apply { init(emptyArray(), arrayOf(tm), null) }
      try {
        OkHttpClient.Builder()
          .sslSocketFactory(ssl.socketFactory, tm)
          .connectTimeout(15, TimeUnit.SECONDS)
          .readTimeout(5, TimeUnit.MINUTES)
          .writeTimeout(30, TimeUnit.SECONDS)
          .retryOnConnectionFailure(true)
          .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
          .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS)) // fresh connection every request (cellular NAT kills idle)
          .build()
      } catch (_: Exception) {
        OkHttpClient.Builder()
          .connectTimeout(15, TimeUnit.SECONDS)
          .readTimeout(5, TimeUnit.MINUTES)
          .writeTimeout(30, TimeUnit.SECONDS)
          .retryOnConnectionFailure(true)
          .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
          .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
          .build()
      }
    }

    private fun isTransientError(msg: String): Boolean {
      val lower = msg.lowercase()
      return lower.contains("connection reset") ||
        lower.contains("stream was reset") ||
        lower.contains("unexpected end of stream") ||
        lower.contains("broken pipe") ||
        lower.contains("socket closed") ||
        lower.contains("connection abort") ||
        lower.contains("connection shutdown") ||
        lower.contains("failed to connect") ||
        lower.contains("timeout") ||
        lower.contains("enetunreach") ||
        lower.contains("enetdown") ||
        lower.contains("network is unreachable") ||
        lower.contains("software caused connection abort") ||
        lower.contains("recvfrom failed") ||
        lower.contains("connection failed")
    }
  }

  var apiKey: String = API_KEY

  fun stream(
    messages: List<JSONObject>,
    model: String,
    tools: List<JSONObject> = emptyList(),
  ): Flow<StreamEvent> = callbackFlow {
    val body = JSONObject().apply {
      put("model", model)
      put("stream", true)
      put("messages", JSONArray(messages))
      if (tools.isNotEmpty()) put("tools", JSONArray(tools))
    }.toString()

    val contentSoFar = StringBuilder()
    var retries = 0
    var inThinkTag = false
    var thinkTagName = ""
    var sseError: String? = null
    var sseDone = false

    while (true) {
      sseError = null
      sseDone = false
      val request = Request.Builder()
        .url("$BASE_URL/chat/completions")
        .header("Content-Type", "application/json")
        .header("Accept", "text/event-stream")
        .header("Connection", "close") // force fresh TCP
        .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
        .post(body.toRequestBody(JSON_MT))
        .build()

      val toolAcc = mutableMapOf<Int, Triple<String, String, StringBuilder>>()
      val contentBeforeAttempt = contentSoFar.length
      val latch = CountDownLatch(1)

      val es = EventSources.createFactory(client).newEventSource(request, object : EventSourceListener() {
        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
          if (data == "[DONE]") {
            if (toolAcc.isNotEmpty()) {
              trySend(StreamEvent.ToolCalls(buildToolCalls(toolAcc)))
            } else {
              trySend(StreamEvent.Done())
            }
            sseDone = true
            latch.countDown()
            return
          }
          val chunk = try { JSONObject(data) } catch (_: Exception) { return }
          chunk.optJSONObject("error")?.let {
            sseError = it.optString("message", "Unknown error")
            latch.countDown()
            return
          }
          val choices = chunk.optJSONArray("choices") ?: return
          if (choices.length() == 0) return
          val choice = choices.getJSONObject(0)
          val delta = choice.optJSONObject("delta") ?: return
          val fr = choice.optString("finish_reason", "").takeIf { it.isNotBlank() && it != "null" }

          // Tool call accumulation
          delta.optJSONArray("tool_calls")?.let { tcArr ->
            for (i in 0 until tcArr.length()) {
              val tc = tcArr.getJSONObject(i)
              val idx = tc.optInt("index", 0)
              val acc = toolAcc.getOrPut(idx) { Triple("", "", StringBuilder()) }
              val tcId = tc.optString("id", "")
              val fn = tc.optJSONObject("function")
              val name = fn?.optString("name", "") ?: ""
              val args = fn?.optString("arguments", "") ?: ""
              toolAcc[idx] = Triple(
                if (tcId.isNotEmpty()) tcId else acc.first,
                if (name.isNotEmpty()) name else acc.second,
                acc.third.append(args),
              )
            }
          }

          if (fr == "tool_calls" || (fr == "stop" && toolAcc.isNotEmpty())) {
            trySend(StreamEvent.ToolCalls(buildToolCalls(toolAcc)))
            sseDone = true
            latch.countDown()
            return
          }
          if (fr == "stop" || fr == "end_turn" || fr == "length") {
            trySend(StreamEvent.Done(fr))
            sseDone = true
            latch.countDown()
            return
          }

          // Content + reasoning
          var content = delta.optString("content", "").let { if (it == "null") "" else it }
          var reasoning = delta.optString("reasoning_content", "").let { if (it == "null") "" else it }
            .ifBlank { delta.optString("reasoning", "").let { if (it == "null") "" else it } }

          // Think tag extraction
          if (!inThinkTag) {
            for (tag in REASONING_TAGS) {
              val open = "<$tag>"
              if (content.contains(open)) {
                inThinkTag = true
                thinkTagName = tag
                content = content.substringAfter(open)
                break
              }
            }
          }
          if (inThinkTag) {
            val close = "</$thinkTagName>"
            if (content.contains(close)) {
              reasoning = content.substringBefore(close)
              content = content.substringAfter(close)
              inThinkTag = false
            } else {
              reasoning = content
              content = ""
            }
          }

          if (content.isNotEmpty()) contentSoFar.append(content)

          // On retry, skip emitting content we already sent
          val shouldEmit = contentSoFar.length > contentBeforeAttempt || reasoning.isNotEmpty()
          if (shouldEmit) {
            if (content.isNotEmpty()) trySend(StreamEvent.Delta(content))
            if (reasoning.isNotEmpty()) trySend(StreamEvent.Reasoning(reasoning))
          }
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
          val msg = t?.message ?: response?.let { "HTTP ${it.code}" } ?: "Connection failed"
          if (!sseDone) sseError = msg
          latch.countDown()
        }

        override fun onClosed(eventSource: EventSource) { latch.countDown() }
      })

      // Wait up to 180s for stream to complete
      val latchOk = latch.await(180, TimeUnit.SECONDS)
      es.cancel()

      if (!latchOk && !sseDone) sseError = "Stream timeout (180s)"

      // Success
      if (sseError == null || sseDone) break

      // Non-retryable (HTTP 4xx, auth errors)
      if (!isTransientError(sseError!!) || sseError!!.startsWith("HTTP 4")) break

      // Retry with backoff
      if (retries < MAX_RETRIES) {
        retries++
        android.util.Log.w("Pulse", "SSE retry ${retries}/$MAX_RETRIES: $sseError")
        Thread.sleep(1000L * retries)
        continue
      }
      break
    }

    if (sseError != null && !sseDone) {
      val note = if (retries > 0) " (after ${retries + 1} attempts)" else ""
      trySend(StreamEvent.Error("$sseError$note"))
    }
    channel.close()

    awaitClose { }
  }.flowOn(Dispatchers.IO)

  fun imageUrl(prompt: String, width: Int = 1024, height: Int = 1024): String {
    val encoded = java.net.URLEncoder.encode(prompt, "UTF-8")
    return "$IMAGE_URL/$encoded?width=$width&height=$height&nologo=true"
  }

  private fun buildToolCalls(acc: Map<Int, Triple<String, String, StringBuilder>>): List<ToolCallInfo> {
    return acc.values.map { (id, name, argsSb) ->
      val argsStr = argsSb.toString()
      val args: Map<String, Any?> = try {
        val j = JSONObject(argsStr)
        j.keys().asSequence().associateWith { k -> j.opt(k) }
      } catch (_: Exception) { emptyMap() }
      ToolCallInfo(id = id, name = name, arguments = args, rawArgs = argsStr)
    }
  }
}
