//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import SwiftUI
import AVFoundation
import SharedLogic

/// Voice/video call screen — iOS counterpart of Android's `AssistantScreen`.
///
/// Half-duplex turn loop mirroring Android:
///  1. STT finalizes the user's utterance → a `finalMessages` event.
///  2. We pause STT, (optionally) capture a camera frame, and send the message.
///  3. The assistant reply is read back and spoken via TTS.
///  4. When TTS finishes, STT resumes for the next turn.
///
/// Effective settings drive every per-turn value: locale, offline (useVosk →
/// `SFSpeechRecognizer.requiresOnDeviceRecognition`), voice, speed, vision,
/// allow-interruptions.
///
/// iOS deviations from Android, all deliberate (see AGENTS.md): the mic listens
/// continuously — there is no tap-to-speak and no terminal silence timeout — and
/// barge-in is *not* gated on the offline toggle, only on headphones.
struct AssistantView: View {
    let threadId: String
    /// The thread MessagesView already has — seeds `currentThread` so the pulsing
    /// avatar shows the real image on the first frame instead of the fallback "?".
    let initialThread: MessageThread?

    init(threadId: String, initialThread: MessageThread? = nil) {
        self.threadId = threadId
        self.initialThread = initialThread
        _currentThread = State(initialValue: initialThread)
    }

    @EnvironmentObject private var app: AppViewModel
    @EnvironmentObject private var litert: LiteRtService
    @Environment(\.dismiss) private var dismiss

    @State private var currentThread: MessageThread?
    @State private var assistantMessage = ""
    @State private var isProcessing = false
    @State private var isMuted = false
    @State private var isVideoCallActive = false

    @State private var showError = false
    @State private var errorMessage = ""
    @State private var showModelAlert = false
    @State private var showVisionDisabled = false
    @State private var showMutedVideo = false
    @State private var showPermissionDenied = false
    @State private var showCameraDenied = false

    @StateObject private var camera = CameraController()
    @StateObject private var stt = SpeechRecognizerService()
    @StateObject private var tts = SpeechSynthesizerService()
    /// The external (OpenAI-compatible) speech engine. Only one of `tts`/`externalTts`
    /// is ever active per turn — `handleFinal` picks by `effective.ttsMode` — but the
    /// caller's `isSpeaking` must read both, because it does not know which spoke.
    @StateObject private var externalTts = ExternalTtsService()
    @StateObject private var audio = CallAudioSession.shared

    private var avatarImage: UIImage? {
        guard let bytes = currentThread?.avatarBytes else { return nil }
        return UIImage(data: bytes.toData())
    }

    var body: some View {
        let effective = resolvedEffective
        let canInterrupt = self.canInterrupt(effective)
        // Android's `micIsOff`, minus its `&& !useVosk` guard: that exists there only
        // because VoskService never sets `isTimedOut`, while iOS uses one engine for
        // both modes.
        let micIsOff = isMuted || stt.isTimedOut

        return withAlerts(callSurface(effective: effective, canInterrupt: canInterrupt, micIsOff: micIsOff))
    }

