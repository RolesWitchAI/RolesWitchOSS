//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

private const val TAG = "VoskService"
private const val SAMPLE_RATE = 16000
private const val SILENCE_TIMEOUT_MS = 2000L

class VoskService(private val context: Context) : SpeechService {
    companion object {
        private var voskInitialized = false
    }

    private var model: Model? = null
    private var loadedModelDirName: String? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var locale: String = "en-US"
    @Volatile private var paused: Boolean = false
    @Volatile private var accumulatedText: String = ""
    var continuousMode: Boolean = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override var onDevice: Boolean = true

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

    private var silenceJob: Job? = null

    init {
        if (!voskInitialized) {
            LibVosk.setLogLevel(LogLevel.INFO)
            voskInitialized = true
        }
    }

    override fun startRecording(locale: String) {
        if (_isRecording.value) return

        this.locale = locale
        this.paused = false
        this.accumulatedText = ""
        _humanMessage.value = ""
        _finalMessage.value = ""

        val modelEntry = VoskModels.get(locale)
        if (modelEntry == null) {
            Log.e(TAG, "No Vosk model for locale: $locale")
            return
        }
        val modelDirName = modelEntry.dirName

        // Reload if this is the first call OR the locale changed since the
        // last load — otherwise the previously-loaded acoustic model (e.g.
        // German) would silently keep transcribing the new language (e.g.
        // Spanish) with the wrong phoneme set.
        if (model == null || loadedModelDirName != modelDirName) {
            Log.d(TAG, "Loading Vosk model: $modelDirName")
            model?.close()
            model = null
            loadedModelDirName = null
            ensureModelLoaded(modelEntry)
            if (model == null) {
                Log.e(TAG, "Failed to load Vosk model")
                return
            }
            loadedModelDirName = modelDirName
        }

        Log.d(TAG, "Starting Vosk recording (locale: $locale)")
        _isRecording.value = true
        startRecognizer()
    }

    /**
     * Whether the model for [locale] can be loaded right now: bundled models
     * are always ready (lazily copied from assets); downloaded models are
     * ready only once their extracted tree exists in `filesDir`.
     */
    fun isModelReady(locale: String): Boolean {
        val entry = VoskModels.get(locale) ?: return false
        if (entry.bundled) return true
        return File(context.filesDir, entry.dirName).exists()
    }

    private fun ensureModelLoaded(entry: VoskModel) {
        val destDir = File(context.filesDir, entry.dirName)
        if (!destDir.exists()) {
            if (!entry.bundled) {
                Log.e(TAG, "Downloadable model ${entry.dirName} is not present; refusing to copy from assets")
                return
            }
            Log.d(TAG, "Copying model from assets to $destDir")
            try {
                copyAssetDirectory(entry.dirName, destDir)
                Log.d(TAG, "Model copied successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy model: ${e.message}")
                return
            }
        }
        model = Model(destDir.absolutePath)
        Log.d(TAG, "Vosk model loaded: ${destDir.absolutePath}")
    }

