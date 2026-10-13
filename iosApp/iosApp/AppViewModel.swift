//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import Foundation
import SharedLogic
import Combine
import Speech
import UIKit
import UserNotifications

/// App-level observable view model that wraps the KMP `AppContainer`.
@MainActor
final class AppViewModel: ObservableObject {

    /// Singleton used by both the SwiftUI scene (`iOSApp`) and the
    /// `AppDelegate` (for routing notification taps to navigation). Keeps a
    /// single source of truth for background-task lifecycle and the
    /// notification deep-link inbox.
    static let shared = AppViewModel()

    let container: AppContainer

    /// The on-device engine. Held here rather than inside `AppContainer` because the SwiftUI
    /// surfaces observe it directly — `initState` and `downloadState` drive the warm-up overlay
    /// and the model screen, and routing those through a Kotlin flow would buy nothing.
    let litert = LiteRtService()

    // MARK: Onboarding / connection state (mirrored from Kotlin StateFlow)

    @Published var hasCompletedOnboarding: Bool = false
    @Published var selectedModel: String = ""
    @Published var openaiAPI: String = ""
    @Published var openaiKey: String = ""
    @Published var language: String = "en"

    // MARK: Intelligence / misc settings

    @Published var enableVision: Bool = false
    @Published var embedTime: Bool = false
    @Published var messageLimit: Int = 50
    @Published var keepLastAttachment: Bool = false
    /// Streams replies in as they are generated (original behaviour); false shows
    /// the reply whole when the turn completes, with no Stop button and a composer
    /// that stays live so a new message cancels the in-flight turn.
    @Published var streamingMode: Bool = true

    // MARK: Audio / voice settings (drive the iOS voice call)

    @Published var voice: String = ""
    /// On iOS this means "use offline speech recognition" (SFSpeechRecognizer
    /// on-device mode). Reuses the Android `useVosk` column for parity.
    @Published var useVosk: Bool = false
    @Published var allowInterruptions: Bool = false
    /// The speech engine: the native `AVSpeechSynthesizer` ("System") vs the
    /// OpenAI-compatible `/v1/audio/speech` endpoint ("External"). The Android
    /// `TtsMode` state space is shared; on iOS "Auto" (neither flag) resolves to
    /// the same native voice as "System", so its picker offers only the two.
    @Published var useSystemTts: Bool = false
    @Published var useExternalTts: Bool = false
    /// The server-side voice id external speech sends; empty lets the server pick.
    @Published var externalTtsVoice: String = ""
    /// The external speech server's base URL and token — their own settings, kept apart
    /// from `openaiAPI`/`openaiKey` (the inference server): a user may run the chat and
    /// the voice on different hosts, and editing one must not touch the other (or the
    /// selected inference model). Empty means external speech is not configured.
    @Published var externalTtsAPI: String = ""
    @Published var externalTtsKey: String = ""

    // MARK: One-time prompts (shared with Android via SettingsManager)

    /// Gates the one-time notification-permission request on first chat open.
    @Published var hasSeenNotificationPrompt: Bool = false
    /// The user has agreed, in as many words, that configuring a remote endpoint sends
    /// their conversations to it. Gates saving the server settings; see `ServerSheet`.
    @Published var hasAcceptedDataSharing: Bool = false
    /// The user has seen the "Some models does not support multiple attachments" gate
    /// and tapped OK. While false the composer raises it before staging a *second*
    /// attachment; once true it accepts any number and never asks again.
    @Published var hasAcceptedMultipleAttachments: Bool = false

    // MARK: Advanced generation params

    @Published var temperature: String = ""
    @Published var topP: String = ""
    @Published var n: String = ""
    @Published var maxCompletionTokens: String = ""
    @Published var presencePenalty: String = ""
    @Published var frequencyPenalty: String = ""

    /// `reasoning_effort` wire value, or "" for Auto — the field is then left out of
    /// the request entirely. Not an Advanced field: it has its own row and picker.
    @Published var reasoningEffort: String = ""

    // MARK: On-device inference (LiteRT)

    /// Whether inference runs on device — LiteRT-LM here and on Android. Backed by the shared
    /// `litertEnabled` preference key, which predates the second engine.
    @Published var useOnDevice: Bool = false
    /// Selected LiteRT checkpoint id (a HuggingFace repo). Empty means none.
    @Published var litertModel: String = ""
    @Published var litertTopK: String = ""
    @Published var litertTopP: String = ""
    @Published var litertTemperature: String = ""
    @Published var litertMaxTokens: String = ""
    /// CPU/GPU text backend. The vision backend is pinned to the checkpoint's own
    /// accelerator regardless (GPU for the Gemma family) and never follows this.
    @Published var litertAccelerator: String = "cpu"
    @Published var litertEnableThinking: Bool = false
    /// HuggingFace token, for gated checkpoints ("Requires a token" in the model picker).
    /// Empty sends no `Authorization` header; a gated download then fails with the token
    /// error from the picker instead.
    @Published var huggingFaceToken: String = ""