    /// The call surface and its lifecycle wiring. Split from the alert stack in `body`
    /// because the whole chain in one expression exceeds the type-checker's budget.
    private func callSurface(
        effective: EffectiveSettings,
        canInterrupt: Bool,
        micIsOff: Bool
    ) -> some View {
        ZStack {
            Color(.systemBackground).ignoresSafeArea()

            VStack(spacing: 0) {
                callTitleBar

                // Fixed, not scrolled. The avatar is who you are talking to — it belongs
                // to the screen the way the control bar does, and scrolling it away (with
                // its glow clipped at the scroll bounds) left the call looking like a
                // document. Only the transcript below moves.
                VStack(spacing: 16) {
                    pulsingAvatar
                        .padding(.top, 24)

                    if stt.isListening && !isMuted {
                        listeningIndicator
                    }
                }

                ScrollView {
                    ConversationLyrics(
                        userMessage: stt.humanMessage,
                        assistantMessage: assistantMessage,
                        isThinking: app.isThinking,
                        isSpeaking: tts.isSpeaking || externalTts.isSpeaking,
                        isMuted: micIsOff,
                        lightMode: false,
                        onStopTtsAndResumeStt: {
                            tts.stop()
                            externalTts.stop()
                            Task { @MainActor in await stt.resumeListening() }
                        }
                    )
                    .padding(.horizontal, 16)
                    .padding(.vertical, 16)
                }

                controlBar(effective: effective, micIsOff: micIsOff)
            }

            // Rendered in the same hierarchy as the voice surface (Android uses a
            // Dialog inside the same composition). A `fullScreenCover` would host it
            // in a separate controller, where the reply text stops tracking.
            if isVideoCallActive {
                videoOverlay(micIsOff: micIsOff)
                    .transition(.opacity)
            }

            // Above everything, including the video layer: until the model is resident there is
            // nothing for a turn to reach, and the mic is already listening.
            LiteRtWarmUpOverlay()
            VStack {
                Spacer()
                // `resolvedSettings`, not `effective`, like the engine sync below: a failure
                // outlives the resolution that caused it, so Retry has to be held to the same
                // rule. With the placeholder it would load the *global* model into a call the
                // thread had overridden to remote, and report success next to a failure banner.
                LiteRtWarmUpFailureBanner(settings: resolvedSettings)
            }
        }
        // `eager: true`, unlike the chat, for what comes with it rather than for the load: the
        // residency hold that stops a memory warning between turns from unloading the model
        // mid-conversation, and the unload of a model an earlier on-device thread left resident
        // when this call is on the remote engine. The load itself already happened — the chat
        // screen warms up on the call button, and `prepare()` waits on it again before the mic
        // opens. Nothing here re-fires on a changed selection: settings are unreachable from a
        // call, so the model cannot change while one is up.
        //
        // `resolvedSettings`, not `effective`: nil until the thread row arrives. Loading against
        // the placeholder would fetch whatever the *global* selection is, then unload it and load
        // the thread's the moment the row landed — a visible unload at the start of every call on
        // a thread that overrides the model.
        .litertEngineSync(settings: resolvedSettings, eager: true)
        .task { await prepare() }
        // For the whole call, not merely while a turn runs: being on this screen *is* being in the
        // call. Counted, so the chat screen's stream ending can no longer re-arm auto-lock here.
        .keepScreenAwake()
        .onDisappear { teardown() }
        .onReceive(stt.finalMessages) { text in handleFinal(text, effective: effective) }
        .onChange(of: tts.isSpeaking) { _, speaking in
            if !speaking, stt.isRecording, !isProcessing {
                assistantMessage = ""
                stt.clearHumanMessage()
                if !isMuted {
                    Task { @MainActor in await stt.resumeListening() }
                }
            }
        }
        .onChange(of: externalTts.isSpeaking) { _, speaking in
            // The external engine's own resume edge — `isSpeaking` flips false when the
            // AAC playback finishes, exactly like the synthesizer's delegate callback.
            if !speaking, stt.isRecording, !isProcessing {
                assistantMessage = ""
                stt.clearHumanMessage()
                if !isMuted {
                    Task { @MainActor in await stt.resumeListening() }
                }
            }
        }
        .onChange(of: isMuted) { _, muted in
            // Mirrors Android's `LaunchedEffect(isMuted)`. Without this the mic keeps
            // transcribing and keeps sending turns while "muted".
            guard stt.isRecording else { return }
            if muted {
                stt.pauseListening()
            } else if !isProcessing && !tts.isSpeaking && !externalTts.isSpeaking {
                Task { @MainActor in await stt.resumeListening() }
            } else if canInterrupt && !app.isThinking {
                Task { @MainActor in await stt.resumeListening() }
            }
        }
        .onChange(of: canInterrupt) { _, value in
            tts.setAssistantAudioMode(value)
        }
        .onChange(of: isVideoCallActive) { _, active in
            // Closing the overlay must release the camera, or the in-use indicator
            // stays lit for the rest of the call.
            if active { camera.start() } else { camera.stop() }
        }
        .onChange(of: app.isSceneBackgrounded) { _, backgrounded in
            // End an on-device call when the app is actually being suspended, as Android does.
            // The reason is sharper here: `stopOnDeviceWork()` unloads the LiteRT weights on that
            // transition (GPU work from a suspended app gets the process killed), so the call
            // would come back with a live mic, a running audio session and no model to answer
            // with. `.background`, not `.inactive` — a banner must not drop a call.
            //
            // Remote calls are untouched — that path is designed to keep talking with the screen
            // locked, which is what the always-running `AVAudioEngine` is for.
            guard backgrounded, effective.useOnDevice else { return }
            endCall()
        }
    }

