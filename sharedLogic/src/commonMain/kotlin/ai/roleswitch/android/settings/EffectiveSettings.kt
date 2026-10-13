//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.settings

import ai.roleswitch.android.data.model.MessageThread
import ai.roleswitch.android.service.LitertModel
import ai.roleswitch.android.service.LitertModels

/**
 * Fully-resolved settings for a thread: per-thread overrides when [MessageThread.useCustomSettings]
 * is on, otherwise the global values from [SettingsManager]. All fields are non-null — consumers
 * (LLMService, AssistantScreen STT/TTS) read exclusively from this.
 *
 * [enabledToolIds] is the exception: it sits outside the custom-settings block and resolves
 * from the thread's own column whichever way that switch is set. See [resolve].
 */
data class EffectiveSettings(
    val openaiAPI: String,
    val openaiKey: String,
    val selectedModel: String,
    val enableVision: Boolean,
    val messageLimit: Int,
    val temperature: String,
    val topP: String,
    val n: String,
    val maxCompletionTokens: String,
    val presencePenalty: String,
    val frequencyPenalty: String,
    /** `reasoning_effort`; empty means the field is left out of the request. See [SettingsManager.reasoningEffort]. */
    val reasoningEffort: String,
    val language: String,
    val voice: String,
    val requireOnDeviceVoice: Boolean,
    val useVosk: Boolean,
    val allowInterruptions: Boolean,
    val embedTime: Boolean,
    val keepLastAttachment: Boolean,
    val useSystemTts: Boolean,
    /**
     * Synthesize speech through the OpenAI-compatible `/v1/audio/speech` endpoint
     * instead of a local engine. Together with [useSystemTts] it selects the
     * speech engine: external wins over system, and the derived [ttsMode] is the
     * single source of truth the callers read (so the engine preference and the
     * "Process on device" toggle cannot disagree — see [ttsMode]).
     */
    val useExternalTts: Boolean,
    /**
     * The voice name sent to the external speech server, e.g. `"alloy"`. Empty
     * means "let the server pick its default voice" — it is a server-side id, not
     * one of the local engine's voices, so it is stored and resolved separately
     * from [voice] and is never cleared by an engine or language change.
     */
    val externalTtsVoice: String,
    /**
     * The external speech server's OpenAI-compatible base URL. Global-only: there is no
     * per-thread speech server column — the voice ([externalTtsVoice]) is the only external
     * speech override. Deliberately separate from [openaiAPI]: inference and speech may run
     * on different servers, and the speech call must never inherit the inference endpoint
     * or token (or clobber the inference model choice).
     */
    val externalTtsAPI: String,
    /** The token for [externalTtsAPI]; may be empty for local speech servers. */
    val externalTtsKey: String,
    /**
     * Whether replies stream into the chat as they are generated (true, the app's
     * original behaviour — Stop button, partial text) or appear whole only when a
     * turn completes (false: the thinking balloon is all the user sees, and sending
     * while a turn is in flight cancels it and starts another).
     */
    val streamingMode: Boolean,
    /**
     * Which MCP endpoints this thread may call, as `mcp_tools` ids. The only tool control
     * there is — an endpoint that resolves in here is one the turn may call, full stop.
     *
     * Empty means no tools, and it is the default: `mcp_tools` is a registry, so a chat that
     * has never opted in calls nothing. There is no global flag to inherit from.
     *
     * Independent of [MessageThread.useCustomSettings]: which endpoints a conversation may call
     * is a property of that conversation, not of whether it overrides the model/audio settings.
     */
    val enabledToolIds: List<String>,
    /**
     * Run inference on device instead of against the OpenAI-compatible endpoint.
     *
     * Which on-device engine that means is the platform's business: LiteRT-LM everywhere,
     * with the `litert*` fields below carrying the selection. The name predates the engine
     * swap on iOS (where MLX's `mlx*` fields once lived); the flag itself has always been
     * engine-agnostic — `LLMService` reads it to pick `onDeviceEngine`, and caps
     * `messageLimit` at `ON_DEVICE_MESSAGE_LIMIT` for either one.
     */
    val useOnDevice: Boolean,
    /** Selected [ai.roleswitch.android.service.LitertModel] id; empty means none. */
    val litertModel: String,
    val litertTopK: String,
    val litertTopP: String,
    val litertTemperature: String,
    val litertMaxTokens: String,
    val litertAccelerator: String,
    /**
     * The runtime's `enable_thinking` flag. Only meaningful when the resolved
     * [litertModelSpec] declares `supportsThinking`; other checkpoints ignore it.
     */
    val litertEnableThinking: Boolean,
) {
    /** The on-device model this thread resolves to, or null when none is selected. */
    val litertModelSpec: LitertModel?
        get() = LitertModels.get(litertModel)

    /**
     * The speech engine this thread actually speaks with.
     *
     * EXTERNAL wins over SYSTEM (the mode picker keeps them mutually exclusive, so a
     * stored combination can only come from older state). Recognition and synthesis are
     * independent choices: [requireOnDeviceVoice] narrows the *recognizer* and the system
     * engine's voice list, but it does not gate the engine itself — an external synth is
     * a deliberate network choice and may be combined with on-device recognition.
     */
    val ttsMode: TtsMode
        get() = when {
            useExternalTts -> TtsMode.EXTERNAL
            useSystemTts -> TtsMode.SYSTEM
            else -> TtsMode.AUTO
        }

    /**
     * Whether this thread may *offer* to send images.
     *
     * Off device it is the user's choice, [enableVision], and nothing else can be known: the
     * endpoint's model list says nothing about vision.
     *
     * On device the registry gets a say, and only one: a checkpoint marked
     * [LitertModel.supportsImage] takes images whatever the preference says — that is what the
     * model is for — but the absence of the flag decides nothing, because whether a vision
     * tower really exists is not knowable until one is built (a model added by URL is recorded
     * as text-only simply because nobody has looked; see `HuggingFaceModelResolver`). So the
     * rest still ask the user.
     *
     * Where the answer comes from a preference it is intent, not capability, and
     * `LiteRtService.visionActive` — what the resident engine actually brought up — is what it
     * is checked against. The two disagreeing is what raises "this model does not support
     * images" instead of an image being dropped in silence.
     */
    val visionAvailable: Boolean
        get() {
            if (!useOnDevice) return enableVision
            litertModelSpec?.let { return it.supportsImage || enableVision }
            return enableVision
        }

    /**
     * Whether this thread has enough configuration to send a message at all.
     *
     * What "configured" means depends entirely on the engine — on device there is no
     * endpoint and no API token, only a selected model — so both screens must ask here
     * rather than testing the OpenAI fields themselves, or a fully working on-device
     * thread reports itself as unconfigured.
     */
    val isConfigured: Boolean
        get() = if (useOnDevice) {
            litertModelSpec != null
        } else {
            openaiAPI.isNotEmpty() && selectedModel.isNotEmpty()
        }

    companion object {
        /**
         * Resolves the effective settings for [thread]. When [MessageThread.useCustomSettings]
         * is true, each non-null `custom*` column wins; otherwise (or if a column was never
         * snapshotted) the [global] value is used. [enabledToolIds] ignores that switch —
         * see below.
         */
        fun resolve(thread: MessageThread, global: SettingsManager): EffectiveSettings {
            val on = thread.useCustomSettings
            fun <T> pick(custom: T?, globalValue: T): T = if (on && custom != null) custom else globalValue
            return EffectiveSettings(
                openaiAPI = pick(thread.customApiUrl, global.openaiAPI.value),
                openaiKey = pick(thread.customApiKey, global.openaiKey.value),
                selectedModel = pick(thread.customModel, global.selectedModel.value),
                enableVision = pick(thread.customEnableVision, global.enableVision.value),
                messageLimit = pick(thread.customMessageLimit, global.messageLimit.value),
                temperature = pick(thread.customTemperature, global.temperature.value),
                topP = pick(thread.customTopP, global.topP.value),
                n = pick(thread.customN, global.n.value),
                maxCompletionTokens = pick(thread.customMaxCompletionTokens, global.maxCompletionTokens.value),
                presencePenalty = pick(thread.customPresencePenalty, global.presencePenalty.value),
                frequencyPenalty = pick(thread.customFrequencyPenalty, global.frequencyPenalty.value),
                reasoningEffort = pick(thread.customReasoningEffort, global.reasoningEffort.value),
                language = pick(thread.customLanguage, global.language.value),
                voice = pick(thread.customVoice, global.voice.value),
                requireOnDeviceVoice = pick(thread.customRequireOnDeviceVoice, global.requireOnDeviceVoice.value),
                useVosk = pick(thread.customUseVosk, global.useVosk.value),
                allowInterruptions = pick(thread.customAllowInterruptions, global.allowInterruptions.value),
                embedTime = pick(thread.customEmbedTime, global.embedTime.value),
                keepLastAttachment = pick(thread.customKeepLastAttachment, global.keepLastAttachment.value),
                useSystemTts = pick(thread.customUseSystemTts, global.useSystemTts.value),
                useExternalTts = pick(thread.customUseExternalTts, global.useExternalTts.value),
                externalTtsVoice = pick(thread.customExternalTtsVoice, global.externalTtsVoice.value),
                // The speech server itself *is* per-thread overridable (like the engine and
                // the voice): a thread may run its voice through a different host than its
                // chat, and it is still decoupled from the inference endpoint/token.
                externalTtsAPI = pick(thread.customExternalTtsAPI, global.externalTtsAPI.value),
                externalTtsKey = pick(thread.customExternalTtsKey, global.externalTtsKey.value),
                streamingMode = pick(thread.customStreamingMode, global.streamingMode.value),
                // Deliberately outside `pick`: the tool selection is not part of the
                // custom-settings override block, so a thread's own list applies whether or not
                // it overrides the model/audio settings. There is nothing to fall back to
                // either — `mcp_tools` only registers endpoints, it does not enable them.
                enabledToolIds = decodeToolIds(thread.customToolIds),
                useOnDevice = pick(thread.customUseLiteRt, global.useOnDevice.value),
                litertModel = pick(thread.customLitertModel, global.litertModel.value),
                litertTopK = pick(thread.customLitertTopK, global.litertTopK.value),
                litertTopP = pick(thread.customLitertTopP, global.litertTopP.value),
                litertTemperature = pick(thread.customLitertTemperature, global.litertTemperature.value),
                litertMaxTokens = pick(thread.customLitertMaxTokens, global.litertMaxTokens.value),
                litertAccelerator = pick(thread.customLitertAccelerator, global.litertAccelerator.value),
                litertEnableThinking = pick(thread.customLitertEnableThinking, global.litertEnableThinking.value)
            )
        }

        /** A point-in-time snapshot of every global setting, used to populate a thread's `custom*`
         *  columns the moment the user enables custom settings (so they start editing from current values). */
        fun global(global: SettingsManager): EffectiveSettings = EffectiveSettings(
            openaiAPI = global.openaiAPI.value,
            openaiKey = global.openaiKey.value,
            selectedModel = global.selectedModel.value,
            enableVision = global.enableVision.value,
            messageLimit = global.messageLimit.value,
            temperature = global.temperature.value,
            topP = global.topP.value,
            n = global.n.value,
            maxCompletionTokens = global.maxCompletionTokens.value,
            presencePenalty = global.presencePenalty.value,
            frequencyPenalty = global.frequencyPenalty.value,
            reasoningEffort = global.reasoningEffort.value,
            language = global.language.value,
            voice = global.voice.value,
            requireOnDeviceVoice = global.requireOnDeviceVoice.value,
            useVosk = global.useVosk.value,
            allowInterruptions = global.allowInterruptions.value,
            embedTime = global.embedTime.value,
            keepLastAttachment = global.keepLastAttachment.value,
            useSystemTts = global.useSystemTts.value,
            useExternalTts = global.useExternalTts.value,
            externalTtsVoice = global.externalTtsVoice.value,
            externalTtsAPI = global.externalTtsAPI.value,
            externalTtsKey = global.externalTtsKey.value,
            streamingMode = global.streamingMode.value,
            // No global counterpart: tools are chosen per chat, never app-wide.
            enabledToolIds = emptyList(),
            useOnDevice = global.useOnDevice.value,
            litertModel = global.litertModel.value,
            litertTopK = global.litertTopK.value,
            litertTopP = global.litertTopP.value,
            litertTemperature = global.litertTemperature.value,
            litertMaxTokens = global.litertMaxTokens.value,
            litertAccelerator = global.litertAccelerator.value,
            litertEnableThinking = global.litertEnableThinking.value
        )
    }
}

