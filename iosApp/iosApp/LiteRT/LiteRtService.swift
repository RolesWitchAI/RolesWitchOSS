//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import Foundation
import LiteRTLM
import OSLog
import SharedLogic

private let logger = Logger(subsystem: Bundle.main.bundleIdentifier ?? "RolesWitch", category: "LiteRT")

/// Channel name LiteRT-LM uses for a model's reasoning tokens.
private let thoughtChannel = "thought"

/// Streamed pieces beginning with this are the model's internal control tokens, not text for
/// the user. The reference gallery filters them the same way.
private let controlTokenPrefix = "<ctrl"

/// Runs a LiteRT-LM checkpoint on device — iOS's on-device engine, the counterpart of Android's
/// `LiteRtService`. It replaces what `MLXService` was before the swap and deliberately mirrors
/// both lifecycles so the warm-up UX is identical on the two platforms.
///
/// It owns the resident engine and nothing else about a turn: `LLMService` (commonMain) still
/// trims the history, resolves the system prompt, persists the reply and drives the streaming
/// flows. `LiteRtInferenceEngine` is the thin adapter that lets it call in here.
///
/// ### Engine lifetime
/// Loading a multi-gigabyte model takes tens of seconds, so exactly one `Engine` is kept
/// alive across turns, chat screens and calls, and is reused unchanged whenever the next thread
/// asks for the same model. It is rebuilt only when something it was configured from changes:
/// the model, the accelerator, `maxNumTokens`, or whether the vision tower is wanted — all of
/// which are baked into the `EngineConfig`.
///
/// The engine is unloaded as eagerly as it is loaded, but only ever on something the *user*
/// did or on suspension. It is **never** unloaded on leaving a screen: moving between the chat
/// and the call, or backing out to the thread list, changes no selection and so keeps the
/// weights resident — the earlier MLX version released on screen departure and dropped the
/// model mid-call, which is the history this lifecycle was fixed against.
///
/// Backgrounding is the opposite of Android, where the engine deliberately stays resident.
/// On iOS a suspended process carrying live Metal/GPU work is terminated by the OS, so
/// `stopForBackground()` cancels a turn in flight and unloads the weights — but never a
/// download, which runs in `nsurlsessiond` outside this process and survives anyway.
///
/// ### Conversation lifetime
/// A fresh `Conversation` is created per turn, seeded with the trimmed history `LLMService`
/// produced. LiteRT's conversation is stateful while the rest of the app is not — messages
/// can be edited, deleted, or fall outside the history limit between turns — and rebuilding is
/// the only way the two stay in agreement. Only the conversation is rebuilt; the engine (and
/// its loaded weights) is reused. ARC is the closing: the conversation deinit deletes its
/// native handle, and the per-turn conversations hold the engine resident until the stream
/// that reads them is done, so the native teardown ordering Android serialises with locks is
/// automatic here.
@MainActor
final class LiteRtService: ObservableObject {
    /// What the resident engine is doing. `Failed` is durable — it stays until something
    /// changes the selection or the user retries, because a load failure the user cannot see
    /// is a chat that silently never answers.
    enum InitState: Equatable {
        case idle
        case loading(String)
        case ready(String)
        case failed(String)

        var isLoading: Bool { if case .loading = self { return true } else { return false } }
        var failure: String? { if case .failed(let message) = self { return message } else { return nil } }
    }

    /// Progress of a model install, or nil when nothing is downloading.
    ///
    /// Carries bytes, speed and remaining time, not just the fraction: the blocking
    /// download sheet mirrors Android's, where a bar alone says nothing over a
    /// multi-gigabyte, ten-minute transfer.
    struct DownloadState: Equatable {
        let modelId: String
        let fraction: Double
        let downloadedBytes: Int64
        let totalBytes: Int64
        let bytesPerSecond: Int64
        let remainingMs: Int64
    }