    // MARK: LLM streaming state

    @Published var streamingResponse: String = ""
    @Published var isStreaming: Bool = false
    @Published var isThinking: Bool = false

    /// True while an MCP tool call is running. The model is not producing text then, so
    /// the chat header says "Using a tool…" rather than "Thinking…".
    @Published var isCallingTool: Bool = false

    // MARK: Lifecycle / deep-link (no KMP equivalent; iOS-only)

    /// True while the SwiftUI scene is `.active`. Mirrors Android's
    /// `isAppForegrounded` — the response-completion observer suppresses
    /// notifications while this is true (the user already sees the reply).
    @Published var isAppForegrounded: Bool = true

    /// True once the scene reaches `.background`, i.e. the app is about to be suspended.
    ///
    /// Deliberately **not** `!isAppForegrounded`. A scene is `.inactive` for all sorts of
    /// transient reasons — the app switcher, a notification banner, notification centre — and
    /// it is still allowed to run Metal work and network transfers in that state. Tearing the
    /// on-device engine down there cancelled model downloads for no reason. Only `.background`
    /// actually leads to suspension, which is the thing the engine cannot survive.
    @Published var isSceneBackgrounded: Bool = false

    /// Set by `AppDelegate` when a completion notification is tapped. ThreadsView
    /// observes and navigates to the thread, then clears it. Mirrors Android's
    /// `MainActivity` `EXTRA_THREAD_ID` deep-link routing.
    @Published var pendingDeepLinkThreadId: String?

    /// Set by any screen that needs to send the user to Settings — the chat and the
    /// call screen both offer "Go to Settings" but neither owns the navigation
    /// stack (`MessagesView` is pushed by `ThreadsView`; `AssistantView` is a bare
    /// `fullScreenCover` with no stack at all). `ThreadsView` observes this and
    /// *appends* Settings to the current path, so it lands on top of the
    /// conversation exactly like Android's `navController.navigate("settings")`.
    @Published var pendingSettingsRequest = false

    /// Same, but for one thread's own model settings — where the "Model is not
    /// configured" alert sends a thread that overrides its model (the split Android's
    /// `navigateBasedOnThread` makes). Holds the thread id; `ThreadsView` observes it
    /// and appends the thread's settings screen to the current path.
    @Published var pendingThreadSettingsRequest: String?

    /// Set when the iOS background-task expiration handler cancels an in-flight
    /// LLM request (i.e. the stream didn't finish within `backgroundTimeRemaining`).
    /// RootView observes this and shows a "Request was cancelled. Retry?" alert
    /// on foreground return. Cleared by Retry / Cancel. User-initiated cancels
    /// (stop button, "leave while thinking" alert) deliberately do not set this.
    @Published var pendingCancellationRetryThreadId: String?

    // MARK: Private

    private var observers: [AnyCancellable] = []
    private var heldObservers: [AnyObject] = []
    private var responseCompletionTask: Task<Void, Never>?

    /// Active iOS background task identifier, or `.invalid` when none is held.
    /// Lets the streaming coroutine survive screen-lock/background for ~30s
    /// (iOS's typical `backgroundTimeRemaining`). Analogous to the wakelock in
    /// Android's `LlmForegroundService`.
    private var backgroundTaskId: UIBackgroundTaskIdentifier = .invalid

    /// Whether the streaming idle-timer hold is currently taken. See the `isStreaming` binding.
    private var streamingHold = false

