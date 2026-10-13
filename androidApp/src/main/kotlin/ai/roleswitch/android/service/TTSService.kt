//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ai.roleswitch.android.BuildConfig
import ai.roleswitch.android.util.SpeechText
import ai.roleswitch.android.settings.SettingsManager
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

private const val TAG = "TTSService"

/**
 * How long a finished external playback is left alone before its player is stopped
 * and released. `MediaPlayer.onCompletion` fires when the last decoded buffer is
 * *submitted*, not when the output sink has sounded it — on a device the sink
 * (Bluetooth above all) can still hold a few hundred milliseconds of audio, and
 * releasing the player at the callback itself cut the tail off every spoken reply.
 * The grace only delays resource cleanup; the resume edge is raised immediately.
 */
private const val EXTERNAL_DRAIN_GRACE_MS = 300L

/** The engine the in-app voice binds to when it is installed. */
private const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"

/**
 * Text-to-speech playback. Every entry point takes `useSystemTts` rather than
 * holding it, because it is a per-thread setting resolved through
 * `EffectiveSettings`: a thread can override the global choice, and this service
 * is a process-wide singleton shared by every screen. Storing it here would mean
 * whichever screen wrote last decides what an unrelated thread speaks with.
 */
class TTSService(context: Context, initialUseSystemTts: Boolean = false) {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var isInitialized = false

    /**
     * Whether TTS audio is routed via the voice-communication path (with AEC)
     * or via the media path. Switched to `true` by AssistantScreen when the
     * user is using headphones + Vosk + allowInterruptions.
     *
     * Defaults to `false` -> [AudioAttributes.USAGE_MEDIA]. Backgrounded
     * playback on Android 14+ is permitted by the `mediaPlayback`
     * foreground-service type declared in the manifest; we no longer use
     * [AudioAttributes.USAGE_ASSISTANT] because on some OEMs it routes
     * through a different DSP path that degrades TTS quality.
     */
    @Volatile private var useVoiceCommPath: Boolean = false

    private var audioFocusRequest: AudioFocusRequest? = null

    /**
     * The engine named by system Settings when the live [TextToSpeech] was built.
     * Note this is the *requested* engine, not necessarily the connected one —
     * the framework silently falls back to the highest-ranked public engine when
     * the requested one refuses the bind (Samsung TTS rejects non-allowlisted
     * callers this way), and `getCurrentEngine()` is not public API.
     */
    @Volatile private var requestedEngine: String? = null

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    /**
     * The OpenAI-compatible `/v1/audio/speech` client for external-mode playback. Stateless,
     * so one instance serves every thread; [speak] passes its own thread's endpoint/token.
     */
    private val externalTts = ExternalTtsClient()

    /**
     * The in-flight external playback, so [stop] can interrupt it. Exists alongside [tts]:
     * the two modes are mutually exclusive per utterance, so only one is ever live.
     */
    private var externalPlayer: MediaPlayer? = null

    /** Completed by the current engine's `OnInitListener`; see [readyEngine]. */
    private var engineReady: CompletableDeferred<Boolean>? = null

    /** Serialises the check-and-rebind in [readyEngine]. */
    private val engineMutex = Mutex()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * The in-flight [speakNow], so [stop] can cancel an utterance that has not
     * reached [TextToSpeech.speak] yet. Without it a stop arriving during the
     * engine bind is a no-op and the queued speech starts afterwards.
     */
    private var speakJob: Job? = null

    init {
        // Warm up on the global setting so the first utterance doesn't pay for the
        // bind. Only a starting guess: every call afterwards passes its own thread's
        // value to readyEngine, which re-binds if this turns out to be the wrong one.
        tts = buildEngine(resolveEngine(initialUseSystemTts))
    }

    private fun systemDefaultSynth(): String? = Settings.Secure.getString(
        appContext.contentResolver,
        Settings.Secure.TTS_DEFAULT_SYNTH
    )

