//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import Foundation
import os
import SharedLogic

/// The body of ``AskAIIntent``: send into a real thread and hand back what came out.
///
/// **Runs without bringing the app forward.** Neither intent sets `openAppWhenRun`, so
/// iOS launches the app in the background if it is not already running and the shortcut
/// completes without a screen ever appearing — which is the point of an automation. That
/// works because everything below reaches the app's own services in-process:
/// `AppViewModel.shared` builds the container on first touch, whether that first touch is
/// the UI or this.
///
/// Deliberately thin. It runs `LLMService.sendMessage` — the same call the chat screen's
/// send button makes — rather than reproducing a turn, so a shortcut is not a second
/// implementation that can drift. Everything downstream follows from that: the user's
/// message is persisted and appears in the chat, `EffectiveSettings.resolve` picks the
/// remote endpoint or LiteRT per the thread's own settings, MCP tools run if that thread has
/// them enabled, and the reply is stored as an ordinary message that can be favourited,
/// copied or edited afterwards.
@MainActor
enum AskAIRunner {

    enum Failure: LocalizedError {
        case threadMissing
        case notConfigured(String)
        case modelNotInstalled(String)
        case notEnoughMemory(String)
        case noReply

        var errorDescription: String? {
            switch self {
            case .threadMissing:
                return String(localized: "That conversation no longer exists.")
            case .notConfigured(let title):
                return String(localized: "\(title) has no model configured. Open it in RolesWitch and pick one.")
            case .modelNotInstalled(let name):
                return String(localized: "\(name) is not downloaded yet. Open RolesWitch and download it first.")
            case .notEnoughMemory(let name):
                return String(localized: "Not enough free memory to load \(name) in the background. Open RolesWitch and run this again with the app on screen.")
            case .noReply:
                return String(localized: "The model returned nothing.")
            }
        }
    }

    /// Sends [prompt] to [threadId] and returns the reply text.
    ///
    /// `sendMessage` suspends until the assistant row is persisted, so the reply is read
    /// back from the database afterwards rather than raced for on a flow. A turn that
    /// produces no content persists no row — hence ``Failure/noReply`` instead of an
    /// empty string, which a shortcut would happily pass on to the next action.
    static func ask(threadId: String, prompt: String) async throws -> String {
        let app = AppViewModel.shared
        let database = app.container.database

        guard let thread = try await database.messageThreadDao().getThreadById(threadId: threadId) else {
            throw Failure.threadMissing
        }

        // The same gate the composer applies, and for the same reason: `isConfigured`
        // answers for whichever engine this thread resolves to, so an on-device thread —
        // which has no endpoint and no token — is not reported as unconfigured.
        let settings = EffectiveSettings.companion.resolve(
            thread: thread,
            global: app.container.settingsManager
        )
        guard settings.isConfigured else { throw Failure.notConfigured(thread.title) }
        if settings.useOnDevice { try preflightOnDevice(settings) }

        let before = try await database.messageDao().getMessagesByThread(threadId: threadId)
        let knownIds = Set(before.map(\.id))

        try await app.sendMessage(text: prompt, threadId: threadId, images: [])

        let after = try await database.messageDao().getMessagesByThread(threadId: threadId)
        guard let reply = after.last(where: { !$0.isUser && !knownIds.contains($0.id) }) else {
            throw Failure.noReply
        }
        return reply.content
    }

    /// Refuses an on-device turn that this process cannot survive, before it starts.
    ///
    /// An intent usually runs in a *background* launch, where the process gets a much
    /// smaller memory ceiling than the foreground app does. Loading several GB of weights
    /// into that ceiling does not fail — the OS jetsams the process, and the shortcut
    /// simply stops with nothing said. A shortcut that dies mid-automation and explains
    /// nothing is worse than one that refuses with a reason, so this checks first.
    ///
    /// `os_proc_available_memory()` is the right question to ask: it reports what *this*
    /// process may still allocate before being killed, so it already accounts for being
    /// in the background, for the increased-memory-limit entitlement, and for whatever
    /// else is resident. In the foreground it is large and this check passes silently,
    /// which is exactly the distinction we want to draw.
    ///
    /// The weights have to be resident to run, so the download size is the floor — not
    /// the whole cost, which adds the KV cache and LiteRT's own buffers on top. Refusing at
    /// the floor is deliberately conservative: it will never reject a turn that had room.
    private static func preflightOnDevice(_ settings: EffectiveSettings) throws {
        guard let model = LiteRtRegistry.model(withId: settings.litertModel) else { return }
        guard model.isDownloaded else { throw Failure.modelNotInstalled(model.displayName) }

        let availableBytes = os_proc_available_memory()
        // 0 means the platform declined to answer; a guess is not worth a false refusal.
        guard availableBytes > 0 else { return }

        let availableMB = Int(availableBytes / (1024 * 1024))
        guard availableMB >= model.downloadSizeMB else {
            throw Failure.notEnoughMemory(model.displayName)
        }
    }
}
