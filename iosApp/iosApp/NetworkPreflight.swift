//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import Foundation

/// A one-shot `HEAD` against the endpoint the user just saved, purely to get iOS to ask
/// for Local Network access before anything depends on the answer.
///
/// iOS gates connections to LAN addresses behind a permission prompt, and the *first*
/// connection is the one that triggers it — that request fails while the alert is up,
/// whatever the user then taps. Model fetching goes through the shared Ktor client
/// (`OAIService`), which surfaces that first failure as a plain connection error, so a
/// user pointing at LM Studio on their desktop would see "could not connect", tap
/// Allow on the system alert, and be left staring at an error that no longer applies.
///
/// Spending that first failure here means the prompt is answered while the user is still
/// in the sheet that caused it, and the Ktor call that follows is the second connection.
/// The result is deliberately ignored: an endpoint that is down, wrong, or unreachable is
/// the model picker's error to report, not this one's.
///
/// iOS only. Android has no equivalent gate — `SettingsSections` saves and is done.
enum NetworkPreflight {

    /// Waits at most [timeout] seconds. `waitsForConnectivity` lets the request sit through
    /// the permission alert rather than failing the instant the sandbox refuses the route.
    static func warmUp(endpoint: String, timeout: TimeInterval = 5) async {
        let trimmed = endpoint.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty, let url = URL(string: trimmed), url.host != nil else { return }

        let config = URLSessionConfiguration.default
        config.waitsForConnectivity = true
        config.timeoutIntervalForRequest = timeout
        config.timeoutIntervalForResource = timeout

        var request = URLRequest(url: url)
        request.httpMethod = "HEAD"

        let session = URLSession(configuration: config)
        defer { session.finishTasksAndInvalidate() }

        do {
            _ = try await session.data(for: request)
            print("[NetworkPreflight] reached \(url.host ?? "?")")
        } catch {
            // Expected on the very first LAN connection, and for any endpoint that is
            // simply not up. Either way the prompt has been shown, which is the point.
            print("[NetworkPreflight] \(url.host ?? "?") did not answer: \(error.localizedDescription)")
        }
    }
}
