//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "STTService"
private const val HOLD_TIMEOUT_MS = 1500L

/**
 * Whether this Android version has an on-device recognizer at all.
 *
 * `isOnDeviceRecognitionAvailable` and `createOnDeviceSpeechRecognizer` both arrived
 * in API 31, as statics on `SpeechRecognizer` — a class that exists all the way down.
 * So nothing fails at load: below API 31 the call throws `NoSuchMethodError` at the
 * moment it runs, which for the Settings toggle was the tap that turned it on.
 *
 * This is the *platform* question and it is what gates the toggle's visibility, which
 * is why it is separate from [isOnDeviceRecognitionSupported]: a device that could
 * have the recognizer but has not downloaded a language pack is offered the toggle
 * and an alert that deep-links somewhere useful, whereas below API 31 no download
 * will ever produce one and the toggle is hidden instead.
 */
val platformSupportsOnDeviceRecognition: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Whether the platform can transcribe *right now* without a network round-trip:
 * [platformSupportsOnDeviceRecognition] plus a recognizer actually installed.
 *
 * Callers fall back the way they already did for a phone that ships no recognizer —
 * the network recognizer here, the "download a language pack" alert in Settings.
 * Offline dictation is not lost on those devices either way: `useVosk` swaps this
 * service out for [VoskService], which is offline everywhere.
 */
fun isOnDeviceRecognitionSupported(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

class STTService(private val context: Context) : SpeechService {
    override var onDevice: Boolean = false
    private var onDeviceFailed: Boolean = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var activeRecognizer: SpeechRecognizer? = null
    private var locale: String = "en-US"
    private var paused: Boolean = false
    private val handler = Handler(Looper.getMainLooper())
    private var holdRunnable: Runnable? = null
    private var accumulatedText: String = ""

    private val _isRecording = MutableStateFlow(false)
    override val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    override val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _humanMessage = MutableStateFlow("")
    override val humanMessage: StateFlow<String> = _humanMessage.asStateFlow()

    private val _finalMessage = MutableStateFlow("")
    override val finalMessage: StateFlow<String> = _finalMessage.asStateFlow()

    private val _isTimedOut = MutableStateFlow(false)
    override val isTimedOut: StateFlow<Boolean> = _isTimedOut.asStateFlow()

    override fun startRecording(locale: String) {
        if (_isRecording.value) return

        this.locale = locale
        this.paused = false
        this.accumulatedText = ""
        this.onDeviceFailed = false

        _humanMessage.value = ""
        _finalMessage.value = ""
        _isTimedOut.value = false

        Log.d(TAG, "Starting recording (locale: $locale)")

        _isRecording.value = true
        createAndStartListening()

        Log.i(TAG, "Recording started")
    }

    private fun createAndStartListening() {
        prepareRecognizer()
        val recognizer = speechRecognizer ?: return
        startListening(recognizer)
    }

    private fun prepareRecognizer() {
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        activeRecognizer = null

        val recognizer = if (
            onDevice && !onDeviceFailed &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

        if (recognizer == null) {
            Log.w(TAG, "createOnDeviceSpeechRecognizer returned null, falling back to network recognizer")
            onDeviceFailed = true
            prepareRecognizer()
            return
        }
        speechRecognizer = recognizer
        activeRecognizer = recognizer

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (activeRecognizer !== recognizer) return
                Log.d(TAG, "Ready for speech")
                _isListening.value = true
            }

            override fun onBeginningOfSpeech() {
                if (activeRecognizer !== recognizer) return
                Log.d(TAG, "Speech beginning")
                cancelHoldTimer()
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                if (activeRecognizer !== recognizer) return
                Log.d(TAG, "End of speech")
            }

            override fun onError(error: Int) {
                if (activeRecognizer !== recognizer) return
                Log.e(TAG, "Recognition error: $error")

                if (onDevice && !onDeviceFailed && error != SpeechRecognizer.ERROR_CLIENT) {
                    Log.w(TAG, "On-device recognition failed (error=$error), falling back to network recognizer")
                    onDeviceFailed = true
                    activeRecognizer = null
                    speechRecognizer?.cancel()
                    speechRecognizer?.destroy()
                    if (_isRecording.value && !paused) {
                        createAndStartListening()
                    }
                    return
                }

                if (_isRecording.value && !paused && error != SpeechRecognizer.ERROR_CLIENT) {
                    if (accumulatedText.isNotEmpty()) {
                        emitFinal()
                    } else {
                        _isTimedOut.value = true
                    }
                    stopRecognizer()
                    _isListening.value = false
                } else {
                    _isListening.value = false
                }
            }

            override fun onResults(results: Bundle?) {
                if (activeRecognizer !== recognizer) return
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                if (text.isNotEmpty()) {
                    accumulatedText = text
                    _humanMessage.value = text
                    Log.d(TAG, "Final result: $text")
                }

                if (accumulatedText.isNotEmpty()) {
                    startHoldTimer()
                }
                _isListening.value = false
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (activeRecognizer !== recognizer) return
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                if (text.isNotEmpty()) {
                    accumulatedText = text
                    _humanMessage.value = text
                    cancelHoldTimer()
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListening(recognizer: SpeechRecognizer) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Keep recognizer alive during pauses but don't require excessive speech
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
        }
        recognizer.startListening(intent)
    }

    private fun startHoldTimer() {
        cancelHoldTimer()
        holdRunnable = Runnable {
            if (accumulatedText.isNotEmpty() && _isRecording.value && !paused) {
                Log.d(TAG, "Hold timeout - emitting final: $accumulatedText")
                emitFinal()
            }
        }
        handler.postDelayed(holdRunnable!!, HOLD_TIMEOUT_MS)
    }

    private fun cancelHoldTimer() {
        holdRunnable?.let { handler.removeCallbacks(it) }
        holdRunnable = null
    }

    private fun emitFinal() {
        cancelHoldTimer()
        if (accumulatedText.isNotEmpty()) {
            _finalMessage.value = accumulatedText
            accumulatedText = ""
        }
    }

    private fun stopRecognizer() {
        activeRecognizer = null
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    override fun pauseListening() {
        if (!_isRecording.value) return
        paused = true
        _isTimedOut.value = false
        cancelHoldTimer()
        if (accumulatedText.isNotEmpty()) {
            emitFinal()
        }
        stopRecognizer()
        _isListening.value = false
        Log.d(TAG, "Listening paused")
    }

    override fun resumeListening() {
        if (!_isRecording.value) return
        paused = false
        _isTimedOut.value = false
        accumulatedText = ""
        _humanMessage.value = ""
        _finalMessage.value = ""
        createAndStartListening()
        Log.d(TAG, "Listening resumed")
    }

    override fun stopRecording() {
        if (!_isRecording.value) return

        Log.d(TAG, "Stopping recording")

        paused = false
        _isTimedOut.value = false
        cancelHoldTimer()
        accumulatedText = ""
        activeRecognizer = null
        speechRecognizer?.stopListening()
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null

        _isRecording.value = false
        _isListening.value = false
        _humanMessage.value = ""
        _finalMessage.value = ""

        Log.i(TAG, "Recording stopped")
    }

    override fun clearHumanMessage() {
        _humanMessage.value = ""
    }

    override fun destroy() {
        stopRecording()
    }
}
