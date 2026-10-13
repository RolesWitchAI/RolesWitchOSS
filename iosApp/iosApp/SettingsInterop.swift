//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import Foundation
import SharedLogic

/// Bridges the Kotlin `SettingsManager` companion maps (language code → display
/// name / STT locale) to Swift. Kotlin `Map<String, String>` exports as
/// `NSDictionary`, so we wrap the access to keep call sites typed.
enum SettingsInterop {
    static func locale(for language: String) -> String {
        let dict = SettingsManager.companion.localeForSTT as NSDictionary
        return (dict[language] as? String) ?? "en-US"
    }

    static func languageName(_ code: String) -> String {
        let dict = SettingsManager.companion.defaultLanguages as NSDictionary
        return (dict[code] as? String) ?? code
    }

    static var languageCodes: [String] {
        let dict = SettingsManager.companion.defaultLanguages as NSDictionary
        return dict.allKeys.compactMap { $0 as? String }.sorted()
    }
}