    /// Every alert this screen can raise, in one place.
    private func withAlerts<Content: View>(_ content: Content) -> some View {
        content
        .alert("Error", isPresented: $showError) { Button("OK") {} } message: { Text(verbatim: errorMessage) }
        .alert("Model is not configured", isPresented: $showModelAlert) {
            // Ending the call dismisses this cover; ThreadsView then pushes the routing
            // target on top of the conversation, matching Android. A thread that
            // overrides its model is fixed in its own settings (`navigateBasedOnThread`);
            // one that inherits the globals goes to the global screen.
            Button("Go to Settings") {
                if currentThread?.useCustomSettings == true {
                    app.requestThreadSettings(threadId: threadId)
                } else {
                    app.requestSettings()
                }
                endCall()
            }
            Button("Cancel", role: .cancel) { endCall() }
        } message: { Text("Model is not configured. Go to settings to set up your AI provider.") }
        .alert("Vision is disabled", isPresented: $showVisionDisabled) { Button("OK") {} } message: { Text("Enable Vision in Settings to attach images.") }
        .alert(Text(verbatim: ""), isPresented: $showMutedVideo) { Button("OK") {} } message: { Text("For video calls turn on the microphone") }
        .alert("Camera access required", isPresented: $showCameraDenied) {
            Button("Open Settings") { Self.openAppSettings() }
            Button("Cancel", role: .cancel) {}
        } message: { Text("Camera permission is required for video calls. You can enable it in app settings.") }
        .alert("Permission denied", isPresented: $showPermissionDenied) {
            Button("Open Settings") { Self.openAppSettings() }
            Button("Cancel", role: .cancel) { endCall() }
        } message: { Text("Microphone and speech recognition permission is required to make calls. You can enable them in app settings.") }
    }

    // MARK: - Pieces

    /// Android's `CenterAlignedTopAppBar`: the thread's name, centred, over the same
    /// surface colour as the screen — no back button, because a call is left by hanging
    /// up. Drawn in the layout rather than in a nav bar so the screen owns its full
    /// height and the video overlay can cover it whole.
    ///
    /// User-named thread title, so the `String` overload (which never localizes) is the
    /// right one — the fallback is resolved through the catalog instead.
    private var callTitleBar: some View {
        Text(verbatim: currentThread?.title ?? String(localized: "Voice Assistant"))
            .font(.headline)
            .lineLimit(1)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
    }

    private var pulsingAvatar: some View {
        avatarContent
            .pulseEffect(active: stt.isListening && !isMuted)
            .accessibilityHidden(true)
    }

    @ViewBuilder
    private var avatarContent: some View {
        if let image = avatarImage {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(width: 140, height: 140)
                .clipShape(Circle())
        } else {
            AvatarView(
                name: currentThread?.title ?? "",
                avatarBytes: currentThread?.avatarBytes,
                size: 140
            )
        }
    }

    private var listeningIndicator: some View {
        HStack(spacing: 6) {
            PulsingDot()
            Text("Listening…").font(.subheadline).foregroundStyle(.red)
        }
    }

