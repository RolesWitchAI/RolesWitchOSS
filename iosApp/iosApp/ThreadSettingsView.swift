//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import SwiftUI
import SharedLogic

/// Per-thread settings override editor. Mirrors Android `ModelSettingsScreen`:
/// - "Use custom settings" master toggle (snapshots current globals on enable)
/// - Intelligence + misc + audio fields, edited against the resolved effective
///   values, each persisted immediately via `applyThreadOverrides`, and hidden
///   entirely while the master toggle is off
/// - A Tools section that stays outside that toggle, shown for a cloud thread (the on-device
///   engine has no tool support, so an on-device thread does not get it)
///
/// The voice call resolves every per-turn value from `EffectiveSettings`, so an
/// override set here (language, voice, speed, offline, interruptions) takes
/// effect on the next call to this thread.
struct ThreadSettingsView: View {
    let thread: MessageThread

    @EnvironmentObject private var app: AppViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var overridesEnabled: Bool = false

    // Effective values (resolved on load). Mutated in place and re-applied on edit.
    @State private var apiUrl: String = ""
    @State private var apiKey: String = ""
    @State private var model: String = ""
    @State private var enableVision: Bool = false
    @State private var messageLimit: Int = 50
    @State private var embedTime: Bool = false
    @State private var keepLastAttachment: Bool = false
    /// Streams replies as they're generated; off means the reply appears whole
    /// when the turn completes and a new message cancels the in-flight turn.
    @State private var streamingMode: Bool = true

    // Advanced (loaded/saved via the Advanced sub-screen binding).
    @State private var temperature: String = ""
    @State private var topP: String = ""
    @State private var n: String = ""
    @State private var maxCompletionTokens: String = ""
    @State private var presencePenalty: String = ""
    @State private var frequencyPenalty: String = ""

    /// `reasoning_effort`; "" is Auto, which omits the field. Its own row, not Advanced.
    @State private var reasoningEffort: String = ""

    // Audio (drive the voice call for this thread).
    @State private var language: String = "en"
    @State private var voice: String = ""
    @State private var useVosk: Bool = false
    @State private var allowInterruptions: Bool = false
    /// The speech engine, resolved per thread like every other audio field. On iOS
    /// "System" and "Auto" (neither flag) both speak the native voice, so the picker
    /// only offers System vs External (OpenAI).
    @State private var useSystemTts: Bool = false
    @State private var useExternalTts: Bool = false
    @State private var externalTtsVoice: String = ""
    /// The thread's own external speech server (URL + token) — overridable per chat like
    /// the voice and engine, and decoupled from the inference server. Empty inherits the
    /// global speech server.
    @State private var externalTtsAPI: String = ""
    @State private var externalTtsKey: String = ""

    // On-device inference for this thread. `useOnDevice` writes `customUseLiteRt`; the rest are
    // LiteRT's, so a thread's engine choice and its tuning travel together.
    @State private var useOnDevice: Bool = false
    @State private var litertModel: String = ""
    @State private var litertTopK: String = ""
    @State private var litertTopP: String = ""
    @State private var litertTemperature: String = ""
    @State private var litertMaxTokens: String = ""
    @State private var litertAccelerator: String = "cpu"
    @State private var litertEnableThinking: Bool = false

    // MARK: Tools — the registry, and which of it this chat may call.
    @State private var tools: [McpTool] = []
    @State private var selectedToolIds: Set<String> = []

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

