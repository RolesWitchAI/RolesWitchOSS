//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import Foundation
import AVFoundation
import Combine
import UIKit

/// Single owner of the shared `AVAudioSession` for the duration of a call.
///
/// STT (`SpeechRecognizerService`) and TTS (`SpeechSynthesizerService`) both need the
/// session, and previously each poked at it directly: STT set the category and
/// activated it on every `beginRecognition`, TTS flipped the mode, and nobody watched
/// for interruptions or route changes. A phone call, Siri, or plugging in headphones
/// tore down the audio engine with no recovery — the mic was dead for the rest of the
/// call.
///
/// Invariant carried over from the previous design: the session stays **active across
/// turns** (pausing STT between turns must not silence the TTS reply) and is
/// deactivated only when the whole call ends.
@MainActor
final class CallAudioSession: ObservableObject {

    static let shared = CallAudioSession()

    /// Recomputed on every route change — drives barge-in and the `.voiceChat`
    /// (echo-cancelling) audio path. Android's counterpart is
    /// `isHeadphonesConnectedCheck` in `AssistantScreen.kt`.
    @Published private(set) var isHeadphonesConnected = false

    /// The session was taken away (interruption began, or the engine's configuration
    /// changed underneath us). Consumers should tear down without ending the call.
    let interrupted = PassthroughSubject<Void, Never>()

    /// The session is available again (interruption ended, route changed, app
    /// returned to the foreground). Consumers should re-activate and restart.
    let resumeAdvised = PassthroughSubject<Void, Never>()

    private(set) var isActive = false
    private var useVoiceChatMode = false

    private init() {
        isHeadphonesConnected = Self.headphonesConnected()
        let center = NotificationCenter.default
        center.addObserver(
            self, selector: #selector(handleInterruption(_:)),
            name: AVAudioSession.interruptionNotification, object: nil
        )
        center.addObserver(
            self, selector: #selector(handleRouteChange(_:)),
            name: AVAudioSession.routeChangeNotification, object: nil
        )
        // Backstop: an interruption does not always end with `.shouldResume` (a long
        // phone call, Siri dismissed by leaving the app). Coming back to the
        // foreground is the other reliable moment to retry.
        center.addObserver(
            self, selector: #selector(handleDidBecomeActive(_:)),
            name: UIApplication.didBecomeActiveNotification, object: nil
        )
        // Deliberately NOT observing `.AVAudioEngineConfigurationChange`: the engine
        // posts it when we start/stop it ourselves, so restarting on it loops. Route
        // changes are the signal that actually matters here.
    }

    // MARK: - Session lifecycle

    /// `.playAndRecord` so STT (mic) and TTS (playback) can coexist in the call.
    /// `.defaultToSpeaker` + `.allowBluetoothHFP` route to the speaker / headphones the
    /// user actually hears. Idempotent, and deliberately a **no-op while already
    /// active**: STT re-activates before every recognition restart, and re-applying
    /// the category there would glitch the route and cut a live TTS reply off.
    ///
    /// The `Task.detached` is the load-bearing part, **not** the `async`. `setActive`'s
    /// async overload still performs the blocking work on the calling actor, so from the
    /// main actor it trips AVAudioSession's own unresponsiveness diagnostic
    /// (`AVAudioSession_iOS.mm` "can lead to UI unresponsiveness if called on the main
    /// thread") exactly like the sync variant does — and races the media pipeline into
    /// `FigApplicationStateMonitor err=-19431` (seen after the AVPlayer external-TTS
    /// switch). Only getting the call *off* the main thread silences it.
    func activate() async throws {
        isHeadphonesConnected = Self.headphonesConnected()
        guard !isActive else { return }
        // Read before detaching: the closure must not touch main-actor state.
        let mode: AVAudioSession.Mode = useVoiceChatMode ? .voiceChat : .default
        try await Task.detached(priority: .userInitiated) {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(
                .playAndRecord,
                mode: mode,
                options: [.defaultToSpeaker, .allowBluetoothHFP, .duckOthers]
            )
            try session.setActive(true, options: .notifyOthersOnDeactivation)
        }.value
        isActive = true
    }

    /// Releases the session. Called only when the call ends. Detached for the same
    /// main-thread reason as `activate()`.
    func deactivate() async {
        guard isActive else { return }
        isActive = false
        useVoiceChatMode = false

        await Task.detached(priority: .userInitiated) {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }.value
    }

    /// Routes playback through the voice-communication path (with echo cancellation)
    /// when the user is on headphones and wants barge-in; otherwise the default path.
    /// Mirrors Android's `TTSService.setAssistantAudioMode`.
    ///
    /// `setMode` is a session mutation like any other, so it goes off the main thread for
    /// the same reason as `activate()`. It stays fire-and-forget rather than `async` so
    /// `SpeechSynthesizerService.setAssistantAudioMode` — and the `onChange` in
    /// `AssistantView` that drives it — keep their current signatures. `useVoiceChatMode`
    /// is the value `activate()` later re-applies through `setCategory`, so a toggle that
    /// lands while the session is inactive is not lost; the only thing the detached call
    /// carries is the live flip.
    func setVoiceChatMode(_ on: Bool) {
        useVoiceChatMode = on
        guard isActive else { return }
        let mode: AVAudioSession.Mode = on ? .voiceChat : .default
        Task.detached(priority: .userInitiated) {
            try? AVAudioSession.sharedInstance().setMode(mode)
        }
    }

    // MARK: - System events

    @objc private nonisolated func handleInterruption(_ note: Notification) {
        guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        let optionsRaw = note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
        Task { @MainActor in
            switch type {
            case .began:
                self.isActive = false
                self.interrupted.send()
            case .ended:
                let options = AVAudioSession.InterruptionOptions(rawValue: optionsRaw)
                if options.contains(.shouldResume) { self.resumeAdvised.send() }
            @unknown default:
                break
            }
        }
    }

    @objc private nonisolated func handleRouteChange(_ note: Notification) {
        guard let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: raw) else { return }
        Task { @MainActor in
            self.isHeadphonesConnected = Self.headphonesConnected()
            switch reason {
            case .newDeviceAvailable, .oldDeviceUnavailable, .override:
                // The input node's hardware format changed; the running tap is stale.
                self.resumeAdvised.send()
            default:
                // `.categoryChange` fires when *we* set the category — restarting on
                // it would loop.
                break
            }
        }
    }

    @objc private nonisolated func handleDidBecomeActive(_ note: Notification) {
        Task { @MainActor in
            self.isHeadphonesConnected = Self.headphonesConnected()
            self.resumeAdvised.send()
        }
    }

    // MARK: - Helpers

    private static func headphonesConnected() -> Bool {
        let outputs = AVAudioSession.sharedInstance().currentRoute.outputs
        let headphonePorts: [AVAudioSession.Port] = [
            .headphones, .bluetoothA2DP, .bluetoothHFP, .usbAudio
        ]
        return outputs.contains { headphonePorts.contains($0.portType) }
    }
}
