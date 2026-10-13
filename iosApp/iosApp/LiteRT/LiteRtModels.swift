//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import Foundation
import LiteRTLM
import SharedLogic

/// One LiteRT-LM model the app can run on device — the Swift view of Kotlin's `LitertModel`.
///
/// The catalogue is never reconstructed here: `LiteRtModelsInterop` copies the fields out of
/// `LitertModels.shared`, so id, sizes, sampler defaults and the vision/thinking capabilities
/// are exactly what `EffectiveSettings` used to gate the thread. This struct only adds what the
/// registry does not know about — where the weights live on disk and what a downloaded file
/// looks like.
struct LiteRtModel: Identifiable, Sendable {
    let id: String
    let displayName: String
    let repoId: String
    let fileName: String
    let commitHash: String
    /// Pinpointed on Android before the app touches the file; here the size is what the
    /// downloaded-file check runs against, so a truncated file never looks installed.
    let sizeBytes: Int64
    /// Nil for gated models — their git-LFS pointer is behind the same auth wall as the
    /// weights, so iOS (like Android) checks those on size alone.
    let sha256: String?
    let minDeviceMemoryGb: Int
    let supportsImage: Bool
    let supportsThinking: Bool
    /// True when downloading this model needs the HuggingFace token. `gated` is the reason
    /// `sha256` is nil for these, and the downloader reads it to decide whether to send a
    /// `Bearer` header — a gated model fetched anonymously is a 401, not a redirect.
    let gated: Bool
    let licenseName: String
    let licenseUrl: String
    let defaultTopK: Int
    let defaultTopP: Float
    let defaultTemperature: Float
    let defaultMaxTokens: Int
    /// The checkpoint's own context-window ceiling, when it publishes one. Used to cap the
    /// Max tokens field in Advanced settings; nil means `defaultMaxTokens` is the only sane
    /// value.
    let maxContextLength: Int?
    /// `"cpu"` or `"gpu"` — the text backend. GPU first would be nicer, but the GPU path
    /// needs a working backend and on a phone without one the engine fails to build outright,
    /// so CPU is the default and Advanced offers GPU as an opt-in.
    let defaultAccelerator: String
    /// Vision tower backend; must be `"gpu"` for the Gemma family.
    let visionAccelerator: String
    /// True for a model the user added by URL rather than one shipped in the registry.
    ///
    /// Custom models are the only ones that can be *removed* from the list — a built-in
    /// whose file is deleted stays on screen, ready to download again, but a custom one
    /// has nowhere to come back from.
    let isCustom: Bool

    init(_ model: LitertModel) {
        id = model.id
        displayName = model.displayName
        repoId = model.repoId
        fileName = model.fileName
        commitHash = model.commitHash
        sizeBytes = model.sizeBytes
        sha256 = model.sha256
        minDeviceMemoryGb = Int(model.minDeviceMemoryGb)
        supportsImage = model.supportsImage
        supportsThinking = model.supportsThinking
        gated = model.gated
        licenseName = model.licenseName
        licenseUrl = model.licenseUrl
        defaultTopK = Int(model.defaultTopK)
        defaultTopP = model.defaultTopP
        defaultTemperature = model.defaultTemperature
        defaultMaxTokens = Int(model.defaultMaxTokens)
        maxContextLength = model.maxContextLength.map { Int($0.intValue) }
        defaultAccelerator = model.defaultAccelerator
        visionAccelerator = model.visionAccelerator
        isCustom = model.isCustom
    }
}

extension LiteRtModel {
    /// The URL the model file is served from, pinned to the exact commit in the registry so an
    /// upstream re-upload can never change what the app fetches.
    var downloadURL: String {
        "https://huggingface.co/\(repoId)/resolve/\(commitHash)/\(fileName)?download=true"
    }

    /// `<appDataDirectory()>/litert/<id>/<commitHash>` — a directory per model, because
    /// LiteRT-LM writes its compiled-weights cache **next to the model file** (the engine is
    /// built with `cacheDir = nil`), so deleting a model means deleting this whole directory
    /// and the compiled-weights cache goes with it.
    var directory: URL {
        LiteRtStorage.baseDirectory.appending(path: "\(id)/\(commitHash)")
    }

    /// The one file that is this model.
    var fileURL: URL {
        directory.appending(path: fileName)
    }