/**
 * Serializes a tool-id selection for [MessageThread.customToolIds]. An empty selection stores
 * as null rather than `""`, so "this chat calls nothing" is the same value a freshly created
 * thread already carries — one representation, not two.
 */
fun encodeToolIds(ids: List<String>): String? = ids.takeIf { it.isNotEmpty() }?.joinToString(",")

/** Parses [MessageThread.customToolIds]. Null, blank and `""` all mean no tools. */
fun decodeToolIds(raw: String?): List<String> =
    raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

/** Returns a copy of this thread with every `custom*` column populated from [snapshot] and
 *  [MessageThread.useCustomSettings] turned on. Used by the "Use custom settings" toggle.
 *
 *  `customToolIds` is pointedly left alone: the tool selection lives outside this switch, so
 *  flipping it must not overwrite a selection the thread already made. [snapshot]'s
 *  [EffectiveSettings.enabledToolIds] is ignored here. */
fun MessageThread.withCustomSettingsEnabled(snapshot: EffectiveSettings): MessageThread = copy(
    useCustomSettings = true,
    customApiUrl = snapshot.openaiAPI,
    customApiKey = snapshot.openaiKey,
    customModel = snapshot.selectedModel,
    customEnableVision = snapshot.enableVision,
    customMessageLimit = snapshot.messageLimit,
    customTemperature = snapshot.temperature,
    customTopP = snapshot.topP,
    customN = snapshot.n,
    customMaxCompletionTokens = snapshot.maxCompletionTokens,
    customPresencePenalty = snapshot.presencePenalty,
    customFrequencyPenalty = snapshot.frequencyPenalty,
    customReasoningEffort = snapshot.reasoningEffort,
    customLanguage = snapshot.language,
    customVoice = snapshot.voice,
    customRequireOnDeviceVoice = snapshot.requireOnDeviceVoice,
    customUseVosk = snapshot.useVosk,
    customAllowInterruptions = snapshot.allowInterruptions,
    customEmbedTime = snapshot.embedTime,
    customKeepLastAttachment = snapshot.keepLastAttachment,
    customUseSystemTts = snapshot.useSystemTts,
    customUseExternalTts = snapshot.useExternalTts,
    customExternalTtsVoice = snapshot.externalTtsVoice,
    customExternalTtsAPI = snapshot.externalTtsAPI,
    customExternalTtsKey = snapshot.externalTtsKey,
    customStreamingMode = snapshot.streamingMode,
    customUseLiteRt = snapshot.useOnDevice,
    customLitertModel = snapshot.litertModel,
    customLitertTopK = snapshot.litertTopK,
    customLitertTopP = snapshot.litertTopP,
    customLitertTemperature = snapshot.litertTemperature,
    customLitertMaxTokens = snapshot.litertMaxTokens,
    customLitertAccelerator = snapshot.litertAccelerator,
    customLitertEnableThinking = snapshot.litertEnableThinking
)

