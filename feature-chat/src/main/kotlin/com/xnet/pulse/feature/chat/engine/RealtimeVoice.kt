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
  private val playbackQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
  private var playbackJob: Job? = null

  private val _isActive = MutableStateFlow(false)
  val isActive: StateFlow<Boolean> = _isActive

  private val _amplitude = MutableStateFlow(0f)
  val amplitude: StateFlow<Float> = _amplitude

  private var connecting = false
  private var historyContext = ""

  companion object {
    private const val SAMPLE_RATE_IN = 16000
    private const val SAMPLE_RATE_OUT = 24000
    private const val TAG = "RealtimeVoice"
    private const val SYSTEM_PROMPT = """You are Pulse AI, a personal intelligent agent running natively on the user's Android device.

Personality: Competent, efficient, and quietly confident. You do not chat — you solve. Be direct and concise.

Tone: Concise and conversational — this is a voice call. Use short sentences. Match the user's energy.

Constraints:
- If you are about to do something significant, confirm first.
- If uncertain, say so and propose a path forward.
- Do not make up information — use tools to verify facts."""
  }

  fun connect(apiKey: String, voiceName: String = "Aoede", thinkingLevel: String = "minimal"): Flow<Event> = callbackFlow {
    if (connecting || _isActive.value) { close(); return@callbackFlow }
    connecting = true

    val url = "wss://inf.xnet.ngo/ws/voice?model=google-ai-studio/gemini-3.1-flash-live-preview"
    android.util.Log.i(TAG, "Connecting to gateway")

    val request = Request.Builder()
      .url(url)
      .addHeader("Authorization", "Bearer $apiKey")
      .build()

    webSocket = http.newWebSocket(request, object : WebSocketListener() {
      override fun onOpen(ws: WebSocket, response: Response) {
        android.util.Log.i(TAG, "WS open, sending setup")
        // Send system prompt + tools — gateway forwards to Gemini
        val setup = JSONObject().apply {
          put("setup", JSONObject().apply {
            put("systemPrompt", SYSTEM_PROMPT + historyContext)
            put("voiceName", voiceName)
            put("tools", JSONArray().apply {
              fun tool(name: String, desc: String, params: String) {
                put(JSONObject().apply {
                  put("name", name); put("description", desc); put("parameters", JSONObject(params))
                })
              }
              tool("search_web", "Search the web for current information", """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""")
              tool("search_images", "Search for images on the web", """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""")
              tool("fetch_url", "Fetch a URL and return text content", """{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}""")
              tool("read_file", "Read file contents", """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""")
              tool("write_file", "Write file", """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}""")
              tool("list_directory", "List directory", """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""")
              tool("get_location", "Get device GPS location", """{"type":"object","properties":{}}""")
              tool("open_intent", "Open a URL, map, or app on device", """{"type":"object","properties":{"uri":{"type":"string"}},"required":["uri"]}""")
              tool("image_generate", "Generate an image from a text prompt", """{"type":"object","properties":{"prompt":{"type":"string"}},"required":["prompt"]}""")
              tool("memory_store", "Remember a fact across conversations", """{"type":"object","properties":{"key":{"type":"string"},"content":{"type":"string"}},"required":["key","content"]}""")
              tool("memory_recall", "Search stored memories", """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""")
            })
          })
        }
        ws.send(setup.toString())
      }

      override fun onMessage(ws: WebSocket, text: String) {
        try {
          val json = JSONObject(text)

          // Gateway sends {"connected":true} when upstream Gemini is ready
          if (json.optBoolean("connected", false)) {
            android.util.Log.i(TAG, "Upstream ready, starting capture")
            trySend(Event.Connected)
            _isActive.value = true
            connecting = false
            startCapture(ws)
            return
          }

          // Audio from model: {"audio":{"pcm":"base64..."}}
          json.optJSONObject("audio")?.optString("pcm")?.let { b64 ->
            if (b64.isNotBlank()) {
              val pcm = Base64.decode(b64, Base64.DEFAULT)
              playAudio(pcm)
              trySend(Event.AudioChunk(pcm))
            }
          }

          // Text from model: {"text":{"delta":"..."}}
          json.optJSONObject("text")?.optString("delta")?.let {
            if (it.isNotBlank()) trySend(Event.TextDelta(it))
          }

          // Turn signals
          if (json.has("turnComplete")) trySend(Event.TurnComplete)
          if (json.has("inputTranscription")) trySend(Event.InputTranscription(json.getString("inputTranscription")))
          if (json.has("outputTranscription")) trySend(Event.OutputTranscription(json.getString("outputTranscription")))

          // Tool calls
          json.optJSONObject("toolCall")?.let { tc ->
            val fcs = tc.optJSONArray("functionCalls") ?: return@let
            for (i in 0 until fcs.length()) {
              val fc = fcs.getJSONObject(i)
              val name = fc.getString("name")
              val id = fc.getString("id")
              val args = fc.optJSONObject("args") ?: JSONObject()
              trySend(Event.ToolCall(name, id, args))
              scope.launch {
                val argsMap = mutableMapOf<String, Any?>()
                args.keys().forEach { k -> argsMap[k] = args.opt(k) }
                val result = try { toolExecutor.execute(name, argsMap) } catch (e: Exception) { "Error: ${e.message}" }
                trySend(Event.ToolResult(name, result))
                sendToolResponse(id, name, result)
              }
            }
          }

          // Error
          if (json.has("error")) trySend(Event.Error(json.getString("error")))

        } catch (e: Exception) {
          android.util.Log.e(TAG, "Parse error: ${e.message}")
        }
      }

      override fun onMessage(ws: WebSocket, bytes: ByteString) {
        // Binary audio frames
        val pcm = bytes.toByteArray()
        playAudio(pcm)
        trySend(Event.AudioChunk(pcm))
      }

      override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
        android.util.Log.e(TAG, "WS failed: ${t.message}", t)
        trySend(Event.Error(t.message ?: "Connection failed"))
        _isActive.value = false; connecting = false; close()
      }

      override fun onClosed(ws: WebSocket, code: Int, reason: String) {
        android.util.Log.i(TAG, "WS closed: $code $reason")
        trySend(Event.Disconnected)
        _isActive.value = false; connecting = false; close()
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
            put("response", JSONObject().apply { put("result", result) })
          })
        })
      })
    }
    webSocket?.send(msg.toString())
  }

  fun sendText(text: String) {
    val msg = JSONObject().apply {
      put("text", JSONObject().apply { put("content", text) })
    }
    webSocket?.send(msg.toString())
  }

  fun sendHistory(messages: List<Pair<String, String>>) {
    if (messages.isEmpty()) { historyContext = ""; return }
    val sb = StringBuilder("\n\n## Conversation so far:\n")
    messages.forEach { (role, content) ->
      sb.append("${if (role == "user") "User" else "Assistant"}: $content\n")
    }
    historyContext = sb.toString()
  }

  private fun startCapture(ws: WebSocket) {
    // Enable speaker for voice call
    val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
    am.isSpeakerphoneOn = true
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
      val speaker = am.availableCommunicationDevices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
      if (speaker != null) am.setCommunicationDevice(speaker)
    }
    am.setStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL, (am.getStreamMaxVolume(android.media.AudioManager.STREAM_VOICE_CALL) * 0.85).toInt(), 0)

    val bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_IN, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (bufSize <= 0) return

    audioRecord = AudioRecord(
      MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE_IN,
      AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize * 2
    )
    if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) { audioRecord?.release(); audioRecord = null; return }

    val sessionId = audioRecord!!.audioSessionId
    if (AcousticEchoCanceler.isAvailable()) {
      echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
    }
    if (NoiseSuppressor.isAvailable()) {
      noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
    }

    // Start playback with shared session ID for AEC pairing
    startPlayback(sessionId)

    audioRecord?.startRecording()

    captureJob = scope.launch {
      val buf = ByteArray(bufSize)
      while (isActive && _isActive.value) {
        val read = audioRecord?.read(buf, 0, buf.size) ?: 0
        if (read > 0) {
          // Amplitude for UI
          var sum = 0L
          for (i in 0 until read step 2) {
            val s = (buf[i].toInt() and 0xFF) or ((buf.getOrNull(i + 1)?.toInt() ?: 0) shl 8)
            val signed = if (s > 32767) s - 65536 else s
            sum += (signed.toLong() * signed.toLong())
          }
          _amplitude.value = (kotlin.math.sqrt((sum / (read / 2)).toDouble()) / 32768.0).toFloat().coerceIn(0f, 1f)

          // Send audio in gateway format
          val b64 = Base64.encodeToString(buf.copyOf(read), Base64.NO_WRAP)
          ws.send("""{"audio":{"pcm":"$b64","sampleRate":$SAMPLE_RATE_IN}}""")
        }
      }
    }
  }

  private fun startPlayback(sessionId: Int = 0) {
    val bufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE_OUT, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val builder = AudioTrack.Builder()
      .setAudioAttributes(AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
      .setAudioFormat(AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(SAMPLE_RATE_OUT)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
      .setBufferSizeInBytes(bufSize * 4)
      .setTransferMode(AudioTrack.MODE_STREAM)
    if (sessionId != 0) builder.setSessionId(sessionId)
    audioTrack = builder.build()
    audioTrack?.play()

    playbackJob = scope.launch {
      while (isActive) {
        val chunk = playbackQueue.poll(100, TimeUnit.MILLISECONDS)
        if (chunk != null) {
          try { audioTrack?.write(chunk, 0, chunk.size) } catch (_: Exception) {}
        }
      }
    }
  }

  private fun playAudio(pcm: ByteArray) {
    playbackQueue.offer(pcm)
  }

  fun disconnect() {
    _isActive.value = false
    connecting = false
    captureJob?.cancel(); captureJob = null
    playbackJob?.cancel(); playbackJob = null
    playbackQueue.clear()
    echoCanceler?.release(); echoCanceler = null
    noiseSuppressor?.release(); noiseSuppressor = null
    audioRecord?.stop(); audioRecord?.release(); audioRecord = null
    audioTrack?.stop(); audioTrack?.release(); audioTrack = null
    webSocket?.close(1000, "Done"); webSocket = null
    _amplitude.value = 0f
    // Restore audio mode
    try {
      val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
      am.mode = android.media.AudioManager.MODE_NORMAL
      am.isSpeakerphoneOn = false
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        am.clearCommunicationDevice()
      }
    } catch (_: Exception) {}
  }
}
