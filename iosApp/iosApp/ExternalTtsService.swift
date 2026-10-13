//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import Foundation
import AVFoundation
import Combine
import SharedLogic

/// Plays back OpenAI-compatible `/v1/audio/speech` output — the iOS counterpart of
/// the external branch in Android's `TTSService` (synthesize via `ExternalTtsClient`,
/// AAC bytes out, player in).
///
/// Playback is `AVPlayer`, not `AVAudioPlayer`: the server sends ADTS AAC, and
/// `AVAudioPlayer` opens it through AudioToolbox's `AACAudioFile` parser, which is
/// strict about frame alignment and rejects streams this server emits ("Open failed /
/// ReadBytes Failed" before any audio). `AVPlayer` decodes through the media pipeline
/// that also serves streaming AAC (Icecast-style), so the same bytes play. The bytes
/// are staged to a UUID-named temp file first (AVPlayer needs a URL), and that file is
/// deleted when playback finishes or stops — never while the player may still be
/// reading it.
///
/// Teardown at natural end is deferred by a short drain grace (see `finishPlayback`):
/// the end-of-item notification fires when the *renderer* reaches the end, which on a
/// device is up to a few hundred milliseconds before the output sink — Bluetooth above
/// all — has actually sounded the last buffered samples. Releasing the player at the
/// notification itself cut the tail off every spoken reply in call mode.
///
/// `isSpeaking` behaves exactly like `SpeechSynthesizerService.isSpeaking` — it is the
/// turn loop's resume signal, so it must never report a false "finished". The only
/// difference is the source: `speak` is async (the audio is *fetched*), whereas the
/// native synthesizer speaks locally.
///
/// Errors are surfaced through the `onError` closure so the caller maps them: an
/// `ExternalTtsNotConfiguredException` is "Voice is not configured", anything else from
/// the server call is "Server error". Cancellation of an in-flight synthesis never
/// reaches `onError` — the turn was stopped, not failed.
@MainActor
final class ExternalTtsService: NSObject, ObservableObject {

    @Published private(set) var isSpeaking = false

    private let client = ExternalTtsClient()
    private var player: AVPlayer?
    private var item: AVPlayerItem?
    /// The temp file the player reads; deleted on finish and on stop.
    private var playingURL: URL?
    private var endObserver: NSObjectProtocol?
    private var statusObserver: NSKeyValueObservation?
    /// Bumped on every new playback. A deferred cleanup from a finished utterance
    /// compares against it and stands down when a newer playback has taken over.
    private var playbackGeneration = 0

    /// Synthesizes `text` through the external server and plays the returned audio.
    /// Returns `true` when playback was handed to the player, `false` when there was
    /// nothing to say after cleaning or the call failed (the failure is `onError`'d;
    /// see the class doc).
    @discardableResult
    func speak(
        text: String,
        voiceName: String?,
        endpoint: String,
        token: String,
        onError: ((Error) -> Void)? = nil
    ) async -> Bool {
        stop()
        let clean = SpeechSynthesizerService.stripMarkdown(SpeechSynthesizerService.removeEmojis(text))
        guard !clean.isEmpty else { return false }

        do {
            let bytes = try await client.synthesize(
                endpoint: endpoint,
                token: token,
                input: clean,
                voice: voiceName
            )
            return startPlayback(bytes)
        } catch is CancellationError {
            return false
        } catch is KotlinCancellationException {
            return false
        } catch {
            onError?(error)
            return false
        }
    }

    /// Writes the AAC bytes to a unique temp file and starts `AVPlayer` on it.
    ///
    /// The file must outlive this function: `AVPlayer`/`AVPlayerItem` read the URL
    /// asynchronously, so the temp file is deleted by `finishPlayback`/`stop` (the
    /// guaranteed-cleanup point), not here — a `defer { remove }` at this scope would
    /// race the item's load. The UUID name also lets overlapping plays (a preview sheet
    /// beside a turn) each own their file instead of clobbering one shared name.
    ///
    /// Any failure here is a server-side problem (bad container, empty body) and maps to
    /// "Server error" — the only client-side "not configured" state is the blank
    /// endpoint, which the client raises before any request.
    private func startPlayback(_ bytes: KotlinByteArray) -> Bool {
        let data = bytes.toData()
        guard !data.isEmpty else { return false }

        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("external_tts_\(UUID().uuidString).aac")
        do {
            try data.write(to: url)
        } catch {
            try? FileManager.default.removeItem(at: url)
            return false
        }

        let item = AVPlayerItem(url: url)
        let player = AVPlayer(playerItem: item)
        playbackGeneration += 1
        self.item = item
        self.player = player
        playingURL = url

        // If the item can't decode the stream (unplayable container), end the turn the
        // same way natural completion would — playback simply doesn't happen, matching
        // Android's MediaPlayer error-listener behaviour.
        statusObserver = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            Task { @MainActor in
                guard let self, item === self.item else { return }
                if item.status == .failed {
                    self.finishPlayback()
                }
            }
        }

        // End-of-playback: raise the resume edge now, tear down after the drain grace.
        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] notification in
            Task { @MainActor in
                guard let self, (notification.object as? AVPlayerItem) === self.item else { return }
                self.finishPlayback()
            }
        }

        player.play()
        isSpeaking = true
        return true
    }

    func stop() {
        let wasPlaying = player != nil
        cleanupPlayback()
        if wasPlaying {
            // Stand in for the end-of-playback notification that never arrives after a
            // hard stop: the turn loop and the preview sheet both resume/dismiss on this flip.
            isSpeaking = false
        }
    }

    private func cleanupPlayback() {
        if let endObserver {
            NotificationCenter.default.removeObserver(endObserver)
        }
        endObserver = nil
        statusObserver?.invalidate()
        statusObserver = nil
        player?.pause()
        // Detach the item so its end-of-playback notification can't fire post-cleanup.
        item = nil
        player = nil
        if let url = playingURL {
            playingURL = nil
            try? FileManager.default.removeItem(at: url)
        }
    }

    /// Natural end of playback — the end-of-item notification, or a mid-play decode
    /// failure surfacing through the status observer. Raises the resume edge
    /// immediately (the turn loop and the preview sheet both react to it), but defers
    /// the player teardown past the sink drain: the notification fires when the
    /// *renderer* reaches the end of the item, which on a device is up to a few
    /// hundred milliseconds before the last buffered samples have actually sounded —
    /// tearing down at that instant cut the tail off every spoken reply. The
    /// generation guard keeps a stale cleanup from touching a playback that started
    /// in the meantime; `stop()` cleans synchronously and leaves nothing for it to do.
    private func finishPlayback() {
        isSpeaking = false
        let generation = playbackGeneration
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 300_000_000)
            guard generation == self.playbackGeneration else { return }
            self.cleanupPlayback()
        }
    }
}