    /**
     * The engine used when the user picked the in-app voice, resolved independently
     * of the system default so the two options are actually different engines.
     *
     * Google TTS when installed, otherwise `null` to fall back to the system
     * default — without Google there is no second engine worth calling "the app's",
     * and the alternative (highest-ranked) is how we ended up binding Samsung, which
     * refuses third-party callers anyway. Package visibility for this query comes
     * from the `TTS_SERVICE` `<queries>` entry in the manifest.
     */
    private fun appEnginePackage(): String? {
        val installed = runCatching {
            appContext.packageManager
                .queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
                .mapNotNull { it.serviceInfo?.packageName }
        }.getOrDefault(emptyList())
        return installed.firstOrNull { it == GOOGLE_TTS_PACKAGE }
    }

    /**
     * @param explicit non-null only when we pin an engine regardless of the system
     *   default, which decides the 2- vs 3-arg [TextToSpeech] constructor.
     * @param target the engine that should be bound; the identity we compare
     *   against [requestedEngine] to decide whether a re-bind is due.
     */
    private data class EngineChoice(val explicit: String?, val target: String?)

    /**
     * In system mode, whatever system Settings names right now. Otherwise the
     * app's own engine, falling back to the system default when it isn't
     * installed. Re-read on every use rather than tracked, so a change made in
     * system Settings while we were backgrounded needs nothing to have noticed it.
     */
    private fun resolveEngine(useSystemTts: Boolean): EngineChoice {
        val explicit = if (useSystemTts) null else appEnginePackage()
        return EngineChoice(explicit, explicit ?: systemDefaultSynth())
    }

    /**
     * The bound engine, re-binding first if it isn't the one [resolveEngine] names.
     *
     * `TextToSpeech` keeps whatever engine it connected to for its whole life and
     * reports only that engine's `voices`, so an engine swapped in system Settings
     * can only be picked up by building a new instance. Binding is asynchronous,
     * hence suspend: callers get the real engine rather than an empty list.
     *
     * Returns `null` when the engine fails to connect.
     */
    private suspend fun readyEngine(useSystemTts: Boolean): TextToSpeech? {
        // resolveEngine can hit PackageManager, a binder round-trip; keep it off
        // whichever thread the caller happens to be on.
        val choice = withContext(Dispatchers.IO) { resolveEngine(useSystemTts) }
        val ready = engineMutex.withLock {
            if (tts == null || choice.target != requestedEngine) {
                Log.d(TAG, "Rebinding TTS: $requestedEngine -> ${choice.target}")
                tts?.shutdown()
                isInitialized = false
                tts = buildEngine(choice)
            }
            engineReady
        }
        if (ready?.await() == true) return tts

        // A failed bind leaves a non-null `TextToSpeech` behind — the object exists
        // even when init failed — and `requestedEngine` still naming the target, so
        // the guard above would never fire again and every later call would await
        // this same already-completed `false`. Clear the engine so the next call
        // rebuilds instead of the service going mute for the life of the process.
        engineMutex.withLock {
            // Skip if someone rebound while we were awaiting; that engine is not ours
            // to discard.
            if (engineReady === ready) {
                Log.w(TAG, "Engine bind failed; clearing so the next call retries")
                tts?.shutdown()
                tts = null
                requestedEngine = null
                engineReady = null
                isInitialized = false
            }
        }
        return null
    }