    var body: some View {
        Form {
            Section {
                // One fixed subtitle and no section footer, matching Android's
                // `ModelSettingsScreen`, where the master switch says this once. The
                // subtitle used to restate the toggle's own state, with a footer under it
                // spelling out which sections the override covers.
                Toggle(isOn: Binding(get: { overridesEnabled }, set: { toggleOverrides($0) })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Use custom settings").font(.body)
                        Text("Override the app-wide settings for this chat")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
            }

            if overridesEnabled {
                intelligenceSection
                speechInputSection
                speechOutputSection
                miscSection
            }

            // Outside the override block: which endpoints a conversation may call is a
            // property of that conversation, not of whether it overrides the model and
            // audio settings. Still last, matching the global Settings screen.
            //
            // Gated on the *effective* engine, not on `overridesEnabled`: a thread that
            // inherits the global on-device engine cannot call tools either. LiteRT ignores
            // `toolSpecs` entirely, so the switches would promise something no turn delivers.
            if !useOnDevice {
                toolsSection
            }
        }
        .navigationTitle("Model settings")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) { Button(role: .confirm) { dismiss() } }
        }
        .sheet(isPresented: $showEngineSheet) {
            EnginePickerSheet(current: useOnDevice) { onDevice in
                // Engine switch: the "Messages to process" ranges differ (no "Unlimited"
                // on device), so this thread's own limit settles on the shared 50 default.
                if onDevice != useOnDevice { messageLimit = 50 }
                useOnDevice = onDevice
                apply()
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
            ServerSheet(initialURL: apiUrl, initialToken: apiKey) { url, token in
                // Mirror Android: a URL change invalidates the selected model. Both
                // fields land in one `apply()`, which writes the whole snapshot.
                if url != apiUrl { model = "" }
                apiUrl = url
                apiKey = token
                apply()
                showServerSheet = false
            }
        }
        .sheet(isPresented: $showModelSheet) {
            RemoteModelPickerSheet(endpoint: apiUrl, token: apiKey, currentModel: model) { newValue in
                model = newValue
                apply()
                showModelSheet = false
            }
        }
        .sheet(isPresented: $showMessageLimitSheet) {
            MessageLimitSheet(current: messageLimit, isOnDevice: useOnDevice) { newValue in
                messageLimit = newValue
                apply()
                showMessageLimitSheet = false
            }
            .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $showReasoningSheet) {
            ReasoningPickerSheet(current: reasoningEffort) { newValue in
                reasoningEffort = newValue
                apply()
                showReasoningSheet = false
            }
            .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $showLanguageSheet) {
            LanguagePickerSheet(current: language) { code in
                // Mirror global settings: changing language invalidates the
                // saved voice (voice names are language-specific).
                language = code
                voice = ""
                apply()
                showLanguageSheet = false
            }
            .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $showVoiceSheet) {
            VoicePickerSheet(language: language, current: voice) { name in
                voice = name
                apply()
                showVoiceSheet = false
            }
            .presentationDetents([.medium, .large])
        }
        // The engine picker mirrors Android's three-way Speech engine sheet, minus the
        // Auto option: on iOS both "System" and "Auto" (neither flag) speak the native
        // voice, so the only real choice is System vs External (OpenAI). Both rows are
        // always offered — recognition and synthesis are independent, so this thread's
        // "Process on device" does not gate the engine.
        .sheet(isPresented: $showSpeechEngineSheet) {
            TtsEnginePickerSheet(
                isExternalSelected: useExternalTts
            ) { external in
                // Any engine change clears the bound voice: a stored name belongs to the
                // engine that reported it (mirrors Android's `saveTtsMode`). The external
                // voice id is a server-side name and survives engine switches.
                voice = ""
                useSystemTts = !external
                useExternalTts = external
                apply()
                showSpeechEngineSheet = false
            }
            .presentationDetents([.medium, .large])
        }
        // The external Server row edits this thread's own external speech server — the
        // `customExternalTtsAPI`/`customExternalTtsKey` overrides, distinct from the
        // inference server (a chat may speak through a different host than it chats), and
        // never touching the global speech or inference settings.
        .sheet(isPresented: $showExternalServerSheet) {
            ServerSheet(initialURL: externalTtsAPI, initialToken: externalTtsKey) { url, token in
                externalTtsAPI = url
                externalTtsKey = token
                apply()
                showExternalServerSheet = false
            }
        }
        // The external voice id is free-form — any endpoint may offer names the picker
        // has never heard of, so a text field beats a static list. Empty is meaningful
        // ("let the server pick"). The sheet saves the value, then previews the
        // per-language greeting through the same server.
        .sheet(isPresented: $showExternalVoiceSheet) {
            ExternalVoiceSheet(current: externalTtsVoice, language: language) { name in
                // Persist as the thread's override — the sheet holds itself up to preview
                // the greeting, then dismisses itself.
                externalTtsVoice = name
                apply()
            }
            .presentationDetents([.fraction(0.35), .medium])
        }
        .task { await load() }
    }

    // MARK: Intelligence

