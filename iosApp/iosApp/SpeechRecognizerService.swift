//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import Foundation
import Speech
import AVFoundation
import Combine

/// Hands microphone buffers from the real-time audio thread to whichever recognition
/// request is currently open, or drops them when there is none.
///
/// The tap is installed once per call and never touched again, so it cannot capture
/// a request that has since been finished — and, more importantly, the engine can
/// keep running between turns (see `startEngine`).
private final class AudioSink: @unchecked Sendable {
    private let lock = NSLock()
    private var request: SFSpeechAudioBufferRecognitionRequest?

    func set(_ request: SFSpeechAudioBufferRecognitionRequest?) {
        lock.lock()
        self.request = request
        lock.unlock()
    }

    func append(_ buffer: AVAudioPCMBuffer) {
        lock.lock()
        let request = self.request
        lock.unlock()
        request?.append(buffer)
    }
}

/// iOS speech-recognition wrapper, the counterpart of Android's `STTService`/
/// `VoskService`. Mirrors the `SpeechService` contract (`isRecording`,
/// `isListening`, `humanMessage`, `isTimedOut`, `onDevice`) — except that
/// `finalMessage` is an **event** (`finalMessages`) rather than a de-duplicating
/// `@Published`, so saying the same sentence twice in a row fires two turns.
///
/// Backed by `SFSpeechRecognizer` + `AVAudioEngine`. `onDevice` selects offline
/// (`requiresOnDeviceRecognition = true`, the iOS meaning of Android's `useVosk`)
/// vs. online network recognition. Offline finishes the utterance automatically
/// after silence; **online recognition never returns `isFinal` on its own**, so we
/// detect silence by watching how long the best transcription has gone unchanged
/// (mirroring Android's 1500 ms hold timer) and finalize manually.
///
/// Unlike Android there is no tap-to-speak on iOS: the mic listens continuously in
/// both modes. Silence, recognizer errors and the platform's ~1-minute cap on a
/// single recognition task all recycle the task instead of ending the session;
/// `isTimedOut` is raised only when restarting itself keeps failing, so the mic
/// button still has a manual escape hatch.
@MainActor
final class SpeechRecognizerService: ObservableObject {

    @Published private(set) var isRecording = false
    @Published private(set) var isListening = false
    @Published private(set) var humanMessage = ""
    @Published private(set) var isTimedOut = false

    /// One event per finalized utterance. `AssistantView` drives the turn loop off
    /// this. A `PassthroughSubject` (not `@Published`) because SwiftUI's `onChange`
    /// would swallow two identical consecutive utterances.
    let finalMessages = PassthroughSubject<String, Never>()

    /// When `true`, recognition runs fully on-device (offline). Mirrors Android's
    /// `useVosk` flag, surfaced in iOS settings as "Process on device".
    var onDevice = false

    private var recognizer: SFSpeechRecognizer?
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private let audioEngine = AVAudioEngine()
    /// Thread-safe handoff between the audio thread's tap and the main actor. The tap
    /// stays installed for the whole call and appends only while a request is set.
    private let sink = AudioSink()

    private var locale = "en-US"
    private var paused = false
    private var interrupted = false
    private var onDeviceFailed = false
    private var tapInstalled = false

    /// Bumped on every teardown. Results and errors carrying a stale generation are
    /// dropped — without this, the error delivered by `task.cancel()` is mistaken for
    /// an on-device failure and silently restarts the mic in the middle of a turn.
    private var generation: UInt64 = 0

    private var accumulatedText = ""
    private var lastChangeTime = Date()
    private var taskStartTime = Date()
    private var silenceTimer: Timer?
    private var restartFailures = 0
    private var observers: [AnyCancellable] = []

    private static let holdInterval: TimeInterval = 1.5      // finalize after 1.5s of stable text
    private static let maxTaskDuration: TimeInterval = 50.0  // recycle before the platform's ~60s cap
    private static let hardTaskLimit: TimeInterval = 57.0    // flush rather than lose the utterance
    private static let maxRestartFailures = 3

