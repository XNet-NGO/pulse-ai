package com.xnet.pulse.feature.chat.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealtimeVoice @Inject constructor(@ApplicationContext private val ctx: Context) {

  sealed class Event {
    data class TextDelta(val text: String) : Event()
    data class AudioChunk(val pcm: ByteArray) : Event()
    data object TurnComplete : Event()
    data class Error(val msg: String) : Event()
    data object Connected : Event()
    data object Disconnected : Event()
  }

  private val client = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.SECONDS)
    .writeTimeout(10, TimeUnit.SECONDS)
    .build()

  private var webSocket: WebSocket? = null
  private var audioRecord: AudioRecord? = null
  private var audioTrack: AudioTrack? = null
  private var captureJob: Job? = null

  private val _isActive = MutableStateFlow(false)
  val isActive: StateFlow<Boolean> = _isActive

  private val _amplitude = MutableStateFlow(0f)
  val amplitude: StateFlow<Float> = _amplitude

  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  companion object {
    private const val SAMPLE_RATE = 16000
    private const val WS_URL = "wss://inf.xnet.ngo/ws/voice"
  }

  fun connect(apiKey: String, model: String = "mistral-4"): Flow<Event> = callbackFlow {
    val request = Request.Builder()
      .url("$WS_URL?model=$model")
      .addHeader("Authorization", "Bearer $apiKey")
      .build()

    webSocket = client.newWebSocket(request, object : WebSocketListener() {
      override fun onOpen(ws: WebSocket, response: Response) {
        trySend(Event.Connected)
        _isActive.value = true
        startCapture(ws)
      }

      override fun onMessage(ws: WebSocket, text: String) {
        try {
          val json = JSONObject(text)
          json.optJSONObject("text")?.optString("delta")?.let {
            if (it.isNotBlank()) trySend(Event.TextDelta(it))
          }
          json.optJSONObject("audio")?.optString("pcm")?.let { b64 ->
            if (b64.isNotBlank()) {
              val pcm = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
              playAudio(pcm)
              trySend(Event.AudioChunk(pcm))
            }
          }
          if (json.has("turnComplete")) trySend(Event.TurnComplete)
          if (json.has("error")) trySend(Event.Error(json.optString("error")))
        } catch (e: Exception) {
          trySend(Event.Error("Parse: ${e.message}"))
        }
      }

      override fun onMessage(ws: WebSocket, bytes: ByteString) {
        val pcm = bytes.toByteArray()
        playAudio(pcm)
        trySend(Event.AudioChunk(pcm))
      }

      override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
        trySend(Event.Error(t.message ?: "Connection failed"))
        _isActive.value = false
        close()
      }

      override fun onClosed(ws: WebSocket, code: Int, reason: String) {
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
          // Amplitude for visualization
          var sum = 0L
          for (i in 0 until read step 2) {
            val s = (buf[i].toInt() and 0xFF) or ((buf.getOrNull(i + 1)?.toInt() ?: 0) shl 8)
            val signed = if (s > 32767) s - 65536 else s
            sum += (signed.toLong() * signed.toLong())
          }
          _amplitude.value = (kotlin.math.sqrt((sum / (read / 2)).toDouble()) / 32768.0).toFloat().coerceIn(0f, 1f)
          // Send
          val b64 = android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP)
          ws.send("""{"audio":{"pcm":"$b64","sampleRate":$SAMPLE_RATE}}""")
        }
      }
    }

    // Start playback track
    startPlayback()
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
    webSocket?.send("""{"text":{"content":"$text"}}""")
  }

  fun endTurn() {
    webSocket?.send("""{"turnEnd":true}""")
  }

  fun disconnect() {
    _isActive.value = false
    captureJob?.cancel()
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
