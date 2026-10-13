//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import Foundation
import SharedLogic

/// Bridges the exported Kotlin `LitertModels` registry to the Swift `LiteRtModel` value type.
///
/// Unlike `MlxModels` (whose catalogue is Swift-only, because mlx-swift-lm's configuration
/// types have no Kotlin form), the LiteRT catalogue lives in `commonMain`
/// (`LitertModels.registry`) and exports fully typed. So the interop is only a conversion from
/// the `SharedLogicLitertModel` class to a Swift `struct` — there is no second copy of the
/// model list to keep in step with Kotlin's, and `EffectiveSettings` reads the same registry
/// that ships the picker.
///
/// The pattern, and the reason this exists at all, is the same as `SettingsInterop`: KMP
/// exports its classes verbatim, but every call site in this module wants ordinary Swift
/// values, not objects hanging off a companion.
enum LiteRtModelsInterop {
    /// Every model the app can run: the built-in `registry` plus the user's own
    /// URL-added ones. The custom list is pushed into Kotlin by `SettingsManager` at
    /// startup and on every change, so `LitertModels.all` is already current.
    static var allModels: [LiteRtModel] {
        LitertModels.shared.all.map(LiteRtModel.init)
    }

    /// The models shipped in the app — `registry` without the user's own. The picker
    /// keeps its custom models in local `@State` (Kotlin's registry is not observable
    /// from SwiftUI), so the two lists are concatenated there rather than read from
    /// `all` in one piece.
    static var builtinModels: [LiteRtModel] {
        LitertModels.shared.registry.map(LiteRtModel.init)
    }

    /// The models the user added by URL — the custom tail of `all`, read back after
    /// `SettingsManager.addCustomLitertModel`/`removeCustomLitertModel` has pushed the
    /// change into Kotlin.
    static var customModels: [LiteRtModel] {
        LitertModels.shared.all.filter { $0.isCustom }.map(LiteRtModel.init)
    }

    /// Look a model up by id, or nil when it is in neither list — which is what a selection
    /// saved by a later version, then downgraded, looks like.
    static func model(withId id: String) -> LiteRtModel? {
        LitertModels.shared.get(id: id).map(LiteRtModel.init)
    }
}