    init() {
        let audio = CallAudioSession.shared
        audio.interrupted
            .sink { [weak self] in self?.handleInterrupted() }
            .store(in: &observers)
        audio.resumeAdvised
            .sink { [weak self] in
                Task { @MainActor in await self?.handleResumeAdvised() }
            }
            .store(in: &observers)
    }

    deinit {
        // `stopRecording()` is main-actor isolated and the engine holds the tap;
        // tearing down here would need a hop we can't make from `deinit`. Callers
        // (AssistantView.endCall / onDisappear) always call `stopRecording()`.
        silenceTimer?.invalidate()
    }

    /// Both grants already in hand, asking nobody. Lets the call screen assert what
    /// `MessagesView.startCall` established without putting a system prompt on top of
    /// itself — authorization can still be revoked from Settings while backgrounded.
    static var isAuthorized: Bool {
        AVAudioApplication.shared.recordPermission == .granted
            && SFSpeechRecognizer.authorizationStatus() == .authorized
    }

    /// Request microphone + speech authorization. Returns `true` only if both are granted.
    ///
    /// Called before the call screen is presented, never from it — see `isAuthorized`.
    static func requestAuthorization() async -> Bool {
        let mic = await withCheckedContinuation { (c: CheckedContinuation<Bool, Never>) in
            AVAudioApplication.requestRecordPermission { granted in
                c.resume(returning: granted)
            }
        }
        guard mic else { return false }
        let status = await withCheckedContinuation { (c: CheckedContinuation<SFSpeechRecognizerAuthorizationStatus, Never>) in
            SFSpeechRecognizer.requestAuthorization { status in c.resume(returning: status) }
        }
        return status == .authorized
    }

    // MARK: - SpeechService surface

    func startRecording(locale: String) async {
        guard !isRecording else { return }
        // Resolve once — falling back to the default recognizer when the locale is
        // unsupported. (Recursing here with the same locale never terminates.)
        recognizer = SFSpeechRecognizer(locale: Locale(identifier: locale)) ?? SFSpeechRecognizer()
        self.locale = locale
        paused = false
        interrupted = false
        onDeviceFailed = false
        restartFailures = 0
        accumulatedText = ""
        humanMessage = ""
        isTimedOut = false

        isRecording = true
        await beginRecognition()
    }

    func pauseListening() {
        guard isRecording else { return }
        paused = true
        isTimedOut = false
        // Flush anything still buffered as a final turn, mirroring Android's
        // `STTService.pauseListening` / `VoskService.pauseListening`.
        if !accumulatedText.isEmpty { emitFinal() }
        stopRecognizer()
    }

    func resumeListening() async {
        guard isRecording else { return }
        // In barge-in mode the recognizer is never paused, and the turn loop still
        // calls this when TTS ends. Restarting a live task there would wipe whatever
        // the user is already saying. Mirrors `VoskService.resumeListening`'s
        // `if (!paused) return` — Vosk being Android's only continuous engine.
        guard paused || !isListening else { return }
        paused = false
        interrupted = false
        isTimedOut = false
        restartFailures = 0
        accumulatedText = ""
        humanMessage = ""
        await beginRecognition()
    }

    func clearHumanMessage() { humanMessage = "" }

    func stopRecording() async {
        guard isRecording else { return }
        paused = false
        interrupted = false
        isTimedOut = false
        accumulatedText = ""
        stopRecognizer()
        // Only now does the mic actually go down.
        teardownAudio()
        isRecording = false
        humanMessage = ""
        // Release the shared audio session now that the call has ended.
        await CallAudioSession.shared.deactivate()
    }

    /// Parity with `SpeechService.destroy()`.
    func destroy() async {
        await stopRecording()
        observers.removeAll()
    }

    // MARK: - Recognition