    @ViewBuilder
    private var intelligenceSection: some View {
        Section {
            row(label: "Engine",
                value: useOnDevice ? String(localized: "On device") : String(localized: "Remote")) {
                showEngineSheet = true
            }

            if useOnDevice {
                // The token above the model row, as on Android and the global screen: a gated
                // model cannot be downloaded without one. It edits the *global* credential —
                // a download is not a per-thread setting — which is also why it is reachable
                // here: with the global engine on remote, the global screen hides its own row.
                row(label: "HuggingFace token",
                    value: app.huggingFaceToken.isEmpty
                        ? String(localized: "Optional")
                        : "••••••••") {
                    showHuggingFaceTokenEditor = true
                }

                // A push, as on the global screen: the picker writes the selection on tap
                // (no Save), so it is a screen the user backs out of.
                NavigationLink {
                    LiteRtModelView(selectedModelId: litertModel) { picked in
                        guard let picked else {
                            // The selected model's weights were deleted on the picker:
                            // clear the id and leave the samplers as they are — Android's
                            // `withLitertModelSelected("")` does exactly this.
                            litertModel = ""
                            apply()
                            return
                        }
                        litertModel = picked.id
                        // The samplers belong to the checkpoint, so a new model replaces them rather
                        // than inheriting the previous one's tuning — the same rule
                        // `SettingsManager.selectLitertModel` follows for the global settings.
                        let defaults = picked.defaultSettings
                        litertTopK = defaults.topK
                        litertTopP = defaults.topP
                        litertTemperature = defaults.temperature
                        litertMaxTokens = defaults.maxTokens
                        litertAccelerator = picked.defaultAccelerator
                        apply()
                    }
                } label: {
                    HStack {
                        Text("Model")
                        Spacer()
                        Text(verbatim: onDeviceModelLabel)
                            .foregroundStyle(litertModel.isEmpty ? Color.red : Color.secondary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                }
            } else {
                // One row, as on the global screen — see `ServerSheet`.
                row(label: "Server",
                    value: apiUrl.isEmpty ? String(localized: "Not configured") : apiUrl,
                    isError: apiUrl.isEmpty) { showServerSheet = true }

                // Gated on the server, as on the global screen.
                row(label: "Model",
                    value: model.isEmpty ? String(localized: "Not configured") : model,
                    isError: model.isEmpty && !apiUrl.isEmpty,
                    enabled: !apiUrl.isEmpty) { showModelSheet = true }
            }

            // Per checkpoint, as on the global screen and Android: LiteRT learns its vision
            // capability by loading, so a text-only checkpoint needs the toggle while a
            // vision one hides it. The stored `customEnableVision` is left as it is rather
            // than cleared — switching this thread back to the cloud engine should find the
            // choice it had.
            if let selected = LiteRtRegistry.model(withId: litertModel), !selected.supportsImage, useOnDevice {
                Toggle(isOn: $enableVision) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Enable Vision").font(.body)
                        Text("Make sure the model has vision capability").font(.caption).foregroundStyle(.secondary)
                    }
                }
                .onChange(of: enableVision) { _, _ in apply() }
            } else if !useOnDevice {
                Toggle(isOn: $enableVision) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Enable Vision").font(.body)
                        Text("Make sure the model has vision capability").font(.caption).foregroundStyle(.secondary)
                    }
                }
                .onChange(of: enableVision) { _, _ in apply() }
            }

            row(label: "Messages to process",
                value: messageLimit >= 100_000 ? String(localized: "Unlimited") : "\(messageLimit)") {
                showMessageLimitSheet = true
            }

            if useOnDevice {
                if LiteRtRegistry.model(withId: litertModel)?.supportsThinking == true {
                    Toggle(isOn: $litertEnableThinking) {
                        Text("Thinking")
                    }
                    .onChange(of: litertEnableThinking) { _, _ in apply() }
                }
            } else {
                row(label: "Reasoning", value: reasoningLabel(reasoningEffort)) {
                    showReasoningSheet = true
                }
            }

