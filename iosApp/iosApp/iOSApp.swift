//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import SwiftUI

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var app = AppViewModel.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(app)
                // Its own environment object, not reached through `app`: nested
                // `ObservableObject`s do not forward their changes, so a view watching
                // `app.litert.initState` would never redraw when the model finishes loading.
                .environmentObject(app.litert)
                .onChange(of: scenePhase) { _, phase in
                    // Mirror Android's ProcessLifecycleOwnerAccesser.isAppForegrounded:
                    // the AppViewModel only posts completion notifications while
                    // the app is in the background (covers screen-locked too).
                    app.isAppForegrounded = (phase == .active)
                    app.isSceneBackgrounded = (phase == .background)
                    // Only on `.background`, never on `.inactive`: see `isSceneBackgrounded`.
                    // iOS terminates a *suspended* app with GPU work outstanding, so the LiteRT
                    // turn and the resident weights have to go — but an app that is merely
                    // inactive is still running, and cancelling there killed downloads.
                    if phase == .background {
                        Task { await app.stopOnDeviceWork() }
                    }
                }
        }
    }
}