    private func beginRecognition() async {
        guard isRecording, !paused else { return }
        guard let recognizer, recognizer.isAvailable else {
            failRestart()
            return
        }

        do {
            try await startEngine()
        } catch {
            failRestart()
            return
        }

        generation &+= 1
        let gen = generation

        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        // Offline if requested AND not already fallen back. Offline recognizers
        // deliver `isFinal` automatically after silence; online ones do not, which
        // is why we run the silence timer regardless.
        request.requiresOnDeviceRecognition = onDevice && !onDeviceFailed
        request.taskHint = .unspecified
        self.request = request
        sink.set(request)

        task = recognizer.recognitionTask(with: request) { [weak self] result, error in
            Task { @MainActor [weak self] in await self?.handle(result: result, error: error, generation: gen) }
        }

        restartFailures = 0
        taskStartTime = Date()
        lastChangeTime = Date()
        isTimedOut = false
        isListening = true
        startSilenceTimer()
    }

    /// Brings up the mic for the **whole call**, not per turn.
    ///
    /// iOS grants the `audio` background mode only while the app is actually running
    /// audio I/O. Stopping the engine between turns (during the LLM request, or while
    /// TTS speaks) leaves a window where locking the screen suspends the app: the
    /// recognizer then can't restart, `isTimedOut` latches, and the call reads as
    /// "You're muted". Keeping the engine hot for the duration of the call is what
    /// lets the user keep talking with the screen locked, the way Android's
    /// `LlmForegroundService` does. Between turns the tap simply has nowhere to
    /// deliver to (`sink` holds no request), so nothing is transcribed.
    private func startEngine() async throws {
        try await CallAudioSession.shared.activate()
        guard !audioEngine.isRunning else { return }

        let inputNode = audioEngine.inputNode
        let format = inputNode.outputFormat(forBus: 0)
        // A 0 Hz format means the route is mid-change; retry on the next resume.
        guard format.sampleRate > 0 else { throw AudioError.inputUnavailable }

        if tapInstalled {
            inputNode.removeTap(onBus: 0)
            tapInstalled = false
        }
        inputNode.installTap(onBus: 0, bufferSize: 1024, format: format) { [sink] buffer, _ in
            sink.append(buffer)
        }
        tapInstalled = true

        audioEngine.prepare()
        do {
            try audioEngine.start()
        } catch {
            teardownAudio()
            throw error
        }
    }

    private enum AudioError: Error { case inputUnavailable }

    private func handle(result: SFSpeechRecognitionResult?, error: Error?, generation gen: UInt64) async {
        // Drop anything from a task we have already torn down — notably the error
        // that `task.cancel()` itself delivers.
        guard gen == generation else { return }

        if let error {
            guard !Self.isCancellation(error) else { return }
            // On-device can fail (no downloaded assets, unsupported locale); fall back
            // to the network recognizer and restart — mirrors Android's STTService.
            if onDevice && !onDeviceFailed {
                onDeviceFailed = true
                await restartRecognition()
                return
            }
            // Network recognizer silence/no-match arrives as an error. Flush whatever
            // we have; otherwise just recycle the task and keep listening.
            if !accumulatedText.isEmpty {
                emitFinal()
            } else {
                await restartRecognition()
            }
            return
        }

        guard let result else { return }
        let text = result.bestTranscription.formattedString
        if !text.isEmpty {
            if text != accumulatedText {
                lastChangeTime = Date()
            }
            accumulatedText = text
            humanMessage = text
        }
        if result.isFinal {
            if accumulatedText.isEmpty {
                // Offline recognition finalizes on silence even when it heard
                // nothing. The task is spent either way, so recycle it — otherwise
                // the mic is dead until the rolling restart.
                await restartRecognition()
            } else {
                emitFinal()
            }
        }
    }

    /// Recycles the recognition task without ending the call: the mic keeps listening.
    private func restartRecognition() async {
        guard isRecording, !paused, !interrupted else { return }
        stopRecognizer()
        await beginRecognition()
    }