    /// Whether the weights are on disk and complete.
    ///
    /// Derived from the file, never from a "downloaded" flag in `UserDefaults`: a flag
    /// outlives the bytes (a purge, a restore, a manual delete) and the failure it produces
    /// is an unexplained load error deep inside the runtime. Size is the only check — for
    /// gated models it is the only check possible — and a truncated file (a failed download,
    /// a manual `rm`) is exactly what it catches.
    var isDownloaded: Bool {
        guard let size = try? fileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize else {
            return false
        }
        return Int64(size) == sizeBytes
    }

    /// Bytes this model occupies on disk. The compiled-weights cache the engine writes next
    /// to the file is counted too — it is part of what deleting the model reclaims.
    var bytesOnDisk: Int64 {
        guard let files = FileManager.default.enumerator(
            at: directory,
            includingPropertiesForKeys: [.fileSizeKey]
        ) else {
            return 0
        }
        var total: Int64 = 0
        for case let url as URL in files {
            total += Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
        return total
    }

    /// The weights' size in whole MB — what the shortcut's memory preflight compares against
    /// `os_proc_available_memory()`. A floor, not the whole cost: KV cache and heavy buffers
    /// land on top once loaded.
    var downloadSizeMB: Int {
        Int(sizeBytes / (1024 * 1024))
    }

    /// The sampler values to persist when this model is selected, as the strings the shared
    /// settings store uses (blank there means "the checkpoint's default", so these are never
    /// blank).
    var defaultSettings: (topK: String, topP: String, temperature: String, maxTokens: String) {
        (
            topK: String(defaultTopK),
            topP: String(defaultTopP),
            temperature: String(defaultTemperature),
            // `maxContextLength` is a ceiling, not a default output length; when a model
            // publishes one the default stays as the checkpoint's `defaultMaxTokens`.
            maxTokens: String(defaultMaxTokens)
        )
    }

    /// The sampler values to generate with for a turn: the checkpoint's defaults, overridden
    /// by whatever the user has set for this thread. Blank and unparseable both mean "leave
    /// the default".
    ///
    /// The overrides arrive per turn in `EffectiveSettings` rather than being read from a
    /// per-model blob, so a thread's own tuning applies without the engine holding settings
    /// from a different thread.
    func samplerSettings(overriddenBy settings: EffectiveSettings) -> SamplerConfig {
        let topK = Int(settings.litertTopK.trimmed) ?? defaultTopK
        let topP = Float(settings.litertTopP.trimmed) ?? defaultTopP
        let temperature = Float(settings.litertTemperature.trimmed) ?? defaultTemperature
        // Defaults satisfy the runtime's guards; only a user value outside them is dropped.
        return (try? SamplerConfig(topK: max(topK, 1), topP: min(max(topP, 0), 1), temperature: max(temperature, 0)))
            ?? (try! SamplerConfig(topK: defaultTopK, topP: defaultTopP, temperature: defaultTemperature))
    }
}

private extension String {
    var trimmed: String { trimmingCharacters(in: .whitespaces) }
}

/// Where LiteRT keeps downloaded weights. Its own type so `LiteRtModel` (a value type built at
/// interop time) does not have to reach into filesystem code during initialization.
enum LiteRtStorage {
    /// The weights root: `<appDataDirectory()>/litert`, sibling of MLX's `<…>/mlx`.
    ///
    /// `appDataDirectory()` creates the directory and re-applies the iCloud-backup exclusion on
    /// every call, so reading this is also what keeps multi-gigabyte weights out of the user's
    /// backup.
    static var baseDirectory: URL {
        URL(fileURLWithPath: AppStorageKt.appDataDirectory()).appending(path: "litert")
    }
}

/// Catalog of on-device models the iOS app can run.
///
/// The list itself is Kotlin's `LitertModels` — see `LiteRtModelsInterop`. The registry
/// ships everything the app knows; the per-device RAM gate lives in the picker
/// (`LiteRtModelView`), which hides a catalogue model whose `minDeviceMemoryGb` the
/// device cannot clear. A URL-added model is the user's own pick and is never hidden.
enum LiteRtRegistry {
    static var allModels: [LiteRtModel] {
        LiteRtModelsInterop.allModels
    }

    static var builtinModels: [LiteRtModel] {
        LiteRtModelsInterop.builtinModels
    }

    static func model(withId id: String) -> LiteRtModel? {
        LiteRtModelsInterop.model(withId: id)
    }
}
