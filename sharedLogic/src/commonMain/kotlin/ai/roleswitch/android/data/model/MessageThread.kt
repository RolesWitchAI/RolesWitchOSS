//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.datetime.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Entity(tableName = "message_threads")
@OptIn(ExperimentalUuidApi::class)
data class MessageThread(
    @PrimaryKey val threadId: String = Uuid.random().toString(),
    val title: String,
    val prompt: String = "",
    val avatarBytes: ByteArray? = null,
    /**
     * Per-thread Live2D character: the id of a bundled Cubism model
     * (see the Android-only `Live2dCharacters` registry). Null means the thread
     * presents itself through its [avatarBytes] photo; non-null means the call
     * screen renders the character instead. Android-only — iOS never reads it.
     */
    val cubismId: String? = null,
    val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
    val updatedAt: Long = Clock.System.now().toEpochMilliseconds(),
    val useCustomSettings: Boolean = false,
    val customApiUrl: String? = null,
    val customApiKey: String? = null,
    val customModel: String? = null,
    val customEnableVision: Boolean? = null,
    val customMessageLimit: Int? = null,
    val customTemperature: String? = null,
    val customTopP: String? = null,
    val customN: String? = null,
    val customMaxCompletionTokens: String? = null,
    val customPresencePenalty: String? = null,
    val customFrequencyPenalty: String? = null,
    val customReasoningEffort: String? = null,
    val customLanguage: String? = null,
    val customVoice: String? = null,
    val customRequireOnDeviceVoice: Boolean? = null,
    val customUseVosk: Boolean? = null,
    val customAllowInterruptions: Boolean? = null,
    val customEmbedTime: Boolean? = null,
    val customKeepLastAttachment: Boolean? = null,
    val customUseSystemTts: Boolean? = null,
    /**
     * Per-thread "External (OpenAI) speech" override — whether this chat synthesizes
     * speech through the `/v1/audio/speech` endpoint rather than a local engine. Follows
     * the same rule as every `custom*` column: null inherits the global value. Mutually
     * exclusive with [customUseSystemTts] at runtime (see `EffectiveSettings.ttsMode`).
     *
     * A thread that had external selected while the global "Process on device" toggle is
     * on renders as AUTO — the derived mode guards that invariant even when the columns
     * disagree with one another.
     */
    val customUseExternalTts: Boolean? = null,
    /**
     * Per-thread voice name for the external speech server. Server-side id, unrelated to
     * [customVoice]; null inherits the global value, and empty means "server default".
     */
    val customExternalTtsVoice: String? = null,
    /**
     * Per-thread speech server for the OpenAI-compatible `/v1/audio/speech` endpoint —
     * URL and token, independent of the inference server ([customApiUrl]/[customApiKey]):
     * a thread may run its voice through a different host than its chat. Null inherits
     * the global values, like every other `custom*` column.
     */
    val customExternalTtsAPI: String? = null,
    val customExternalTtsKey: String? = null,
    /**
     * Per-thread "Streaming mode" override — whether this chat streams replies as they
     * are generated or shows them whole when a turn completes. Follows the same rule as
     * every `custom*` column: null/blank inherits the global value.
     */
    val customStreamingMode: Boolean? = null,
    /**
     * Per-thread tool selection: a comma-separated list of `mcp_tools` ids.
     *
     * Unlike the other overrides this is not "a different value for a global setting" —
     * there is no global counterpart. [McpTool] only registers endpoints, so this column
     * is the sole answer to "may this chat call that server". Null (the default, and what
     * a new thread carries) means no tools at all; a chat opts in one id at a time.
     *
     * Also unlike the other overrides, it applies whether or not [useCustomSettings] is on.
     */
    val customToolIds: String? = null,
    /**
     * On-device inference overrides. `customUseLiteRt` picks on-device vs remote for this
     * thread; the `customLitert*` columns mirror the global `litert*` settings — blank/null
     * means "inherit", exactly like every other `custom*` column. The legacy `customMlx*`
     * columns were dropped by migration 21_22.
     */
    val customUseLiteRt: Boolean? = null,
    val customLitertModel: String? = null,
    val customLitertTopK: String? = null,
    val customLitertTopP: String? = null,
    val customLitertTemperature: String? = null,
    val customLitertMaxTokens: String? = null,
    val customLitertAccelerator: String? = null,
    val customLitertEnableThinking: Boolean? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageThread) return false
        return threadId == other.threadId &&
            title == other.title &&
            prompt == other.prompt &&
            avatarBytes.contentEquals(other.avatarBytes) &&
            cubismId == other.cubismId &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt &&
            useCustomSettings == other.useCustomSettings &&
            customApiUrl == other.customApiUrl &&
            customApiKey == other.customApiKey &&
            customModel == other.customModel &&
            customEnableVision == other.customEnableVision &&
            customMessageLimit == other.customMessageLimit &&
            customTemperature == other.customTemperature &&
            customTopP == other.customTopP &&
            customN == other.customN &&
            customMaxCompletionTokens == other.customMaxCompletionTokens &&
            customPresencePenalty == other.customPresencePenalty &&
            customFrequencyPenalty == other.customFrequencyPenalty &&
            customReasoningEffort == other.customReasoningEffort &&
            customLanguage == other.customLanguage &&
            customVoice == other.customVoice &&
            customRequireOnDeviceVoice == other.customRequireOnDeviceVoice &&
            customUseVosk == other.customUseVosk &&
            customAllowInterruptions == other.customAllowInterruptions &&
            customEmbedTime == other.customEmbedTime &&
            customKeepLastAttachment == other.customKeepLastAttachment &&
            customUseSystemTts == other.customUseSystemTts &&
            customUseExternalTts == other.customUseExternalTts &&
            customExternalTtsVoice == other.customExternalTtsVoice &&
            customExternalTtsAPI == other.customExternalTtsAPI &&
            customExternalTtsKey == other.customExternalTtsKey &&
            customStreamingMode == other.customStreamingMode &&
            customToolIds == other.customToolIds &&
            customUseLiteRt == other.customUseLiteRt &&
            customLitertModel == other.customLitertModel &&
            customLitertTopK == other.customLitertTopK &&
            customLitertTopP == other.customLitertTopP &&
            customLitertTemperature == other.customLitertTemperature &&
            customLitertMaxTokens == other.customLitertMaxTokens &&
            customLitertAccelerator == other.customLitertAccelerator &&
            customLitertEnableThinking == other.customLitertEnableThinking
    }

    override fun hashCode(): Int {
        var result = threadId.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + prompt.hashCode()
        result = 31 * result + (avatarBytes?.contentHashCode() ?: 0)
        result = 31 * result + (cubismId?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + useCustomSettings.hashCode()
        result = 31 * result + (customApiUrl?.hashCode() ?: 0)
        result = 31 * result + (customApiKey?.hashCode() ?: 0)
        result = 31 * result + (customModel?.hashCode() ?: 0)
        result = 31 * result + (customEnableVision?.hashCode() ?: 0)
        result = 31 * result + (customMessageLimit?.hashCode() ?: 0)
        result = 31 * result + (customTemperature?.hashCode() ?: 0)
        result = 31 * result + (customTopP?.hashCode() ?: 0)
        result = 31 * result + (customN?.hashCode() ?: 0)
        result = 31 * result + (customMaxCompletionTokens?.hashCode() ?: 0)
        result = 31 * result + (customPresencePenalty?.hashCode() ?: 0)
        result = 31 * result + (customFrequencyPenalty?.hashCode() ?: 0)
        result = 31 * result + (customReasoningEffort?.hashCode() ?: 0)
        result = 31 * result + (customLanguage?.hashCode() ?: 0)
        result = 31 * result + (customVoice?.hashCode() ?: 0)
        result = 31 * result + (customRequireOnDeviceVoice?.hashCode() ?: 0)
        result = 31 * result + (customUseVosk?.hashCode() ?: 0)
        result = 31 * result + (customAllowInterruptions?.hashCode() ?: 0)
        result = 31 * result + (customEmbedTime?.hashCode() ?: 0)
        result = 31 * result + (customKeepLastAttachment?.hashCode() ?: 0)
        result = 31 * result + (customUseSystemTts?.hashCode() ?: 0)
        result = 31 * result + (customUseExternalTts?.hashCode() ?: 0)
        result = 31 * result + (customExternalTtsVoice?.hashCode() ?: 0)
        result = 31 * result + (customExternalTtsAPI?.hashCode() ?: 0)
        result = 31 * result + (customExternalTtsKey?.hashCode() ?: 0)
        result = 31 * result + (customStreamingMode?.hashCode() ?: 0)
        result = 31 * result + (customToolIds?.hashCode() ?: 0)
        result = 31 * result + (customUseLiteRt?.hashCode() ?: 0)
        result = 31 * result + (customLitertModel?.hashCode() ?: 0)
        result = 31 * result + (customLitertTopK?.hashCode() ?: 0)
        result = 31 * result + (customLitertTopP?.hashCode() ?: 0)
        result = 31 * result + (customLitertTemperature?.hashCode() ?: 0)
        result = 31 * result + (customLitertMaxTokens?.hashCode() ?: 0)
        result = 31 * result + (customLitertAccelerator?.hashCode() ?: 0)
        result = 31 * result + (customLitertEnableThinking?.hashCode() ?: 0)
        return result
    }
}
