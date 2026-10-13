//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import Foundation
import SharedLogic

/// Plugs ``LiteRtService`` into the shared `LLMService` as its on-device engine.
///
/// Swift conforms to the exported `InferenceEngine` protocol directly — the same trick
/// `ClosureFlowCollector` uses for `FlowCollector` — so `LLMService` cannot tell which engine
/// ran, and everything around a turn (history trimming, the `ON_DEVICE_MESSAGE_LIMIT` cap,
/// persistence, the streaming flows) stays in `commonMain` exactly as it is for `OAIService`
/// and `LiteRtService` on Android.
///
/// Kotlin calls `generate` from a coroutine on an arbitrary thread, so this type is *not*
/// main-actor isolated; it hops onto ``LiteRtService``'s actor itself and completes the handler
/// from there.
final class LiteRtInferenceEngine: NSObject, InferenceEngine {
    private let service: LiteRtService

    init(service: LiteRtService) {
        self.service = service
        super.init()
    }

    func generate(
        messages: [LLMMessage],
        systemPrompt: String,
        settings: EffectiveSettings,
        onChunk: ((String) -> Void)?,
        toolSpecs: [McpToolSpec],
        onToolCall: ((KotlinBoolean) -> Void)?,
        callTool: (any KotlinSuspendFunction2)?,
        completionHandler: @escaping (GenerationResult?, (any Error)?) -> Void
    ) {
        // `toolSpecs`, `onToolCall` and `callTool` are accepted and ignored: MCP tools are not
        // wired to the on-device engine, and a thread with tools selected answers without them
        // rather than failing. Android's `LiteRtService` reaches the same endpoints by declaring
        // each MCP function natively as an `OpenApiTool`; this engine declares none (see
        // `LiteRtService.generate`, which builds its conversation with `tools: []` — with no
        // tools registered the model can never emit a tool call for the runtime to choke on).
        Task { @MainActor in
            do {
                let result = try await service.generate(
                    messages: messages,
                    systemPrompt: systemPrompt,
                    settings: settings,
                    onChunk: onChunk
                )
                completionHandler(result, nil)
            } catch is CancellationError {
                // The turn was stopped. `LLMService` already cleared its flags synchronously
                // and treats a turn that persists nothing as discarded, so an empty result is
                // the quiet way out; an error here would raise a "failed" alert for a Stop tap.
                completionHandler(GenerationResult(content: "", reasoning: "", toolCalls: []), nil)
            } catch {
                // Kotlin resumes the coroutine by throwing this, and it travels all the way
                // out through `LLMService.sendMessage` to the `catch` in `MessagesView`. Should
                // the message not survive the round trip, `LiteRtService.initState` carries the
                // same text to the warm-up banner independently.
                completionHandler(nil, error)
            }
        }
    }

    func cancel() {
        Task { @MainActor in service.cancel() }
    }
}