    /// Everything the loaded engine was built from. A change in any of these means the weights
    /// have to be reloaded; anything else (sampler settings, thinking, the system prompt) only
    /// affects the per-turn conversation and is free to change.
    private struct EngineKey: Equatable {
        let filePath: String
        let accelerator: String
        let maxTokens: Int
        let vision: Bool
    }

    private struct Loaded {
        let modelId: String
        let key: EngineKey
        let engine: Engine
    }

    @Published private(set) var initState: InitState = .idle

    /// Whether the resident engine actually has a vision tower.
    ///
    /// Capability, not intent — `EffectiveSettings.visionAvailable` is intent. The two
    /// disagreeing is what raises "this model does not support images" instead of an
    /// attachment being dropped in silence. Unlike `MLXModel.type` (which *is* capability),
    /// a LiteRT vision model whose required GPU backend is unavailable is loaded text-only
    /// rather than not at all, so this is answered by what came up, not by the registry.
    @Published private(set) var visionActive: Bool = false

    @Published private(set) var downloadState: DownloadState?

    /// True from the moment a turn claims the engine until it releases it. Guards the unload
    /// paths: dropping the weights mid-stream would free memory the runtime is still reading.
    @Published private(set) var isGenerating = false

    private var loaded: Loaded?

    /// The per-turn conversation, kept so `cancel()` can reach the native runtime's
    /// `cancel_process` rather than only ending a Swift loop.
    private var activeConversation: Conversation?

    /// The task running the current turn, so `cancel()` can stop it.
    private var generationTask: Task<GenerationResult, any Error>?

    /// The model currently downloading, so `cancelDownload()` has something to name.
    private var downloadingId: String?

    /// The last speed sample point, so `reportDownload` can derive bytes/second from the
    /// fraction deltas. Reset at the start of every download and on cancel.
    private var speedSample: (at: Date, bytes: Int64)?

    /// How many screens currently need the model to stay resident. Held by the call screen for
    /// its whole duration; only the memory-pressure path consults it.
    private var residencyHolds = 0

    // MARK: - Model lifecycle

    /// The model [settings] selects, or nil when the selection names nothing this build ships.
    func model(for settings: EffectiveSettings) -> LiteRtModel? {
        LiteRtRegistry.model(withId: settings.litertModel)
    }

    /// Brings the resident engine in line with [settings]: loads the selected model, or
    /// unloads whatever is resident when the thread is not on-device.
    ///
    /// The only entry point the screens use. Idempotent — a model already resident is left
    /// alone, which is what makes it safe to call from a `task(id:)` that re-fires on every
    /// settings change.
    func syncTo(_ settings: EffectiveSettings) async {
        guard settings.useOnDevice, let model = model(for: settings) else {
            release()
            return
        }
        guard model.isDownloaded else {
            // Not an error state: the model screen is where this is resolved, and a banner
            // saying "failed" would misdescribe a model that was simply never installed.
            release()
            initState = .idle
            return
        }
        do {
            _ = try await ensureLoaded(model, settings)
        } catch {
            // `ensureLoaded` records `.failed` itself; the throw is for `generate` to surface.
            logger.error("LiteRT load failed: \(error.localizedDescription)")
        }
    }

    /// Unloads the resident engine **only if [settings] wants a different one, or wants no
    /// on-device engine at all**. Otherwise it is left exactly where it is.
    ///
    /// That "only" is the whole rule, and it is what the chat screen calls instead of
    /// ``syncTo(_:)``: opening a conversation must not pull gigabytes off disk, but neither may
    /// leaving one throw away a model the next screen is about to use. Moving between the chat
    /// and the call changes no selection and so unloads nothing.
    func releaseIfNotFor(_ settings: EffectiveSettings) {
        guard loaded != nil else { return }
        let wanted = settings.useOnDevice ? keyFor(settings) : nil
        guard wanted != loaded?.key else { return }
        logger.info("Selection changed; unloading \(self.loaded?.modelId ?? "-")")
        release()
    }