    init() {
        // Before the container: `createAppContainer` caches its result, so the engine has to
        // exist by the first call or the app would run without one for the rest of the process.
        self.container = AppContainerFactoryKt.createAppContainer(
            onDeviceEngine: LiteRtInferenceEngine(service: litert)
        )
        let sm = container.settingsManager

        bind(sm.hasCompletedOnboarding) { self.hasCompletedOnboarding = $0 as? Bool ?? false }
        bind(sm.selectedModel) { self.selectedModel = $0 as? String ?? "" }
        bind(sm.openaiAPI) { self.openaiAPI = $0 as? String ?? "" }
        bind(sm.openaiKey) { self.openaiKey = $0 as? String ?? "" }
        bind(sm.language) { self.language = $0 as? String ?? "" }
        bind(sm.enableVision) { self.enableVision = $0 as? Bool ?? false }
        bind(sm.embedTime) { self.embedTime = $0 as? Bool ?? false }
        bind(sm.messageLimit) { self.messageLimit = ($0 as? Int32).map(Int.init) ?? 50 }
        bind(sm.keepLastAttachment) { self.keepLastAttachment = $0 as? Bool ?? false }
        bind(sm.streamingMode) { self.streamingMode = $0 as? Bool ?? true }
        bind(sm.voice) { self.voice = $0 as? String ?? "" }
        bind(sm.useVosk) { self.useVosk = $0 as? Bool ?? false }
        bind(sm.allowInterruptions) { self.allowInterruptions = $0 as? Bool ?? false }
        bind(sm.useSystemTts) { self.useSystemTts = $0 as? Bool ?? false }
        bind(sm.useExternalTts) { self.useExternalTts = $0 as? Bool ?? false }
        bind(sm.externalTtsVoice) { self.externalTtsVoice = $0 as? String ?? "" }
        bind(sm.externalTtsAPI) { self.externalTtsAPI = $0 as? String ?? "" }
        bind(sm.externalTtsKey) { self.externalTtsKey = $0 as? String ?? "" }
        bind(sm.hasSeenNotificationPrompt) { self.hasSeenNotificationPrompt = $0 as? Bool ?? false }
        bind(sm.hasAcceptedDataSharing) { self.hasAcceptedDataSharing = $0 as? Bool ?? false }
        bind(sm.hasAcceptedMultipleAttachments) { self.hasAcceptedMultipleAttachments = $0 as? Bool ?? false }
        bind(sm.temperature) { self.temperature = $0 as? String ?? "" }
        bind(sm.topP) { self.topP = $0 as? String ?? "" }
        bind(sm.n) { self.n = $0 as? String ?? "" }
        bind(sm.maxCompletionTokens) { self.maxCompletionTokens = $0 as? String ?? "" }
        bind(sm.presencePenalty) { self.presencePenalty = $0 as? String ?? "" }
        bind(sm.frequencyPenalty) { self.frequencyPenalty = $0 as? String ?? "" }
        bind(sm.reasoningEffort) { self.reasoningEffort = $0 as? String ?? "" }
        bind(sm.useOnDevice) { self.useOnDevice = $0 as? Bool ?? false }
        bind(sm.litertModel) { self.litertModel = $0 as? String ?? "" }
        bind(sm.litertTopK) { self.litertTopK = $0 as? String ?? "" }
        bind(sm.litertTopP) { self.litertTopP = $0 as? String ?? "" }
        bind(sm.litertTemperature) { self.litertTemperature = $0 as? String ?? "" }
        bind(sm.litertMaxTokens) { self.litertMaxTokens = $0 as? String ?? "" }
        bind(sm.litertAccelerator) { self.litertAccelerator = $0 as? String ?? "" }
        bind(sm.litertEnableThinking) { self.litertEnableThinking = $0 as? Bool ?? false }
        bind(sm.huggingFaceToken) { self.huggingFaceToken = $0 as? String ?? "" }

        let llm = container.llmService
        bind(llm.streamingResponse) { self.streamingResponse = $0 as? String ?? "" }
        bind(llm.isStreaming) { value in
            let streaming = value as? Bool ?? false
            self.isStreaming = streaming
            // Begin / end an iOS background task in lock-step with the shared
            // `isStreaming` flag. Without this, the ktor-darwin `NSURLSession`
            // is suspended mid-stream when the app backgrounds, which is the
            // source of the crash on screen-lock.
            if streaming {
                self.startBackgroundTask()
            } else {
                self.endBackgroundTask()
            }
            // Keep the screen on while the model is generating so the user can
            // watch the streamed reply without re-authenticating / waking the
            // device. Mirrors Android's `FLAG_KEEP_SCREEN_ON` on the window.
            //
            // A counted hold rather than the flag itself: a call and a model download want the
            // same screen awake, and assigning it here used to clear theirs. Guarded by
            // `streamingHold` so repeated `false`s cannot release someone else's.
            if streaming != self.streamingHold {
                self.streamingHold = streaming
                if streaming { IdleTimer.retain() } else { IdleTimer.release() }
            }
        }
        bind(llm.isThinking) { self.isThinking = $0 as? Bool ?? false }
        bind(llm.isCallingTool) { self.isCallingTool = $0 as? Bool ?? false }

        // Give the weights back under memory pressure — iOS's answer to Android's
        // `onTrimMemory`. `releaseIfUnused`, not `release`: a no-op mid-turn *and* while a call
        // holds the model, because a warning arriving between two turns of a conversation was
        // unloading the model the user was still talking to. It remains a safety valve for a
        // model left resident with nothing using it.
        NotificationCenter.default.addObserver(
            forName: UIApplication.didReceiveMemoryWarningNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.litert.releaseIfUnused() }
        }

