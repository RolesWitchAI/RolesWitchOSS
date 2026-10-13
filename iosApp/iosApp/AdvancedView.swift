//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import SwiftUI
import SharedLogic

/// Generation-parameters sub-screen. All six fields are optional strings.
/// Validation only fires for non-empty values: Temperature/TopP/Presence/Frequency
/// must parse as Double; N/MaxCompletionTokens must parse as positive Int.
///
/// On-device threads get a different set entirely — see ``LiteRtAdvancedView``. The engines share
/// no sampler: the remote one sends `n` and the penalties, LiteRT has topK and neither penalty.
struct AdvancedView: View {
    @EnvironmentObject private var app: AppViewModel

    var body: some View {
        // Branching here rather than at the call sites: Settings and Thread settings both
        // navigate to "Advanced", and which fields that means is a property of the engine.
        if app.useOnDevice {
            LiteRtAdvancedView()
        } else {
            RemoteAdvancedView()
        }
    }
}

private struct RemoteAdvancedView: View {
    @EnvironmentObject private var app: AppViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var temperature: String = ""
    @State private var topP: String = ""
    @State private var n: String = ""
    @State private var maxCompletionTokens: String = ""
    @State private var presencePenalty: String = ""
    @State private var frequencyPenalty: String = ""

    @State private var tempError = false
    @State private var topPError = false
    @State private var nError = false
    @State private var mctError = false
    @State private var ppError = false
    @State private var fpError = false

    var body: some View {
        Form {
            AdvancedField(
                label: "Temperature",
                hint: "Controls randomness. Range: 0.0 – 2.0.",
                value: $temperature,
                isError: $tempError,
                keyboard: .default
            )
            AdvancedField(
                label: "Top P",
                hint: "Nucleus sampling. Range: 0.0 – 1.0.",
                value: $topP,
                isError: $topPError,
                keyboard: .default
            )
            AdvancedField(
                label: "N",
                hint: "Number of choices. Must be a positive integer.",
                value: $n,
                isError: $nError,
                keyboard: .numberPad
            )
            AdvancedField(
                label: "Max Completion Tokens",
                hint: "Maximum tokens in response. Must be a positive integer.",
                value: $maxCompletionTokens,
                isError: $mctError,
                keyboard: .numberPad
            )
            AdvancedField(
                label: "Presence Penalty",
                hint: "Penalizes tokens that already appeared. Range: -2.0 – 2.0.",
                value: $presencePenalty,
                isError: $ppError,
                keyboard: .default
            )
            AdvancedField(
                label: "Frequency Penalty",
                hint: "Penalizes frequent tokens. Range: -2.0 – 2.0.",
                value: $frequencyPenalty,
                isError: $fpError,
                keyboard: .default
            )

            Section {
                Button("Reset") {
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
            ToolbarItem(placement: .confirmationAction) {
                Button(role: .confirm) { save() }
            }
        }
        .onAppear {
            temperature = app.temperature
            topP = app.topP
            n = app.n
            maxCompletionTokens = app.maxCompletionTokens
            presencePenalty = app.presencePenalty
            frequencyPenalty = app.frequencyPenalty
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

        if tempError || topPError || nError || mctError || ppError || fpError { return }

        app.setTemperature(t)
        app.setTopP(tp)
        app.setN(nVal)
        app.setMaxCompletionTokens(mct)
        app.setPresencePenalty(pp)
        app.setFrequencyPenalty(fp)
        // Android's Save calls `onBack()` on success. Without it the screen just
        // sits there and a successful save is indistinguishable from a no-op.
        dismiss()
    }
}

/// Sampler settings for the on-device engine — the twin of Android's `LitertAdvancedScreen`,
/// plus the accelerator Android's screen also offers.
///
/// Blank means "use the checkpoint's own default". They are rarely blank in practice: selecting
/// a model writes its defaults in (`SettingsManager.selectLitertModel`) so this screen shows what
/// is actually in force rather than a set of empty fields.
///
/// No reload here: the sync modifier keys the resident engine by max-tokens and accelerator (in
/// `EngineKey`), so a change to either rebuilds it when the chat next draws.
struct LiteRtAdvancedView: View {
    @EnvironmentObject private var app: AppViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var maxTokens: String = ""
    @State private var topK: String = ""
    @State private var topP: String = ""
    @State private var temperature: String = ""
    @State private var accelerator: String = "cpu"

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
                value: $maxTokens,
                isError: $maxTokensError,
                keyboard: .numberPad
            )
            AdvancedField(
                label: "Top K",
                hint: "Sample from the K likeliest tokens. Must be a positive integer.",
                value: $topK,
                isError: $topKError,
                keyboard: .numberPad
            )
            AdvancedField(
                label: "Top P",
                hint: "Nucleus sampling. Range: 0.0 – 1.0.",
                value: $topP,
                isError: $topPError,
                keyboard: .default
            )
            AdvancedField(
                label: "Temperature",
                hint: "Controls randomness. 0.0 is deterministic.",
                value: $temperature,
                isError: $temperatureError,
                keyboard: .default
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
        .onAppear {
            maxTokens = app.litertMaxTokens
            topK = app.litertTopK
            topP = app.litertTopP
            temperature = app.litertTemperature
            accelerator = app.litertAccelerator
        }
    }

    private func resetToModelDefaults() {
        maxTokensError = false; topKError = false; topPError = false; temperatureError = false
        guard let model = LiteRtRegistry.model(withId: app.litertModel) else {
            maxTokens = ""; topK = ""; topP = ""; temperature = ""
            accelerator = "cpu"
            return
        }
        let defaults = model.defaultSettings
        maxTokens = defaults.maxTokens
        topK = defaults.topK
        topP = defaults.topP
        temperature = defaults.temperature
        accelerator = model.defaultAccelerator
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

        if maxTokensError || topKError || topPError || temperatureError { return }

        app.setLitertMaxTokens(mt)
        app.setLitertTopK(tk)
        app.setLitertTopP(tp)
        app.setLitertTemperature(t)
        app.setLitertAccelerator(accelerator)
        // No reload: `EngineKey` folds max-tokens and accelerator into the resident engine's
        // identity, so the sync modifier rebuilds it (via `releaseIfNotFor`) on next draw.
        dismiss()
    }
}

struct AdvancedField: View {
    /// Both are UI chrome, so they take `LocalizedStringKey` — the literals at the
    /// call sites above are what land in `Localizable.xcstrings`.
    let label: LocalizedStringKey
    let hint: LocalizedStringKey
    @Binding var value: String
    @Binding var isError: Bool
    let keyboard: UIKeyboardType

    var body: some View {
        Section {
            TextField(label, text: $value)
                .keyboardType(keyboard)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                // Clear the error as soon as the user starts correcting it, the way
                // Android does in `onValueChange = { …; fpError = false }`. Without
                // this the field stays red until the next Save attempt.
                .onChange(of: value) { _, _ in if isError { isError = false } }
        } footer: {
            Text(isError ? "Invalid number format" : hint)
                .foregroundStyle(isError ? Color.red : Color.secondary)
                .font(.footnote)
        }
    }
}

#Preview {
    NavigationStack { AdvancedView() }.environmentObject(AppViewModel())
        .environmentObject(AppViewModel.shared.litert)
}
