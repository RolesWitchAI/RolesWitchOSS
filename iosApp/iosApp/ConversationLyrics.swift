//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import SwiftUI
import UIKit

/// The call screen's single line of "lyrics" — iOS counterpart of Android's
/// `ConversationLyricsView` (`androidApp/.../ui/assistant/AssistantScreen.kt`).
///
/// Both iOS surfaces (the voice screen and the video overlay) render *this* view, so
/// they cannot drift apart. The branch order below is Android's, verbatim:
///
///  1. thinking, nothing spoken yet → "Thinking…"
///  2. a live user transcript       → the transcript, in green
///  3. mic off and idle             → "You're muted"
///  4. an assistant reply           → the reply, markdown-stripped
///  5. otherwise                    → empty
///
/// The reply is shown **whole, at once** — Android does not stream or reveal it
/// progressively (`TTSService` exposes only a binary `isSpeaking`, and the call
/// screen reads the persisted row rather than `LLMService.streamingResponse`).
struct ConversationLyrics: View {
    let userMessage: String
    let assistantMessage: String
    let isThinking: Bool
    let isSpeaking: Bool
    /// Android's `micIsOff`: muted *or* the recognizer gave up.
    let isMuted: Bool
    /// `true` over the camera preview: smaller, light-on-dark, line-capped.
    let lightMode: Bool
    var onStopTtsAndResumeStt: () -> Void = {}

    private static let userColorDark = Color(red: 0x4C / 255, green: 0xAF / 255, blue: 0x50 / 255)
    private static let userColorLight = Color(red: 0x81 / 255, green: 0xC7 / 255, blue: 0x84 / 255)

    /// Mixes localized chrome with the live transcript / the assistant's reply, so the
    /// two UI branches resolve through the catalog here and the result is rendered
    /// `verbatim` below — a `Text(literal)` anywhere in this path would try to
    /// translate someone's conversation.
    private var displayText: String {
        if isThinking && assistantMessage.isEmpty { return String(localized: "Thinking…") }
        if !userMessage.isEmpty { return userMessage }
        if isMuted && !isThinking && !isSpeaking { return String(localized: "You're muted") }
        if !assistantMessage.isEmpty { return SpeechSynthesizerService.stripMarkdown(assistantMessage) }
        return ""
    }

    private var isUser: Bool { !userMessage.isEmpty && !isThinking }

    private var textColor: Color {
        if isUser { return lightMode ? Self.userColorLight : Self.userColorDark }
        return lightMode ? .white : .accentColor
    }

    var body: some View {
        let text = displayText
        Text(verbatim: text.isEmpty ? " " : text)
            .font(lightMode ? .title3.weight(.medium) : .title.weight(.medium))
            .multilineTextAlignment(.center)
            .foregroundStyle(textColor)
            .lineLimit(lightMode ? 4 : nil)
            .frame(maxWidth: .infinity)
            // A one-line reply is a thin strip to press. The padding is part of the
            // hit area, not the layout — `contentShape` is what the gesture tests
            // against, so it has to come after.
            .padding(.vertical, 12)
            .contentShape(Rectangle())
            .modifier(BargeInOnLongPress(active: isSpeaking, action: onStopTtsAndResumeStt))
            .animation(.easeInOut(duration: 0.15), value: text)
    }
}

/// Long-press to barge in: stop the reply and hand the mic back.
///
/// Attached **only while the assistant is speaking**, which is how Android does it
/// (`combinedClickable` sits inside an `if (isSpeaking)`). The previous version attached
/// the gesture always and returned early on `isSpeaking` inside the closure — equivalent
/// in principle, but it makes the gesture's presence independent of the state it depends
/// on, which is worth not doing.
///
/// `simultaneousGesture` rather than `onLongPressGesture` is the substantive change: the
/// lyrics live inside a `ScrollView`, whose pan recognizer competes for the same touch,
/// and the plain modifier can lose that race — which reads as "holding does nothing".
/// Declaring the gesture simultaneous lets both recognizers see the press.
private struct BargeInOnLongPress: ViewModifier {
    let active: Bool
    let action: () -> Void

    @ViewBuilder
    func body(content: Content) -> some View {
        if active {
            content.simultaneousGesture(
                LongPressGesture(minimumDuration: 0.5).onEnded { _ in
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    action()
                }
            )
        } else {
            content
        }
    }
}