            NavigationLink {
                // Which samplers "Advanced" means is a property of the engine: the remote one
                // has `n` and the penalties, LiteRT has topK and neither.
                if useOnDevice {
                    ThreadLiteRtAdvancedView(
                        maxTokens: $litertMaxTokens, topK: $litertTopK,
                        topP: $litertTopP, temperature: $litertTemperature,
                        accelerator: $litertAccelerator,
                        modelId: litertModel,
                        onChanged: { apply() }
                    )
                } else {
                    ThreadAdvancedView(
                        temperature: $temperature, topP: $topP, n: $n,
                        maxCompletionTokens: $maxCompletionTokens,
                        presencePenalty: $presencePenalty, frequencyPenalty: $frequencyPenalty,
                        onChanged: { apply() }
                    )
                }
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
    /// how the send/call guards treat it.
    private var onDeviceModelLabel: String {
        guard let model = LiteRtRegistry.model(withId: litertModel),
              model.isDownloaded else {
            return String(localized: "Not configured")
        }
        return model.displayName
    }

    // MARK: Speech Input

    /// Recognition, with the language that scopes it — the per-thread mirror of the
    /// global screen's split (and of Android's Speech input section).
    @ViewBuilder
    private var speechInputSection: some View {
        Section {
            row(label: "Language", value: SettingsInterop.languageName(language)) {
                showLanguageSheet = true
            }

            Toggle(isOn: $useVosk) {
                Text("Process on device")
            }
            .onChange(of: useVosk) { _, _ in
                apply()
            }
        } header: { Text("Speech input") }
    }

    // MARK: Speech Output

    /// Synthesis: the engine and the rows it brings with it.
    @ViewBuilder
    private var speechOutputSection: some View {
        Section {
            row(label: "Speech engine",
                value: useExternalTts ? String(localized: "External (OpenAI)") : String(localized: "System")) {
                showSpeechEngineSheet = true
            }

            if useExternalTts {
                // External speech hides the local Voice row — it configures the engine
                // that will not be used — and shows the OpenAI pair instead.
                // The Server row edits this thread's *own* external speech server (URL +
                // token), overridable per chat and decoupled from the inference server.
                row(label: "Server",
                    value: externalTtsAPI.isEmpty ? String(localized: "Not configured") : externalTtsAPI,
                    isError: externalTtsAPI.isEmpty) { showExternalServerSheet = true }

                row(label: "Voice",
                    value: externalTtsVoice.isEmpty ? String(localized: "Default") : externalTtsVoice) {
                    showExternalVoiceSheet = true
                }
            } else {
                row(label: "Voice", value: voice.isEmpty ? String(localized: "Default") : voice) {
                    showVoiceSheet = true
                }
            }
        } header: { Text("Speech output") }
    }

    // MARK: Tools

    /// Every registered endpoint, one switch each — this chat's own list is the whole
    /// answer, so there is nothing to inherit and no sub-screen to push. A chat starts
    /// with everything off.
    ///
    /// Only reached for a thread whose effective engine is the cloud one; see the call site.
    ///
    /// Deliberately does *not* go through `apply()`: that writes the whole override
    /// snapshot and early-returns while overrides are off, which would swallow every
    /// edit made here. Each toggle persists on its own via `applyThreadToolSelection`.
    @ViewBuilder
    private var toolsSection: some View {
        Section {
            if tools.isEmpty {
                Text("No tools yet. Add them in Settings \u{2192} Tools, then turn them on here.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(tools, id: \.id) { tool in
                    Toggle(isOn: Binding(
                        get: { selectedToolIds.contains(tool.id) },
                        set: { on in setToolSelected(tool, on) }
                    )) {
                        VStack(alignment: .leading, spacing: 2) {
                            // User-entered registry entries.
                            Text(verbatim: tool.name)
                            Text(verbatim: tool.url)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                    }
                }
            }
        } header: { Text("Tools") }
    }

    /// Writes the new selection straight through, then mirrors it locally so the switch
    /// does not snap back while the write lands.
    private func setToolSelected(_ tool: McpTool, _ on: Bool) {
        var ids = selectedToolIds
        if on { ids.insert(tool.id) } else { ids.remove(tool.id) }
        selectedToolIds = ids
        Task { await app.applyThreadToolSelection(thread, toolIds: Array(ids)) }
    }

    // MARK: Misc

    @ViewBuilder
    private var miscSection: some View {
        Section("Miscellaneous") {
            Toggle(isOn: $embedTime) { Text("Share date and time") }
                .onChange(of: embedTime) { _, _ in apply() }
            Toggle(isOn: $keepLastAttachment) { Text("Keep last attachment") }
                .onChange(of: keepLastAttachment) { _, _ in apply() }
            Toggle(isOn: $streamingMode) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Streaming mode").font(.body)
                    Text("Show replies as they're generated")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .onChange(of: streamingMode) { _, _ in apply() }
            Toggle(isOn: $allowInterruptions) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Allow interruptions").font(.body)
                    Text("Requires headphones").font(.caption).foregroundStyle(.secondary)
                }
            }
            .onChange(of: allowInterruptions) { _, _ in apply() }
        }
    }

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