    @ViewBuilder
    private func controlBar(effective: EffectiveSettings, micIsOff: Bool) -> some View {
        HStack(spacing: 28) {
            if stt.isRecording {
                videoButton(effective: effective)
            }
            endCallButton
            if stt.isRecording {
                micButton(micIsOff: micIsOff)
            }
        }
        .padding(.bottom, 28)
        .padding(.top, 12)
    }

    @ViewBuilder
    private func videoButton(effective: EffectiveSettings) -> some View {
        // `visionAvailable`, not `enableVision`: on device the registry answers it (a VLM
        // checkpoint needs no preference, and there is no toggle to set), and off device the
        // thread's resolved value is what counts. Android's `AssistantScreen` reads the same one.
        let enabled = effective.visionAvailable && !isMuted
        Button {
            if !effective.visionAvailable { showVisionDisabled = true }
            else if isMuted { showMutedVideo = true }
            else if isVideoCallActive { isVideoCallActive = false }
            else { startVideo() }
        } label: {
            circleIcon(
                systemName: isVideoCallActive ? "video.slash.fill" : "video.fill",
                background: enabled ? (isVideoCallActive ? Color.orange : Color.blue) : Color.gray
            )
        }
    }

    private var endCallButton: some View {
        Button { endCall() } label: {
            ZStack {
                Circle().fill(Color.red).frame(width: 76, height: 76)
                Image(systemName: "phone.down.fill")
                    .font(.system(size: 30))
                    .foregroundStyle(.white)
            }
        }
        .accessibilityLabel("End call")
    }

    @ViewBuilder
    private func micButton(micIsOff: Bool) -> some View {
        Button {
            if isMuted { isMuted = false }
            else if stt.isTimedOut {
                Task { @MainActor in await stt.resumeListening() }
            }
            else { isMuted = true }
        } label: {
            circleIcon(
                systemName: micIsOff ? "mic.slash.fill" : "mic.fill",
                background: micIsOff ? Color.orange : Color.blue
            )
        }
        .accessibilityLabel("Mute")
    }

    private func circleIcon(systemName: String, background: Color) -> some View {
        ZStack {
            Circle().fill(background).frame(width: 56, height: 56)
            Image(systemName: systemName)
                .font(.system(size: 24))
                .foregroundStyle(.white)
        }
    }

    @ViewBuilder
    private func videoOverlay(micIsOff: Bool) -> some View {
        ZStack {
            Color.black.ignoresSafeArea()
            CameraPreview(session: camera.session)
                .ignoresSafeArea()

            VStack {
                HStack {
                    Button { isVideoCallActive = false } label: {
                        circleIcon(systemName: "xmark", background: .black.opacity(0.5))
                    }
                    .accessibilityLabel("Close video")
                    Spacer()
                    Button { camera.toggleCamera() } label: {
                        circleIcon(systemName: "camera.rotate", background: .black.opacity(0.5))
                    }
                }
                .padding()

                Spacer()

                // Bottom scrim carrying the same lyrics as the voice surface,
                // matching Android's video-dialog overlay.
                VStack(spacing: 8) {
                    if stt.isListening && !isMuted { listeningIndicator }

                    ConversationLyrics(
                        userMessage: stt.humanMessage,
                        assistantMessage: assistantMessage,
                        isThinking: app.isThinking,
                        isSpeaking: tts.isSpeaking || externalTts.isSpeaking,
                        isMuted: micIsOff,
                        lightMode: true
                    )
                    .padding(.horizontal, 16)

                    Button { isVideoCallActive = false } label: {
                        ZStack {
                            Circle().fill(Color.red).frame(width: 64, height: 64)
                            Image(systemName: "video.slash.fill")
                                .font(.system(size: 26))
                                .foregroundStyle(.white)
                        }
                    }
                    .accessibilityLabel("Close video")
                    .padding(.top, 4)
                }
                .padding(.vertical, 16)
                .frame(maxWidth: .infinity)
                .background(Color.black.opacity(0.5))
            }
        }
    }