    /// Drops the resident engine and frees its weights.
    ///
    /// A no-op while a turn is in flight — the stream holds the conversation, the conversation
    /// holds the engine, and unloading under it is a crash rather than a cancelled turn.
    ///
    /// [ignoringHolds] is for the two callers that must free regardless of who is mid-call:
    /// suspension, and deleting the very model that is loaded.
    func release(ignoringHolds: Bool = true) {
        guard !isGenerating else {
            logger.debug("Skipping release: a turn is in flight")
            return
        }
        guard ignoringHolds || residencyHolds == 0 else {
            logger.debug("Skipping release: \(self.residencyHolds) screen(s) still need the model")
            return
        }
        guard loaded != nil else {
            if initState != .idle { initState = .idle }
            return
        }
        closeLoaded()
    }

    /// Frees the model only if no screen is actively relying on it. The memory-warning path.
    func releaseIfUnused() {
        release(ignoringHolds: false)
    }

    /// Marks the model as needed until the matching ``relinquish()``. Held by the call screen
    /// for the length of a call.
    func retain() {
        residencyHolds += 1
    }

    func relinquish() {
        residencyHolds = max(0, residencyHolds - 1)
    }

    /// Clears a `.failed` state so a screen can offer Retry. The next ``syncTo(_:)`` reloads.
    func clearFailure() {
        if initState.failure != nil { initState = .idle }
    }

    /// Stops all on-device work and gives the weights back, for the app leaving the foreground.
    ///
    /// Not the optional tidy-up it is on Android, where backgrounding deliberately keeps the
    /// engine resident so that taking a photo does not cost a reload. On iOS, work submitted by
    /// a suspended app is not merely paused — the OS terminates the process for it. So a turn
    /// in flight is cancelled and the model unloaded, every time.
    ///
    /// Deliberately *not* `cancelDownload()`: a download runs in `nsurlsessiond`, outside this
    /// process, so suspension does not endanger it and cancelling here only threw away the
    /// user's transfer when they locked the screen.
    func stopForBackground() async {
        let task = generationTask
        generationTask = nil
        task?.cancel()
        _ = await task?.result
        isGenerating = false
        release()
    }

    // MARK: - Downloads

    /// Downloads a model's weights. Unlike MLX — where install and load are one operation —
    /// fetching and loading are separate here, so nothing is warmed until the next `syncTo`.
    func download(_ model: LiteRtModel, token: String) async throws {
        downloadingId = model.id
        speedSample = nil
        downloadState = DownloadState(
            modelId: model.id,
            fraction: 0,
            downloadedBytes: 0,
            totalBytes: model.sizeBytes,
            bytesPerSecond: 0,
            remainingMs: 0
        )
        defer {
            downloadingId = nil
            downloadState = nil
        }
        do {
            try await LiteRtDownloader.shared.download(model, token: token) { [weak self] fraction in
                Task { @MainActor in self?.reportDownload(fraction, of: model.id) }
            }
        } catch {
            logger.error("LiteRT download of \(model.id, privacy: .public) failed: \(String(describing: error))")
            throw error
        }
    }

    /// Publishes progress for [model].
    ///
    /// No monotonic guard: the downloader is the authority on the count, and it sometimes
    /// steps the fraction *back* on purpose — when a server answers a ranged request with
    /// the whole body, the on-disk resume base stops counting and the bar drops to the
    /// transfer's real position. Holding the old maximum there kept the sheet pinned at
    /// 100% while the rest of the body arrived.
    ///
    /// Bytes come from the fraction against the pinned size (the registry knows it); speed
    /// from a sample point refreshed at most once a second, so a burst of callbacks does not
    /// produce a burst of divide-by-tiny-numbers.
    private func reportDownload(_ fraction: Double, of modelId: String) {
        guard let current = downloadState, current.modelId == modelId else { return }
        if Int(fraction * 10) > Int(current.fraction * 10) {
            logger.info("LiteRT download \(Int(fraction * 100), privacy: .public)%: \(modelId, privacy: .public)")
        }

        let downloaded = min(
            Int64((fraction * Double(current.totalBytes)).rounded()),
            current.totalBytes
        )
        var speed = current.bytesPerSecond
        let now = Date()
        if let sample = speedSample {
            let elapsed = now.timeIntervalSince(sample.at)
            if elapsed >= 1.0 {
                let measured = Int64(Double(downloaded - sample.bytes) / elapsed)
                speed = max(0, measured)
                speedSample = (at: now, bytes: downloaded)
            }
        } else {
            speedSample = (at: now, bytes: downloaded)
        }
        let remainingMs = speed > 0
            ? max(0, (current.totalBytes - downloaded) * 1000 / speed)
            : 0

        downloadState = DownloadState(
            modelId: modelId,
            fraction: fraction,
            downloadedBytes: downloaded,
            totalBytes: current.totalBytes,
            bytesPerSecond: speed,
            remainingMs: remainingMs
        )
    }

