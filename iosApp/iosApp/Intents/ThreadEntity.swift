//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import AppIntents
import SharedLogic

/// A conversation, as the Shortcuts app sees it.
///
/// The *only* parameter either intent takes besides its input. The shipped app let a
/// shortcut pick a model; picking a thread instead settles more than a model does — the
/// engine (remote or on-device), the endpoint and token, the system prompt, the message
/// limit, and which MCP tools may be called are all properties of the thread, resolved
/// per turn by `EffectiveSettings`. A shortcut that named a model would have to restate
/// the rest of that, and would drift from the app the moment the user changed it.
struct ThreadEntity: AppEntity {
    let id: String
    let title: String

    static let typeDisplayRepresentation = TypeDisplayRepresentation(name: "Conversation")
    static let defaultQuery = ThreadQuery()

    var displayRepresentation: DisplayRepresentation {
        // The user's own thread name: shown as-is, never translated.
        DisplayRepresentation(title: "\(title)")
    }
}

struct ThreadQuery: EntityQuery {
    @MainActor
    func entities(for identifiers: [ThreadEntity.ID]) async throws -> [ThreadEntity] {
        let wanted = Set(identifiers)
        return try await allThreads().filter { wanted.contains($0.id) }
    }

    /// Every thread — each is a perfectly good place to send a
    /// shortcut's question.
    @MainActor
    func suggestedEntities() async throws -> [ThreadEntity] {
        try await allThreads()
    }

    @MainActor
    private func allThreads() async throws -> [ThreadEntity] {
        let threads = try await AppViewModel.shared.container.database.getAllThreads()
        return threads.map { ThreadEntity(id: $0.threadId, title: $0.title) }
    }
}
