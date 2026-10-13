//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import UIKit
import UserNotifications

/// App delegate wired via `@UIApplicationDelegateAdaptor` in `iOSApp`.
///
/// Two responsibilities, both mirroring Android's notification flow
/// (`LlmForegroundService.postCompletionNotification` + `MainActivity` deep
/// link via `EXTRA_THREAD_ID`):
///
/// 1. Be the `UNUserNotificationCenterDelegate` so the app can react to local
///    notification taps while in the foreground *and* be granted foreground
///    presentation options for completion notifications posted by
///    `AppViewModel.observeResponseCompletions()`.
/// 2. Hand the tapped notification's `threadId` to `AppViewModel.pendingDeepLinkThreadId`
///    so `ThreadsView` can navigate to the matching thread.
final class AppDelegate: NSObject, UIApplicationDelegate {

    /// iOS relaunches the app when a background download finishes while it was not running, and
    /// hands over a handler to call once the work is done. `LiteRtDownloader` calls it when its
    /// session says it has delivered everything.
    func application(
        _ application: UIApplication,
        handleEventsForBackgroundURLSession identifier: String,
        completionHandler: @escaping () -> Void
    ) {
        LiteRtDownloader.shared.handleBackgroundEvents(completionHandler: completionHandler)
    }

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Register early so taps that launch the app are routed to us.
        UNUserNotificationCenter.current().delegate = self
        // Builds the background session, which is what re-attaches this process to any model
        // download `nsurlsessiond` kept running while the app was gone. Without it a transfer
        // that finished in the meantime has no delegate to hand its file to.
        LiteRtDownloader.shared.adoptOutstandingTasks()
        return true
    }
}

// MARK: - UNUserNotificationCenterDelegate

extension AppDelegate: UNUserNotificationCenterDelegate {
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        // Suppress UI while the user is in-app — they already see the response
        // in the chat. Mirrors Android's `if (isAppForegrounded.value) return`
        // guard in `RolesWitchApp.observeResponseCompletions`.
        completionHandler([])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        defer { completionHandler() }
        guard let threadId = response.notification.request.content.userInfo["threadId"] as? String
        else { return }
        Task { @MainActor in
            AppViewModel.shared.pendingDeepLinkThreadId = threadId
        }
    }
}