    /// Stops an install in progress. The bytes that landed stay on disk, so restarting resumes
    /// from them.
    func cancelDownload() {
        guard let id = downloadingId else { return }
        LiteRtDownloader.shared.cancel(modelId: id)
        downloadingId = nil
        speedSample = nil
        downloadState = nil
        initState = .idle
    }

    /// Deletes a model's weights. Unloads it first when it is the resident one.
    ///
    /// The whole directory goes: LiteRT writes its compiled-weights cache next to the model
    /// file, so deleting just the file would leave gigabytes of cache behind and a model that
    /// still "uses" it.
    ///
    /// A transfer in flight for this model dies with it — letting it finish would recreate
    /// the directory the lines below remove and plant weights the user just deleted, which
    /// is exactly the stale leftover that later breaks a fresh download's flush.
    func deleteModel(_ model: LiteRtModel) {
        if downloadingId == model.id { cancelDownload() }
        if loaded?.modelId == model.id { release() }
        try? FileManager.default.removeItem(at: model.directory)
    }

    // MARK: - Engine

    /// The engine [settings] would need for [model]; the identity a resident engine is matched
    /// on.
    private func keyFor(_ settings: EffectiveSettings) -> EngineKey {
        let model = model(for: settings)
        return EngineKey(
            filePath: model?.fileURL.path ?? "",
            accelerator: (settings.litertAccelerator.isEmpty ? model?.defaultAccelerator ?? "cpu" : settings.litertAccelerator),
            maxTokens: Int(settings.litertMaxTokens.trimmed) ?? model?.defaultMaxTokens ?? 4000,
            // The user's toggle, not the registry flag on its own: `EffectiveSettings` resolves
            // `visionAvailable` from the checkpoint for the on-device engine, so this *is* the
            // capability answer here.
            vision: settings.visionAvailable
        )
    }

