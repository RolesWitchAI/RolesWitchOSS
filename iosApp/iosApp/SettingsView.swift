//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import SwiftUI
import AVFoundation
import SharedLogic

/// Settings screen mirroring Android's `SettingsScreen.kt`. Skips the Audio
/// section (language / voice / Vosk) — iOS will surface those differently.
struct SettingsView: View {
    @EnvironmentObject private var app: AppViewModel

    @State private var showEngineSheet = false
    @State private var showHuggingFaceTokenEditor = false
    @State private var showServerSheet = false
    @State private var showModelSheet = false
    @State private var showMessageLimitSheet = false
    @State private var showReasoningSheet = false
    @State private var showLanguageSheet = false
    @State private var showVoiceSheet = false
    @State private var showSpeechEngineSheet = false
    @State private var showExternalServerSheet = false
    @State private var showExternalVoiceSheet = false
    @State private var showResetAlert = false
    @State private var showAcknowledgements = false
    /// Registered MCP endpoints, for the Tools row's value. Reloaded on every appear
    /// because `ToolsView` is a push — returning from it is the one moment this can
    /// have changed, and there is no observable tool flow on this side.
    @State private var toolCount = 0

    var body: some View {
        Form {
            intelligenceSection
            speechInputSection
            speechOutputSection
            miscSection
            // Last, because it is the only section that leads off to a separate management
            // screen rather than flipping settings in place. Matches Android's order.
            //
            // Cloud only: MCP tools are not wired to the on-device engine (`LiteRtInferenceEngine`
            // accepts `toolSpecs`/`callTool` and ignores them), so on device an endpoint registered
            // here could not be called by anything that would actually run. Android keeps the
            // section on both engines — LiteRT declares each MCP function individually as an
            // `OpenApiTool` and reaches the endpoints through that path.
            if !app.useOnDevice {
                toolsSection
            }
            Section {
                Button {
                    showResetAlert = true
                } label: {
                    Text("Reset settings")
                        .frame(maxWidth: .infinity)
                        .foregroundStyle(.red)
                        .fontWeight(.semibold)
                }
            } footer: {
                // Centred under the last row, as on Android (`SettingsScreen.kt`) — the
                // build number is the first thing worth asking for in a bug report.
                Text("Version \(Self.appVersion) (\(Self.appBuild))")
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.top, 8)
            }
        }
        .navigationTitle("Settings")
        .onAppear { Task { toolCount = await app.loadTools().count } }
        // A push, as on Android, rather than a sheet: it is a reference screen you read
        // and back out of, not a task with a result.
        .navigationDestination(isPresented: $showAcknowledgements) {
            AcknowledgementsView()
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Link(destination: URL(string: "mailto:hello@roleswitch.ai")!) {
                        Label("Contact Us", systemImage: "envelope")
                    }
                    Link(destination: URL(string: "https://roleswitch.ai/privacy-policy.html")!) {
                        Label("Privacy Policy", systemImage: "hand.raised")
                    }
                    Link(destination: URL(string: "https://roleswitch.ai/terms-and-conditions.html")!) {
                        Label("Terms and Conditions", systemImage: "doc.text")
                    }
                    // Last, as on Android — same four items in the same order.
                    Button { showAcknowledgements = true } label: {
                        Label("Acknowledgements", systemImage: "info.circle")
                    }
                } label: {
                    Image(systemName: "ellipsis")
                }
            }
        }
        .sheet(isPresented: $showEngineSheet) {
            EnginePickerSheet(current: app.useOnDevice) { onDevice in
                app.setUseOnDevice(onDevice)
                showEngineSheet = false
            }
            .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $showHuggingFaceTokenEditor) {
            HuggingFaceTokenSheet(current: app.huggingFaceToken) { token in
                app.setHuggingFaceToken(token)
                showHuggingFaceTokenEditor = false
            }
            .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $showServerSheet) {
            ServerSheet(initialURL: app.openaiAPI, initialToken: app.openaiKey) { url, token in
                let urlChanged = url != app.openaiAPI
                app.setOpenaiAPI(url)
                app.setOpenaiKey(token)
                // Mirror Android: a URL change invalidates the previously-selected model.
                if urlChanged { app.setSelectedModel("") }
                showServerSheet = false
            }
        }
        .sheet(isPresented: $showModelSheet) {
            RemoteModelPickerSheet(
                endpoint: app.openaiAPI,
                token: app.openaiKey,
                currentModel: app.selectedModel
            ) { newValue in
                app.setSelectedModel(newValue)
                showModelSheet = false
            }
        }
        .sheet(isPresented: $showMessageLimitSheet) {
            MessageLimitSheet(current: app.messageLimit, isOnDevice: app.useOnDevice) { newValue in
                app.setMessageLimit(newValue)
                showMessageLimitSheet = false
            }
            .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $showReasoningSheet) {
            ReasoningPickerSheet(current: app.reasoningEffort) { newValue in
                app.setReasoningEffort(newValue)
                showReasoningSheet = false
            }
            .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $showLanguageSheet) {
            LanguagePickerSheet(current: app.language) { code in
                // Changing language invalidates the previously-chosen voice
                // (voice names are language-specific).
                app.setLanguage(code)
                app.setVoice("")
                showLanguageSheet = false
            }
            .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $showVoiceSheet) {
            VoicePickerSheet(language: app.language, current: app.voice) { name in
                app.setVoice(name)
                showVoiceSheet = false
            }
            .presentationDetents([.medium, .large])
        }
        // The engine picker mirrors Android's three-way Speech engine sheet, minus the
        // Auto option: on iOS both "System" and "Auto" (neither flag) speak the native
        // voice, so the only real choice is System vs External (OpenAI). Both rows are
        // always offered — recognition and synthesis are independent, so "Process on
        // device" does not gate the engine.
        .sheet(isPresented: $showSpeechEngineSheet) {
            TtsEnginePickerSheet(
                isExternalSelected: app.useExternalTts
            ) { external in
                // Any engine change clears the bound voice: a stored name belongs to the
                // engine that reported it (mirrors Android's `saveTtsMode`). The external
                // voice id is server-side and survives engine switches.
                app.setVoice("")
                app.setUseSystemTts(!external)
                app.setUseExternalTts(external)
                showSpeechEngineSheet = false
            }
            .presentationDetents([.medium, .large])
        }
        // The external Server row edits the *global external-speech* settings — the speech
        // endpoint and token, distinct from the chat's OpenAI settings (inference and
        // speech may live on different hosts). No model clearing: an inference model id is
        // irrelevant to a speech server, and the speech settings never touch `openaiAPI`/
        // `openaiKey`/`selectedModel`.
        .sheet(isPresented: $showExternalServerSheet) {
            ServerSheet(initialURL: app.externalTtsAPI, initialToken: app.externalTtsKey) { url, token in
                app.setExternalTtsAPI(url)
                app.setExternalTtsKey(token)
                showExternalServerSheet = false
            }
        }
        // The external voice id is free-form — any endpoint may offer names the picker
        // has never heard of, so a text field beats a static list. Empty is meaningful
        // ("let the server pick"). The sheet saves the value, then previews the
        // per-language greeting through the same server.
        .sheet(isPresented: $showExternalVoiceSheet) {
            ExternalVoiceSheet(current: app.externalTtsVoice, language: app.language) { name in
                // Persist only — the sheet holds itself up to preview the greeting, then
                // dismisses itself (`onChange` of its own `isSpeaking`).
                app.setExternalTtsVoice(name)
            }
            .presentationDetents([.fraction(0.35), .medium])
        }
        .alert("Reset settings?", isPresented: $showResetAlert) {
            Button("Reset", role: .destructive) {
                Task { await app.resetAllData() }
            }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text("This will delete all records and reset app. You will be returned to onboarding screen.")
        }
    }

    // MARK: Version

    /// `CFBundleShortVersionString` / `CFBundleVersion` — Xcode fills both from
    /// `MARKETING_VERSION` and `CURRENT_PROJECT_VERSION`, the counterparts of Android's
    /// `versionName` / `versionCode`. Read as `verbatim`, never localized.
    private static var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—"
    }

    private static var appBuild: String {
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "—"
    }

    // MARK: Intelligence

    @ViewBuilder
    private var intelligenceSection: some View {
        Section {
            // First row, as on Android: everything below it means something different
            // depending on the answer.
            row(label: "Engine",
                value: app.useOnDevice ? String(localized: "On device") : String(localized: "Remote")) {
                showEngineSheet = true
            }

            if app.useOnDevice {
                // The token sits above the model row, as on Android: a gated model cannot
                // be downloaded without one, so the order matches what the user has to do.
                // Its "Optional" value carries the whole message — which models actually
                // need it is said by the download's own refusal, at the moment it happens.
                row(label: "HuggingFace token",
                    value: app.huggingFaceToken.isEmpty
                        ? String(localized: "Optional")
                        : "••••••••") {
                    showHuggingFaceTokenEditor = true
                }

                // A push, like Tools below: the picker writes the selection on tap
                // (no Save), so it is a screen the user backs out of, not a modal
                // choice with a result.
                NavigationLink {
                    LiteRtModelView(selectedModelId: app.litertModel) { model in
                        // nil = the selection was cleared on the picker (its weights
                        // deleted); the empty id is the "no model" marker, as on Android.
                        app.selectLitertModel(model?.id ?? "")
                    }
                } label: {
                    HStack {
                        Text("Model")
                        Spacer()
                        Text(verbatim: onDeviceModelLabel)
                            .foregroundStyle(app.litertModel.isEmpty ? Color.red : Color.secondary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                }
            } else {
                // One row for the endpoint and its token: they are edited together in
                // `ServerSheet`. The value shows the URL, since the token is optional and
                // secret — there is nothing useful to render for it here.
                row(label: "Server",
                    value: app.openaiAPI.isEmpty ? String(localized: "Not configured") : app.openaiAPI,
                    isError: app.openaiAPI.isEmpty) { showServerSheet = true }

                // Not tappable until there is a server to ask: the picker lists what the
                // endpoint reports, and saving the server is also what runs the preflight
                // that gets Local Network access granted before that first fetch.
                row(label: "Model",
                    value: app.selectedModel.isEmpty ? String(localized: "Not configured") : app.selectedModel,
                    isError: app.selectedModel.isEmpty && !app.openaiAPI.isEmpty,
                    enabled: !app.openaiAPI.isEmpty) { showModelSheet = true }
            }

            // Per checkpoint, as on Android, not blanket-hidden: LiteRT only learns whether its
            // vision tower built by building it, so capability is a property of the model, not
            // of the engine. All four catalogued checkpoints today support vision, which leaves
            // the toggle hidden in practice — but a text-only checkpoint would need it, and the
            // branch is what decides, per checkpoint, not the engine name.
            if app.useOnDevice {
                if let selected = LiteRtRegistry.model(withId: app.litertModel), !selected.supportsImage {
                    Toggle(isOn: Binding(get: { app.enableVision }, set: { app.setEnableVision($0) })) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Enable Vision").font(.body)
                            // Shown whether or not the toggle is on: it is advice about picking
                            // a model, which is most useful *before* switching vision on.
                            Text("Make sure the model has vision capability").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            } else {
                Toggle(isOn: Binding(get: { app.enableVision }, set: { app.setEnableVision($0) })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Enable Vision").font(.body)
                        // Shown whether or not the toggle is on: it is advice about picking
                        // a model, which is most useful *before* switching vision on.
                        Text("Make sure the model has vision capability").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }

            row(label: "Messages to process",
                value: app.messageLimit >= 100_000 ? String(localized: "Unlimited") : "\(app.messageLimit)") {
                showMessageLimitSheet = true
            }

            if app.useOnDevice {
                // A plain on/off, unlike the remote engine's `reasoning_effort` levels — the
                // chat template's `enable_thinking` kwarg has no degrees. Only offered for
                // checkpoints whose template honours it; the rest ignore the flag.
                if LiteRtRegistry.model(withId: app.litertModel)?.supportsThinking == true {
                    Toggle(isOn: Binding(get: { app.litertEnableThinking }, set: { app.setLitertEnableThinking($0) })) {
                        Text("Thinking")
                    }
                }
            } else {
                row(label: "Reasoning",
                    value: reasoningLabel(app.reasoningEffort)) {
                    showReasoningSheet = true
                }
            }

            NavigationLink {
                AdvancedView()
            } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Advanced").foregroundStyle(.primary)
                        Text("Such as topP and others").font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                }
            }
        } header: { Text("Intelligence") }
    }

    /// The on-device model's name, or why there isn't a usable one. A selection whose
    /// weights were deleted reads as "Not configured" rather than as set up — exactly
    /// how the send/call guards treat it, and why deleting a model clears the global
    /// selection on the picker.
    private var onDeviceModelLabel: String {
        guard let model = LiteRtRegistry.model(withId: app.litertModel),
              model.isDownloaded else {
            return String(localized: "Not configured")
        }
        return model.displayName
    }

    // MARK: Tools

    @ViewBuilder
    private var toolsSection: some View {
        Section {
            // No "use tools" switch above this: whether an endpoint participates is said
            // once, on the endpoint's own row inside ToolsView.
            NavigationLink {
                ToolsView()
            } label: {
                HStack {
                    Text("Manage tools")
                    Spacer()
                    // How many endpoints are registered, as on Android's row — the
                    // section otherwise gives no sign whether there is anything in it.
                    Text(toolCountLabel).foregroundStyle(.secondary)
                }
            }
        } header: { Text("Tools") }
    }

    /// "None", or Android's "%d configured". Its plural is one string there for both
    /// quantities, so the interpolation needs no variation here either.
    private var toolCountLabel: String {
        toolCount == 0 ? String(localized: "None") : String(localized: "\(toolCount) configured")
    }

    // MARK: Speech Input

    /// Recognition, with the language that scopes it — the iOS mirror of Android's
    /// Speech input section. iOS has no recognizer engine to pick, so the on-device
    /// toggle is the last row here.
    @ViewBuilder
    private var speechInputSection: some View {
        Section {
            row(label: "Language",
                value: SettingsInterop.languageName(app.language)) {
                showLanguageSheet = true
            }

            Toggle(isOn: Binding(get: { app.useVosk }, set: { app.setUseVosk($0) })) {
                Text("Process on device")
            }
        } header: { Text("Speech input") }
    }

    // MARK: Speech Output

    /// Synthesis: the engine and the rows it brings with it.
    @ViewBuilder
    private var speechOutputSection: some View {
        Section {
            // The speech engine, as on Android — minus Android's "Auto" option: on iOS
            // "Auto" (neither flag) and "System" both speak the native voice, so the
            // sheet offers only the two real choices.
            row(label: "Speech engine",
                value: app.useExternalTts
                    ? String(localized: "External (OpenAI)")
                    : String(localized: "System")) {
                showSpeechEngineSheet = true
            }

            if app.useExternalTts {
                // External speech hides the Voice/Voice Speed rows — they configure the
                // engine that will not be used — and shows the OpenAI pair instead. The
                // Server row edits the *global external-speech* server (URL + token),
                // kept apart from the chat's inference endpoint: the two may be different
                // hosts, and editing one never touches the other or the selected model.
                row(label: "Server",
                    value: app.externalTtsAPI.isEmpty ? String(localized: "Not configured") : app.externalTtsAPI,
                    isError: app.externalTtsAPI.isEmpty) { showExternalServerSheet = true }

                row(label: "Voice",
                    value: app.externalTtsVoice.isEmpty ? String(localized: "Default") : app.externalTtsVoice) {
                    showExternalVoiceSheet = true
                }
            } else {
                row(label: "Voice",
                    value: app.voice.isEmpty ? String(localized: "Default") : app.voice) {
                    showVoiceSheet = true
                }
            }
        } header: { Text("Speech output") }
    }

    // MARK: Misc

    @ViewBuilder
    private var miscSection: some View {
        Section("Miscellaneous") {
            Toggle(isOn: Binding(get: { app.embedTime }, set: { app.setEmbedTime($0) })) {
                Text("Share date and time")
            }
            Toggle(isOn: Binding(get: { app.keepLastAttachment }, set: { app.setKeepLastAttachment($0) })) {
                Text("Keep last attachment")
            }
            Toggle(isOn: Binding(get: { app.streamingMode }, set: { app.setStreamingMode($0) })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Streaming mode").font(.body)
                    Text("Show replies as they're generated")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Toggle(isOn: Binding(get: { app.allowInterruptions }, set: { app.setAllowInterruptions($0) })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Allow interruptions").font(.body)
                    Text("Requires headphones").font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }

    // MARK: About — removed (now lives in the top-trailing toolbar menu)

    // MARK: Row helper

    @ViewBuilder
    /// `label` is always UI chrome, so it goes through the catalog. `value` carries
    /// user/endpoint data (URL, model id, voice name) and must not — callers pass
    /// `String(localized:)` for their own placeholder branches.
    private func row(
        label: LocalizedStringKey,
        value: String,
        isError: Bool = false,
        enabled: Bool = true,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack {
                Text(label).foregroundStyle(.primary)
                Spacer()
                Text(verbatim: value)
                    .foregroundStyle(isError ? Color.red : Color.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.tertiary)
            }
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

// MARK: - Server sheet (endpoint + token)

/// The endpoint and its token, edited together — they are one credential, and a URL
/// saved without the token it needs fails on the next request with nothing on screen
/// to explain why. `onSave` reports both so the caller writes them in one go.
///
/// The token stays optional; only the URL gates Save.
struct ServerSheet: View {
    let initialURL: String
    let initialToken: String
    let onSave: (String, String) -> Void

    @EnvironmentObject private var app: AppViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var urlText: String = ""
    @State private var tokenText: String = ""
    @State private var error: String?
    @State private var showDataSharingAlert = false
    @State private var isPreflighting = false
    @FocusState private var urlFocused: Bool

    /// Names the host in the Data Sharing copy, re-read as the user types. Falls back to
    /// generic wording while the field has no host yet.
    private var destination: String {
        app.hostname(fromApiUrl: urlText) ?? String(localized: "the endpoint you configure")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    // An example endpoint, not translatable copy — `verbatim` keeps
                    // it out of the string catalog.
                    TextField(text: $urlText, prompt: Text(verbatim: "http://192.168.0.100:1234/v1")) { EmptyView() }
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .focused($urlFocused)
                        .onChange(of: urlFocused) { _, isFocused in
                            if !isFocused && !urlText.isEmpty {
                                let normalized = app.normalizeApiUrl(urlText)
                                if normalized != urlText {
                                    urlText = normalized
                                    error = nil
                                }
                            }
                        }
                } header: {
                    Text("API URL")
                }

                if let error {
                    Section { Text(verbatim: error).foregroundStyle(.red).font(.footnote) }
                }

                Section {
                    // Plain text, like the HuggingFace token below and Android's server
                    // sheet: the token is pasted, and dots would only make checking what
                    // landed impossible.
                    TextField(String(localized: "Your token"), text: $tokenText)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: {
                    Text("API Token")
                } footer: {
                    Text("Optional")
                }

                // Names the host the user is about to send their conversations to — a
                // warning that says "a remote server" teaches nobody which one they
                // just configured.
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Label("Data Sharing", systemImage: "hand.raised.fill")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.primary)

                        Text("In Cloud mode the app may transmit your messages, attached images, voice transcriptions, timestamps and basic client metadata to \(destination). Roles Witch does not control how \(destination) stores, processes or shares this data — their own privacy policy applies.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)

                        Link(destination: URL(string: "https://roleswitch.ai/privacy-policy.html")!) {
                            Text("Read Privacy Policy").font(.footnote.weight(.semibold))
                        }
                    }
                    .padding(.vertical, 4)
                }
            }
            .navigationTitle("Server")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(role: .close) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if isPreflighting {
                        ProgressView().controlSize(.small)
                    } else {
                        Button(role: .confirm) { attemptSave() }
                            // The token is optional, so only the URL can block the save.
                            .disabled(urlText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
            }
            .onAppear {
                urlText = initialURL
                tokenText = initialToken
                urlFocused = true
            }
            .alert("Data Sharing", isPresented: $showDataSharingAlert) {
                // Declining leaves the sheet exactly as it is, edits intact — nothing is
                // saved and nothing is dismissed.
                Button("Cancel", role: .cancel) { }
                Button("I Understand") {
                    app.setHasAcceptedDataSharing(true)
                    commit()
                }
            } message: {
                Text("Connecting to \(destination) may share your messages, attached images, voice transcriptions and timestamps with that remote server.\n\nRoles Witch does not control how \(destination) stores or uses your data — their own privacy policy applies.\n\nTap I Understand to give your explicit permission.")
            }
        }
    }

    /// Validates the URL, then either saves or asks for consent first.
    private func attemptSave() {
        guard URL(string: app.normalizeApiUrl(urlText)) != nil else {
            error = String(localized: "Invalid URL format")
            return
        }
        // First save on a fresh install has to pass the Data Sharing alert.
        if app.hasAcceptedDataSharing { commit() } else { showDataSharingAlert = true }
    }

    /// Warms up the connection, then hands both fields back. The preflight runs before
    /// the sheet closes so the Local Network prompt is answered here rather than landing
    /// on the model picker's first Ktor call — see `NetworkPreflight`.
    private func commit() {
        let normalized = app.normalizeApiUrl(urlText)
        let token = tokenText
        isPreflighting = true
        Task {
            await NetworkPreflight.warmUp(endpoint: normalized)
            isPreflighting = false
            onSave(normalized, token)
        }
    }
}

// MARK: - HuggingFace token editor

/// The token for gated checkpoints, edited in the standard form sheet. Optional: the
/// public checkpoints download without one, and a gated download with an empty or wrong
/// token fails in the model picker with the real 401/403 text.
struct HuggingFaceTokenSheet: View {
    let current: String
    let onSave: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var tokenText: String = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    // Plain text, not a SecureField — Android's editor shows the token the
                    // same way: it is pasted, never typed from memory, and dots would only
                    // make checking what landed impossible.
                    TextField("HuggingFace token", text: $tokenText, prompt: Text(verbatim: "hf_..."))
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
            }
            .navigationTitle("HuggingFace token")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(role: .close) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(role: .confirm) {
                        onSave(tokenText)
                        dismiss()
                    }
                }
            }
            .onAppear { tokenText = current }
        }
    }
}

