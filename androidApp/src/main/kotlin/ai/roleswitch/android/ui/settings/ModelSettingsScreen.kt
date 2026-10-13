//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.roleswitch.android.R
import ai.roleswitch.android.RolesWitchApp
import ai.roleswitch.android.data.model.MessageThread
import ai.roleswitch.android.service.LitertModel
import ai.roleswitch.android.settings.EffectiveSettings
import ai.roleswitch.android.settings.SettingsManager
import ai.roleswitch.android.settings.TtsMode
import ai.roleswitch.android.settings.withCustomSettingsEnabled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * [SettingsBinding] backed by a thread's `custom*` override columns. Values are
 * resolved through the shared [EffectiveSettings.resolve] (per-thread override
 * when custom settings are on, live global otherwise), so this screen can never
 * drift from what LLMService/AssistantScreen actually use.
 */
private class ThreadSettingsBinding(
    private val thread: MessageThread?,
    private val settingsManager: SettingsManager,
    private val setThread: ((MessageThread) -> MessageThread) -> Unit
) : SettingsBinding {
    private val eff: EffectiveSettings =
        thread?.let { EffectiveSettings.resolve(it, settingsManager) }
            ?: EffectiveSettings.global(settingsManager)

    override val apiUrl: String get() = eff.openaiAPI
    override val apiKey: String get() = eff.openaiKey
    override val model: String get() = eff.selectedModel
    override val enableVision: Boolean get() = eff.enableVision
    override val messageLimit: Int get() = eff.messageLimit
    override val language: String get() = eff.language
    override val voice: String get() = eff.voice
    override val useVosk: Boolean get() = eff.useVosk
    override val allowInterruptions: Boolean get() = eff.allowInterruptions
    override val requireOnDeviceVoice: Boolean get() = eff.requireOnDeviceVoice
    override val embedTime: Boolean get() = eff.embedTime
    override val keepLastAttachment: Boolean get() = eff.keepLastAttachment
    override val useSystemTts: Boolean get() = eff.useSystemTts
    override val useExternalTts: Boolean get() = eff.useExternalTts
    override val externalTtsVoice: String get() = eff.externalTtsVoice
    override val externalTtsAPI: String get() = eff.externalTtsAPI
    override val externalTtsKey: String get() = eff.externalTtsKey
    override val streamingMode: Boolean get() = eff.streamingMode
    override val reasoningEffort: String get() = eff.reasoningEffort
    override val useOnDevice: Boolean get() = eff.useOnDevice
    override val litertModel: String get() = eff.litertModel
    override val litertEnableThinking: Boolean get() = eff.litertEnableThinking

    override fun saveServer(url: String, key: String) {
        val changed = url != eff.openaiAPI
        // One `setThread`, not two: each call transforms the same composition-time
        // snapshot of the row, so a second write would drop the first one's field.
        setThread {
            it.copy(
                customApiUrl = url,
                customApiKey = key,
                customModel = if (changed) "" else it.customModel
            )
        }
    }
    override fun saveModel(model: String) = setThread { it.copy(customModel = model) }
    override fun saveEnableVision(value: Boolean) = setThread { it.copy(customEnableVision = value) }
    override fun saveMessageLimit(value: Int) = setThread { it.copy(customMessageLimit = value) }

    override fun saveLanguage(lang: String, voskReady: Boolean) = setThread { t ->
        t.copy(
            customLanguage = lang,
            customVoice = "",
            customUseVosk = if (voskReady) t.customUseVosk else false,
            customAllowInterruptions = false,
            customRequireOnDeviceVoice = false
        )
    }

    override fun saveVoice(name: String) = setThread { it.copy(customVoice = name) }
    override fun saveUseVoskEnabled() = setThread { it.copy(customUseVosk = true) }
    override fun saveUseVoskDisabled() =
        setThread { it.copy(customUseVosk = false, customAllowInterruptions = false) }

    override fun saveAllowInterruptions(value: Boolean) =
        setThread { it.copy(customAllowInterruptions = value) }

    override fun saveRequireOnDeviceVoice(value: Boolean) =
        setThread { it.copy(customRequireOnDeviceVoice = value) }

    override fun saveEmbedTime(value: Boolean) = setThread { it.copy(customEmbedTime = value) }
    override fun saveKeepLastAttachment(value: Boolean) =
        setThread { it.copy(customKeepLastAttachment = value) }

    override fun saveReasoningEffort(value: String) =
        setThread { it.copy(customReasoningEffort = value) }

    override fun saveUseOnDevice(value: Boolean) = setThread { t ->
        // Engine switch: the "Messages to process" ranges differ (no "Unlimited" on
        // device), so a thread whose engine changes settles its own limit on the
        // shared 50 default instead of carrying a value the picker cannot show.
        if (value != t.customUseLiteRt) t.copy(customUseLiteRt = value, customMessageLimit = 50)
        else t.copy(customUseLiteRt = value)
    }

    override fun saveLitertEnableThinking(value: Boolean) =
        setThread { it.copy(customLitertEnableThinking = value) }

    // Clears the voice for the same reason saveLanguage does: a stored voice name
    // belongs to the engine that reported it and will not resolve on the new one.
    // The external voice id is a server-side name that survives engine switches,
    // so it is deliberately not cleared here.
    override fun saveTtsMode(mode: TtsMode) =
        setThread {
            it.copy(
                customUseSystemTts = mode == TtsMode.SYSTEM,
                customUseExternalTts = mode == TtsMode.EXTERNAL,
                customVoice = ""
            )
        }

    override fun saveExternalTtsVoice(value: String) =
        setThread { it.copy(customExternalTtsVoice = value) }

    /** The thread's own speech server — the `customExternalTtsAPI`/`customExternalTtsKey`
     *  override columns, written without touching the inference `customApiUrl`/`customApiKey`. */
    override fun saveExternalTtsServer(url: String, key: String) =
        setThread { it.copy(customExternalTtsAPI = url, customExternalTtsKey = key) }

    override fun saveStreamingMode(value: Boolean) =
        setThread { it.copy(customStreamingMode = value) }
}

