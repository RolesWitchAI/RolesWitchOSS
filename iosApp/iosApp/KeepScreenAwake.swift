//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import SwiftUI
import UIKit

/// Reference-counted access to `UIApplication.isIdleTimerDisabled`.
///
/// The flag is one boolean for the whole app, and three places wanted it: a streaming reply, a
/// call, and a model download. Assigned directly, the last writer won — which is why
/// `AssistantView` carried a re-assert on `isStreaming` changes, to undo the chat screen clearing
/// the flag when a stream ended *during a call*. A download had no such defence, so anything
/// finishing elsewhere let the screen lock in the middle of a multi-gigabyte transfer.
///
/// Counting holds fixes it for every caller at once: the timer is disabled while at least one hold
/// stands, and re-armed only when the last one goes. The direct analogue of Android's
/// `KeepScreenOn`, which counts `FLAG_KEEP_SCREEN_ON` holds per window for the same reason.
@MainActor
enum IdleTimer {
    private static var holds = 0

    static func retain() {
        holds += 1
        apply()
    }

    static func release() {
        holds = max(0, holds - 1)
        apply()
    }

    private static func apply() {
        UIApplication.shared.isIdleTimerDisabled = holds > 0
    }
}

private struct KeepScreenAwakeModifier: ViewModifier {
    let enabled: Bool

    /// Whether *this* modifier holds the timer. SwiftUI can send `onAppear` more than once for the
    /// same view, and an unbalanced retain would leak a hold — pinning the screen awake for the
    /// rest of the session.
    @State private var holding = false

    func body(content: Content) -> some View {
        content
            .onAppear { sync() }
            .onChange(of: enabled) { _, _ in sync() }
            .onDisappear {
                guard holding else { return }
                holding = false
                IdleTimer.release()
            }
    }

    private func sync() {
        if enabled, !holding {
            holding = true
            IdleTimer.retain()
        } else if !enabled, holding {
            holding = false
            IdleTimer.release()
        }
    }
}

extension View {
    /// Keeps the screen awake while [enabled], releasing on the way out whatever the reason.
    func keepScreenAwake(_ enabled: Bool = true) -> some View {
        modifier(KeepScreenAwakeModifier(enabled: enabled))
    }
}
