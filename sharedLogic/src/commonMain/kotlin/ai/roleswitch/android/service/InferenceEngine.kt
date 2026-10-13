//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service

import ai.roleswitch.android.service.mcp.McpToolSpec
import ai.roleswitch.android.settings.EffectiveSettings

/**
 * One backend capable of producing an assistant turn.
 *
 * [LLMService] owns everything around a turn — history trimming, the MCP session,
 * persistence, the streaming flows — and delegates only the generation itself here,
 * so the two implementations stay interchangeable:
 *
 *  - [OAIService] (`commonMain`) talks to an OpenAI-compatible endpoint.
 *  - `LiteRtService` (`:androidApp`) runs a LiteRT-LM model on the device.
 *  - `LiteRtInferenceEngine` (`iosApp/iosApp/LiteRT/`) wraps a LiteRT-LM model on the
 *    device — in Swift.
 *
 * The whole seam is `public` on purpose. `:androidApp` is a separate Gradle module,
 * so `internal` declarations in `:sharedLogic` are not visible to it and an
 * Android-only implementation could not exist otherwise. It is also what lets the iOS
 * implementation be written in Swift: `public` is what puts this interface in the ObjC
 * header, where a Swift class can conform to it and be passed back in through
 * `createAppContainer(onDeviceEngine:)`.
 *
 * Configuration is passed per turn as [EffectiveSettings] rather than through a
 * separate `setConfig` step: each implementation reads the fields it cares about,
 * and there is no window in which an engine holds settings from a different thread.
 */
interface InferenceEngine {
    /**
     * Streams one assistant turn.
     *
     * @param messages the already-trimmed history; only the most recent entry may
     *   carry images (see `LLMService.consolidateMessages`).
     * @param systemPrompt the resolved system prompt for the thread.
     * @param settings the thread's effective settings — endpoint/model/sampler.
     * @param onChunk invoked with each incremental piece of the reply.
     * @param toolSpecs functions to advertise; empty disables tool calling.
     * @param onToolCall true when a tool call starts, false once the round is done.
     * @param callTool executes a tool call and returns its result as text. Expected
     *   to report failures as text rather than throwing.
     */
    suspend fun generate(
        messages: List<LLMMessage>,
        systemPrompt: String,
        settings: EffectiveSettings,
        onChunk: ((String) -> Unit)?,
        toolSpecs: List<McpToolSpec> = emptyList(),
        onToolCall: ((Boolean) -> Unit)? = null,
        callTool: (suspend (String, String) -> String)? = null
    ): GenerationResult

    /** Aborts the in-flight generation, if any. Safe to call when idle. */
    fun cancel()
}