    // MARK: Load / persist

    /// Seeds every field from the resolved effective settings so the form opens
    /// showing the values that are actually in effect for this thread.
    private func load() async {
        let sm = app.container.settingsManager
        let eff = EffectiveSettings.companion.resolve(thread: thread, global: sm)
        let loadedTools = await app.loadTools()
        await MainActor.run {
            tools = loadedTools
            // Resolved through the same path LLMService uses, so what is ticked is
            // exactly what the thread will send.
            selectedToolIds = Set(eff.enabledToolIds)
            overridesEnabled = thread.useCustomSettings
            apiUrl = eff.openaiAPI
            apiKey = eff.openaiKey
            model = eff.selectedModel
            enableVision = eff.enableVision
            messageLimit = Int(eff.messageLimit)
            embedTime = eff.embedTime
            keepLastAttachment = eff.keepLastAttachment
            streamingMode = eff.streamingMode
            temperature = eff.temperature
            topP = eff.topP
            n = eff.n
            maxCompletionTokens = eff.maxCompletionTokens
            presencePenalty = eff.presencePenalty
            frequencyPenalty = eff.frequencyPenalty
            reasoningEffort = eff.reasoningEffort
            language = eff.language
            voice = eff.voice
            useVosk = eff.useVosk
            allowInterruptions = eff.allowInterruptions
            useSystemTts = eff.useSystemTts
            useExternalTts = eff.useExternalTts
            externalTtsVoice = eff.externalTtsVoice
            externalTtsAPI = eff.externalTtsAPI
            externalTtsKey = eff.externalTtsKey
            useOnDevice = eff.useOnDevice
            litertModel = eff.litertModel
            litertTopK = eff.litertTopK
            litertTopP = eff.litertTopP
            litertTemperature = eff.litertTemperature
            litertMaxTokens = eff.litertMaxTokens
            litertAccelerator = eff.litertAccelerator
            litertEnableThinking = eff.litertEnableThinking
        }
    }

    /// Writes the current local state as the thread's overrides. Immediate-save
    /// semantics match Android's per-field `setThread { it.copy(...) }`.
    private func apply() {
        guard overridesEnabled else { return }
        Task { await app.applyThreadOverrides(thread, overrides: currentSnapshot()) }
    }

    /// Toggling on snapshots the current globals (the local state was seeded from
    /// `EffectiveSettings.resolve`, which equals global while overrides are off);
    /// toggling off delegates to `disableThreadOverrides` (keeps the stale columns
    /// so re-enabling restores them).
    private func toggleOverrides(_ on: Bool) {
        overridesEnabled = on
        Task {
            if on {
                await app.applyThreadOverrides(thread, overrides: currentSnapshot())
            } else {
                await app.disableThreadOverrides(thread)
            }
        }
    }

    private func currentSnapshot() -> ThreadOverrides {
        ThreadOverrides(
            apiUrl: apiUrl, apiKey: apiKey, model: model, enableVision: enableVision,
            messageLimit: messageLimit, temperature: temperature, topP: topP, n: n,
            maxCompletionTokens: maxCompletionTokens, presencePenalty: presencePenalty,
            frequencyPenalty: frequencyPenalty, reasoningEffort: reasoningEffort,
            embedTime: embedTime,
            keepLastAttachment: keepLastAttachment,
            streamingMode: streamingMode,
            language: language, voice: voice,
            useVosk: useVosk, allowInterruptions: allowInterruptions,
            useSystemTts: useSystemTts, useExternalTts: useExternalTts,
            externalTtsVoice: externalTtsVoice,
            externalTtsAPI: externalTtsAPI, externalTtsKey: externalTtsKey,
            useOnDevice: useOnDevice, litertModel: litertModel,
            litertTopK: litertTopK, litertTopP: litertTopP,
            litertTemperature: litertTemperature, litertMaxTokens: litertMaxTokens,
            litertAccelerator: litertAccelerator, litertEnableThinking: litertEnableThinking
        )
    }
}