        // Deliberately *not* seeding here. The welcome thread can be deleted, and a
        // launch-time seed would put it straight back on the next start. Onboarding
        // seeds instead (as on Android), so Settings → Reset — which returns the user
        // to onboarding — is the way to get it back.
        observeResponseCompletions()
    }

    /// The device's bare language code, as the shared seed helpers want it.
    static var deviceLanguage: String {
        Locale.current.language.languageCode?.identifier ?? "en"
    }

    /// Resolves the device locale to one of the supported languages. Narrowed by shared
    /// code against `SettingsManager.defaultLanguages`, so it cannot drift from Android.
    static var resolvedLocale: String {
        SeedDefaultsKt.resolveSeedLocale(deviceLanguage: deviceLanguage)
    }

    /// iOS's half of the shared `seedDefaults`: supplies the things only this platform can
    /// answer — the device locale, whether this is a debug build, and the bundled profile
    /// image that seeds the default thread's avatar — and lets shared code decide everything
    /// else, including whether to seed at all.
    ///
    /// **Onboarding is the only caller** (`OnboardingView.task`, matching Android's
    /// `LaunchedEffect(Unit)`), so on an onboarded launch it never runs at all — `bind`
    /// applies `hasCompletedOnboarding` synchronously, so `RootView`'s first frame is
    /// already `ThreadsView`. The shared function keeps its own guard regardless, so a
    /// welcome thread the user deleted cannot come back behind their back.
    func seedDefaults() async {
        // The asset catalog is compiled into `Assets.car`, so the original bytes are not
        // reachable by name; re-encode the decoded image instead (visually lossless JPEG).
        let avatarBytes = UIImage(named: "Profile")?
            .jpegData(compressionQuality: 1.0)?
            .toKotlinByteArray()
        _ = try? await SeedDefaultsKt.seedDefaults(
            database: container.database,
            settingsManager: container.settingsManager,
            deviceLanguage: Self.deviceLanguage,
            stressMessageCount: Self.stressSeedMessageCount,
            defaultAvatarBytes: avatarBytes
        )
    }

    /// TEST SEED — canned messages dropped into the welcome thread on first seed, so the
    /// chat can be exercised with a history long enough to show scrolling and memory cost.
    /// The count itself is shared with Android; only "is this a debug build" is local.
    ///
    /// A release build gets 0, so no real user meets the fake conversation. The shared
    /// `seedStressMessages` no-ops on a thread already holding more than the welcome
    /// message, so repeated onboarding runs cannot stack batches.
    #if DEBUG
    private static let stressSeedMessageCount: Int32 = SeedDefaultsKt.STRESS_SEED_MESSAGE_COUNT
    #else
    private static let stressSeedMessageCount: Int32 = 0
    #endif

    /// Mirrors a Kotlin `StateFlow` into a `@Published` property.
    ///
    /// The current value is applied **synchronously**, before the Combine subscription:
    /// `SettingsManager` builds its flows from a `runBlocking` preferences snapshot, so
    /// every value is already correct by the time the container exists, and hopping the
    /// first one through `DispatchQueue.main` only delayed it by a frame. That frame was
    /// enough for `RootView` to render `OnboardingView` on a launch that was already
    /// onboarded — and for that screen's `.task` to start seeding — so the app flashed
    /// onboarding on every start. Later emissions still arrive on the main queue.
    private func bind(_ flow: StateFlow, handler: @escaping (Any?) -> Void) {
        let observer = KotlinStateFlowObserver(flow)
        heldObservers.append(observer)
        handler(flow.value)
        observer.$value
            // The value applied above; `@Published` replays it on subscribe.
            .dropFirst()
            .receive(on: DispatchQueue.main)
            .sink(receiveValue: handler)
            .store(in: &observers)
    }
}

// MARK: - Settings setters

extension AppViewModel {
    func setHasCompletedOnboarding(_ value: Bool) {
        container.settingsManager.setHasCompletedOnboarding(value: value)
    }
    func setSelectedModel(_ value: String) {
        container.settingsManager.setSelectedModel(value: value)
    }
    func setOpenaiAPI(_ value: String) {
        container.settingsManager.setOpenaiAPI(value: value)
    }
    func setOpenaiKey(_ value: String) {
        container.settingsManager.setOpenaiKey(value: value)
    }
    func setLanguage(_ value: String) {
        container.settingsManager.setLanguage(value: value)
    }
    func setEnableVision(_ value: Bool) {
        container.settingsManager.setEnableVision(value: value)
    }
    func setEmbedTime(_ value: Bool) {
        container.settingsManager.setEmbedTime(value: value)
    }
    func setMessageLimit(_ value: Int) {
        container.settingsManager.setMessageLimit(value: Int32(value))
    }
    func setKeepLastAttachment(_ value: Bool) {
        container.settingsManager.setKeepLastAttachment(value: value)
    }
    func setStreamingMode(_ value: Bool) {
        container.settingsManager.setStreamingMode(value: value)
    }
    func setVoice(_ value: String) {
        container.settingsManager.setVoice(value: value)
    }
    func setUseVosk(_ value: Bool) {
        container.settingsManager.setUseVosk(value: value)
    }
    func setUseSystemTts(_ value: Bool) {
        container.settingsManager.setUseSystemTts(value: value)
    }
    func setUseExternalTts(_ value: Bool) {
        container.settingsManager.setUseExternalTts(value: value)
    }
    func setExternalTtsVoice(_ value: String) {
        container.settingsManager.setExternalTtsVoice(value: value)
    }
    /// Writes the *global* external-speech server (URL + token), which never touches the
    /// inference endpoint/token or the selected model — speech may live on another host.
    func setExternalTtsAPI(_ value: String) {
        container.settingsManager.setExternalTtsAPI(value: value)
    }
    func setExternalTtsKey(_ value: String) {
        container.settingsManager.setExternalTtsKey(value: value)
    }
    func setAllowInterruptions(_ value: Bool) {
        container.settingsManager.setAllowInterruptions(value: value)
    }
    func setHasSeenNotificationPrompt(_ value: Bool) {
        container.settingsManager.setHasSeenNotificationPrompt(value: value)
    }