/**
 * Per-thread "Model settings" screen. Renders the same [SettingsSections] as the
 * global [SettingsScreen] but reads/writes the thread's `custom*` override
 * columns. The master "Use custom settings" toggle snapshots the current global
 * settings into the thread when turned on; while off those sections are hidden
 * outright rather than shown greyed out.
 *
 * [ThreadToolsSection] sits outside that switch and is always shown: which endpoints
 * a conversation may call is a property of that conversation either way (see
 * [EffectiveSettings.resolve]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSettingsScreen(
    app: RolesWitchApp,
    threadId: String,
    onBack: () -> Unit,
    onNavigateToAdvanced: () -> Unit,
    onNavigateToLitertModel: () -> Unit
) {
    val settingsManager = app.settingsManager
    val scope = rememberCoroutineScope()

    // No global StateFlows are collected here. While custom settings are off every section
    // that reads them is hidden, and the Tools row that stays counts endpoints off the
    // `mcp_tools` flow below rather than off SettingsManager.
    val thread by app.database.messageThreadDao().observeThreadById(threadId).collectAsState(initial = null)

    fun setThread(transform: (MessageThread) -> MessageThread) {
        val current = thread ?: return
        scope.launch { app.database.updateThreadSettings(current, transform) }
    }

    // Rebuilt each recomposition: resolves against the latest thread row and globals.
    val binding = ThreadSettingsBinding(thread, settingsManager, ::setThread)
    val customOn = thread?.useCustomSettings == true

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.model_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Master "Use custom settings" toggle.
            SettingsCard {
                SettingsToggle(
                    title = stringResource(R.string.use_custom_settings),
                    subtitle = stringResource(R.string.use_custom_settings_subtitle),
                    checked = customOn,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            setThread { it.withCustomSettingsEnabled(EffectiveSettings.global(settingsManager)) }
                        } else {
                            setThread { it.copy(useCustomSettings = false) }
                        }
                    }
                )
            }

            Spacer(Modifier.height(24.dp))

            // Hidden, not greyed out, while the master switch is off: these rows would
            // otherwise show live global values the user cannot touch. Matches iOS.
            if (customOn) {
                SettingsSections(
                    app = app,
                    binding = binding,
                    onNavigateToAdvanced = onNavigateToAdvanced,
                    onNavigateToLitertModel = onNavigateToLitertModel
                )

                Spacer(Modifier.height(24.dp))
            }

            // Always shown — a thread picks its own tools regardless of the override block.
            ThreadToolsSection(app = app, thread = thread)

            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * The per-thread Tools section: every registered endpoint, one switch each.
 *
 * This replaced a sub-screen with a "Follow global selection" toggle at the top. There is
 * nothing to inherit any more — `mcp_tools` is a registry, so a chat's own list is the whole
 * answer and fits inline. A chat starts with everything off.
 *
 * Unlike the rest of [SettingsSections] this is thread-shaped rather than binding-shaped: it
 * writes one column, on one row, and has no global counterpart.
 */
