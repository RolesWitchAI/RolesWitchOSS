//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.app

import ai.roleswitch.android.data.database.AppDatabase
import ai.roleswitch.android.data.model.Message
import ai.roleswitch.android.data.model.MessageThread
import ai.roleswitch.android.service.LLMService
import ai.roleswitch.android.service.OAIService
import ai.roleswitch.android.service.mcp.McpService
import ai.roleswitch.android.settings.EffectiveSettings
import ai.roleswitch.android.settings.SettingsManager
import ai.roleswitch.android.settings.decodeToolIds

/**
 * Top-level dependency container shared across platforms. Construct via
 * platform-specific factories ([createAppContainer] on iOS, [RolesWitchApp] on Android).
 */
class AppContainer(
    val database: AppDatabase,
    val settingsManager: SettingsManager,
    val oaiService: OAIService,
    val llmService: LLMService,
    /** On-device MCP client. iOS has no Tools UI yet, but the loop is already wired. */
    val mcpService: McpService
) {
    // Helper for Swift — Kotlin default args are not exported to ObjC/Swift.
    fun createThreadWithPrompt(title: String, prompt: String): MessageThread =
        MessageThread(title = title, prompt = prompt)

    /** Placeholder thread used by iOS to resolve [EffectiveSettings] before the row
     *  has finished loading from Room (`MessageThread`'s default args don't export
     *  to ObjC/Swift). Carries only the id; `useCustomSettings` defaults to false so
     *  resolution falls through to the global settings. */
    fun threadForId(threadId: String): MessageThread =
        MessageThread(threadId = threadId, title = "")

    fun createMessage(content: String, isUser: Boolean, threadId: String?): Message =
        Message(content = content, isUser = isUser, threadId = threadId)

    /**
     * Swift-friendly facade for writing per-thread settings overrides. Kotlin data-class
     * `copy(...)` does not export to ObjC/Swift (19-arg constructor, no default args), so
     * iOS cannot mutate an [EffectiveSettings] directly. This takes every field iOS
     * surfaces (14 intelligence + misc + 9 audio: language/voice/useVosk/
     * allowInterruptions/useSystemTts/useExternalTts/externalTtsVoice/externalTtsAPI/
     * externalTtsKey + 7 on-device:
     * useOnDevice and the six `litert*` model/sampler/
     * accelerator/toggle fields) as plain values, fills `requireOnDeviceVoice`
     * from the current global (iOS never edits it — `useVosk`
     * is its offline toggle, and the on-device-voice preference has no iOS surface),
     * builds the snapshot, and delegates to [AppDatabase.applyThreadOverrides].
     * Tools are not in scope here — they are not part of the
     * custom-settings block and have their own facades.
     *
     * Callers resolve the current effective settings via
     * `EffectiveSettings.resolve(thread, settingsManager)` for display, mutate one field
     * in Swift local state, then pass all fields back here for an immediate save
     * (matching Android's per-field `setThread { it.copy(...) }` semantics).
     */
    suspend fun applyThreadOverrides(
        thread: MessageThread,
        apiUrl: String,
        apiKey: String,
        model: String,
        enableVision: Boolean,
        messageLimit: Int,
        temperature: String,
        topP: String,
        n: String,
        maxCompletionTokens: String,
        presencePenalty: String,
        frequencyPenalty: String,
        reasoningEffort: String,
        embedTime: Boolean,
        keepLastAttachment: Boolean,
        streamingMode: Boolean,
        language: String,
        voice: String,
        useVosk: Boolean,
        allowInterruptions: Boolean,
        useSystemTts: Boolean,
        useExternalTts: Boolean,
        externalTtsVoice: String,
        externalTtsAPI: String,
        externalTtsKey: String,
        useOnDevice: Boolean,
        litertModel: String,
        litertTopK: String,
        litertTopP: String,
        litertTemperature: String,
        litertMaxTokens: String,
        litertAccelerator: String,
        litertEnableThinking: Boolean
    ) {
        val g = settingsManager
        database.applyThreadOverrides(
            thread,
            EffectiveSettings(
                openaiAPI = apiUrl,
                openaiKey = apiKey,
                selectedModel = model,
                enableVision = enableVision,
                messageLimit = messageLimit,
                temperature = temperature,
                topP = topP,
                n = n,
                maxCompletionTokens = maxCompletionTokens,
                presencePenalty = presencePenalty,
                frequencyPenalty = frequencyPenalty,
                reasoningEffort = reasoningEffort,
                embedTime = embedTime,
                keepLastAttachment = keepLastAttachment,
                streamingMode = streamingMode,
                language = language,
                voice = voice,
                requireOnDeviceVoice = g.requireOnDeviceVoice.value,
                useVosk = useVosk,
                allowInterruptions = allowInterruptions,
                useSystemTts = useSystemTts,
                // The external-speech fields were seeded from the current global until the
                // TTS engine selector landed on iOS; they are now explicit params, edited
                // by the thread settings screen like every other override. The speech
                // server (API + key) is per-thread overridable too, and stays decoupled
                // from the inference endpoint/token.
                useExternalTts = useExternalTts,
                externalTtsVoice = externalTtsVoice,
                externalTtsAPI = externalTtsAPI,
                externalTtsKey = externalTtsKey,
                // Passed through unchanged and ignored: the tool selection sits outside the
                // custom-settings block, so `withCustomSettingsEnabled` no longer consumes it.
                // It is edited on its own screen, via [AppDatabase.applyThreadToolSelection].
                enabledToolIds = decodeToolIds(thread.customToolIds),
                // On-device vs remote is engine-agnostic, so iOS does edit this one.
                useOnDevice = useOnDevice,
                // iOS's on-device engine, written from this facade. The legacy `customMlx*`
                // columns were dropped by migration 21_22, so the snapshot rewrites only the
                // `customLitert*` block.
                litertModel = litertModel,
                litertTopK = litertTopK,
                litertTopP = litertTopP,
                litertTemperature = litertTemperature,
                litertMaxTokens = litertMaxTokens,
                litertAccelerator = litertAccelerator,
                litertEnableThinking = litertEnableThinking
            )
        )
    }
}
