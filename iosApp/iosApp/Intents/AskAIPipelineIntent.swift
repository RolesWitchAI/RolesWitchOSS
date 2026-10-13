//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import AppIntents

/// "Ask AI (Pipeline)" — ``AskAIIntent`` with the dialog left off.
///
/// The only difference is the return type: this one is `ReturnsValue<String>` alone,
/// where the plain intent is also `ProvidesDialog`. A dialog is what makes Shortcuts and
/// Siri announce the reply — right when a person ran the shortcut to hear an answer,
/// wrong in the middle of an automation, where the reply is an input to the next action
/// and the announcement is an interruption. Both exist so the user picks which they
/// wanted rather than having to work around one of them.
///
/// A sibling rather than a flag on one intent: `@Parameter` and the result type are
/// declared, not computed, so a single intent cannot decide at runtime whether it speaks.
/// The body is one shared call to ``AskAIRunner``, so there is nothing here to drift.
struct AskAIPipelineIntent: AppIntent {
    static let title: LocalizedStringResource = "Ask AI (Pipeline)"
    static let description = IntentDescription(
        "Send a message to a conversation and pass the reply to the next action, without announcing it. The conversation's own settings decide which model answers and which tools it may use."
    )

    @Parameter(title: "Conversation")
    var thread: ThreadEntity

    @Parameter(title: "Message", inputOptions: .init(multiline: true))
    var prompt: String

    static var parameterSummary: some ParameterSummary {
        Summary("Ask \(\.$thread): \(\.$prompt)")
    }

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<String> {
        let reply = try await AskAIRunner.ask(threadId: thread.id, prompt: prompt)
        return .result(value: reply)
    }
}