    func setHasAcceptedDataSharing(_ value: Bool) {
        container.settingsManager.setHasAcceptedDataSharing(value: value)
    }

    func setHasAcceptedMultipleAttachments(_ value: Bool) {
        container.settingsManager.setHasAcceptedMultipleAttachments(value: value)
    }

    /// The host of the configured endpoint, for naming the destination in the Data
    /// Sharing copy. Shared with Android through `ApiUrl.kt` so both name the same host.
    func hostname(fromApiUrl input: String) -> String? {
        ApiUrlKt.hostnameFromApiUrl(input: input)
    }
    /// Asks `ThreadsView` to push Settings onto the current navigation path.
    func requestSettings() {
        pendingSettingsRequest = true
    }
    /// Asks `ThreadsView` to push this thread's model settings onto the current path —
    /// the routing target for a thread that overrides its model.
    func requestThreadSettings(threadId: String) {
        pendingThreadSettingsRequest = threadId
    }
    func setTemperature(_ value: String) {
        container.settingsManager.setTemperature(value: value)
    }
    func setTopP(_ value: String) {
        container.settingsManager.setTopP(value: value)
    }
    func setN(_ value: String) {
        container.settingsManager.setN(value: value)
    }
    func setMaxCompletionTokens(_ value: String) {
        container.settingsManager.setMaxCompletionTokens(value: value)
    }
    func setPresencePenalty(_ value: String) {
        container.settingsManager.setPresencePenalty(value: value)
    }
    func setFrequencyPenalty(_ value: String) {
        container.settingsManager.setFrequencyPenalty(value: value)
    }
    func setReasoningEffort(_ value: String) {
        container.settingsManager.setReasoningEffort(value: value)
    }
    func setUseOnDevice(_ value: Bool) {
        container.settingsManager.setUseOnDevice(value: value)
    }
    /// Selects a LiteRT model. The shared side writes the checkpoint's own sampler and
    /// accelerator defaults (`SettingsManager.selectLitertModel`), the same rule Android
    /// follows: the samplers belong to the checkpoint, so switching models replaces them
    /// rather than carrying the old one's tuning over.
    func selectLitertModel(_ modelId: String) {
        container.settingsManager.selectLitertModel(modelId: modelId)
    }
    func setLitertTopK(_ value: String) {
        container.settingsManager.setLitertTopK(value: value)
    }
    func setLitertTopP(_ value: String) {
        container.settingsManager.setLitertTopP(value: value)
    }
    func setLitertTemperature(_ value: String) {
        container.settingsManager.setLitertTemperature(value: value)
    }
    func setLitertMaxTokens(_ value: String) {
        container.settingsManager.setLitertMaxTokens(value: value)
    }
    func setLitertAccelerator(_ value: String) {
        container.settingsManager.setLitertAccelerator(value: value)
    }
    func setLitertEnableThinking(_ value: Bool) {
        container.settingsManager.setLitertEnableThinking(value: value)
    }
    func setHuggingFaceToken(_ value: String) {
        container.settingsManager.setHuggingFaceToken(value: value)
    }
}

// MARK: - Onboarding actions

extension AppViewModel {
    /// Android's `OnboardingScreen.completeOnboarding(saveCredentials:)` minus the
    /// credentials: iOS onboarding no longer asks for an endpoint, so `openaiAPI`,
    /// `openaiKey` and `selectedModel` are left at their empty defaults and are
    /// written only from Settings.
    func completeOnboarding() {
        let resolved = Self.resolvedLocale
        setLanguage(resolved)
        // Android turns Vosk on here iff a *bundled* model exists for the locale, so
        // offline recognition works with no download. The iOS analogue of "works
        // offline out of the box" is the recognizer reporting on-device support.
        let sttLocale = SettingsInterop.locale(for: resolved)
        let supportsOnDevice = SFSpeechRecognizer(locale: Locale(identifier: sttLocale))?
            .supportsOnDeviceRecognition ?? false
        setUseVosk(supportsOnDevice)
        // On device by default, as the hand-written build had it
        // (`SettingsManager.Defaults.mode = .device`). Only the engine — no model is
        // picked here, so the first send raises the usual "Model is not configured"
        // alert and sends the user to Settings, exactly as it would on the remote engine
        // with no endpoint.
        //
        // Set here rather than as the shared `SettingsManager` default, which Android
        // reads too: its on-device engine is LiteRT, several gigabytes the user has to
        // choose deliberately, and its onboarding *does* configure an endpoint.
        setUseOnDevice(true)
        setHasCompletedOnboarding(true)
    }