// MARK: - Message-limit picker

struct MessageLimitSheet: View {
    let current: Int
    /// "Unlimited" is remote-only: an unbounded prefill would hang an on-device
    /// turn, so the on-device picker stops at 120 (see `ON_DEVICE_MESSAGE_LIMIT`).
    var isOnDevice: Bool = false
    // Last, so call sites' trailing closures match forward — a closure in the
    // middle is only reachable by backward matching, which Swift deprecates.
    let onPick: (Int) -> Void

    @Environment(\.dismiss) private var dismiss

    private var options: [Int] {
        isOnDevice ? [20, 50, 80, 120] : [20, 50, 80, 120, 100_000]
    }

    var body: some View {
        NavigationStack {
            List(options, id: \.self) { option in
                Button {
                    onPick(option)
                    dismiss()
                } label: {
                    HStack {
                        // One branch is chrome, one is a number — resolve both to a
                        // `String` and render verbatim rather than risk the ternary
                        // picking the localizing initializer for the count.
                        Text(verbatim: option >= 100_000 ? String(localized: "Unlimited") : "\(option)")
                            .foregroundStyle(.primary)
                        Spacer()
                        if option == current {
                            Image(systemName: "checkmark").foregroundStyle(.tint)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            .navigationTitle("Messages to process")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(role: .close) { dismiss() } }
            }
        }
    }
}

// MARK: - Reasoning picker

/// One Reasoning choice. `value` is the literal `reasoning_effort` sent to the server,
/// so it must stay in the API's vocabulary — except for `""`, which is this app's own
/// marker for "send nothing". Mirrors Android's `ReasoningOption`.
struct ReasoningOption {
    let value: String
    let title: String
}

/// The Reasoning choices, in ascending order of effort. Kept identical to Android's
/// `reasoningOptions` in `SettingsComponents.kt` — the two lists are the same product
/// decision and must not drift.
///
/// `""` leads deliberately: it is the default, and the only one that changes nothing
/// about the request.
let reasoningOptions: [ReasoningOption] = [
    ReasoningOption(value: "", title: String(localized: "Auto")),
    ReasoningOption(value: "none", title: String(localized: "None")),
    ReasoningOption(value: "low", title: String(localized: "Low")),
    ReasoningOption(value: "medium", title: String(localized: "Medium")),
    ReasoningOption(value: "high", title: String(localized: "High"))
]

/// Label for a stored Reasoning value. An unrecognised one shows verbatim rather than
/// falling back to "Auto" — a thread carrying a value this build has no name for is
/// still sending it, and the row must not claim otherwise.
func reasoningLabel(_ value: String) -> String {
    reasoningOptions.first { $0.value == value }?.title ?? value
}

struct ReasoningPickerSheet: View {
    let current: String
    let onPick: (String) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(reasoningOptions, id: \.value) { option in
                    Button {
                        onPick(option.value)
                        dismiss()
                    } label: {
                        HStack {
                            Text(verbatim: option.title).foregroundStyle(.primary)
                            Spacer()
                            if option.value == current {
                                Image(systemName: "checkmark").foregroundStyle(.tint)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .navigationTitle("Reasoning")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(role: .close) { dismiss() } }
            }
        }
    }
}

// MARK: - Language picker

struct LanguagePickerSheet: View {
    let current: String
    let onPick: (String) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(SettingsInterop.languageCodes, id: \.self) { code in
                    Button {
                        onPick(code)
                    } label: {
                        HStack {
                            // Display names come from the shared Kotlin map.
                            Text(verbatim: SettingsInterop.languageName(code))
                                .foregroundStyle(.primary)
                            Spacer()
                            if code == current {
                                Image(systemName: "checkmark").foregroundStyle(.tint)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .navigationTitle("Language")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(role: .close) { dismiss() } }
            }
        }
    }
}

// MARK: - Voice picker

/// Pick the voice the assistant speaks with, previewing each as you go.
///
/// Selection is local until Save, matching Android — tapping a voice auditions it, and
/// auditioning half a dozen should not write six values into settings, nor close the
/// sheet on the first tap.
struct VoicePickerSheet: View {
    let language: String
    let current: String
    let onPick: (String) -> Void

    @EnvironmentObject private var app: AppViewModel
    @Environment(\.dismiss) private var dismiss

    /// Its own synthesizer: the previews are this sheet's, and stopping them on the way
    /// out must not silence a reply the call screen is in the middle of speaking.
    @StateObject private var preview = SpeechSynthesizerService()

    @State private var selection: String = ""
    @State private var searchText = ""

    /// Read once, not per render. `AVSpeechSynthesisVoice.speechVoices()` walks every
    /// installed voice and this view sorts the result; leaving that in a computed property
    /// ran it on every body evaluation — twice, since the empty check called it again —
    /// and `.searchable` plus the synthesizer's own published state meant a great many
    /// body evaluations. That is where the CPU was going.
    @State private var allVoices: [AVSpeechSynthesisVoice] = []

    private var voices: [AVSpeechSynthesisVoice] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return allVoices }
        return allVoices.filter {
            $0.name.localizedCaseInsensitiveContains(query)
                || $0.language.localizedCaseInsensitiveContains(query)
        }
    }