@Composable
private fun ThreadToolsSection(app: RolesWitchApp, thread: MessageThread?) {
    SectionHeader(stringResource(R.string.tools))

    val allTools by app.database.mcpToolDao().observeAllTools().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    // Resolved through the same path LLMService uses, so what is ticked here is exactly what
    // the thread will send. Ids whose row was since deleted simply do not match anything.
    val selectedIds = thread?.let { EffectiveSettings.resolve(it, app.settingsManager) }
        ?.enabledToolIds.orEmpty().toSet()

    SettingsCard {
        if (allTools.isEmpty()) {
            // A card with nothing in it reads as a bug; say where endpoints come from instead.
            Text(
                stringResource(R.string.thread_tools_empty),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            allTools.forEachIndexed { index, tool ->
                if (index > 0) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(tool.name, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            tool.url,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Switch(
                        checked = tool.id in selectedIds,
                        enabled = thread != null,
                        onCheckedChange = { checked ->
                            val current = thread ?: return@Switch
                            val next = if (checked) selectedIds + tool.id else selectedIds - tool.id
                            scope.launch {
                                app.database.applyThreadToolSelection(current, next.toList())
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * [AdvancedBinding] backed by a thread's `custom*` advanced columns. Used by the
 * `model_advanced` route so the thread's Advanced sub-screen reuses [AdvancedScreen].
 */
internal class ThreadAdvancedBinding(
    private val app: RolesWitchApp,
    private val thread: MessageThread,
    private val scope: CoroutineScope
) : AdvancedBinding {
    override val temperature: String = thread.customTemperature ?: ""
    override val topP: String = thread.customTopP ?: ""
    override val n: String = thread.customN ?: ""
    override val maxCompletionTokens: String = thread.customMaxCompletionTokens ?: ""
    override val presencePenalty: String = thread.customPresencePenalty ?: ""
    override val frequencyPenalty: String = thread.customFrequencyPenalty ?: ""

    override fun save(
        temperature: String,
        topP: String,
        n: String,
        maxCompletionTokens: String,
        presencePenalty: String,
        frequencyPenalty: String
    ) {
        scope.launch {
            app.database.updateThreadSettings(thread) {
                it.copy(
                    customTemperature = temperature,
                    customTopP = topP,
                    customN = n,
                    customMaxCompletionTokens = maxCompletionTokens,
                    customPresencePenalty = presencePenalty,
                    customFrequencyPenalty = frequencyPenalty
                )
            }
        }
    }
}

/**
 * Per-thread counterpart of [ThreadAdvancedBinding] for the on-device engine. Wired to
 * the `model_litert_advanced` route so a thread running LiteRT gets the same Advanced
 * screen as the global settings, writing to its own `customLitert*` columns.
 */
internal class ThreadLitertAdvancedBinding(
    private val app: RolesWitchApp,
    private val thread: MessageThread,
    private val scope: CoroutineScope
) : LitertAdvancedBinding {
    override val maxTokens: String = thread.customLitertMaxTokens ?: ""
    override val topK: String = thread.customLitertTopK ?: ""
    override val topP: String = thread.customLitertTopP ?: ""
    override val temperature: String = thread.customLitertTemperature ?: ""
    override val accelerator: String = thread.customLitertAccelerator ?: ""
    // Resolved rather than read off the column: a thread that has not overridden the
    // model still runs the global one, and the defaults shown here must be that model's.
    override val model: LitertModel? =
        EffectiveSettings.resolve(thread, app.settingsManager).litertModelSpec

    override fun save(
        maxTokens: String,
        topK: String,
        topP: String,
        temperature: String,
        accelerator: String
    ) {
        scope.launch {
            app.database.updateThreadSettings(thread) {
                it.copy(
                    customLitertMaxTokens = maxTokens,
                    customLitertTopK = topK,
                    customLitertTopP = topP,
                    customLitertTemperature = temperature,
                    customLitertAccelerator = accelerator
                )
            }
        }
    }
}
