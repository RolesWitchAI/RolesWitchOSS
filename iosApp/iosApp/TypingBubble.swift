//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import SwiftUI

/// One full cycle of the three dots.
private let cycle: Double = 1.2

/// How far into the cycle each dot starts, so they ripple instead of blinking together.
private let dotDelays: [Double] = [0, 0.16, 0.32]

/// The "assistant is working" placeholder: an assistant-side bubble with three pulsing dots.
///
/// Deliberately wordless. What the app is waiting for — thinking, or a running tool — is said
/// once, in the thread header; repeating it here is what made the screen read as saying the
/// same thing twice. This only has to show that something is happening, which matters most
/// with tools: nothing streams until the model has finished calling them, which can be tens
/// of seconds after the message is sent.
struct TypingBubble: View {
    @State private var animating = false

    var body: some View {
        HStack {
            HStack(spacing: 5) {
                ForEach(dotDelays, id: \.self) { delay in
                    Circle()
                        .fill(Color.secondary)
                        .frame(width: 7, height: 7)
                        .opacity(animating ? 1 : 0.3)
                        .animation(
                            .easeInOut(duration: cycle / 3)
                                .repeatForever(autoreverses: true)
                                .delay(delay),
                            value: animating
                        )
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 14)
            .background(Color(.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))

            Spacer(minLength: 0)
        }
        .onAppear { animating = true }
    }
}