    /**
     * Constructs a fresh [TextToSpeech] for [choice] and wires the
     * [UtteranceProgressListener] that backs [isSpeaking]. Call through
     * [readyEngine] rather than directly — it holds the lock and awaits the bind.
     */
    private fun buildEngine(choice: EngineChoice): TextToSpeech {
        // In system mode, bind whatever system Settings names (2-arg constructor).
        // Otherwise bind the app's own engine explicitly (3-arg), so the in-app
        // voice does not silently follow the system default — that is what made
        // the voice picker list SherpaTTS voices for both options.
        val (explicitEngine, chosenEngine) = choice
        requestedEngine = chosenEngine
        val ready = CompletableDeferred<Boolean>()
        engineReady = ready
        Log.d(
            TAG,
            "Building TTS engine (pinned=$explicitEngine, requested=$chosenEngine, " +
                "system default=${systemDefaultSynth()})"
        )
        // The callback can arrive before `tts` has been reassigned (the field still
        // holds the engine we just shut down), so configure the instance we hold
        // here rather than reading the field.
        lateinit var engine: TextToSpeech
        val onInit = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                engine.setAudioAttributes(buildAudioAttributes())
                // `defaultEngine` reports the *system* default, not the engine this
                // instance connected to, and `getCurrentEngine()` is not public API.
                // So it only tells us anything on the 2-arg path, where the two are
                // meant to agree; a mismatch there means the framework refused the
                // bind and fell back (Samsung rejects non-allowlisted callers).
                if (explicitEngine == null && chosenEngine != null &&
                    engine.defaultEngine != chosenEngine
                ) {
                    Log.w(
                        TAG,
                        "Requested engine $chosenEngine unavailable; using ${engine.defaultEngine} instead"
                    )
                } else {
                    Log.d(TAG, "TTS initialized successfully (requested: $chosenEngine)")
                }
                logEngineCapabilities(engine)
            } else {
                Log.e(TAG, "TTS initialization failed: $status")
            }
            ready.complete(status == TextToSpeech.SUCCESS)
        }

        engine = if (explicitEngine != null) {
            TextToSpeech(appContext, onInit, explicitEngine)
        } else {
            TextToSpeech(appContext, onInit)
        }

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "Started speaking")
                _isSpeaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "Finished speaking")
                _isSpeaking.value = false
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS error")
                _isSpeaking.value = false
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "TTS error: $errorCode")
                _isSpeaking.value = false
            }
        })
        return engine
    }

    /**
     * System-TTS mode leaves the engine's own configuration alone — but some
     * engines connect with *nothing* selected (SherpaTTS reports
     * `voice=null locale=null`), and [TextToSpeech.speak] then renders silence.
     * This fills in a missing selection only; an engine that already has a voice
     * keeps whatever the user configured in system Settings.
     *
     * Locale naming differs per engine (SherpaTTS advertises bare `ru`, Google
     * `ru-RU`), so the fallback matches on language rather than exact equality.
     */
    private fun ensureEngineHasVoice(engine: TextToSpeech, language: String) {
        if (runCatching { engine.voice }.getOrNull() != null) return

        val desired = localeFromCode(language)
        val supported = runCatching { engine.availableLanguages }.getOrNull().orEmpty()
        val chosen = supported.firstOrNull { it == desired }
            ?: supported.firstOrNull { matchesLanguage(it, language) }
            ?: supported.firstOrNull()
            ?: desired

        val status = engine.setLanguage(chosen)
        Log.d(TAG, "Engine had no voice selected; set language to $chosen (status=$status, supported=$supported)")
    }

    /**
     * Dumps what the freshly connected engine reports it can render. Engines vary
     * wildly in how they name locales (sherpa-onnx exposes ISO-639-3 "rus" with
     * voices like `ru_RU-irina-medium`, Google uses BCP-47 "ru-RU"), and a
     * mismatch makes [speak] fail silently, so this is the first thing to check
     * when TTS goes quiet.
     *
     * The summary line is always logged — `voice=null` is exactly the state that
     * makes an engine render silence, so it's worth having in a production bug
     * report. The per-voice fan-out is debug-only: Google TTS reports ~400 voices
     * and this runs on every re-init, and release builds set
     * `isMinifyEnabled = false`, so nothing would strip it for us.
     */
    private fun logEngineCapabilities(engine: TextToSpeech) {
        runCatching {
            val voices = engine.voices.orEmpty()
            Log.d(
                TAG,
                "  current voice=${engine.voice?.name} locale=${engine.voice?.locale} " +
                    "(${voices.size} voices available)"
            )
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "  availableLanguages=${engine.availableLanguages?.map { it.toLanguageTag() }}")
                voices.sortedBy { it.name }.forEach {
                    Log.d(TAG, "  voice: ${it.name} locale=${it.locale} (iso3=${runCatching { it.locale.isO3Language }.getOrNull()})")
                }
            }
        }.onFailure { Log.w(TAG, "Could not enumerate voices: ${it.message}") }
    }

    private fun buildAudioAttributes(): AudioAttributes {
        val usage = if (useVoiceCommPath) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION
        } else {
            AudioAttributes.USAGE_MEDIA
        }
        return AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    private fun applyAudioAttributes() {
        tts?.setAudioAttributes(buildAudioAttributes())
    }

    /**
     * Configure the assistant's audio path and hold audio focus for the
     * duration of voice-communication mode.
     *
     * @param useVoiceCommPath `true` to route through the voice-communication
     *     path (enables echo cancellation, used when the user is on headphones
     *     with Vosk + allowInterruptions so they can be interrupted while
     *     speaking). While `true`, audio focus is held on the voice-call stream
     *     to keep the platform in MODE_IN_COMMUNICATION — this is what keeps a
     *     Bluetooth headset's SCO link up between TTS utterances so the
     *     headset mic stays available to VoskService's AudioRecord. `false`
     *     routes through the media path ([AudioAttributes.USAGE_MEDIA]) and
     *     releases any held focus.
     */
    fun setAssistantAudioMode(useVoiceCommPath: Boolean) {
        if (this.useVoiceCommPath == useVoiceCommPath) return
        this.useVoiceCommPath = useVoiceCommPath
        if (isInitialized) applyAudioAttributes()
        if (useVoiceCommPath) requestAudioFocus() else abandonAudioFocus()
    }

    /**
     * @param useSystemTts this thread's effective engine choice — see the class
     *   doc for why it is passed in rather than remembered.
     * @param useExternalTts true to synthesize through the OpenAI-compatible
     *   `/v1/audio/speech` endpoint instead of a local engine; takes precedence
     *   over [useSystemTts] when both are somehow set. Callers derive it from
     *   `effective.ttsMode == TtsMode.EXTERNAL`, never from the raw flag, so the
     *   "process on device" guard is applied before this is reached.
     * @param externalVoice the server-side voice id, or null to let the server pick.
     * @param externalEndpoint the server base URL this thread's [EffectiveSettings] resolves.
     * @param externalToken the API key (may be empty for local servers).
     * @param onError invoked with the failure when external synthesis cannot complete;
     *   callers map it to a user-facing message. Never receives a
     *   [CancellationException] — those are rethrown and end the job silently.
     */
    fun speak(
        text: String,
        language: String = "en",
        voiceName: String? = null,
        useSystemTts: Boolean,
        useExternalTts: Boolean = false,
        externalVoice: String? = null,
        externalEndpoint: String = "",
        externalToken: String = "",
        onError: ((Throwable) -> Unit)? = null
    ) {
        stop()

        if (text.isEmpty()) {
            Log.d(TAG, "Ignoring empty text")
            return
        }

        // Resolving the engine can suspend (only when it has to re-bind), so hop
        // off the caller's thread; every call site is a click handler or effect
        // that has nothing to wait for. The `stop()` above already cancelled any
        // previous job, so this cannot race an utterance we are replacing.
        speakJob = scope.launch {
            speakNow(
                text, language, voiceName, useSystemTts,
                useExternalTts, externalVoice, externalEndpoint, externalToken, onError
            )
        }
    }

    private suspend fun speakNow(
        text: String,
        language: String,
        voiceName: String?,
        useSystemTts: Boolean,
        useExternalTts: Boolean,
        externalVoice: String?,
        externalEndpoint: String,
        externalToken: String,
        onError: ((Throwable) -> Unit)?
    ) {
        if (useExternalTts) {
            val cleanText = SpeechText.forSpeech(text)
            Log.d(
                TAG,
                "speak external: endpoint=${externalEndpoint.ifBlank { "<blank>" }} " +
                    "voice=${externalVoice ?: "<server default>"} textLen=${cleanText.length}"
            )
            try {
                val bytes = externalTts.synthesize(externalEndpoint, externalToken, cleanText, externalVoice)
                if (!playExternal(bytes)) {
                    // The only failure playback cannot report through its own listeners:
                    // the bytes never reached a file, so no player exists to fail. The
                    // caller is told the same way a synthesis failure is — a preview
                    // sheet must not spin forever, and a call must not end in silence.
                    onError?.invoke(IOException("Could not stage synthesized audio"))
                }
            } catch (e: CancellationException) {
                // Never swallow cancellation — a replaced utterance or a stop() must end the job.
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "External TTS failed: ${e.message}", e)
                _isSpeaking.value = false
                onError?.invoke(e)
            }
            return
        }

        val tts = readyEngine(useSystemTts) ?: run {
            Log.w(TAG, "No TTS engine available")
            return
        }

        val cleanText = SpeechText.forSpeech(text)

        Log.d(
            TAG,
            "speak: useSystemTts=$useSystemTts engine=${tts.defaultEngine} " +
                "lang=$language voice=$voiceName textLen=${cleanText.length}"
        )

        // App-managed voice: set the chosen voice (or fall back to the language
        // locale). In system-TTS mode none of these are touched so the engine
        // renders with whatever the user configured in system Settings.
        if (!useSystemTts) {
            if (!voiceName.isNullOrEmpty()) {
                val voice = runCatching { tts.voices }.getOrNull()?.firstOrNull { it.name == voiceName }
                if (voice != null) {
                    tts.voice = voice
                } else {
                    tts.language = localeFromCode(language)
                }
            } else {
                tts.language = localeFromCode(language)
            }

            tts.setPitch(1.0f)
        } else {
            // System-TTS mode: honour an explicit voice pick if the user made one
            // (the picker lists whatever the bound engine reports), otherwise leave
            // the engine's own Settings-configured voice alone.
            val voice = voiceName?.takeIf { it.isNotEmpty() }
                ?.let { name -> runCatching { tts.voices }.getOrNull()?.firstOrNull { it.name == name } }
            if (voice != null) tts.voice = voice else ensureEngineHasVoice(tts, language)
        }

        val utteranceId = UUID.randomUUID().toString()
        // speak() reports rejection through its return value, not the progress
        // listener: an engine that cannot render the current voice/locale simply
        // returns ERROR and stays silent, with no onError callback to notice.
        val result = tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "speak() rejected (result=$result); current voice=${runCatching { tts.voice?.name }.getOrNull()}")
        }
    }

    fun greeting(language: String): String {
        return mapOf(
            "en" to "Hello",
            "ru" to "Привет",
            "fr" to "Bonjour",
            "de" to "Hallo",
            "es" to "Hola",
            "pt" to "Olá"
        )[language] ?: "Hello"
    }

    /**
     * The voices of the engine selected right now — [readyEngine] re-binds first
     * if system Settings has since named a different one, so this never reports a
     * stale engine's voices. Read live off the engine (no cache): voice data
     * installed or removed inside the same engine shows up on the next call.
     *
     * @param useSystemTts the engine choice to list voices for — the picker must
     *   show the thread's own engine, not whichever one last spoke.
     */
    suspend fun getAvailableVoices(language: String, useSystemTts: Boolean): List<android.speech.tts.Voice> {
        val engine = readyEngine(useSystemTts) ?: return emptyList()
        // Engines can throw here, notably right after a re-bind (see `speak`).
        val voices = runCatching { engine.voices }.getOrNull()?.toList() ?: emptyList()
        return if (language.isNotEmpty()) {
            voices.filter { matchesLanguage(it.locale, language) }.sortedBy { it.name }
        } else {
            voices.sortedBy { it.name }
        }
    }

    /**
     * Whether [locale] is the language [code] names, in whichever form the engine
     * chose to advertise it.
     *
     * `Locale.getLanguage` hands back whatever the engine supplied — Google reports
     * `ru-RU` so it yields `ru`, sherpa-onnx reports ISO-639-3 `rus` so it yields
     * `rus`. Comparing that against our own 2-letter codes therefore drops every
     * voice from the latter, which is why such engines showed an empty picker.
     * ISO-639-3 is the common denominator (`ru` and `rus` both resolve to `rus`),
     * so fall back to it; it throws for languages with no 3-letter code, hence the
     * `runCatching`.
     */
    private fun matchesLanguage(locale: Locale, code: String): Boolean {
        val desired = localeFromCode(code)
        if (locale.language == desired.language) return true
        return runCatching { locale.isO3Language == desired.isO3Language }.getOrDefault(false)
    }

    /**
     * Plays externally-synthesized AAC bytes through [MediaPlayer] — the one decoder
     * every Android device has, no third-party libs. Callbacks arrive on the thread
     * that created the player; [speakNow] runs on the Main scope so state writes are
     * safe here.
     *
     * Writes the bytes to one fixed cache file rather than a per-utterance temp: the
     * file is rewritten on every utterance, so there is nothing to clean up, and a
     * fixed path avoids ever leaking files.
     */
    private fun playExternal(bytes: ByteArray): Boolean {
        stopExternalPlayer()
        val file = File(appContext.cacheDir, "external_tts.aac")
        try {
            file.writeBytes(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "Writing external audio failed: ${e.message}")
            _isSpeaking.value = false
            return false
        }

        val player = MediaPlayer()
        player.setAudioAttributes(buildAudioAttributes())
        player.setOnPreparedListener {
            // Prepared → playback actually reaches the DAC here.
            _isSpeaking.value = true
            it.start()
        }
        player.setOnCompletionListener {
            Log.d(TAG, "External audio finished")
            // Raise the resume edge now — the call loop resumes the mic on this flip —
            // but defer the teardown by [EXTERNAL_DRAIN_GRACE_MS] so the sink can drain
            // the tail. The identity guard keeps a stale release from touching a player
            // that replaced this one (a new speak/stop releases its own); `destroy()`
            // cancels the scope, so the pending release dies with the service.
            _isSpeaking.value = false
            scope.launch {
                delay(EXTERNAL_DRAIN_GRACE_MS)
                if (externalPlayer === player) stopExternalPlayer(player)
            }
        }
        player.setOnErrorListener { _, _, _ ->
            Log.e(TAG, "External audio playback error")
            _isSpeaking.value = false
            stopExternalPlayer(player)
            // Consumed — we surfaced the state ourselves, no need for MediaPlayer to
            // treat this as fatal.
            true
        }
        externalPlayer = player
        player.setDataSource(file.absolutePath)
        player.prepareAsync()
        return true
    }

    private fun stopExternalPlayer(player: MediaPlayer? = externalPlayer) {
        if (externalPlayer === player) externalPlayer = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
    }

    /**
     * Both halves are needed. `speak` is asynchronous — it returns before the
     * engine is even bound — so a stop arriving in that window has no utterance
     * to interrupt yet and must cancel the pending job instead; one that arrives
     * later has nothing left to cancel and must interrupt the engine. Barge-in
     * hits the first case whenever the engine is cold. External playback is
     * stopped the same way: the pending synthesize is cancelled with the job,
     * a playing [MediaPlayer] is stopped and released.
     */
    fun stop() {
        speakJob?.cancel()
        speakJob = null
        if (tts?.isSpeaking == true) {
            Log.d(TAG, "Stopping speech")
            tts?.stop()
        }
        stopExternalPlayer()
        _isSpeaking.value = false
    }

    fun destroy() {
        stop()
        abandonAudioFocus()
        scope.cancel()
        tts?.shutdown()
        tts = null
    }

    private fun requestAudioFocus() {
        val manager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val attrs = buildAudioAttributes()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { /* no-op — we just want eligibility */ }
            .build()
        audioFocusRequest = request
        try {
            val result = manager.requestAudioFocus(request)
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.w(TAG, "Audio focus request denied: $result")
            }
        } catch (e: Exception) {
            Log.e(TAG, "requestAudioFocus failed: ${e.message}")
        }
    }

    private fun abandonAudioFocus() {
        val manager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val request = audioFocusRequest ?: return
        try {
            manager.abandonAudioFocusRequest(request)
        } catch (_: Exception) {
            // ignore — best-effort cleanup
        }
        audioFocusRequest = null
    }

    /** Resolves a UI language code via the shared [SettingsManager.localeForSTT]
     *  table, so TTS can't drift from the locale STT already uses. */
    private fun localeFromCode(code: String): Locale =
        Locale.forLanguageTag(SettingsManager.localeForSTT[code] ?: "en-US")
}