    var body: some View {
        NavigationStack {
            Group {
                if allVoices.isEmpty {
                    VStack(spacing: 8) {
                        Image(systemName: "person.crop.circle.badge.questionmark")
                            .font(.system(size: 44)).foregroundStyle(.secondary)
                        Text("No voices installed for this language").foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List {
                        Section {
                            // Only when nothing is being searched for: it is not a voice, so a
                            // query that matches no voice should not still show it as a hit.
                            if searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                                row(
                                    title: Text("Default"),
                                    subtitle: Text("Enhanced voice for the language, else system default"),
                                    isEnhanced: false,
                                    isSelected: selection.isEmpty
                                ) {
                                    select("")
                                }
                            }

                            // Keyed by `identifier`, which is unique per installed voice.
                            // Keying by name collapsed voices that share one — iOS ships
                            // several — and keying by list offset re-bound every row to a new
                            // position whenever the search filtered the list, which is what
                            // made rows refuse to answer a tap.
                            ForEach(voices, id: \.identifier) { voice in
                                row(
                                    // A platform voice name and a BCP-47 tag: never translated.
                                    title: Text(verbatim: voice.name),
                                    subtitle: Text(verbatim: voice.language),
                                    isEnhanced: voice.quality != .default,
                                    isSelected: voice.identifier == selection
                                ) {
                                    select(voice.identifier)
                                }
                            }
                        } footer: {
                            Text("Enhanced and premium voices are installed in the system settings: Accessibility > Spoken Content > Voices")
                        }
                    }
                    .searchable(text: $searchText, prompt: Text("Search voices"))
                }
            }
            .navigationTitle("Select Voice")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(role: .close) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(role: .confirm) { onPick(voiceName(for: selection)) }
                }
            }
            .onAppear {
                allVoices = SpeechSynthesizerService.availableVoices(for: language)
                selection = allVoices.first { $0.name == current }?.identifier ?? ""
                Task { await beginPreviewAudio() }
            }
            .onDisappear {
                preview.stop()
                Task { await endPreviewAudio() }
            }
        }
    }

    /// One row, laid out so nothing moves between rows.
    ///
    /// The badge and the checkmark used to share a trailing `HStack` in which each was
    /// present only when it applied, so the checkmark sat at a different x on every row
    /// and jumped as the selection moved. Now the checkmark is always the last element
    /// and always occupies its width — hidden by opacity rather than removed — and the
    /// badge has a slot of its own in front of it.
    private func row(
        title: Text,
        subtitle: Text,
        isEnhanced: Bool,
        isSelected: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    title.foregroundStyle(.primary)
                    subtitle.font(.caption).foregroundStyle(.secondary)
                }
                Spacer(minLength: 8)
                if isEnhanced {
                    Text("ENHANCED")
                        .font(.caption2.weight(.semibold))
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color.accentColor.opacity(0.15), in: Capsule())
                        .foregroundStyle(.tint)
                }
                Image(systemName: "checkmark")
                    .foregroundStyle(.tint)
                    .opacity(isSelected ? 1 : 0)
                    .frame(width: 16)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// A synthesizer speaks into whatever audio session is current, and nothing had made
    /// one — the call screen activates its own, and this sheet is not the call screen — so
    /// the previews were being spoken into an inactive session and heard by nobody.
    ///
    /// `.playback`, not the call session's `.playAndRecord`: this only plays, and asking
    /// for the microphone would light the recording indicator to audition a voice.
    /// Skipped entirely while a call owns the session, so a preview cannot reconfigure
    /// audio out from under a conversation in progress. Detached, because the `async`
    /// overload of `setActive` alone does not avoid AVAudioSession's main-thread
    /// diagnostic — this is the reference site for that; the external-voice sheet and
    /// `CallAudioSession` do the same.
    private func beginPreviewAudio() async {
        guard !CallAudioSession.shared.isActive else { return }

        await Task.detached(priority: .userInitiated) {
            let session = AVAudioSession.sharedInstance()
            try? session.setCategory(.playback, mode: .default, options: [.duckOthers])
            try? session.setActive(true)
        }.value
    }

    private func endPreviewAudio() async {
        guard !CallAudioSession.shared.isActive else { return }

        await Task.detached(priority: .userInitiated) {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }.value
    }

    /// Selects and auditions. The greeting is spoken in the language being previewed —
    /// a voice saying a word from another language tells you very little about it.
    private func select(_ identifier: String) {
        selection = identifier
        let name = voiceName(for: identifier)
        _ = preview.speak(
            text: SpeechSynthesizerService.greeting(for: language),
            language: language,
            voiceName: name.isEmpty ? nil : name
        )
    }

    /// Persistence and TTS matching are by voice *name*, but rows are keyed by the unique
    /// `identifier` — same-name voices (e.g. "Sandy" in fr-CA and fr-FR) must stay distinct
    /// in the UI. The selected identifier resolves back to its name for `onPick` and preview.
    private func voiceName(for identifier: String) -> String {
        guard !identifier.isEmpty else { return "" }
        return allVoices.first { $0.identifier == identifier }?.name ?? ""
    }
}

