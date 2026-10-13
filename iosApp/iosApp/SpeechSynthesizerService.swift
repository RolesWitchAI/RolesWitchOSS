//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import Foundation
import AVFoundation
import Combine
import SharedLogic

/// iOS text-to-speech wrapper, the counterpart of Android's `TTSService`.
///
/// Voice selection mirrors Android's rule but maps to `AVSpeechSynthesisVoice`:
///  1. an explicitly-chosen voice name (from the voice picker), if installed;
///  2. otherwise the best-quality (premium, then enhanced) voice for the language;
///  3. otherwise the platform default — **English → Samantha**, other languages →
///     the first available voice.
///
/// `isSpeaking` is the turn loop's resume signal, so it must never report a false
/// "finished": delegate callbacks are matched against the utterance they belong to,
/// and a superseded utterance's `didFinish`/`didCancel` is ignored.
@MainActor
final class SpeechSynthesizerService: NSObject, ObservableObject {

    @Published private(set) var isSpeaking = false

    private let synthesizer = AVSpeechSynthesizer()
    /// The utterance whose callbacks we currently accept. `AVSpeechSynthesizer`
    /// delivers `didCancel`/`didFinish` for flushed utterances asynchronously, and
    /// those can land after the replacement's `didStart`.
    private var currentUtterance: AVSpeechUtterance?

    override init() {
        super.init()
        synthesizer.delegate = self
    }

    /// Speaks `text`, returning `false` when there was nothing left to say after
    /// cleaning. The caller must handle `false` itself — no `didStart`/`didFinish`
    /// pair is delivered in that case, so waiting on `isSpeaking` would hang the
    /// turn loop forever.
    @discardableResult
    func speak(text: String, language: String, voiceName: String?) -> Bool {
        stop()
        let clean = Self.stripMarkdown(Self.removeEmojis(text))
        guard !clean.isEmpty else { return false }

        let utterance = AVSpeechUtterance(string: clean)
        if let voice = Self.selectVoice(language: language, preferred: voiceName) {
            utterance.voice = voice
        } else {
            utterance.voice = AVSpeechSynthesisVoice(language: Self.localeTag(for: language))
        }
        utterance.pitchMultiplier = 1.0
        currentUtterance = utterance
        synthesizer.speak(utterance)
        return true
    }

    func stop() {
        currentUtterance = nil
        if synthesizer.isSpeaking {
            synthesizer.stopSpeaking(at: .immediate)
        }
        isSpeaking = false
    }

    /// Routes playback through the voice-communication path (with echo cancellation)
    /// when the user is on headphones and wants barge-in; otherwise the default path.
    /// Mirrors Android's `TTSService.setAssistantAudioMode`.
    func setAssistantAudioMode(_ useVoiceCommPath: Bool) {
        CallAudioSession.shared.setVoiceChatMode(useVoiceCommPath)
    }

    // MARK: - Voice selection

    /// The word the voice picker speaks when you tap a voice. Not localized through the
    /// catalogue: it has to be in *the voice's* language, which is the language being
    /// previewed, not the one the app's UI happens to be in. Mirrors Android's
    /// `TTSService.greeting`, same table.
    static func greeting(for language: String) -> String {
        [
            "en": "Hello",
            "ru": "Привет",
            "fr": "Bonjour",
            "de": "Hallo",
            "es": "Hola",
            "pt": "Olá"
        ][language] ?? "Hello"
    }

    static func selectVoice(language: String, preferred: String?) -> AVSpeechSynthesisVoice? {
        let prefix = language.lowercased()
        let voices = AVSpeechSynthesisVoice.speechVoices().filter {
            $0.language.lowercased().hasPrefix(prefix)
        }
        // 1. Explicit user pick.
        if let name = preferred, !name.isEmpty,
           let v = voices.first(where: { $0.name == name }) {
            return v
        }
        // 2. Best-quality voice for the language (premium beats enhanced).
        if let v = voices.max(by: { $0.quality.rawValue < $1.quality.rawValue }),
           v.quality != .default {
            return v
        }
        // 3. English → Samantha; otherwise the first available voice.
        if prefix.hasPrefix("en"), let v = voices.first(where: { $0.name == "Samantha" }) { return v }
        return voices.first
    }

    /// Lists available voices for the language, best quality first (for the picker).
    static func availableVoices(for language: String) -> [AVSpeechSynthesisVoice] {
        let prefix = language.lowercased()
        return AVSpeechSynthesisVoice.speechVoices()
            .filter { $0.language.lowercased().hasPrefix(prefix) }
            .sorted {
                $0.quality.rawValue != $1.quality.rawValue
                    ? $0.quality.rawValue > $1.quality.rawValue
                    : $0.name < $1.name
            }
    }

    private static func localeTag(for code: String) -> String {
        switch code {
        case "en": return "en-US"
        case "ru": return "ru-RU"
        case "fr": return "fr-FR"
        case "de": return "de-DE"
        case "es": return "es-ES"
        case "pt": return "pt-PT"
        default: return "en-US"
        }
    }

    // MARK: - Text cleaning

    /// Delegates to the shared `MarkdownProcessor` in `commonMain` — the same one
    /// Android's `TTSService.stripMarkdown` and `ConversationLyricsView` use — so the
    /// spoken text and the on-screen lyrics are identical across platforms.
    static func stripMarkdown(_ text: String) -> String {
        MarkdownProcessor.shared.stripMarkdown(text: text)
    }

    /// Mirrors Android's `TTSService.removeEmojis`.
    static func removeEmojis(_ text: String) -> String {
        text.replacingOccurrences(of: "[\\p{So}\\p{Cn}]", with: "", options: .regularExpression)
    }
}

extension SpeechSynthesizerService: AVSpeechSynthesizerDelegate {
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didStart utterance: AVSpeechUtterance) {
        Task { @MainActor in
            guard utterance === self.currentUtterance else { return }
            self.isSpeaking = true
        }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor in self.finish(utterance) }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        Task { @MainActor in self.finish(utterance) }
    }

    // NOTE: `didPause` is deliberately not implemented — a pause is not a finish, and
    // clearing `isSpeaking` there would resume STT into the middle of the reply.

    @MainActor
    private func finish(_ utterance: AVSpeechUtterance) {
        guard utterance === currentUtterance else { return }
        currentUtterance = nil
        isSpeaking = false
    }
}
