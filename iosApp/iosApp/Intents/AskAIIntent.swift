//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import AppIntents

/// "Ask AI" — send text to a conversation and get the reply back.
///
/// The message lands in the thread the same way one typed into the chat does: persisted,
/// answered by whichever engine that thread is configured for, and still there when the
/// user next opens the app.
struct AskAIIntent: AppIntent {
    static let title: LocalizedStringResource = "Ask AI"
    static let description = IntentDescription(
        "Send a message to a conversation and return the reply. The conversation's own settings decide which model answers and which tools it may use."
    )

    @Parameter(title: "Conversation")
    var thread: ThreadEntity

    @Parameter(title: "Message", inputOptions: .init(multiline: true))
    var prompt: String

    static var parameterSummary: some ParameterSummary {
        Summary("Ask \(\.$thread): \(\.$prompt)")
    }

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<String> & ProvidesDialog {
        let reply = try await AskAIRunner.ask(threadId: thread.id, prompt: prompt)
        return .result(value: reply, dialog: IntentDialog(stringLiteral: reply))
    }
}