    /// A restart that could not even get off the ground. After a few in a row we give
    /// up and surface `isTimedOut` so the mic button can offer a manual resume.
    private func failRestart() {
        isListening = false
        restartFailures += 1
        guard restartFailures < Self.maxRestartFailures else {
            isTimedOut = true
            return
        }
        let delay = Double(restartFailures) * 0.5
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard let self, self.isRecording, !self.paused, !self.interrupted else { return }
            await self.beginRecognition()
        }
    }

    // MARK: - Silence / lifetime watchdog

    /// Finalizes the turn if the transcription has been stable for `holdInterval`,
    /// recycles the task after `noSpeechTimeout` of pure silence, and recycles it
    /// again before the platform's per-task duration cap.
    private func startSilenceTimer() {
        silenceTimer?.invalidate()
        let timer = Timer(timeInterval: 0.25, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in await self?.tick() }
        }
        // `.common` so the watchdog keeps firing while the user scrolls or drags.
        RunLoop.main.add(timer, forMode: .common)
        silenceTimer = timer
    }

    private func tick() async {
        guard isRecording, !paused, !interrupted else { return }
        let now = Date()
        if !accumulatedText.isEmpty, now.timeIntervalSince(lastChangeTime) >= Self.holdInterval {
            emitFinal()
            return
        }
        // The platform caps a single recognition task at roughly a minute, after
        // which it goes quiet without an error. Recycle it — but only between
        // utterances, so the restart's brief mic gap can't clip someone mid-sentence.
        let uptime = now.timeIntervalSince(taskStartTime)
        guard uptime >= Self.maxTaskDuration else { return }
        if accumulatedText.isEmpty {
            await restartRecognition()
        } else if uptime >= Self.hardTaskLimit {
            emitFinal()
        }
    }

    private func emitFinal() {
        guard !accumulatedText.isEmpty else { return }
        let text = accumulatedText
        accumulatedText = ""
        stopRecognizer()
        finalMessages.send(text)
    }

    // MARK: - Teardown

    /// Ends the current recognition task only. The mic (`audioEngine`) and the audio
    /// session both stay up — pausing STT between turns must neither silence the TTS
    /// reply nor let the app be suspended while the screen is locked. Both are torn
    /// down only when the whole call ends (`stopRecording`).
    private func stopRecognizer() {
        generation &+= 1
        silenceTimer?.invalidate()
        silenceTimer = nil
        sink.set(nil)
        request?.endAudio()
        request = nil
        task?.cancel()
        task = nil
        isListening = false
    }

    /// Always removes the tap, even when the engine never started — otherwise a
    /// failed `audioEngine.start()` leaves a tap appending into a dead request.
    private func teardownAudio() {
        sink.set(nil)
        if audioEngine.isRunning { audioEngine.stop() }
        if tapInstalled {
            audioEngine.inputNode.removeTap(onBus: 0)
            tapInstalled = false
        }
    }

    // MARK: - Audio-session events

    private func handleInterrupted() {
        guard isRecording else { return }
        interrupted = true
        stopRecognizer()
        // The system has already stopped our engine; drop the tap so it is rebuilt
        // against whatever the input format is once we get the session back.
        teardownAudio()
    }

    private func handleResumeAdvised() async {
        guard isRecording, !paused else { return }
        interrupted = false
        restartFailures = 0
        stopRecognizer()
        teardownAudio()
        await beginRecognition()
    }

    /// `task.cancel()` reports through the same callback as a real failure, and the
    /// code it uses is *not* 203. The generation guard already drops those, but keep
    /// this as a second line of defence against a self-inflicted "on-device failed"
    /// downgrade. Codes are Siri's undocumented `kAFAssistantErrorDomain` values.
    private static func isCancellation(_ error: Error) -> Bool {
        let ns = error as NSError
        switch ns.domain {
        case "kAFAssistantErrorDomain":
            // 203 no result, 216 cancelled, 301 request cancelled, 1700 task cancelled.
            return [203, 216, 301, 1700].contains(ns.code)
        case NSCocoaErrorDomain:
            return ns.code == NSUserCancelledError
        default:
            return false
        }
    }
}
