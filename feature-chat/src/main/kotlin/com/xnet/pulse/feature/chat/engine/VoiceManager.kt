package com.xnet.pulse.feature.chat.engine

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceManager @Inject constructor(@ApplicationContext private val ctx: Context) {

  private var tts: TextToSpeech? = null
  private var recognizer: SpeechRecognizer? = null

  private val _isListening = MutableStateFlow(false)
  val isListening: StateFlow<Boolean> = _isListening

  private val _result = MutableStateFlow<String?>(null)
  val result: StateFlow<String?> = _result

  var autoRead = false

  init {
    tts = TextToSpeech(ctx) { status ->
      if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
    }
  }

  fun startListening() {
    if (!SpeechRecognizer.isRecognitionAvailable(ctx)) return
    recognizer = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
      setRecognitionListener(object : RecognitionListener {
        override fun onResults(results: Bundle?) {
          val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
          _result.value = text
          _isListening.value = false
        }
        override fun onError(error: Int) { _isListening.value = false }
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
      })
    }
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
    }
    _isListening.value = true
    _result.value = null
    recognizer?.startListening(intent)
  }

  fun stopListening() {
    recognizer?.stopListening()
    _isListening.value = false
  }

  fun speak(text: String) {
    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pulse_tts")
  }

  fun consumeResult() { _result.value = null }

  fun destroy() {
    tts?.shutdown()
    recognizer?.destroy()
  }
}