    /// Forwards to the shared `normalizeApiUrl` in `ai.roleswitch.android.util.ApiUrl`.
    func normalizeApiUrl(_ input: String) -> String {
        ApiUrlKt.normalizeApiUrl(input: input)
    }

    func fetchModels(endpoint: String, token: String) async throws -> [String] {
        try await container.oaiService.fetchModels(endpoint: endpoint, token: token)
    }
}

// MARK: - Threads / messages actions

extension AppViewModel {
    func insertThread(title: String, prompt: String) async -> String {
        let thread = container.createThreadWithPrompt(title: title, prompt: prompt)
        try? await container.database.messageThreadDao().insert(thread: thread)
        return thread.threadId
    }

    func insertGreeting(threadId: String, text: String) async {
        let msg = container.createMessage(content: text, isUser: false, threadId: threadId)
        try? await container.database.messageDao().insert(message: msg)
    }

    func deleteThread(_ thread: MessageThread) async {
        _ = try? await container.database.deleteThread(thread: thread)
    }

    func deleteMessage(_ message: Message) async {
        _ = try? await container.database.messageDao().delete(message: message)
    }

    func clearThreadMessages(threadId: String) async {
        _ = try? await container.database.messageDao().deleteByThread(threadId: threadId)
    }

    func setMessageFavorite(_ message: Message, favorite: Bool) async {
        _ = try? await container.database.setMessageFavorite(message: message, favorite: favorite)
    }

    func updateMessageContent(_ message: Message, content: String) async {
        _ = try? await container.database.updateMessageContent(message: message, content: content)
    }

    func updateThread(_ thread: MessageThread, title: String, prompt: String) async {
        _ = try? await container.database.updateThreadTitleAndPrompt(thread: thread, title: title, prompt: prompt)
    }

    /// Persists a thread's avatar JPEG `Data` (or clears it when `data` is nil).
    /// Mirrors Android's `AppDatabase.updateThreadAvatar`.
    func updateThreadAvatar(_ thread: MessageThread, data: Data?) async {
        let kba = data?.toKotlinByteArray()
        _ = try? await container.database.updateThreadAvatar(thread: thread, avatarBytes: kba)
    }

    /// Writes the intelligence + misc + audio fields as the thread's overrides and
    /// enables per-thread overrides (`requireOnDeviceVoice` is filled from global
    /// server-side since iOS never edits it — `useVosk` is its offline toggle).
    func applyThreadOverrides(_ thread: MessageThread, overrides: ThreadOverrides) async {
        _ = try? await container.applyThreadOverrides(
            thread: thread,
            apiUrl: overrides.apiUrl,
            apiKey: overrides.apiKey,
            model: overrides.model,
            enableVision: overrides.enableVision,
            messageLimit: Int32(overrides.messageLimit),
            temperature: overrides.temperature,
            topP: overrides.topP,
            n: overrides.n,
            maxCompletionTokens: overrides.maxCompletionTokens,
            presencePenalty: overrides.presencePenalty,
            frequencyPenalty: overrides.frequencyPenalty,
            reasoningEffort: overrides.reasoningEffort,
            embedTime: overrides.embedTime,
            keepLastAttachment: overrides.keepLastAttachment,
            streamingMode: overrides.streamingMode,
            language: overrides.language,
            voice: overrides.voice,
            useVosk: overrides.useVosk,
            allowInterruptions: overrides.allowInterruptions,
            useSystemTts: overrides.useSystemTts,
            useExternalTts: overrides.useExternalTts,
            externalTtsVoice: overrides.externalTtsVoice,
            externalTtsAPI: overrides.externalTtsAPI,
            externalTtsKey: overrides.externalTtsKey,
            useOnDevice: overrides.useOnDevice,
            litertModel: overrides.litertModel,
            litertTopK: overrides.litertTopK,
            litertTopP: overrides.litertTopP,
            litertTemperature: overrides.litertTemperature,
            litertMaxTokens: overrides.litertMaxTokens,
            litertAccelerator: overrides.litertAccelerator,
            litertEnableThinking: overrides.litertEnableThinking
        )
    }

    /// Disables per-thread overrides (keeps the stale columns so re-enabling
    /// restores them).
    func disableThreadOverrides(_ thread: MessageThread) async {
        _ = try? await container.database.disableThreadOverrides(thread: thread)
    }

    // MARK: MCP tools

    /// Every configured MCP endpoint. Fetched on demand rather than observed: the
    /// tool screens are the only readers and they are short-lived.
    func loadTools() async -> [McpTool] {
        (try? await container.database.mcpToolDao().getAllTools()) ?? []
    }