    /// Brings the resident engine in line with [model]/[settings], loading it if needed.
    ///
    /// The bounce list mirrors Android's `ensureEngine` exactly: the text backend the user asked
    /// for first, CPU as the degraded fallback for a GPU request (LiteRT's GPU path can fail on
    /// hardware it cannot drive, and that is a property of the device, not a mistake the user
    /// made), and finally — for a vision model whose tower will not come up — the same model
    /// without vision rather than no assistant at all. The vision backend is always the model's
    /// own `visionAccelerator` (GPU for the Gemma family); it is **not** derived from the text
    /// backend.
    private func ensureLoaded(_ model: LiteRtModel, _ settings: EffectiveSettings) async throws -> Engine {
        let desiredKey = keyFor(settings)
        if let loaded, loaded.key == desiredKey {
            if initState != .ready(model.displayName) { initState = .ready(model.displayName) }
            return loaded.engine
        }
        closeLoaded()

        let accelerator = settings.litertAccelerator.isEmpty ? model.defaultAccelerator : settings.litertAccelerator

        // Re-check on every load: a filesystem change can remove the file after it was
        // downloaded, and handing a missing path to the runtime crashes the process instead of
        // raising an error.
        guard model.isDownloaded else {
            initState = .failed("Model file is missing or incomplete — download it again.")
            throw LiteRtServiceError.loadFailed("Model file is missing or incomplete")
        }

        let textAttempts = accelerator.lowercased() == "cpu" ? ["cpu"] : ["gpu", "cpu"]
        let wantsVision = desiredKey.vision
        var attempts: [(String, Bool)] = textAttempts.map { ($0, wantsVision) }
        if wantsVision { attempts.append((textAttempts.last ?? "cpu", false)) }

        // The *first* failure is the one worth reporting: it is the configuration the user
        // actually asked for. Reporting the last one made every failure read as the final
        // fallback's error, so changing the accelerator appeared to do nothing at all.
        var firstError: (any Error)?
        for (attempt, withVision) in attempts {
            initState = .loading(model.displayName)
            do {
                let config = try EngineConfig(
                    modelPath: model.fileURL.path,
                    backend: backend(for: attempt),
                    // Non-nil enables the vision tower, on the accelerator the checkpoint
                    // itself demands. Null makes LiteRT cache compiled weights beside the
                    // model file, inside the per-model directory — the same choice Android
                    // makes, so a `deleteModel` really reclaims everything.
                    visionBackend: withVision ? backend(for: model.visionAccelerator) : nil,
                    audioBackend: nil,
                    maxNumTokens: desiredKey.maxTokens,
                    cacheDir: nil
                )
                let engine = Engine(engineConfig: config)
                // The actor hops off the main actor here; `initialize()` blocks its executor
                // for tens of seconds, not the UI.
                try await engine.initialize()
                if attempt != accelerator || !withVision {
                    logger.info("""
                        Loaded \(model.id, privacy: .public) on \(attempt, privacy: .public) \
                        with vision=\(withVision, privacy: .public) \
                        (requested \(accelerator, privacy: .public), vision=\(model.supportsImage, privacy: .public))
                        """)
                }
                visionActive = withVision
                // Keyed on what was *requested*, not on what loaded. Storing the fallback
                // would make every subsequent turn compare "cpu" against the requested "gpu",
                // miss, and reload the whole model.
                loaded = Loaded(modelId: model.id, key: desiredKey, engine: engine)
                initState = .ready(model.displayName)
                return engine
            } catch {
                logger.error("Engine init failed for \(model.id) on \(attempt) (vision=\(withVision)): \(String(describing: error))")
                if firstError == nil { firstError = error }
            }
        }

        let message = firstError?.localizedDescription ?? "Model failed to load"
        initState = .failed(message)
        throw LiteRtServiceError.loadFailed("Could not load \(model.displayName): \(message)")
    }

    private func backend(for accelerator: String) -> Backend {
        accelerator.lowercased() == "gpu" ? .gpu : .cpu()
    }

    private func closeLoaded() {
        loaded = nil
        activeConversation = nil
        visionActive = false
        initState = .idle
    }

    // MARK: - Generation

