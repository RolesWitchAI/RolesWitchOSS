//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import SharedLogic
import SwiftUI

/// The warm-up contract for on-device inference, ported piece for piece from Android's
/// `LitertWarmUp.kt`: keep the resident engine in step with a screen's settings, cover the
/// screen while it loads, and report a failure without trapping the user behind it.

// MARK: - Engine sync

private struct LiteRtEngineSyncModifier: ViewModifier {
    let settings: EffectiveSettings?
    /// True to load the model as soon as the screen appears; false to only *unload* one that is
    /// no longer wanted. The call screen is eager because its first turn arrives as speech, with
    /// no send button to hang the wait on; the chat screen is not, because opening a
    /// conversation must not pull gigabytes off disk.
    let eager: Bool

    @EnvironmentObject private var app: AppViewModel
    @EnvironmentObject private var litert: LiteRtService

    /// Whether *this* modifier instance is currently holding the model resident. SwiftUI can send
    /// `onAppear` more than once for the same view, and an unbalanced retain would leak a hold —
    /// which would quietly disable the memory-pressure valve for the rest of the session.
    @State private var holding = false

    func body(content: Content) -> some View {
        content
            // Re-fires on every input the resident engine depends on, so switching model or
            // engine in Settings is reflected without a screen change. Not on the samplers:
            // those are read per turn and need no reload.
            .task(id: syncKey) {
                // Never load on the way into the background — the mapping needs the app
                // running, and a load started there is a load that fails. `.inactive` is not
                // checked: that state still runs, and treating it as "leaving" made the engine
                // churn every time a banner appeared.
                guard !app.isSceneBackgrounded else { return }
                // `nil` means "this screen does not know its settings yet". Acting on it is what
                // unloaded the model when moving between the chat and the call: the chat resolves
                // a *placeholder* thread until its row arrives from Room, and a placeholder has
                // `useCustomSettings = false`, so a thread that overrides the model looked — for
                // one frame — like a thread wanting the global one.
                guard let settings else { return }
                if eager {
                    await litert.syncTo(settings)
                } else {
                    litert.releaseIfNotFor(settings)
                }
            }
            // An eager screen is a call: hold the model for as long as it is up, so a memory
            // warning arriving between turns cannot unload it mid-conversation.
            .onAppear {
                guard eager, !holding else { return }
                holding = true
                litert.retain()
            }
            .onDisappear {
                guard holding else { return }
                holding = false
                litert.relinquish()
            }
    }

    /// Everything the resident engine is configured from. Only those appear here: screen
    /// appearance and disappearance are deliberately not inputs, and the samplers never are.
    /// The only unloads are a changed model/accelerator/token-budget/vision, a changed engine,
    /// and suspension.
    private var syncKey: String {
        guard let settings else { return "none" }
        return [
            app.isSceneBackgrounded ? "b" : "f",
            String(settings.useOnDevice),
            settings.litertModel,
            settings.litertAccelerator,
            settings.litertMaxTokens,
            String(settings.visionAvailable),
        ].joined(separator: "|")
    }
}

extension View {
    /// Keeps the resident on-device engine in step with [settings]. See
    /// `LiteRtEngineSyncModifier` for why `eager` differs between the chat and the call.
    func litertEngineSync(settings: EffectiveSettings?, eager: Bool) -> some View {
        modifier(LiteRtEngineSyncModifier(settings: settings, eager: eager))
    }
}

// MARK: - Overlay

/// Blocking cover shown while the model loads.
///
/// Blocking on purpose: a half-loaded engine cannot answer, and letting the user type into a
/// composer that will not send is worse than a wait with a name on it. It swallows taps.
struct LiteRtWarmUpOverlay: View {
    @EnvironmentObject private var litert: LiteRtService

    var body: some View {
        if case .loading(let name) = litert.initState {
            ZStack {
                Color.black.opacity(0.35).ignoresSafeArea()
                VStack(spacing: 12) {
                    ProgressView()
                    Text("Loading \(name)…")
                        .font(.callout)
                    Text("The first load of a model takes a moment.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding(24)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
                .padding(40)
            }
            .contentShape(Rectangle())
            .onTapGesture {}
            .transition(.opacity)
        }
    }
}

// MARK: - Failure banner

/// Non-blocking banner for a load failure, with Retry.
///
/// Not a cover: a failure is durable — it stays until the selection changes or the user retries
/// — and a modal one would lock the user out of the very settings that fix it.
struct LiteRtWarmUpFailureBanner: View {
    let settings: EffectiveSettings?

    @EnvironmentObject private var litert: LiteRtService

    var body: some View {
        if let message = litert.initState.failure {
            VStack(alignment: .leading, spacing: 8) {
                Text("Could not load the on-device model")
                    .font(.callout.weight(.semibold))
                Text(verbatim: message)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                HStack {
                    Spacer()
                    Button("Retry") {
                        litert.clearFailure()
                        guard let settings else { return }
                        Task { await litert.syncTo(settings) }
                    }
                    // Nothing to retry *to* until the thread row is in. The button stays so
                    // the banner does not jump, but it says so rather than guessing an engine.
                    .disabled(settings == nil)
                    .font(.caption)
                }
            }
            .padding(12)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
            .padding(12)
            .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }
}