// MARK: - Speech engine picker

/// Picks the speech engine: System (the native `AVSpeechSynthesizer`) or External
/// (OpenAI-compatible `/v1/audio/speech`). This is the port of Android's three-way
/// `Speech engine` sheet minus the Auto option — on iOS "Auto" (neither flag) resolves
/// to the same native voice as "System", so a third row would select what System
/// already does.
///
/// Selection commits on tap, like the other picker sheets. Both rows are always offered:
/// recognition and synthesis are independent, so External stays selectable while
/// "Process on device" is on.
struct TtsEnginePickerSheet: View {
    /// True when the current engine is External. The System row is selected otherwise:
    /// an "Auto" state (neither flag) shows System, since both speak the native voice.
    let isExternalSelected: Bool
    let onPick: (Bool) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                optionRow(title: String(localized: "System"), selected: !isExternalSelected) {
                    onPick(false)
                    dismiss()
                }
                optionRow(title: String(localized: "External (OpenAI)"), selected: isExternalSelected) {
                    onPick(true)
                    dismiss()
                }
            }
            .navigationTitle("Speech engine")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(role: .close) { dismiss() } }
            }
        }
    }

    private func optionRow(title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(verbatim: title).foregroundStyle(.primary)
                Spacer()
                if selected {
                    Image(systemName: "checkmark").foregroundStyle(.tint)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - External voice editor

/// The free-form voice id sent to the external speech server. Any endpoint may offer
/// names the picker has never heard of, so a text field beats a static list; empty is
/// meaningful ("let the server pick the voice"), so Save is not blocked on it.
///
/// The model picker's contract applied to a save: the request runs with the sheet up,
/// not behind it. Saving persists through `onSave` first (mirroring Android, which
/// writes the value before previewing), then previews the per-language greeting through
/// the same server the chat targets — a spinner stands in for the synthesis request
/// exactly how `RemoteModelPickerSheet` stands in for a model fetch — and the sheet
/// holds until the greeting has been heard, then dismisses itself. A failed request
/// never dismisses anything: the mapped reason — "Voice is not configured" when the
/// server endpoint is blank, "Server error" otherwise — stays in the sheet with Save
/// re-armed for a corrected retry.
struct ExternalVoiceSheet: View {
    let current: String
    let language: String
    let onSave: (String) -> Void

    @EnvironmentObject private var app: AppViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var voiceName: String = ""
    /// The synthesis request is in flight.
    @State private var isTesting = false
    @State private var hasSpoken = false
    @State private var errorMessage: String?

    /// Its own player: stopping the preview on the way out must not silence a reply
    /// the call screen is in the middle of speaking (`ExternalTtsService` is
    /// per-surface, exactly like the synthesizer previews).
    @StateObject private var preview = ExternalTtsService()

    var body: some View {
        NavigationStack {
            Form {
                TextField("Voice", text: $voiceName, prompt: Text(verbatim: "e.g. alloy, echo, onyx"))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                if isTesting {
                    HStack(spacing: 8) {
                        ProgressView().controlSize(.small)
                        Text("Testing voice…").foregroundStyle(.secondary)
                    }
                } else if let errorMessage {
                    // Verbatim: it is an error string, not a catalogue key.
                    Text(verbatim: errorMessage)
                        .font(.footnote)
                        .foregroundStyle(.red)
                }
            }
            .navigationTitle("Voice")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(role: .close) {
                        preview.stop()
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(role: .confirm) {
                        onSave(voiceName)
                        previewGreeting()
                    }
                    .disabled(isTesting)
                }
            }
            .onAppear {
                voiceName = current
                Task { await beginPreviewAudio() }
            }
            .onDisappear {
                preview.stop()
                Task { await endPreviewAudio() }
            }
            .onChange(of: preview.isSpeaking) { _, speaking in
                // The greeting has been heard in full: close the sheet.
                if !speaking && hasSpoken { dismiss() }
            }
        }
    }

    /// The saved value is written first, then the greeting is spoken through the
    /// external server, exactly as Android's voice-preview sheet does.
    private func previewGreeting() {
        Task {
            isTesting = true
            errorMessage = nil
            let spoke = await preview.speak(
                text: SpeechSynthesizerService.greeting(for: language),
                voiceName: voiceName.isEmpty ? nil : voiceName,
                endpoint: app.externalTtsAPI,
                token: app.externalTtsKey
            ) { error in
                errorMessage = error is ExternalTtsNotConfiguredException
                    ? String(localized: "Voice is not configured")
                    : String(localized: "Server error")
            }
            isTesting = false
            if spoke {
                hasSpoken = true
            } else if errorMessage == nil {
                // Nothing to say after cleaning — the greeting is never empty, so this
                // guards a hypothetical; the sheet just closes.
                dismiss()
            }
        }
    }

    // Preview-audio session handling mirrors the voice picker: `.playback` (this only
    // plays), and skipped while a call owns the session. Detached, because the `async`
    // overload of `setActive` alone does not avoid AVAudioSession's main-thread
    // diagnostic — see ``VoicePickerSheet.beginPreviewAudio()``.
    private func beginPreviewAudio() async {
        guard !CallAudioSession.shared.isActive else { return }

        await Task.detached(priority: .userInitiated) {
            let session = AVAudioSession.sharedInstance()
            try? session.setCategory(.playback, mode: .default, options: [.duckOthers])
            try? session.setActive(true)
        }.value
    }

    private func endPreviewAudio() async {
        guard !CallAudioSession.shared.isActive else { return }

        await Task.detached(priority: .userInitiated) {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }.value
    }
}

#Preview {
    NavigationStack { SettingsView() }.environmentObject(AppViewModel())
        .environmentObject(AppViewModel.shared.litert)
}