    // MARK: - Lifecycle / loop

    private var resolvedEffective: EffectiveSettings {
        let sm = app.container.settingsManager
        return EffectiveSettings.companion.resolve(
            thread: currentThread ?? app.container.threadForId(threadId: threadId),
            global: sm
        )
    }

    /// [resolvedEffective], but only once the thread row is really loaded — nil before that.
    /// The engine sync uses this so it cannot act on the placeholder's global-resolved values.
    private var resolvedSettings: EffectiveSettings? {
        guard let currentThread else { return nil }
        return EffectiveSettings.companion.resolve(
            thread: currentThread,
            global: app.container.settingsManager
        )
    }

    /// Android additionally requires `useVosk` here because only Vosk can listen
    /// through the reply. On iOS the single recognizer does that in both modes, so
    /// barge-in only needs headphones (same precedent as the video gate).
    private func canInterrupt(_ effective: EffectiveSettings) -> Bool {
        effective.allowInterruptions && audio.isHeadphonesConnected
    }

    /// `isConfigured` answers for whichever engine this thread resolves to — on device there is
    /// no endpoint and no token, only a selected model — so testing the OpenAI fields here would
    /// refuse to start a call on a perfectly configured on-device thread. Plus the weights: the
    /// registry keeps a deleted model "configured", but the call would die in the load, so a
    /// selection whose weights are gone is treated as not configured — same as the chat's gate.
    private func hasValidModel(_ effective: EffectiveSettings) -> Bool {
        guard effective.isConfigured else { return false }
        guard effective.useOnDevice else { return true }
        return LiteRtRegistry.model(withId: effective.litertModel)?.isDownloaded ?? false
    }

    private func prepare() async {
        if currentThread == nil {
            currentThread = try? await app.container.database.messageThreadDao().getThreadById(threadId: threadId)
        }
        let effective = resolvedEffective
        guard hasValidModel(effective) else {
            showModelAlert = true
            return
        }

        // Asserted, not requested: `MessagesView.startCall` settles both grants before
        // presenting this view, matching Android, where the call screen is reached only
        // through the RECORD_AUDIO gate. This still catches a grant revoked in Settings
        // while the app was backgrounded.
        guard SpeechRecognizerService.isAuthorized else { showPermissionDenied = true; return }

        // The mic never opens ahead of the engine. The chat screen loads the model before it
        // presents this view, so this normally returns at once — `syncTo` leaves a resident
        // model alone — but the weights can still have been dropped in between (a memory
        // warning), and a first turn spoken into a model that is still loading is heard with
        // nothing there to answer it. A load that fails leaves the warm-up banner on screen.
        if effective.useOnDevice {
            await litert.syncTo(effective)
            guard case .ready = litert.initState else { return }
        }

        stt.onDevice = effective.useVosk
        await stt.stopRecording()
        await stt.startRecording(locale: SettingsInterop.locale(for: effective.language))
    }

