//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android

import android.app.Application
import ai.roleswitch.android.data.database.AppDatabase
import ai.roleswitch.android.data.database.getAppDatabase
import ai.roleswitch.android.service.LLMService
import ai.roleswitch.android.service.LiteRtModelDownloader
import ai.roleswitch.android.service.LitertModels
import ai.roleswitch.android.service.LiteRtService
import ai.roleswitch.android.service.LlmForegroundService
import ai.roleswitch.android.service.OAIService
import ai.roleswitch.android.service.onDeviceInferenceSupported
import ai.roleswitch.android.service.initializePdfBox
import ai.roleswitch.android.service.STTService
import ai.roleswitch.android.service.TTSService
import ai.roleswitch.android.service.VoskModelDownloader
import ai.roleswitch.android.service.VoskService
import ai.roleswitch.android.service.mcp.McpService
import ai.roleswitch.android.settings.SettingsManager
import ai.roleswitch.android.settings.getSettingsManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RolesWitchApp : Application() {
    lateinit var database: AppDatabase
    lateinit var settingsManager: SettingsManager
    lateinit var oaiService: OAIService
    lateinit var sttService: STTService
    lateinit var voskService: VoskService
    lateinit var voskModelDownloader: VoskModelDownloader
    lateinit var ttsService: TTSService
    lateinit var llmService: LLMService
    lateinit var mcpService: McpService
    lateinit var liteRtService: LiteRtService
    lateinit var liteRtModelDownloader: LiteRtModelDownloader

    /**
     * Process-wide scope for things that must outlive any single Activity / composable
     * (here: observing LLM + recording state and driving the foreground service).
     */
    /**
     * Process-lifetime scope. Used for work that must survive the composition
     * that started it — notably Settings → Reset, which navigates away from the
     * screen (and cancels its `rememberCoroutineScope`) partway through.
     */
    internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * `true` while any Activity of this process is in the started state
     * (i.e. the app is in the foreground). Driven by [ProcessLifecycleOwner].
     */
    private val _isAppForegrounded = MutableStateFlow(true)

    /**
     * True while any Activity of this process is started. Exposed so the on-device engine
     * can be warmed on the way *in* — `LitertEngineSyncEffect` syncs when this turns true
     * and does nothing when it turns false.
     *
     * Nothing releases the engine on backgrounding, so a call does not have to end there:
     * both engines now keep running with the screen locked. This used to read the other
     * way, and the call screen popped itself for on-device calls to avoid a reload that
     * can no longer happen.
     */
    val isAppForegrounded: StateFlow<Boolean> = _isAppForegrounded.asStateFlow()

    /**
     * `true` for as long as the call screen is composed, which is exactly as long as a
     * call lasts — being on that screen *is* being in the call, and ending one pops the
     * back stack. Set from `AssistantScreen`'s teardown effect, the same one that stops
     * the mic and TTS.
     *
     * It exists because "app is not foregrounded" is not the same question as "the user
     * is not looking at this reply", and [observeResponseCompletions] needs the second
     * one. A remote-engine call deliberately survives the screen locking, so every
     * spoken turn completed with the app backgrounded and posted a notification for a
     * reply the user had just heard out loud.
     */
    private val _isCallActive = MutableStateFlow(false)
    val isCallActive: StateFlow<Boolean> = _isCallActive.asStateFlow()

    /** See [isCallActive]. Called from the call screen's `DisposableEffect`. */
    fun setCallActive(active: Boolean) {
        _isCallActive.value = active
    }

    /** Whether [LlmForegroundService] is believed to be running (see [observeForegroundState]). */
    private var foregroundServiceStarted = false

    override fun onCreate() {
        super.onCreate()

        // pdfbox-android resolves its glyph lists / fonts / CMaps from assets via
        // `PDFBoxResourceLoader`; without this one-time init the first PDF send crashes
        // with `ExceptionInInitializerError: GlyphList '.../glyphlist.txt' not found`.
        initializePdfBox(this)

        database = getAppDatabase(this)
        settingsManager = getSettingsManager(this)
        oaiService = OAIService()
        sttService = STTService(this)
        voskService = VoskService(this)
        voskModelDownloader = VoskModelDownloader(this)
        // Seed the engine choice at construction. Without it the service binds the
        // in-app engine and only learns the real setting once a thread is opened,
        // so the Settings voice picker would list Google's voices until then.
        ttsService = TTSService(this, settingsManager.useSystemTts.value)
        mcpService = McpService()
        liteRtService = LiteRtService(this)
        liteRtModelDownloader = LiteRtModelDownloader(this)
        llmService = LLMService(
            oaiService = oaiService,
            settingsManager = settingsManager,
            messageDao = database.messageDao(),
            threadDao = database.messageThreadDao(),
            mcpToolDao = database.mcpToolDao(),
            mcpService = mcpService,
            // Below Android 12 the on-device engine is unavailable: a stored or
            // onboarded `useOnDevice` choice then falls back to remote (see engineFor)
            // instead of attempting inference on old hardware.
            onDeviceEngine = if (onDeviceInferenceSupported) liteRtService else null
        )

        LlmForegroundService.createStreamingChannel(this)
        LlmForegroundService.createCompletionChannel(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                _isAppForegrounded.value = true
            }
            override fun onStop(owner: LifecycleOwner) {
                _isAppForegrounded.value = false
            }
        })

        observeForegroundState()
        observeResponseCompletions()
    }

    /**
     * Combined state used to decide whether the foreground service (which keeps
     * the process alive during streaming / recording) should be running.
     */
    private data class ForegroundState(
        val llmActive: Boolean,
        val recording: Boolean,
        val downloading: Boolean,
        /** Model being downloaded, for the notification text. */
        val downloadLabel: String? = null,
        /** 0..100, or -1 while connecting or verifying (shown as indeterminate). */
        val downloadPercent: Int = -1
    ) {
        val shouldRun: Boolean get() = llmActive || recording || downloading
    }

    private fun observeForegroundState() {
        appScope.launch {
            // Paired first so the five-argument `combine` still fits: the notification
            // needs the model's name as well as the transfer's progress.
            val download = combine(
                liteRtModelDownloader.state,
                liteRtModelDownloader.activeModelId
            ) { downloadState, modelId -> downloadState to modelId }

            combine(
                llmService.isThinking,
                llmService.isStreaming,
                sttService.isRecording,
                voskService.isRecording,
                download
            ) { thinking, streaming, sttRecording, voskRecording, (downloadState, modelId) ->
                ForegroundState(
                    llmActive = thinking || streaming,
                    recording = sttRecording || voskRecording,
                    // A model download runs for many minutes; without the service the
                    // process is killed the moment the user leaves the app.
                    downloading = downloadState is LiteRtModelDownloader.State.Downloading ||
                        downloadState is LiteRtModelDownloader.State.Verifying,
                    downloadLabel = modelId?.let { LitertModels.get(it)?.displayName },
                    // Whole percent, not raw bytes: `distinctUntilChanged` below then lets
                    // through at most 100 notification updates over a multi-gigabyte
                    // transfer instead of one every 250 ms.
                    downloadPercent = (downloadState as? LiteRtModelDownloader.State.Downloading)
                        ?.let { progress ->
                            if (progress.total > 0) {
                                (progress.downloaded * 100 / progress.total).toInt().coerceIn(0, 100)
                            } else {
                                -1
                            }
                        } ?: -1
                )
            }.distinctUntilChanged().collect { state ->
                if (state.shouldRun) {
                    LlmForegroundService.start(
                        context = this@RolesWitchApp,
                        isRecording = state.recording,
                        isDownloading = state.downloading,
                        downloadLabel = state.downloadLabel,
                        downloadPercent = state.downloadPercent
                    )
                } else if (foregroundServiceStarted) {
                    // Skip the very first (all-false) emission: stopping a
                    // service that was never started is a pointless IPC on
                    // every cold start.
                    LlmForegroundService.stop(this@RolesWitchApp)
                }
                foregroundServiceStarted = state.shouldRun
            }
        }
    }

    /**
     * Listen for completed LLM responses and post a messenger-style notification
     * *only* when the app is in the background (which also covers screen-locked).
     * In the foreground the user already sees the response in the chat, so we
     * stay silent.
     */
    private fun observeResponseCompletions() {
        appScope.launch {
            llmService.responseCompleted.collect { response ->
                if (_isAppForegrounded.value) return@collect
                // A call speaks its replies, so a notification for one is telling the user
                // something they just heard. The app is "backgrounded" for the whole of a
                // locked-screen call, so [_isAppForegrounded] alone cannot tell the two
                // cases apart — this is the one that means "answered in a call" rather
                // than "answered after the user typed and walked away", which is the case
                // the notification is actually for.
                if (_isCallActive.value) return@collect
                val title = resolveThreadTitle(response.threadId) ?: ""
                LlmForegroundService.postCompletionNotification(
                    context = this@RolesWitchApp,
                    threadTitle = title,
                    threadId = response.threadId,
                    content = response.content
                )
            }
        }
    }

    private suspend fun resolveThreadTitle(threadId: String): String? {
        return withContext(Dispatchers.IO) {
            runCatching {
                database.messageThreadDao().getThreadById(threadId)?.title
            }.getOrNull()
        }
    }
}
