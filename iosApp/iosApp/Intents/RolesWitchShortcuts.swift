//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import AppIntents

/// The shortcuts offered to the Shortcuts app and to Siri.
struct RolesWitchShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: AskAIIntent(),
            phrases: [
                "Ask AI in \(.applicationName)",
                "Ask \(.applicationName)"
            ],
            shortTitle: "Ask AI",
            systemImageName: "sparkles"
        )
        AppShortcut(
            intent: AskAIPipelineIntent(),
            phrases: [
                "Ask AI in \(.applicationName) (Pipeline)"
            ],
            shortTitle: "Ask AI (Pipeline)",
            systemImageName: "arrow.right.circle"
        )
    }
}