/// Snapshot of the in-scope per-thread overrides, carried from Swift local
/// state into `AppViewModel.applyThreadOverrides`. Tools are not in it — they
/// are not part of the custom-settings block and persist on their own.
struct ThreadOverrides {
    let apiUrl: String
    let apiKey: String
    let model: String
    let enableVision: Bool
    let messageLimit: Int
    let temperature: String
    let topP: String
    let n: String
    let maxCompletionTokens: String
    let presencePenalty: String
    let frequencyPenalty: String
    let reasoningEffort: String
    let embedTime: Bool
    let keepLastAttachment: Bool
    let streamingMode: Bool
    let language: String
    let voice: String
    let useVosk: Bool
    let allowInterruptions: Bool
    /// The thread's speech engine: System (`useSystemTts`) or External/OpenAI
    /// (`useExternalTts` + `externalTtsVoice`). On iOS "Auto" writes neither flag,
    /// which is what a drop from External on "Process on device" produces.
    let useSystemTts: Bool
    let useExternalTts: Bool
    let externalTtsVoice: String
    /// The thread's own external speech server, overridable like the voice and engine.
    let externalTtsAPI: String
    let externalTtsKey: String
    /// On-device vs remote for this thread. Engine-agnostic — it writes `customUseLiteRt`,
    /// which both platforms read.
    let useOnDevice: Bool
    let litertModel: String
    let litertTopK: String
    let litertTopP: String
    let litertTemperature: String
    let litertMaxTokens: String
    let litertAccelerator: String
    let litertEnableThinking: Bool
}

// MARK: - Per-thread advanced

/// Generation-parameters sub-screen bound to `ThreadSettingsView`'s state.
/// Validation mirrors `AdvancedView`; each successful Save calls `onChanged`.
/// On-device sampler sub-screen bound to `ThreadSettingsView`'s state — the per-thread twin of
/// ``LiteRtAdvancedView``, and the counterpart of Android's `ThreadLitertAdvancedBinding`.
private struct ThreadLiteRtAdvancedView: View {
    @Binding var maxTokens: String
    @Binding var topK: String
    @Binding var topP: String
    @Binding var temperature: String
    @Binding var accelerator: String
    /// Only to resolve the checkpoint's defaults for Reset.
    let modelId: String
    let onChanged: () -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var maxTokensError = false
    @State private var topKError = false
    @State private var topPError = false
    @State private var temperatureError = false

    var body: some View {
        Form {
            Section {
                Toggle(isOn: Binding(
                    get: { accelerator == "gpu" },
                    set: { accelerator = $0 ? "gpu" : "cpu" }
                )) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Use GPU for inference").font(.body)
                        Text("Faster where it works, but some phones cannot run the model on the GPU. Turn this off if it fails to load.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            AdvancedField(
                label: "Max Tokens",
                hint: "Longest reply the model may produce. Must be a positive integer.",
                value: $maxTokens, isError: $maxTokensError, keyboard: .numberPad
            )
            AdvancedField(
                label: "Top K",
                hint: "Sample from the K likeliest tokens. Must be a positive integer.",
                value: $topK, isError: $topKError, keyboard: .numberPad
            )
            AdvancedField(
                label: "Top P",
                hint: "Nucleus sampling. Range: 0.0 – 1.0.",
                value: $topP, isError: $topPError, keyboard: .default
            )
            AdvancedField(
                label: "Temperature",
                hint: "Controls randomness. 0.0 is deterministic.",
                value: $temperature, isError: $temperatureError, keyboard: .default
            )
            Section {
                Button("Reset") { resetToModelDefaults() }
            } footer: {
                Text("Restores the selected model's own recommended values.")
            }
        }
        .navigationTitle("Advanced")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) { Button(role: .confirm) { save() } }
        }
    }

    private func resetToModelDefaults() {
        maxTokensError = false; topKError = false; topPError = false; temperatureError = false
        guard let defaults = LiteRtRegistry.model(withId: modelId)?.defaultSettings else {
            maxTokens = ""; topK = ""; topP = ""; temperature = ""
            accelerator = "cpu"
            return
        }
        maxTokens = defaults.maxTokens
        topK = defaults.topK
        topP = defaults.topP
        temperature = defaults.temperature
        accelerator = LiteRtRegistry.model(withId: modelId)?.defaultAccelerator ?? "cpu"
    }

    private func save() {
        let mt = maxTokens.trimmingCharacters(in: .whitespaces)
        let tk = topK.trimmingCharacters(in: .whitespaces)
        let tp = topP.trimmingCharacters(in: .whitespaces)
        let t = temperature.trimmingCharacters(in: .whitespaces)

        maxTokensError = mt.isEmpty ? false : (Int(mt) ?? 0) <= 0
        topKError = tk.isEmpty ? false : (Int(tk) ?? 0) <= 0
        topPError = !tp.isEmpty && Double(tp) == nil
        temperatureError = !t.isEmpty && Double(t) == nil

        guard !(maxTokensError || topKError || topPError || temperatureError) else { return }

        maxTokens = mt; topK = tk; topP = tp; temperature = t
        onChanged()
        dismiss()
    }
}