    private fun copyAssetDirectory(assetPath: String, destDir: File) {
        val assetManager = context.assets
        destDir.mkdirs()

        val entries = assetManager.list(assetPath) ?: return
        for (entry in entries) {
            val childAssetPath = "$assetPath/$entry"
            val childDestFile = File(destDir, entry)

            val subEntries = assetManager.list(childAssetPath)
            if (subEntries != null && subEntries.isNotEmpty()) {
                copyAssetDirectory(childAssetPath, childDestFile)
            } else {
                childDestFile.outputStream().use { output ->
                    assetManager.open(childAssetPath).use { input ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun startRecognizer() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid buffer size for AudioRecord")
            stopRecording()
            return
        }

        // The job cancelled by the preceding stopRecognizer() may still be
        // inside its finally, releasing the old AudioRecord/Recognizer. Opening
        // the new session before it finishes would briefly hold the mic twice
        // and let the stale teardown clear this session's state, so wait for it.
        val previousJob = recordingJob
        recordingJob = scope.launch(Dispatchers.IO) {
            previousJob?.join()

            val capturedRecognizer = try {
                Recognizer(model, SAMPLE_RATE.toFloat())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create Recognizer: ${e.message}")
                withContext(Dispatchers.Main) { stopRecording() }
                return@launch
            }

            val capturedRecorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize * 2
                )
            } catch (e: SecurityException) {
                // RECORD_AUDIO is runtime-granted; the constructor throws if it is gone
                // (notification-dismissed mic, permission revoked in settings, ...).
                Log.e(TAG, "AudioRecord: RECORD_AUDIO not granted: ${e.message}")
                withContext(Dispatchers.Main) { stopRecording() }
                return@launch
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "AudioRecord failed to initialize: ${e.message}")
                withContext(Dispatchers.Main) { stopRecording() }
                return@launch
            }

            if (capturedRecorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                capturedRecorder.release()
                capturedRecognizer.close()
                withContext(Dispatchers.Main) { stopRecording() }
                return@launch
            }

            recognizer = capturedRecognizer
            audioRecord = capturedRecorder
            _isListening.value = true
            capturedRecorder.startRecording()

            val buffer = ByteArray(bufferSize)

            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)

            try {
                while (isActive) {
                    val read = capturedRecorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        try {
                            val isFinal = capturedRecognizer.acceptWaveForm(buffer, read)
                            val json = if (isFinal) capturedRecognizer.result else capturedRecognizer.partialResult
                            withContext(Dispatchers.Main) {
                                handleResult(json, isFinal)
                            }
                        } catch (e: Exception) {
                            if (isActive) Log.e(TAG, "Recognition error: ${e.message}")
                        }
                    } else if (read < 0) {
                        Log.e(TAG, "AudioRecord read error: $read")
                        withContext(Dispatchers.Main) {
                            stopRecording()
                        }
                        return@launch
                    }
                }
            } finally {
                try { capturedRecorder.stop() } catch (e: Exception) {
                    Log.w(TAG, "AudioRecord.stop failed: ${e.message}")
                }
                capturedRecorder.release()
                capturedRecognizer.close()
                withContext(NonCancellable) {
                    // Only clear state still owned by *this* session: a newer
                    // one may already be running (see the join above).
                    if (audioRecord === capturedRecorder) audioRecord = null
                    if (recognizer === capturedRecognizer) {
                        recognizer = null
                        _isListening.value = false
                    }
                }
            }
        }
    }

    private fun handleResult(json: String, isFinal: Boolean) {
        try {
            val jsonObj = JSONObject(json)
            val text = if (isFinal) {
                jsonObj.optString("text", "")
            } else {
                jsonObj.optString("partial", "")
            }

            if (text.isNotEmpty()) {
                if (isFinal) {
                    accumulatedText = if (accumulatedText.isEmpty()) text else "$accumulatedText $text"
                    _humanMessage.value = accumulatedText
                    cancelSilenceTimer()
                    silenceJob = scope.launch {
                        delay(SILENCE_TIMEOUT_MS)
                        if (accumulatedText.isNotEmpty() && _isRecording.value && !paused) {
                            val text = accumulatedText
                            accumulatedText = ""
                            Log.d(TAG, "Silence timeout - emitting final: $text")
                            _finalMessage.value = text
                            if (!continuousMode) {
                                stopRecognizer()
                            }
                        }
                    }
                } else {
                    _humanMessage.value = if (accumulatedText.isEmpty()) text else "$accumulatedText $text"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Vosk result: ${e.message}")
        }
    }

    private fun cancelSilenceTimer() {
        silenceJob?.cancel()
        silenceJob = null
    }

    override fun pauseListening() {
        if (!_isRecording.value) return
        paused = true
        cancelSilenceTimer()
        val text = accumulatedText.ifEmpty { _humanMessage.value }
        if (text.isNotEmpty()) {
            accumulatedText = ""
            _humanMessage.value = ""
            _finalMessage.value = text
        }
        stopRecognizer()
        Log.d(TAG, "Listening paused")
    }

    override fun resumeListening() {
        if (!_isRecording.value) return
        if (!paused) return
        paused = false
        accumulatedText = ""
        _humanMessage.value = ""
        _finalMessage.value = ""
        startRecognizer()
        Log.d(TAG, "Listening resumed")
    }

    override fun stopRecording() {
        if (!_isRecording.value) return

        Log.d(TAG, "Stopping recording")
        paused = false
        cancelSilenceTimer()
        stopRecognizer()
        accumulatedText = ""
        _isRecording.value = false
        _isListening.value = false
        _humanMessage.value = ""
        _finalMessage.value = ""
        Log.i(TAG, "Recording stopped")
    }

    /**
     * Cancels the capture loop. The job's own `finally` releases the recorder
     * and recognizer; [recordingJob] is deliberately kept so a following
     * [startRecognizer] can join it before opening the mic again.
     */
    private fun stopRecognizer() {
        recordingJob?.cancel()
        _isListening.value = false
    }

    override fun clearHumanMessage() {
        _humanMessage.value = ""
    }

    override fun destroy() {
        stopRecording()
        scope.cancel()
        model?.close()
        model = null
    }
}