    private func handleFinal(_ text: String, effective: EffectiveSettings) {
        guard !text.isEmpty else { return }
        let canInterrupt = self.canInterrupt(effective)
        if canInterrupt {
            if app.isThinking { return }
            // Stop whichever engine is speaking — the turn loop does not know which one
            // answered — and fall through to start the new turn, exactly as Android's
            // `AssistantScreen` stops its TTS on a barge-in.
            if tts.isSpeaking || externalTts.isSpeaking {
                tts.stop()
                externalTts.stop()
                assistantMessage = ""
            }
            if isProcessing && !tts.isSpeaking && !externalTts.isSpeaking { return }
        } else if isProcessing {
            return
        }

        guard hasValidModel(effective) else {
            showModelAlert = true
            return
        }

        isProcessing = true
        stt.clearHumanMessage()
        if !canInterrupt { stt.pauseListening() }

        Task {
            defer { isProcessing = false }
            let images: [Data] = isVideoCallActive ? ((await camera.capture()).map { [$0] } ?? []) : []
            do {
                try await app.sendMessage(text: text, threadId: threadId, images: images)
                let messages = (try? await app.container.database.messageDao().getMessagesByThread(threadId: threadId)) ?? []
                let response = messages.last(where: { !$0.isUser })?.content ?? ""
                guard !response.isEmpty, stt.isRecording else {
                    await resumeAfterTurn(canInterrupt: canInterrupt)
                    return
                }
                assistantMessage = response
                if effective.ttsMode == TtsMode.external {
                    // The external engine: synthesize the reply through the server and
                    // play the returned audio. A failure maps to the two Android error
                    // states — "Voice is not configured" for a blank endpoint, "Server
                    // error" for anything else — and the mic reopens either way.
                    let speaking = await externalTts.speak(
                        text: response,
                        voiceName: effective.externalTtsVoice.isEmpty ? nil : effective.externalTtsVoice,
                        endpoint: effective.externalTtsAPI,
                        token: effective.externalTtsKey
                    ) { error in
                        errorMessage = error is ExternalTtsNotConfiguredException
                            ? String(localized: "Voice is not configured")
                            : String(localized: "Server error")
                        showError = true
                    }
                    // Nothing left to say after cleaning — no `isSpeaking` edge is coming,
                    // so resume here or the call would sit there forever.
                    if !speaking { await resumeAfterTurn(canInterrupt: canInterrupt) }
                } else {
                    let speaking = tts.speak(
                        text: response,
                        language: effective.language,
                        voiceName: effective.voice.isEmpty ? nil : effective.voice
                    )
                    // Nothing left to say after cleaning — no `isSpeaking` edge is coming,
                    // so resume here or the call would sit there forever.
                    if !speaking { await resumeAfterTurn(canInterrupt: canInterrupt) }
                }
            } catch {
                errorMessage = error.localizedDescription
                showError = true
                await resumeAfterTurn(canInterrupt: canInterrupt)
            }
        }
    }

    private func resumeAfterTurn(canInterrupt: Bool) async {
        guard !canInterrupt, stt.isRecording, !isMuted else { return }
        await stt.resumeListening()
    }

    private func startVideo() {
        let status = AVCaptureDevice.authorizationStatus(for: .video)
        switch status {
        case .authorized:
            isVideoCallActive = true
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                Task { @MainActor in
                    if granted { self.isVideoCallActive = true }
                    else { self.showCameraDenied = true }
                }
            }
        case .denied, .restricted:
            showCameraDenied = true
        @unknown default:
            showCameraDenied = true
        }
    }

    private func endCall() {
        teardown()
        dismiss()
    }

    /// Order matters: STT owns the audio session, so it is released last.
    private func teardown() {
        isVideoCallActive = false
        camera.stop()
        tts.stop()
        externalTts.stop()
        app.cancelLLM()
        Task { @MainActor in await stt.stopRecording() }
    }

    // MARK: - Helpers

    private static func openAppSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }
}

// MARK: - Pulse

private struct PulsingDot: View {
    @State private var opacity: Double = 0.3
    var body: some View {
        Circle()
            .fill(Color.red)
            .frame(width: 8, height: 8)
            .opacity(opacity)
            .onAppear {
                withAnimation(.easeInOut(duration: 0.8).repeatForever(autoreverses: true)) { opacity = 1 }
            }
    }
}

private extension View {
    /// Subtle breathing scale on the avatar while the mic is live.
    func pulseEffect(active: Bool) -> some View {
        modifier(PulseModifier(active: active))
    }
}

private struct PulseModifier: ViewModifier {
    let active: Bool
    @State private var scale: CGFloat = 1.0
    func body(content: Content) -> some View {
        content
            .scaleEffect(scale)
            .onAppear {
                guard active else { scale = 1.0; return }
                withAnimation(.easeInOut(duration: 2).repeatForever(autoreverses: true)) { scale = 1.05 }
            }
            .animation(.easeInOut(duration: 0.3), value: active)
    }
}