private struct ThreadAdvancedView: View {
    @Binding var temperature: String
    @Binding var topP: String
    @Binding var n: String
    @Binding var maxCompletionTokens: String
    @Binding var presencePenalty: String
    @Binding var frequencyPenalty: String
    let onChanged: () -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var tempError = false
    @State private var topPError = false
    @State private var nError = false
    @State private var mctError = false
    @State private var ppError = false
    @State private var fpError = false

    var body: some View {
        Form {
            AdvancedField(label: "Temperature", hint: "Controls randomness. Range: 0.0 – 2.0.", value: $temperature, isError: $tempError, keyboard: .default)
            AdvancedField(label: "Top P", hint: "Nucleus sampling. Range: 0.0 – 1.0.", value: $topP, isError: $topPError, keyboard: .default)
            AdvancedField(label: "N", hint: "Number of choices. Must be a positive integer.", value: $n, isError: $nError, keyboard: .numberPad)
            AdvancedField(label: "Max Completion Tokens", hint: "Maximum tokens in response. Must be a positive integer.", value: $maxCompletionTokens, isError: $mctError, keyboard: .numberPad)
            AdvancedField(label: "Presence Penalty", hint: "Penalizes tokens that already appeared. Range: -2.0 – 2.0.", value: $presencePenalty, isError: $ppError, keyboard: .default)
            AdvancedField(label: "Frequency Penalty", hint: "Penalizes frequent tokens. Range: -2.0 – 2.0.", value: $frequencyPenalty, isError: $fpError, keyboard: .default)
            Section {
                Button("Reset") {
                    // Clears the fields only — nothing is persisted until Save, as
                    // on Android and in the global `AdvancedView`. Backing out after
                    // a Reset therefore leaves the stored values untouched.
                    temperature = ""; topP = ""; n = ""; maxCompletionTokens = ""
                    presencePenalty = ""; frequencyPenalty = ""
                    tempError = false; topPError = false; nError = false
                    mctError = false; ppError = false; fpError = false
                }
            }
        }
        .navigationTitle("Advanced")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) { Button(role: .confirm) { save() } }
        }
    }

    private func save() {
        let t = temperature.trimmingCharacters(in: .whitespaces)
        let tp = topP.trimmingCharacters(in: .whitespaces)
        let nVal = n.trimmingCharacters(in: .whitespaces)
        let mct = maxCompletionTokens.trimmingCharacters(in: .whitespaces)
        let pp = presencePenalty.trimmingCharacters(in: .whitespaces)
        let fp = frequencyPenalty.trimmingCharacters(in: .whitespaces)

        tempError = !t.isEmpty && Double(t) == nil
        topPError = !tp.isEmpty && Double(tp) == nil
        nError = nVal.isEmpty ? false : (Int(nVal) == nil || (Int(nVal) ?? 0) <= 0)
        mctError = mct.isEmpty ? false : (Int(mct) == nil || (Int(mct) ?? 0) <= 0)
        ppError = !pp.isEmpty && Double(pp) == nil
        fpError = !fp.isEmpty && Double(fp) == nil

        guard !(tempError || topPError || nError || mctError || ppError || fpError) else { return }
        onChanged()
        // Matches Android's `onBack()` after a successful save — see `AdvancedView`.
        dismiss()
    }
}