/**
 * Returns a copy of this thread with [modelId] selected for on-device inference and the
 * `customLitert*` sampler columns filled from that checkpoint's defaults.
 *
 * The per-thread twin of [SettingsManager.selectLitertModel] — same rule, same reason:
 * the samplers belong to the checkpoint, so selecting a model replaces them rather than
 * inheriting the previous model's tuning. An unknown id writes the id alone, leaving the
 * columns to fall back to the global values.
 */
fun MessageThread.withLitertModelSelected(modelId: String): MessageThread {
    val model = LitertModels.get(modelId) ?: return copy(customLitertModel = modelId)
    return copy(
        customLitertModel = modelId,
        customLitertTopK = model.defaultTopK.toString(),
        customLitertTopP = model.defaultTopP.toString(),
        customLitertTemperature = model.defaultTemperature.toString(),
        customLitertMaxTokens = model.defaultMaxTokens.toString(),
        customLitertAccelerator = model.defaultAccelerator
    )
}

/**
 * The speech engine choice, as distinct from the two booleans that store it:
 * "auto" is the in-app voice with neither flag set, "system" speaks through the
 * OS engine, and "external" synthesizes through the OpenAI-compatible
 * `/v1/audio/speech` endpoint (see [EffectiveSettings.ttsMode]).
 *
 * iOS has no separate auto option — its TTS is natively the system voice, so its
 * selector offers only SYSTEM and EXTERNAL and AUTO is what "neither" resolves to.
 */
enum class TtsMode { AUTO, SYSTEM, EXTERNAL }
