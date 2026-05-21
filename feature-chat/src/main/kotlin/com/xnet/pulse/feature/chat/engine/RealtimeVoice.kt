package com.xnet.pulse.feature.chat.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealtimeVoice @Inject constructor(
  @ApplicationContext private val ctx: Context,
  private val toolExecutor: ToolExecutor
) {

  sealed class Event {
    data class TextDelta(val text: String) : Event()
    data class AudioChunk(val pcm: ByteArray) : Event()
    data class InputTranscription(val text: String) : Event()
    data class OutputTranscription(val text: String) : Event()
    data class ToolCall(val name: String, val id: String, val args: JSONObject) : Event()
    data class ToolResult(val name: String, val result: String) : Event()
    data object TurnComplete : Event()
    data class Error(val msg: String) : Event()
    data object Connected : Event()
    data object Disconnected : Event()
  }

  private val http = OkHttpClient.Builder()
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .pingInterval(30, TimeUnit.SECONDS)
    .build()

  private var webSocket: WebSocket? = null
  private var audioRecord: AudioRecord? = null
  private var audioTrack: AudioTrack? = null
  private var echoCanceler: AcousticEchoCanceler? = null
  private var noiseSuppressor: NoiseSuppressor? = null
  private var captureJob: Job? = null
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private val playbackLock = Any()

  private val _isActive = MutableStateFlow(false)
  val isActive: StateFlow<Boolean> = _isActive

  private val _amplitude = MutableStateFlow(0f)
  val amplitude: StateFlow<Float> = _amplitude

  private var connecting = false

  companion object {
    private const val SAMPLE_RATE_IN = 16000
    private const val SAMPLE_RATE_OUT = 24000
    private const val TAG = "RealtimeVoice"
    private const val MODEL = "gemini-3.1-flash-live-preview"
    private const val SYSTEM_PROMPT = """You are Pulse AI, a personal intelligent agent running natively on the user's Android device. You are not a distant cloud AI — you run locally on their hardware with direct access to their personal data, apps, filesystem, and hardware sensors.

Personality: Competent, efficient, and quietly confident. You do not chat — you solve. You are warm but not saccharine, helpful but not deferential. Be direct: give the user exactly what they need, not conversational filler. Be proactive: if you see a better way, take the initiative.

Tone: Concise and conversational — this is a voice call. Use short sentences. Avoid hedging language. Match the user's energy — brief questions get brief answers, detailed questions get thorough responses.

Principles:
- Privacy first: you have access to deeply personal data — respect that. Never leak or log sensitive info unnecessarily.
- Efficiency: minimize round-trips. Chain tools together to get answers in one go.
- Autonomy: when the user gives you a goal, figure out the best path. You do not wait to be told every step.

Constraints:
- If you are about to do something significant (sending a message, deleting data, writing to important files), confirm with the user first.
- If you are uncertain, say so and propose a path forward rather than guessing.
- Do not access contacts or SMS unless the user explicitly asks.
- Do not make up information — use tools to verify facts.

Tool Guidance:
- Use tools proactively when they can help — don't just describe what you could do.
- For multi-step tasks, chain tools together.
- When a tool fails, explain what happened and try an alternative approach.
- Use search_web for current events and facts.
- Report tool results naturally in speech — no markdown, no formatting, no URLs unless asked."""
  }

  fun connect(apiKey: String, voiceName: String = "Aoede", thinkingLevel: String = "minimal"): Flow<Event> = callbackFlow {
    if (connecting || _isActive.value) { close(); return@callbackFlow }
    connecting = true
    val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
    android.util.Log.i(TAG, "Connecting to Gemini Live API")

    val request = Request.Builder().url(url).build()

    webSocket = http.newWebSocket(request, object : WebSocketListener() {
      override fun onOpen(ws: WebSocket, response: Response) {
        android.util.Log.i(TAG, "WebSocket open, sending setup")
        ws.send(buildSetup(voiceName, thinkingLevel).toString())
      }

      override fun onMessage(ws: WebSocket, text: String) { handleMessage(ws, text) }
      override fun onMessage(ws: WebSocket, bytes: ByteString) { handleMessage(ws, bytes.utf8()) }

      override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
        android.util.Log.e(TAG, "Failed: ${t.message}", t)
        trySend(Event.Error(t.message ?: "Connection failed"))
        _isActive.value = false; connecting = false; close()
      }

      override fun onClosed(ws: WebSocket, code: Int, reason: String) {
        android.util.Log.i(TAG, "Closed: $code $reason")
        trySend(Event.Disconnected)
        _isActive.value = false; connecting = false; close()
      }

      private fun handleMessage(ws: WebSocket, text: String) {
        try {
          val json = JSONObject(text)

          if (json.has("setupComplete")) {
            android.util.Log.i(TAG, "Setup complete")
            trySend(Event.Connected)
            _isActive.value = true
            startCapture(ws)
            startPlayback()
            return
          }

          // Tool calls from model
          json.optJSONObject("toolCall")?.let { tc ->
            val fcs = tc.optJSONArray("functionCalls") ?: return@let
            for (i in 0 until fcs.length()) {
              val fc = fcs.getJSONObject(i)
              val name = fc.getString("name")
              val id = fc.getString("id")
              val args = fc.optJSONObject("args") ?: JSONObject()
              android.util.Log.i(TAG, "Tool call: $name($args)")
              trySend(Event.ToolCall(name, id, args))
              // Execute tool and send response
              scope.launch {
                val argsMap = mutableMapOf<String, Any?>()
                args.keys().forEach { k -> argsMap[k] = args.opt(k) }
                val result = try { toolExecutor.execute(name, argsMap) } catch (e: Exception) { "Error: ${e.message}" }
                android.util.Log.i(TAG, "Tool result: ${result.take(100)}")
                trySend(Event.ToolResult(name, result))
                sendToolResponse(id, name, result)
              }
            }
            return
          }

          // Server content
          val sc = json.optJSONObject("serverContent") ?: return
          sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
              val part = parts.getJSONObject(i)
              part.optJSONObject("inlineData")?.optString("data")?.let { data ->
                if (data.isNotBlank()) {
                  val pcm = Base64.decode(data, Base64.DEFAULT)
                  playAudio(pcm)
                  trySend(Event.AudioChunk(pcm))
                }
              }
              val t = part.optString("text", "")
              if (t.isNotBlank()) trySend(Event.TextDelta(t))
            }
          }
          sc.optJSONObject("inputTranscription")?.optString("text")?.let {
            if (it.isNotBlank()) trySend(Event.InputTranscription(it))
          }
          sc.optJSONObject("outputTranscription")?.optString("text")?.let {
            if (it.isNotBlank()) trySend(Event.OutputTranscription(it))
          }
          if (sc.optBoolean("turnComplete", false)) trySend(Event.TurnComplete)
        } catch (e: Exception) {
          android.util.Log.e(TAG, "Parse error: ${e.message}")
        }
      }
    })

    awaitClose { disconnect() }
  }.flowOn(Dispatchers.IO)

  private fun sendToolResponse(id: String, name: String, result: String) {
    val msg = JSONObject().apply {
      put("toolResponse", JSONObject().apply {
        put("functionResponses", JSONArray().apply {
          put(JSONObject().apply {
            put("id", id)
            put("name", name)
            put("response", JSONObject().apply { put("result", result) })
          })
        })
      })
    }
    webSocket?.send(msg.toString())
  }

  fun sendText(text: String) {
    val msg = JSONObject().apply {
      put("clientContent", JSONObject().apply {
        put("turns", JSONArray().apply {
          put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply { put(JSONObject().apply { put("text", text) }) })
          })
        })
        put("turnComplete", true)
      })
    }
    webSocket?.send(msg.toString())
  }

  fun sendHistory(messages: List<Pair<String, String>>) {
    if (messages.isEmpty()) return
    val msg = JSONObject().apply {
      put("clientContent", JSONObject().apply {
        put("turns", JSONArray().apply {
          messages.forEach { (role, content) ->
            put(JSONObject().apply {
              put("role", role)
              put("parts", JSONArray().apply { put(JSONObject().apply { put("text", content) }) })
            })
          }
        })
        put("turnComplete", false)
      })
    }
    webSocket?.send(msg.toString())
  }

  private fun startCapture(ws: WebSocket) {
    val bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_IN, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (bufSize <= 0) return

    // VOICE_COMMUNICATION enables platform-level echo cancellation path
    audioRecord = AudioRecord(
      MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE_IN,
      AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize * 2
    )
    if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) { audioRecord?.release(); audioRecord = null; return }

    // Enable AEC and noise suppression
    val sessionId = audioRecord!!.audioSessionId
    if (AcousticEchoCanceler.isAvailable()) {
      echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
    }
    if (NoiseSuppressor.isAvailable()) {
      noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
    }

    audioRecord?.startRecording()

    captureJob = scope.launch {
      val buf = ByteArray(bufSize)
      while (isActive && _isActive.value) {
        val read = audioRecord?.read(buf, 0, buf.size) ?: 0
        if (read > 0) {
          var sum = 0L
          for (i in 0 until read step 2) {
            val s = (buf[i].toInt() and 0xFF) or ((buf.getOrNull(i + 1)?.toInt() ?: 0) shl 8)
            val signed = if (s > 32767) s - 65536 else s
            sum += (signed.toLong() * signed.toLong())
          }
          _amplitude.value = (kotlin.math.sqrt((sum / (read / 2)).toDouble()) / 32768.0).toFloat().coerceIn(0f, 1f)
          val b64 = Base64.encodeToString(buf.copyOf(read), Base64.NO_WRAP)
          ws.send("""{"realtimeInput":{"audio":{"data":"$b64","mimeType":"audio/pcm;rate=$SAMPLE_RATE_IN"}}}""")
        }
      }
    }
  }

  private fun startPlayback() {
    val bufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE_OUT, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    audioTrack = AudioTrack.Builder()
      .setAudioAttributes(AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
      .setAudioFormat(AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(SAMPLE_RATE_OUT)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
      .setBufferSizeInBytes(bufSize * 4)
      .setTransferMode(AudioTrack.MODE_STREAM)
      .build()
    audioTrack?.play()
  }

  private fun playAudio(pcm: ByteArray) {
    synchronized(playbackLock) {
      val track = audioTrack ?: return
      if (track.state != AudioTrack.STATE_INITIALIZED) return
      try { track.write(pcm, 0, pcm.size) } catch (_: Exception) {}
    }
  }

  fun disconnect() {
    _isActive.value = false
    connecting = false
    captureJob?.cancel(); captureJob = null
    echoCanceler?.release(); echoCanceler = null
    noiseSuppressor?.release(); noiseSuppressor = null
    audioRecord?.stop(); audioRecord?.release(); audioRecord = null
    synchronized(playbackLock) { audioTrack?.stop(); audioTrack?.release(); audioTrack = null }
    webSocket?.close(1000, "Done"); webSocket = null
    _amplitude.value = 0f
  }

  private fun buildSetup(voiceName: String, thinkingLevel: String): JSONObject = JSONObject().apply {
    put("setup", JSONObject().apply {
      put("model", "models/$MODEL")
      put("generationConfig", JSONObject().apply {
        put("responseModalities", JSONArray().apply { put("AUDIO") })
        put("speechConfig", JSONObject().apply {
          put("voiceConfig", JSONObject().apply {
            put("prebuiltVoiceConfig", JSONObject().apply { put("voiceName", voiceName) })
          })
        })
        put("thinkingConfig", JSONObject().apply {
          put("thinkingLevel", thinkingLevel)
        })
      })
      put("systemInstruction", JSONObject().apply {
        put("parts", JSONArray().apply { put(JSONObject().apply { put("text", SYSTEM_PROMPT) }) })
      })
      put("tools", JSONArray().apply {
        put(JSONObject().apply { put("functionDeclarations", buildToolDeclarations()) })
      })
    })
  }

  private fun buildToolDeclarations(): JSONArray {
    val decls = JSONArray()
    fun tool(name: String, desc: String, params: String) {
      decls.put(JSONObject().apply {
        put("name", name); put("description", desc); put("parameters", JSONObject(params))
      })
    }
    // All Pulse AI tools
    tool("search", "Search the web. Use category 'images' for image results, 'general' (default) for web", """{"type":"object","properties":{"query":{"type":"string","description":"Search query"},"category":{"type":"string","description":"general or images"}},"required":["query"]}""")
    tool("fetch_url", "Fetch URL content. Modes: text (default), md, raw, image (saves locally)", """{"type":"object","properties":{"url":{"type":"string","description":"URL to fetch"},"mode":{"type":"string","description":"text, md, raw, or image"}},"required":["url"]}""")
    tool("list_directory", "List directory contents", """{"type":"object","properties":{"path":{"type":"string","description":"Directory path"}},"required":["path"]}""")
    tool("read_file", "Read file contents", """{"type":"object","properties":{"path":{"type":"string","description":"File path"}},"required":["path"]}""")
    tool("write_file", "Write content to a file", """{"type":"object","properties":{"path":{"type":"string","description":"File path"},"content":{"type":"string","description":"File content"}},"required":["path","content"]}""")
    tool("edit_file", "Edit a file by replacing text", """{"type":"object","properties":{"path":{"type":"string","description":"File path"},"old":{"type":"string","description":"Text to find"},"new":{"type":"string","description":"Replacement text"}},"required":["path","old","new"]}""")
    tool("export_document", "Export a file to office format: docx, pdf, xlsx, csv", """{"type":"object","properties":{"path":{"type":"string","description":"Source file path"},"format":{"type":"string","description":"Target format: docx, pdf, xlsx, csv"}},"required":["path","format"]}""")
    tool("get_location", "Get device GPS location", """{"type":"object","properties":{}}""")
    tool("open_intent", "Open a URL, map, or app on the device", """{"type":"object","properties":{"uri":{"type":"string","description":"URI to open"}},"required":["uri"]}""")
    tool("image_generate", "Generate an image from a text prompt", """{"type":"object","properties":{"prompt":{"type":"string","description":"Image generation prompt"}},"required":["prompt"]}""")
    tool("memory_store", "Remember a fact across conversations", """{"type":"object","properties":{"key":{"type":"string","description":"Short key"},"content":{"type":"string","description":"Fact to remember"},"category":{"type":"string","description":"Category"}},"required":["key","content"]}""")
    tool("memory_recall", "Search stored memories", """{"type":"object","properties":{"query":{"type":"string","description":"Search term"}},"required":["query"]}""")
    return decls
  }
}
