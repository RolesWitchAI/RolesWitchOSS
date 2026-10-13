//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.ui.assistant

import android.Manifest
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import ai.roleswitch.android.RolesWitchApp
import ai.roleswitch.android.ui.components.KeepScreenOn
import ai.roleswitch.android.ui.components.LitertEngineSyncEffect
import ai.roleswitch.android.ui.components.LitertWarmUpOverlay
import ai.roleswitch.android.ui.components.rememberPermissionRequester
import ai.roleswitch.android.data.model.MessageThread
import ai.roleswitch.android.settings.EffectiveSettings
import ai.roleswitch.android.service.LiteRtService
import ai.roleswitch.android.service.LitertModelStore
import ai.roleswitch.android.service.LitertModels
import ai.roleswitch.android.service.VoskModels
import ai.roleswitch.android.service.OAIError
import ai.roleswitch.android.service.ExternalTtsNotConfiguredException
import ai.roleswitch.android.service.TTSService
import ai.roleswitch.android.settings.TtsMode
import ai.roleswitch.android.util.MarkdownProcessor
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.material3.TopAppBarDefaults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import ai.roleswitch.android.R
import ai.roleswitch.android.ui.components.InfoDialog
import ai.roleswitch.android.ui.components.PermissionDeniedDialog
import ai.roleswitch.android.ui.components.safePopBackStack
import ai.roleswitch.android.ui.components.ThreadAvatar


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(app: RolesWitchApp, navController: NavController, threadId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Read in composition (not via LocalContext) so lint stays quiet; formatted at
    // failure time with the engine's message below.
    val prepareFailedPattern = stringResource(R.string.litert_prepare_failed)

    val settingsManager = app.settingsManager
    val sttService = app.sttService
    val voskService = app.voskService
    val ttsService = app.ttsService
    val llmService = app.llmService

    val isThinking by llmService.isThinking.collectAsState()
    val ttsIsSpeaking by ttsService.isSpeaking.collectAsState()
    var assistantMessage by remember { mutableStateOf("") }
    var showErrorAlert by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(false) }
    var isVideoCallActive by remember { mutableStateOf(false) }
    var showCameraPermissionAlert by remember { mutableStateOf(false) }
    var showVisionDisabledAlert by remember { mutableStateOf(false) }
    var showMutedVideoAlert by remember { mutableStateOf(false) }
    var showLanguageNotSupportedAlert by remember { mutableStateOf(false) }
    var showVoskNotEnabledAlert by remember { mutableStateOf(false) }
    var showModelNotConfiguredAlert by remember { mutableStateOf(false) }

    var threads by remember { mutableStateOf<List<MessageThread>>(emptyList()) }
    val currentThread by app.database.messageThreadDao().observeThreadById(threadId)
        .collectAsState(initial = null)

    // Effective settings: per-thread overrides when enabled, otherwise the global values.
    // Recomputed when the thread changes; global settings are edited in a separate screen
    // so they can't shift while the call is in the foreground.
    val effective = remember(currentThread) {
        EffectiveSettings.resolve(currentThread ?: MessageThread(threadId = threadId, title = ""), settingsManager)
    }

    // [effective] again, but only once the thread row is really loaded — null before that.
    //
    // The placeholder above resolves every override to the *global* value, which is right for
    // the things read synchronously each redraw (the send gate, the vision check, the STT/TTS
    // settings) but wrong for anything that acts once, on arrival. This screen used to hand that
    // placeholder resolution straight to the engine sync below, and the placeholder has
    // `useCustomSettings = false` — so a thread that overrides the engine to remote read, for the
    // frames before Room emitted, like a thread wanting the global on-device model. The eager
    // sync then loaded gigabytes behind a blocking overlay for a call that would answer through
    // an API, and because `ensureEngine` is NonCancellable the unload that followed could only
    // queue behind a load it could not abandon. Same shape as iOS's `resolvedSettings`.
    val resolvedSettings: EffectiveSettings? = currentThread?.let { thread ->
        EffectiveSettings.resolve(thread, settingsManager)
    }

    // Not the load — that happens before this screen opens, on the chat screen's call
    // button, and the recording effect below waits on it again. What is left is the unload
    // half: a call on the remote engine drops a model an earlier on-device thread left
    // resident, rather than holding gigabytes it will never ask a question of.
    //
    // `resolvedSettings`, not `effective`: nil until the thread row arrives, so there is no
    // engine to sync against and the whole effect stands down. It re-fires the moment the row
    // lands, which is what a call that *is* on device needs. And the resolved value is a
    // snapshot taken then — the settings screens are unreachable from here, so this cannot
    // re-fire on a changed selection: there is no such thing as changing the model mid-call.
    LitertEngineSyncEffect(app, resolvedSettings)

    val resolvedSttLocale = ai.roleswitch.android.settings.SettingsManager.localeForSTT[effective.language] ?: "en-US"

    val speechService = if (effective.useVosk) voskService else sttService
    var isHeadphonesConnected by remember { mutableStateOf(false) }
    val canInterrupt = effective.allowInterruptions && effective.useVosk && isHeadphonesConnected

    val isRecording by speechService.isRecording.collectAsState()

    // Unconditional, for as long as this screen is composed: being on it *is* being in
    // the call — ending one pops the back stack — so the display has to stay up for the
    // whole of it. Gating this on `isRecording` let the screen sleep through everything
    // that is not the user actively talking: the model loading, the assistant thinking,
    // the reply being spoken, and any moment the mic was muted. `KeepScreenOn` clears
    // the flag in `onDispose`, so leaving by any route releases it.
    KeepScreenOn(enabled = true)

    val isListening by speechService.isListening.collectAsState()
    val humanMessage by speechService.humanMessage.collectAsState()
    val finalMessage by speechService.finalMessage.collectAsState()
    val isTimedOut by speechService.isTimedOut.collectAsState()

    val micIsOff = isMuted || (isTimedOut && !effective.useVosk)

    val cameraManager = remember { CameraManager(context) }

    LaunchedEffect(Unit) {
        app.database.messageThreadDao().observeAllThreads().collect {
            threads = it
        }
    }

    val currentThreadTitle = threads.firstOrNull { it.threadId == threadId }?.title ?: stringResource(R.string.voice_assistant)

    // Engine-aware: on device there is no endpoint or token to check, only a model — and
    // the model is only as configured as its weights on disk. A selection whose model was
    // deleted reads as configured to the registry but the call would die in the load, so
    // the guard catches it here and routes to settings instead, exactly as on iOS.
    val hasValidModel = effective.isConfigured && (!effective.useOnDevice ||
        LitertModels.get(effective.litertModel)
            ?.let { LitertModelStore.isDownloaded(context, it) } == true)

    // Flow: finalMessage received → capture frame if video → send to LLM → TTS speaks
    LaunchedEffect(finalMessage) {
        if (finalMessage.isEmpty()) return@LaunchedEffect

        if (canInterrupt) {
            if (isThinking) return@LaunchedEffect
            if (ttsIsSpeaking) {
                ttsService.stop()
                assistantMessage = ""
            }
            if (isProcessing && !ttsIsSpeaking) return@LaunchedEffect
        } else {
            if (isProcessing) return@LaunchedEffect
        }

        if (!hasValidModel) {
            showModelNotConfiguredAlert = true
            return@LaunchedEffect
        }

        isProcessing = true
        speechService.clearHumanMessage()
        if (!canInterrupt) {
            speechService.pauseListening()
        }

        try {
            // Capture camera frame if video call is active
            val images = if (isVideoCallActive) {
                cameraManager.capture()
            } else {
                emptyList()
            }

            // Send to LLM with optional image
            llmService.sendMessage(text = finalMessage, threadId = threadId, images = images)

            // Get the response
            val messages = app.database.messageDao().getMessagesByThread(threadId)
            val response = messages.lastOrNull { !it.isUser }?.content ?: ""

            if (response.isNotEmpty() && isRecording) {
                assistantMessage = response
                ttsService.speak(
                    text = response,
                    language = effective.language,
                    voiceName = effective.voice.ifEmpty { null },
                    useSystemTts = effective.useSystemTts,
                    useExternalTts = effective.ttsMode == TtsMode.EXTERNAL,
                    externalVoice = effective.externalTtsVoice.ifEmpty { null },
                    externalEndpoint = effective.externalTtsAPI,
                    externalToken = effective.externalTtsKey,
                    // Two distinct failures, two distinct messages: the server answered
                    // and failed (Server error) vs. there is no server configured at all
                    // (Voice is not configured) — a blank endpoint is a configuration
                    // problem, not a server problem, and the fix differs in kind.
                    onError = { t ->
                        errorMessage = if (t is ExternalTtsNotConfiguredException) {
                            context.getString(R.string.tts_voice_not_configured)
                        } else {
                            context.getString(R.string.tts_server_error)
                        }
                        showErrorAlert = true
                    }
                )
            } else {
                // No response or stopped recording
                if (!canInterrupt && isRecording && !isMuted) {
                    speechService.resumeListening()
                }
            }
        } catch (e: CancellationException) {
            // ignore
        } catch (e: OAIError) {
            if (!e.isSilent) {
                errorMessage = e.message ?: "Unknown error"
                showErrorAlert = true
            }
            if (!canInterrupt && isRecording && !isMuted) {
                speechService.resumeListening()
            }
        } catch (e: Exception) {
            errorMessage = e.message ?: "Unknown error"
            showErrorAlert = true
            if (!canInterrupt && isRecording && !isMuted) {
                speechService.resumeListening()
            }
        }

        isProcessing = false
    }

    // Resume STT after TTS finishes speaking
    LaunchedEffect(ttsIsSpeaking) {
        if (!ttsIsSpeaking && isRecording && !isProcessing) {
            assistantMessage = ""
            speechService.clearHumanMessage()
            if (!isMuted) {
                speechService.resumeListening()
            }
        }
    }

    // Apply echo cancellation and continuous Vosk mode when interruptions are enabled
    LaunchedEffect(canInterrupt) {
        // When the user is on headphones + Vosk + allowInterruptions we route TTS
        // through the voice-communication path so the engine can apply AEC and the
        // assistant can be interrupted mid-utterance. Otherwise we stay on the
        // assistant audio path, which the system allows to play immediately even
        // when the app is in the background (e.g. screen locked during a call).
        ttsService.setAssistantAudioMode(canInterrupt)
        voskService.continuousMode = canInterrupt
    }

    // Handle mute toggle
    LaunchedEffect(isMuted) {
        if (isRecording) {
            if (isMuted) {
                speechService.pauseListening()
            } else if (!isProcessing && !ttsIsSpeaking) {
                speechService.resumeListening()
            } else if (canInterrupt && !isThinking) {
                speechService.resumeListening()
            }
        }
    }

    LaunchedEffect(currentThread) {
        if (currentThread == null) return@LaunchedEffect
        if (!hasValidModel) {
            showModelNotConfiguredAlert = true
        } else {
            // The mic never opens ahead of the engine. The chat screen loads the model before
            // it navigates here, so this normally passes straight through — `syncTo` hands
            // back a resident engine without reloading it — but the weights can still have
            // been dropped in between (a settings change, or the model deleted), and a first
            // turn spoken into a model that is still loading is transcribed with nothing
            // there to answer it.
            if (effective.useOnDevice && !app.liteRtService.syncTo(effective)) {
                val reason = (app.liteRtService.initState.value as? LiteRtService.InitState.Failed)?.message
                errorMessage = prepareFailedPattern.format(reason.orEmpty())
                showErrorAlert = true
                return@LaunchedEffect
            }
            speechService.onDevice = effective.requireOnDeviceVoice
            isHeadphonesConnected = isHeadphonesConnectedCheck(context)
            speechService.stopRecording()
            speechService.startRecording(locale = resolvedSttLocale)
        }
    }


    val requestCameraForVideo = rememberPermissionRequester(
        permission = Manifest.permission.CAMERA,
        onDenied = { showCameraPermissionAlert = true }
    ) { isVideoCallActive = true }

    // This effect's lifetime is the call's, which is why the call flag is raised and
    // cleared here rather than anywhere else: leaving by any route — ending the call,
    // back, or the on-device pop above — runs `onDispose`, so the flag cannot be left
    // stranded on. It suppresses the completion notification for spoken replies; see
    // `RolesWitchApp.isCallActive`.
    DisposableEffect(Unit) {
        app.setCallActive(true)
        onDispose {
            app.setCallActive(false)
            speechService.stopRecording()
            ttsService.stop()
            llmService.cancelRequest()
            cameraManager.shutdown()
        }
    }

    // Fullscreen camera dialog
    if (isVideoCallActive) {
        Dialog(
            onDismissRequest = { isVideoCallActive = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                CameraPreviewScreen(
                    cameraManager = cameraManager,
                    onClose = { isVideoCallActive = false }
                )

                // Overlay conversation text at bottom
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isRecording) {
                        // Listening indicator
                        AnimatedVisibility(visible = isListening && !isMuted) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 8.dp)
                            ) {
                                PulsingDot()
                                Text(
                                    stringResource(R.string.listening),
                                    fontSize = 12.sp,
                                    color = Color.Red,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }

                        ConversationLyricsView(
                            userMessage = humanMessage,
                            assistantMessage = assistantMessage,
                            isThinking = isThinking,
                            isSpeaking = ttsIsSpeaking,
                            isMuted = micIsOff,
                            lightMode = true
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // End video button - returns to voice mode
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color.Red)
                            .clickable { isVideoCallActive = false },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.VideocamOff,
                            stringResource(R.string.close_video),
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(currentThreadTitle)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Scrollable content area
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ProfileCircle(
                        name = currentThreadTitle,
                        avatarBytes = currentThread?.avatarBytes
                    )

                    // Recording indicator
                    AnimatedVisibility(visible = isListening && !isMuted && !isVideoCallActive) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            PulsingDot()
                            Text(
                                stringResource(R.string.listening),
                                fontSize = 12.sp,
                                color = Color.Red,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    if (isRecording && !isVideoCallActive) {
                        ConversationLyricsView(
                            userMessage = humanMessage,
                            assistantMessage = assistantMessage,
                            isThinking = isThinking,
                            isSpeaking = ttsIsSpeaking,
                            isMuted = micIsOff,
                            onStopTtsAndResumeStt = {
                                ttsService.stop()
                                speechService.resumeListening()
                            }
                        )
                    }
                }

            // Bottom control buttons — fixed at bottom
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp)
            ) {
                if (isRecording) {
                    // Video call button
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(
                                if (!effective.visionAvailable) Color.Gray
                                else if (!effective.useVosk) Color.Gray
                                else if (isVideoCallActive) Color(0xFFFF9800)
                                else Color(0xFF2196F3)
                            )
                            .clickable {
                                if (!effective.visionAvailable) {
                                    showVisionDisabledAlert = true
                                } else if (resolvedSttLocale !in VoskModels.registry) {
                                    showLanguageNotSupportedAlert = true
                                } else if (!effective.useVosk) {
                                    showVoskNotEnabledAlert = true
                                } else if (isMuted) {
                                    showMutedVideoAlert = true
                                } else if (isVideoCallActive) {
                                    isVideoCallActive = false
                                } else {
                                    requestCameraForVideo()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isVideoCallActive) Icons.Default.VideocamOff else Icons.Default.Videocam,
                            stringResource(R.string.video),
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                // End call button
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(Color.Red)
                        .clickable {
                            navController.safePopBackStack()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.CallEnd,
                        contentDescription = stringResource(R.string.end_call),
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                if (isRecording) {
                    // Mic button
                    val timedOut = isTimedOut && !effective.useVosk
                    val micOff = isMuted || timedOut
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(if (micOff) Color(0xFFFF9800) else Color(0xFF2196F3))
                            .clickable {
                                if (isMuted) {
                                    isMuted = false
                                } else if (timedOut) {
                                    speechService.resumeListening()
                                } else {
                                    isMuted = true
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (micOff) Icons.Default.MicOff else Icons.Default.Mic,
                            stringResource(R.string.mute),
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
        LitertWarmUpOverlay(app)
        } // Box
    } // Scaffold

    if (showErrorAlert) {
        InfoDialog(errorMessage) { showErrorAlert = false }
    }

    if (showCameraPermissionAlert) {
        PermissionDeniedDialog(R.string.camera_permission_required) { showCameraPermissionAlert = false }
    }

    if (showVisionDisabledAlert) {
        InfoDialog(R.string.vision_disabled_message) { showVisionDisabledAlert = false }
    }

    if (showMutedVideoAlert) {
        InfoDialog(R.string.video_call_mic_disabled) { showMutedVideoAlert = false }
    }

    if (showLanguageNotSupportedAlert) {
        InfoDialog(R.string.video_call_language_not_supported) { showLanguageNotSupportedAlert = false }
    }

    if (showVoskNotEnabledAlert) {
        InfoDialog(R.string.video_call_enable_vosk) { showVoskNotEnabledAlert = false }
    }

    if (showModelNotConfiguredAlert) {
        InfoDialog(R.string.intelligence_not_configured_message) {
            showModelNotConfiguredAlert = false
            navController.safePopBackStack()
        }
    }

}

@Composable
private fun ProfileCircle(
    name: String,
    avatarBytes: ByteArray? = null,
    onClick: () -> Unit = {}
) {
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Reverse),
        label = "pulse"
    )

    Box(
        modifier = Modifier
            .size(140.dp)
            .scale(scale)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        ThreadAvatar(
            name = name,
            avatarBytes = avatarBytes,
            size = 130.dp
        )
    }
}

@Composable
fun PulsingDot() {
    val infiniteTransition = rememberInfiniteTransition(label = "dot")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "alpha"
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(Color.Red.copy(alpha = alpha))
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationLyricsView(
    userMessage: String,
    assistantMessage: String,
    isThinking: Boolean,
    isSpeaking: Boolean,
    isMuted: Boolean = false,
    lightMode: Boolean = false,
    onStopTtsAndResumeStt: () -> Unit = {}
) {
    val displayText = when {
        isThinking && assistantMessage.isEmpty() -> stringResource(R.string.thinking)
        userMessage.isNotEmpty() -> userMessage
        isMuted && !isThinking && !isSpeaking -> stringResource(R.string.you_are_muted)
        assistantMessage.isNotEmpty() -> MarkdownProcessor.stripMarkdown(assistantMessage)
        else -> ""
    }
    val isUser = userMessage.isNotEmpty() && !isThinking

    val userColor = if (lightMode) Color(0xFF81C784) else Color(0xFF4CAF50)
    val assistantColor = if (lightMode) Color.White else MaterialTheme.colorScheme.primary

    val haptic = LocalHapticFeedback.current

    Text(
        text = displayText,
        fontSize = if (lightMode) 20.sp else 28.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        color = if (isUser) userColor else assistantColor,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .let { modifier ->
                if (isSpeaking) {
                    modifier.combinedClickable(
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onStopTtsAndResumeStt()
                        },
                        onClick = {}
                    )
                } else {
                    modifier
                }
            },
        maxLines = if (lightMode) 4 else Int.MAX_VALUE
    )
}

private fun isHeadphonesConnectedCheck(context: Context): Boolean {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    return devices.any { device ->
        when (device.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET -> true
            else -> false
        }
    }
}
