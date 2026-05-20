package com.xnet.pulse.feature.chat.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
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
class RealtimeVoice @Inject constructor(@ApplicationContext private val ctx: Context) {

  sealed class Event {
    data class TextDelta(val text: String) : Event()
    data class AudioChunk(val pcm: ByteArray) : Event()
    data class InputTranscription(val text: String) : Event()
    data class OutputTranscription(val text: String) : Event()
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
  private var captureJob: Job? = null
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  private val _isActive = MutableStateFlow(false)
  val isActive: StateFlow<Boolean> = _isActive

  private val _amplitude = MutableStateFlow(0f)
  val amplitude: StateFlow<Float> = _amplitude

  companion object {
    private const val SAMPLE_RATE = 16000
    private const val TAG = "RealtimeVoice"
    private const val MODEL = "gemini-2.5-flash-preview-native-audio-dialog"
    private const val SYSTEM_PROMPT = "You are a helpful voice assistant. Be concise and conversational."
  }

  fun connect(apiKey: String): Flow<Event> = callbackFlow {
    val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
    android.util.Log.i(TAG, "Connecting to Gemini Live API")

    val request = Request.Builder().url(url).build()

    webSocket = http.newWebSocket(request, object : WebSocketListener() {
      override fun onOpen(ws: WebSocket, response: Response) {
        android.util.Log.i(TAG, "WebSocket open, sending setup")
        // Send setup message
        val setup = JSONObject().apply {
          put("setup", JSONObject().apply {
            put("model", "models/$MODEL")
            put("generationConfig", JSONObject().apply {
              put("responseModalities", JSONArray().apply { put("AUDIO") })
              put("speechConfig", JSONObject().apply {
                put("voiceConfig", JSONObject().apply {
                  put("prebuiltVoiceConfig", JSONObject().apply {
                    put("voiceName", "Aoede")
                  })
                })
              })
            })
            put("systemInstruction", JSONObject().apply {
              put("parts", JSONArray().apply {
                put(JSONObject().apply { put("text", SYSTEM_PROMPT) })
              })
            })
          })
        }
        ws.send(setup.toString())
      }

      override fun onMessage(ws: WebSocket, text: String) {
        try {
          val json = JSONObject(text)

          // Setup complete
          if (json.has("setupComplete")) {
            android.util.Log.i(TAG, "Setup complete, starting capture")
            trySend(Event.Connected)
            _isActive.value = true
            startCapture(ws)
            startPlayback()
            return
          }

          // Server content
          val sc = json.optJSONObject("serverContent")
          if (sc != null) {
            // Model turn - audio/text parts
            sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
              for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                // Audio
                part.optJSONObject("inlineData")?.let { inline ->
                  val data = inline.optString("data", "")
                  if (data.isNotBlank()) {
                    val pcm = Base64.decode(data, Base64.DEFAULT)
                    playAudio(pcm)
                    trySend(Event.AudioChunk(pcm))
                  }
                }
                // Text
                val t = part.optString("text", "")
                if (t.isNotBlank()) trySend(Event.TextDelta(t))
              }
            }
            // Transcriptions
            sc.optJSONObject("inputTranscription")?.optString("text")?.let {
              if (it.isNotBlank()) trySend(Event.InputTranscription(it))
            }
            sc.optJSONObject("outputTranscription")?.optString("text")?.let {
              if (it.isNotBlank()) trySend(Event.OutputTranscription(it))
            }
            // Turn complete
            if (sc.optBoolean("turnComplete", false)) {
              trySend(Event.TurnComplete)
            }
          }
        } catch (e: Exception) {
          android.util.Log.e(TAG, "Parse error: ${e.message}")
        }
      }

      override fun onMessage(ws: WebSocket, bytes: ByteString) {
        // Binary frame - try parsing as JSON (Google sends binary UTF-8)
        try {
          val text = bytes.utf8()
          val json = JSONObject(text)
          val sc = json.optJSONObject("serverContent")
          sc?.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
              parts.getJSONObject(i).optJSONObject("inlineData")?.optString("data")?.let { data ->
                if (data.isNotBlank()) {
                  val pcm = Base64.decode(data, Base64.DEFAULT)
                  playAudio(pcm)
                  trySend(Event.AudioChunk(pcm))
                }
              }
            }
          }
        } catch (_: Exception) {
          // Raw binary audio
          val pcm = bytes.toByteArray()
          playAudio(pcm)
          trySend(Event.AudioChunk(pcm))
        }
      }

      override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
        android.util.Log.e(TAG, "Failed: ${t.message}", t)
        trySend(Event.Error(t.message ?: "Connection failed"))
        _isActive.value = false
        close()
      }

      override fun onClosed(ws: WebSocket, code: Int, reason: String) {
        android.util.Log.i(TAG, "Closed: $code $reason")
        trySend(Event.Disconnected)
        _isActive.value = false
        close()
      }
    })

    awaitClose { disconnect() }
  }.flowOn(Dispatchers.IO)

  private fun startCapture(ws: WebSocket) {
    val bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (bufSize <= 0) return

    audioRecord = AudioRecord(
      MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
      AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize * 2
    )
    if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) { audioRecord?.release(); audioRecord = null; return }
    audioRecord?.startRecording()

    captureJob = scope.launch {
      val buf = ByteArray(bufSize)
      while (isActive && _isActive.value) {
        val read = audioRecord?.read(buf, 0, buf.size) ?: 0
        if (read > 0) {
          val chunk = buf.copyOf(read)
          // Amplitude
          var sum = 0L
          for (i in 0 until read step 2) {
            val s = (buf[i].toInt() and 0xFF) or ((buf.getOrNull(i + 1)?.toInt() ?: 0) shl 8)
            val signed = if (s > 32767) s - 65536 else s
            sum += (signed.toLong() * signed.toLong())
          }
          _amplitude.value = (kotlin.math.sqrt((sum / (read / 2)).toDouble()) / 32768.0).toFloat().coerceIn(0f, 1f)
          // Send as Google Live API format
          val b64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
          val msg = """{"realtimeInput":{"audio":{"data":"$b64","mimeType":"audio/pcm;rate=$SAMPLE_RATE"}}}"""
          ws.send(msg)
        }
      }
    }
  }

  private fun startPlayback() {
    val bufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    audioTrack = AudioTrack.Builder()
      .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
      .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
      .setBufferSizeInBytes(bufSize * 2)
      .setTransferMode(AudioTrack.MODE_STREAM)
      .build()
    audioTrack?.play()
  }

  private fun playAudio(pcm: ByteArray) {
    if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
      scope.launch { audioTrack?.write(pcm, 0, pcm.size) }
    }
  }

  fun sendText(text: String) {
    val msg = JSONObject().apply {
      put("clientContent", JSONObject().apply {
        put("turns", JSONArray().apply {
          put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply {
              put(JSONObject().apply { put("text", text) })
            })
          })
        })
        put("turnComplete", true)
      })
    }
    webSocket?.send(msg.toString())
  }

  fun disconnect() {
    _isActive.value = false
    captureJob?.cancel()
    captureJob = null
    audioRecord?.stop()
    audioRecord?.release()
    audioRecord = null
    audioTrack?.stop()
    audioTrack?.release()
    audioTrack = null
    webSocket?.close(1000, "Done")
    webSocket = null
    _amplitude.value = 0f
  }
}
