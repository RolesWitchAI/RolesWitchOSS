//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import SwiftUI
import SharedLogic

/// Top-level router. Mirrors Android's `MainActivity` `if (hasCompletedOnboarding)`
/// branch: shows onboarding until the user completes it, then the threads list.
/// ThreadsView itself owns the navigation stack with destinations for settings
/// (gear icon, top-left) and messages (row tap), plus the new-thread button
/// (top-right) — so no separate tab container is needed.
struct RootView: View {
    @EnvironmentObject private var app: AppViewModel

    var body: some View {
        Group {
            if app.hasCompletedOnboarding {
                ThreadsView()
            } else {
                OnboardingView()
            }
        }
        .alert(
            "Request was cancelled",
            isPresented: Binding(
                get: { app.pendingCancellationRetryThreadId != nil },
                set: { if !$0 { app.pendingCancellationRetryThreadId = nil } }
            )
        ) {
            Button("Retry") {
                guard let threadId = app.pendingCancellationRetryThreadId else { return }
                Task { await app.retryLLM(threadId: threadId) }
            }
            Button("Cancel", role: .cancel) {
                app.pendingCancellationRetryThreadId = nil
            }
        } message: {
            Text("The request was cancelled while the app was in the background. Would you like to retry?")
        }
    }
}

#Preview {
    RootView().environmentObject(AppViewModel())
        .environmentObject(AppViewModel.shared.litert)
}