    /// Adds (`id == nil`) or updates an endpoint. Goes through the shared facade
    /// because `McpTool.copy(...)` and its default arguments do not export to Swift.
    func saveTool(id: String?, name: String, url: String, token: String) async {
        _ = try? await container.database.upsertMcpTool(
            id: id, name: name, url: url, token: token
        )
    }

    func deleteTool(_ tool: McpTool) async {
        _ = try? await container.database.mcpToolDao().delete(tool: tool)
    }

    /// Connects to [url] and returns the tool names it advertises, so the editor can
    /// tell the user the endpoint works before they rely on it mid-conversation.
    /// Throws so the caller can surface the reason.
    func discoverToolNames(url: String, token: String) async throws -> [String] {
        try await container.mcpService.discoverToolNames(url: url, token: token)
    }

    /// Writes a thread's tool selection. Null follows the global per-endpoint flags;
    /// a list replaces them, and an empty list means this thread calls no tools.
    func applyThreadToolSelection(_ thread: MessageThread, toolIds: [String]) async {
        _ = try? await container.database.applyThreadToolSelection(thread: thread, toolIds: toolIds)
    }

    /// Sends a user message + triggers LLM generation. `images` is raw JPEG `Data`;
    /// `documents` is picked-file bytes + names that the shared service extracts to
    /// text before persisting — neither the raw document bytes nor the text are ever
    /// sent to the endpoint as files, only the extracted text rides in the prompt.
    func sendMessage(text: String, threadId: String, images: [Data], documents: [DocumentInput] = []) async throws {
        let kbaImages = images.map { $0.toKotlinByteArray() }
        try await container.llmService.sendMessage(
            text: text, threadId: threadId, images: kbaImages, documents: documents
        )
    }

    /// Tears down on-device inference when the app leaves the foreground.
    ///
    /// The remote engine deliberately keeps streaming across the transition — that is what the
    /// `UIBackgroundTask` around `isStreaming` is for — but LiteRT cannot: a suspended app with
    /// GPU/Metal work outstanding is killed by the OS, not paused. So an on-device turn is
    /// cancelled and the weights released.
    ///
    /// Cancelled *through* `LLMService` rather than only in the engine, so the shared side clears
    /// `isStreaming` and reports the turn as discarded; the chat then drops its partial bubble
    /// instead of stranding text no `Message` row backs. The existing
    /// `pendingCancellationRetryThreadId` path already offers the retry on return.
    ///
    /// Downloads are never cancelled here: they run in `nsurlsessiond` outside this process and
    /// survive suspension, and cancelling them is what threw a model install away every time the
    /// screen locked.
    func stopOnDeviceWork() async {
        if litert.isGenerating {
            let threadId = container.llmService.currentThreadId.value as? String
            container.llmService.cancelRequest()
            pendingCancellationRetryThreadId = threadId
        }
        await litert.stopForBackground()
    }

    func cancelLLM() {
        // User-initiated cancel — never pair with a retry prompt. Clear any
        // stale `pendingCancellationRetryThreadId` that might have been set by
        // an earlier background expiration and not yet surfaced.
        pendingCancellationRetryThreadId = nil
        container.llmService.cancelRequest()
    }

    /// Re-issues the LLM request for a thread whose previous run was cancelled
    /// by the background-task expiration handler. Uses `processLLMResponse`
    /// (not `sendMessage`) so the user's original message — including any
    /// attached image bytes already persisted in Room — is reused as-is and not
    /// duplicated in the chat history.
    func retryLLM(threadId: String) async {
        pendingCancellationRetryThreadId = nil
        let emptyImages: [KotlinByteArray] = []
        try? await container.llmService.processLLMResponse(
            userText: "",
            threadId: threadId,
            images: emptyImages
        )
    }

    /// Wipes every persisted row and resets settings — mirrors Android's "Reset" action.
    ///
    /// It deliberately does **not** re-seed. `reset()` clears `hasCompletedOnboarding`,
    /// which sends `RootView` to `OnboardingView`, and that screen's `.task` is the one
    /// and only seeding site. Seeding here as well used to overlap with it: both callers
    /// read an empty `message_threads`, both inserted the fixed `DEFAULT_THREAD_ID`, and
    /// the loser crashed the process with `UNIQUE constraint failed`.
    func resetAllData() async {
        _ = try? await container.database.clearAllData()
        _ = try? await container.settingsManager.reset()
        // The selection is gone, so the resident model is no longer anything's — released here
        // rather than waiting for the next screen's `syncTo` to notice. Downloaded weights stay
        // on disk, exactly as Android keeps its built-in checkpoints through a reset: they are
        // still listed and still usable, and silently discarding gigabytes would be a much
        // bigger action than the button promises.
        await litert.stopForBackground()
    }
}

// MARK: - Image helpers