    /// Streams one assistant turn. Called only by `LiteRtInferenceEngine`.
    ///
    /// Throws ``LiteRtServiceError/noModelSelected`` when the thread is on-device with nothing
    /// selected, and whatever the runtime throws when a load or a generation fails — either way
    /// the message reaches the chat, and ``initState`` records it for the banner.
    func generate(
        messages: [LLMMessage],
        systemPrompt: String,
        settings: EffectiveSettings,
        onChunk: ((String) -> Void)?
    ) async throws -> GenerationResult {
        guard let model = model(for: settings) else {
            throw LiteRtServiceError.noModelSelected
        }

        // Cancel rather than queue: `LLMService` allows one turn at a time and cancels the
        // previous itself, so anything still running here is a leftover.
        generationTask?.cancel()

        let engine: Engine
        do {
            engine = try await ensureLoaded(model, settings)
        } catch {
            throw error
        }

        // The last user turn is what we send; everything before it seeds the conversation.
        // History replay is text-only, which is exactly right here: `consolidateMessages`
        // already strips images from all but the newest message.
        let lastUserIdx = messages.lastIndex { $0.role == LLMMessage.LLMRole.user }
        let history: [LLMMessage] = lastUserIdx.map { Array(messages[..<$0]) } ?? messages
        let current = lastUserIdx.map { messages[$0] }

        let conversation = try await engine.createConversation(with: ConversationConfig(
            systemMessage: LiteRtChat.systemMessage(systemPrompt),
            initialMessages: LiteRtChat.historyMessages(for: history),
            // Tools are not wired to this engine — see `LiteRtInferenceEngine`. An empty list
            // is the safe choice: with no tools declared the model cannot emit a tool call the
            // runtime would fail to handle, which is why streaming needs no tool loop.
            tools: [],
            samplerConfig: model.samplerSettings(overriddenBy: settings),
            enableToolCallStreaming: false,
            automaticToolCalling: true,
            enableResponseFormat: false
            // No `visualTokenBudget` — nil lets the checkpoint's own config decide how many
            // patches an image gets, which is what the gallery and Android both do. A hard-coded
            // value here was read as a per-image *token* budget, not an image count: passing 10
            // capped every image at 10 × 9 = 90 patches, so on-device vision could not read text
            // that the same attachment's remote turn handled fine.
        ))
        activeConversation = conversation

        let turnContents = Message(
            contents: LiteRtChat.turnContents(for: current, supportsImage: visionActive),
            role: .user
        )
        // `visionActive`, not `supportsImage`: the engine may have been loaded without vision
        // because its required backend was unavailable.
        let enableThinking = model.supportsThinking && settings.litertEnableThinking
        // Passed on every turn, even when false: a checkpoint that thinks by default must not
        // quietly ignore the setting. Models without a thinking mode ignore the flag entirely.
        let thinkingConfig = ThinkingConfig(enableThinking: enableThinking)

        isGenerating = true
        let task = Task<GenerationResult, any Error> {
            var answer = ""
            var reasoning = ""
            do {
                let stream = conversation.sendMessageStream(turnContents, thinkingConfig: thinkingConfig)
                for try await chunk in stream {
                    try Task.checkCancellation()
                    let delta = chunk.toString
                    // Control tokens are the model talking to the runtime, not to the user.
                    if delta.hasPrefix(controlTokenPrefix) { continue }
                    if let thought = chunk.channels[thoughtChannel] {
                        reasoning.append(thought)
                    }
                    if !delta.isEmpty {
                        answer.append(delta)
                        onChunk?(delta)
                    }
                }
            } catch {
                // A turn we cancelled surfaces as whatever the runtime threw in response, not
                // as a `CancellationError`. If we were the ones who cancelled, that is the
                // cancel's doing and reads as one; anything else is a real failure.
                throw Task.isCancelled ? CancellationError() : error
            }
            return GenerationResult(
                content: answer.trimmingCharacters(in: .whitespacesAndNewlines),
                reasoning: reasoning.trimmingCharacters(in: .whitespacesAndNewlines),
                toolCalls: []
            )
        }
        generationTask = task
        defer {
            isGenerating = false
            generationTask = nil
            activeConversation = nil
        }
        return try await task.value
    }

    /// Aborts the in-flight turn, at the native level as well as in Swift. Safe when idle.
    func cancel() {
        generationTask?.cancel()
        generationTask = nil
        // `cancel_process` is a blocking native call; the tap that gets here is on the main
        // thread, so the runtime is told from elsewhere. The conversation is still referenced
        // by the stream while it runs, so calling into it here cannot reach freed memory.
        if let conversation = activeConversation, conversation.isAlive {
            Task.detached(priority: .userInitiated) { try? conversation.cancel() }
        }
    }
}

private extension String {
    var trimmed: String { trimmingCharacters(in: .whitespaces) }
}

/// Failures the service raises itself, as opposed to the ones the runtime throws.
enum LiteRtServiceError: LocalizedError {
    case noModelSelected
    case loadFailed(String)

    var errorDescription: String? {
        switch self {
        case .noModelSelected:
            String(localized: "No on-device model is selected.")
        case .loadFailed(let message):
            message
        }
    }
}