extension AppViewModel {
    /// Compresses a `UIImage` to JPEG, capping the longest edge at `maxDimension` (defaults to 1024 to
    /// mirror Android). Returns the encoded bytes suitable for `LLMService.sendMessage(images:)`.
    static func encodeImage(_ image: UIImage, maxDimension: CGFloat = 1024, quality: CGFloat = 0.8) -> Data? {
        let scale = min(maxDimension / image.size.width, maxDimension / image.size.height, 1)
        let target = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: target, format: format)
        let resized = renderer.image { _ in image.draw(in: CGRect(origin: .zero, size: target)) }
        return resized.jpegData(compressionQuality: quality)
    }
}

// MARK: - Background lifecycle + completion notifications

extension AppViewModel {

    /// Request `.alert` + `.sound` authorization. Called from `OnboardingView`
    /// on finish. Idempotent — iOS surfaces the prompt only the first time.
    func requestNotificationAuthorization() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    /// Begin an iOS background task so the in-flight ktor-darwin stream isn't
    /// suspended when the user locks the screen or backgrounds the app. iOS
    /// grants roughly 30s of `backgroundTimeRemaining`; for streams that finish
    /// within that window the response is delivered and a completion
    /// notification is posted by `observeResponseCompletions`.
    ///
    /// If the stream is still running when iOS fires the expiration handler,
    /// we cancel the request cleanly — leaving the ktor `NSURLSession`
    /// half-open is the actual cause of the backgrounding crash.
    fileprivate func startBackgroundTask() {
        endBackgroundTask()
        backgroundTaskId = UIApplication.shared.beginBackgroundTask(
            withName: "LLMStreaming"
        ) { [weak self] in
            // Expiration handler — runs on a background queue. Hop to main to
            // safely touch the KMP service and our own state.
            Task { @MainActor in
                guard let self else { return }
                // Capture the active threadId *before* cancelling — LLMService
                // resets `currentThreadId` to null in its `finally` block once
                // the coroutine observes the cancellation. We need the id so we
                // can offer the user a retry when they next foreground the app.
                let threadId = self.container.llmService.currentThreadId.value as? String
                self.cancelLLM()
                self.endBackgroundTask()
                guard let threadId else { return }
                self.pendingCancellationRetryThreadId = threadId
            }
        }
    }

    /// End the active background task (if any). Idempotent.
    fileprivate func endBackgroundTask() {
        guard backgroundTaskId != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTaskId)
        backgroundTaskId = .invalid
    }

    /// Mirrors Android's `RolesWitchApp.observeResponseCompletions`. Collects
    /// `llmService.responseCompleted` and posts a local notification per
    /// completed response *only when the app is in the background* (covers
    /// screen-locked). Foreground emissions are ignored — the user already
    /// sees the reply in the open chat.
    private func observeResponseCompletions() {
        responseCompletionTask = Task { [weak self] in
            guard let self else { return }
            let flow = self.container.llmService.responseCompleted
            let collector = ClosureFlowCollector { [weak self] value in
                guard let self else { return }
                guard let response = value as? CompletedResponse else { return }
                Task { @MainActor in self.handleResponseCompletion(response) }
            }
            _ = try? await flow.collect(collector: collector)
        }
    }

    @MainActor
    private func handleResponseCompletion(_ response: CompletedResponse) {
        guard !isAppForegrounded else { return }
        Task {
            let title = await resolveThreadTitle(threadId: response.threadId)
            await postCompletionNotification(
                threadId: response.threadId,
                title: title,
                content: response.content
            )
        }
    }

    /// Resolve the thread title for the notification. Falls back to the app's
    /// display name (or "RolesWitch") — same fallback policy as Android's
    /// `LlmForegroundService.postCompletionNotification`.
    private func resolveThreadTitle(threadId: String) async -> String {
        if let thread = try? await container.database.messageThreadDao()
            .getThreadById(threadId: threadId),
           !thread.title.isEmpty {
            return thread.title
        }
        return (Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String)
            ?? "RolesWitch"
    }

    /// Post a messenger-style local notification for a completed response.
    /// Per-thread identifier so a new response in the same thread replaces the
    /// previous one (matches Android's `completionIdFor(threadId)`).
    @MainActor
    private func postCompletionNotification(
        threadId: String,
        title: String,
        content: String
    ) async {
        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        guard settings.authorizationStatus == .authorized
                || settings.authorizationStatus == .provisional else { return }

        // Cap body length to mirror Android `MAX_BODY_LEN = 500`.
        let body = String(content.prefix(500))

        let notifContent = UNMutableNotificationContent()
        notifContent.title = title
        notifContent.body = body
        notifContent.userInfo = ["threadId": threadId]
        notifContent.categoryIdentifier = "MESSAGE"
        notifContent.sound = .default

        let request = UNNotificationRequest(
            identifier: "llm-completion-\(threadId)",
            content: notifContent,
            trigger: nil
        )
        try? await center.add(request)
    }
}
