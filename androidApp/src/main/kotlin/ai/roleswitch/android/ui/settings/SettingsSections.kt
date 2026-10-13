//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import android.content.Intent
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ai.roleswitch.android.R
import ai.roleswitch.android.RolesWitchApp
import ai.roleswitch.android.ui.components.ConfirmDialog
import ai.roleswitch.android.ui.components.InfoDialog
import ai.roleswitch.android.ui.components.SystemSettingsDialog
import ai.roleswitch.android.ui.components.rememberLocalNetworkGate
import ai.roleswitch.android.service.LitertModelStore
import ai.roleswitch.android.service.LitertModels
import ai.roleswitch.android.service.isOnDeviceRecognitionSupported
import ai.roleswitch.android.service.onDeviceInferenceSupported
import ai.roleswitch.android.service.platformSupportsOnDeviceRecognition
import ai.roleswitch.android.service.VoskModel
import ai.roleswitch.android.service.VoskModels
import ai.roleswitch.android.settings.SettingsManager
import ai.roleswitch.android.settings.TtsMode

/**
 * Read/write adapter over the user-facing settings shared by the global
 * [SettingsScreen] and the per-thread [ModelSettingsScreen]. Mirrors the
 * [AdvancedBinding] pattern: implementations capture current values at
 * composition and own the global-vs-thread write semantics — including the
 * clear-model-on-URL-change rule and the language-change resets — so the two
 * screens cannot drift.
 */
internal interface SettingsBinding {
    val apiUrl: String
    val apiKey: String
    val model: String
    val enableVision: Boolean
    val messageLimit: Int
    val language: String
    val voice: String
    val useVosk: Boolean
    val allowInterruptions: Boolean
    val requireOnDeviceVoice: Boolean
    val embedTime: Boolean
    val keepLastAttachment: Boolean
    val useSystemTts: Boolean

    /** True when this scope speaks through the OpenAI-compatible `/v1/audio/speech` endpoint. */
    val useExternalTts: Boolean

    /** The server-side voice id sent to the external speech server; empty = server default. */
    val externalTtsVoice: String

    /**
     * The external speech server's base URL and token, resolved for this scope. Kept
     * apart from [apiUrl]/[apiKey] — inference and speech may live on different hosts —
     * and per-thread overridable like the voice and the engine.
     */
    val externalTtsAPI: String
    val externalTtsKey: String

    /**
     * The resolved speech engine for this scope, matching [EffectiveSettings.ttsMode]:
     * EXTERNAL wins over SYSTEM. Recognition and synthesis are independent, so
     * [requireOnDeviceVoice] does not gate the engine.
     */
    val ttsMode: TtsMode
        get() = when {
            useExternalTts -> TtsMode.EXTERNAL
            useSystemTts -> TtsMode.SYSTEM
            else -> TtsMode.AUTO
        }

    /** True when replies stream in as they are generated; false shows them whole when done. */
    val streamingMode: Boolean

    /** The `reasoning_effort` wire value, or empty for "leave it out of the request". */
    val reasoningEffort: String

    /** True when this scope runs inference on-device instead of against a remote endpoint. */
    val useOnDevice: Boolean

    /** Selected on-device model id; empty when none has been picked yet. */
    val litertModel: String

    /** The runtime's `enable_thinking` flag; only offered for checkpoints that have one. */
    val litertEnableThinking: Boolean

    /**
     * Saves the endpoint and its token together, in one write; when [url] differs from
     * the current one the selected model is cleared. One call rather than two because
     * the per-thread binding writes a snapshot of the whole row — two sequential saves
     * would both start from the same pre-edit snapshot and the second would clobber the
     * first.
     */
    fun saveServer(url: String, key: String)
    fun saveModel(model: String)
    fun saveEnableVision(value: Boolean)
    fun saveMessageLimit(value: Int)

    /**
     * Saves [lang] with the shared reset semantics: voice cleared, interruptions
     * and on-device-voice off, and Vosk kept on only when [voskReady] (the chosen
     * language's model is bundled or already extracted — the user must re-toggle
     * Vosk to trigger a download for a new locale).
     */
    fun saveLanguage(lang: String, voskReady: Boolean)
    fun saveVoice(name: String)

    /** Called only once the language's Vosk model is known to be ready. */
    fun saveUseVoskEnabled()

    /** Turns Vosk off; also forces allowInterruptions off (it depends on Vosk). */
    fun saveUseVoskDisabled()
    fun saveAllowInterruptions(value: Boolean)
    fun saveRequireOnDeviceVoice(value: Boolean)
    fun saveEmbedTime(value: Boolean)
    fun saveKeepLastAttachment(value: Boolean)
    fun saveReasoningEffort(value: String)

    /**
     * Saves the speech-engine choice and clears the selected *standard* voice, since voice
     * names are engine-specific and will not resolve against the engine this binds. The
     * external voice id is a server-side name and survives engine switches, so it is not
     * cleared here (see [saveExternalTtsVoice]). Vosk and allowInterruptions are
     * speech-*input* concerns and stay as-is.
     */
    fun saveTtsMode(mode: TtsMode)

    /** Saves the server-side voice id for external speech; empty means "server default". */
    fun saveExternalTtsVoice(value: String)

    /** Saves the external speech server URL and its token together, in one write. */
    fun saveExternalTtsServer(url: String, key: String)

    /** Saves the "Streaming mode" choice — see [streamingMode]. */
    fun saveStreamingMode(value: Boolean)

    /** Switches this scope between the remote endpoint and the on-device engine. */
    fun saveUseOnDevice(value: Boolean)

    fun saveLitertEnableThinking(value: Boolean)
}

/**
 * The Intelligence / Audio / Miscellaneous sections plus every sheet and dialog
 * they open. Emitted inside the caller's scrolling Column; the caller owns the
 * Scaffold, any screen-specific header (the per-thread master toggle) and
 * footer (the global Reset button).
 *
 * Tools are *not* here — see [ToolsSection]. Per-thread these sections are hidden
 * outright while "Use custom settings" is off, so nothing in here has a disabled
 * state to render.
 */
@Composable
internal fun SettingsSections(
    app: RolesWitchApp,
    binding: SettingsBinding,
    onNavigateToAdvanced: () -> Unit,
    /** Opens the on-device model list (download / license / select). */
    onNavigateToLitertModel: () -> Unit
) {
    var showOfflineUnavailableAlert by remember { mutableStateOf(false) }
    var showVoskNotAvailableAlert by remember { mutableStateOf(false) }
    var pendingVoskModel by remember { mutableStateOf<VoskModel?>(null) }
    var showVoskDownloadSheet by remember { mutableStateOf(false) }
    var showVoskCorruptedAlert by remember { mutableStateOf(false) }
    var showVoicePicker by remember { mutableStateOf(false) }
    var showRecognitionPicker by remember { mutableStateOf(false) }
    var showSpeechEnginePicker by remember { mutableStateOf(false) }
    var showExternalServerEditor by remember { mutableStateOf(false) }
    var showExternalVoiceEditor by remember { mutableStateOf(false) }
    var showServerEditor by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    var showMessageLimitPicker by remember { mutableStateOf(false) }
    var showReasoningPicker by remember { mutableStateOf(false) }
    var showInferenceEnginePicker by remember { mutableStateOf(false) }
    var showHuggingFaceTokenEditor by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Saving the speech server must surface the local-network permission too: the user
    // may enter just the address and never touch the voice picker, and the first thing
    // to actually knock on the server would otherwise be a call's first reply. The
    // probe is a light GET — the same /v1/models fetchModels issues — and its result
    // is ignored: reachability is the voice preview's job, this exists so the one-shot
    // system prompt (or the settings alert) lands while the user is still in Settings.
    var pendingVoiceServerProbe by remember { mutableStateOf<Pair<String, String>?>(null) }
    val gateVoiceServerProbe = rememberLocalNetworkGate {
        val (url, token) = pendingVoiceServerProbe ?: return@rememberLocalNetworkGate
        scope.launch {
            try {
                app.oaiService.fetchModels(url, token.ifEmpty { "none" })
            } catch (_: Exception) {
                // Probe only — the permission moment is the point, not the answer.
            }
        }
    }

    // Intelligence Section
    SectionHeader(stringResource(R.string.intelligence))

    SettingsCard {
        // Topmost: everything below it depends on which engine is selected.
        SettingsRow(
            title = stringResource(R.string.engine),
            value = stringResource(
                if (binding.useOnDevice && onDeviceInferenceSupported) {
                    R.string.engine_device
                } else {
                    R.string.engine_remote
                }
            ),
            onClick = { showInferenceEnginePicker = true }
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        if (binding.useOnDevice) {
            // On-device: no endpoint, and vision is a property of the chosen checkpoint
            // rather than a preference. The token sits above the model row because a
            // gated model cannot be downloaded without one — the order matches what the
            // user has to do. Its "Optional" value carries the whole message; which
            // models actually need it is said by the download's own dialog, at the
            // moment it is refused.
            SettingsRow(
                title = stringResource(R.string.huggingface_token),
                value = if (app.settingsManager.huggingFaceToken.collectAsState().value.isNotEmpty()) {
                    "••••••••"
                } else {
                    stringResource(R.string.optional)
                },
                onClick = { showHuggingFaceTokenEditor = true }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // "Not configured" covers both a missing selection and one whose weights were
            // deleted: the send/call guards treat the two identically, so the row must
            // not promise a model the chat cannot use. The iOS label says the same.
            val selected = LitertModels.get(binding.litertModel)?.takeIf {
                LitertModelStore.isDownloaded(context, it)
            }
            SettingsRow(
                title = stringResource(R.string.model),
                value = selected?.displayName ?: stringResource(R.string.not_configured),
                isError = selected == null,
                subtitle = selected?.let {
                    if (it.supportsImage) stringResource(R.string.model_supports_images) else null
                },
                onClick = onNavigateToLitertModel
            )

            // Hidden for a checkpoint the registry knows takes images: it is on regardless
            // (see `EffectiveSettings.visionAvailable`), and a switch that changes nothing
            // is worse than no switch. The model row's "supports images" subtitle says so
            // in its place. A model added by URL has no such flag — nobody has looked at
            // its vision tower — so it still gets the toggle: it asks the engine to try,
            // and one that cannot answers with an alert on the first image rather than by
            // silently ignoring it.
            if (selected?.supportsImage != true) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                SettingsToggle(
                    title = stringResource(R.string.enable_vision),
                    subtitle = stringResource(R.string.vision_subtitle),
                    checked = binding.enableVision,
                    onCheckedChange = { binding.saveEnableVision(it) }
                )
            }
        } else {
            // One row for the endpoint and its token: they are edited together in
            // `ServerSheet`. The value shows the URL, since the token is optional and
            // secret — there is nothing useful to render for it here.
            SettingsRow(
                title = stringResource(R.string.server),
                value = binding.apiUrl.ifEmpty { stringResource(R.string.not_configured) },
                isError = binding.apiUrl.isEmpty(),
                onClick = { showServerEditor = true }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Not tappable until there is a server to ask: the picker lists what the
            // endpoint reports, and on iOS the save also runs the preflight that gets
            // local-network access granted before the first model fetch.
            SettingsRow(
                title = stringResource(R.string.model),
                value = binding.model.ifEmpty { stringResource(R.string.not_configured) },
                isError = binding.model.isEmpty() && binding.apiUrl.isNotEmpty(),
                enabled = binding.apiUrl.isNotEmpty(),
                onClick = { showModelPicker = true }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsToggle(
                title = stringResource(R.string.enable_vision),
                // Shown whether or not the toggle is on: it is advice about picking a model,
                // which is most useful *before* switching vision on.
                subtitle = stringResource(R.string.vision_subtitle),
                checked = binding.enableVision,
                onCheckedChange = { binding.saveEnableVision(it) }
            )
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        val limitLabel = if (binding.messageLimit >= 100000) stringResource(R.string.unlimited)
        else binding.messageLimit.toString()
        SettingsRow(
            title = stringResource(R.string.messages_to_process),
            value = limitLabel,
            onClick = { showMessageLimitPicker = true }
        )

        // Remote only. `reasoning_effort` is an OpenAI request field; the on-device
        // engine takes no such parameter — LiteRT-LM only *reports* reasoning back on
        // its `thought` channel, and how hard a local checkpoint thinks is baked into
        // the checkpoint. Showing the row on device would offer a setting nothing reads.
        if (!binding.useOnDevice) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsRow(
                title = stringResource(R.string.reasoning_mode),
                value = reasoningLabel(binding.reasoningEffort),
                onClick = { showReasoningPicker = true }
            )
        }

        // On device only, and only for checkpoints that have a thinking mode — on the
        // rest the runtime flag is inert, and a switch that changes nothing is worse
        // than no switch. Sits directly above Advanced because it changes what the
        // model does on every turn, unlike the sampler values behind that row.
        if (binding.useOnDevice && LitertModels.get(binding.litertModel)?.supportsThinking == true) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsToggle(
                title = stringResource(R.string.litert_enable_thinking),
                checked = binding.litertEnableThinking,
                onCheckedChange = { binding.saveLitertEnableThinking(it) }
            )
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        SettingsRow(
            title = stringResource(R.string.advanced),
            value = "",
            subtitle = stringResource(R.string.advanced_subtitle),
            onClick = onNavigateToAdvanced
        )
    }

    Spacer(Modifier.height(24.dp))

    // Speech Input Section (recognition) — the shared audio settings live here too:
    // the language scopes the recognition model as well as the voices, and the
    // on-device preference sits directly above the engine row it qualifies.
    SectionHeader(stringResource(R.string.speech_input))

    SettingsCard {
        val currentLangName = SettingsManager.defaultLanguages[binding.language] ?: "English"
        SettingsRow(
            title = stringResource(R.string.language),
            value = currentLangName,
            onClick = { showLanguagePicker = true }
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        // Hidden outright below API 31, toggle and divider and all: the platform has
        // no on-device recognizer there, and unlike the "not downloaded yet" case
        // below there is nothing the user could install to get one — the alert
        // would deep-link to a voice-input screen that cannot help.
        if (platformSupportsOnDeviceRecognition) {
            // One offline preference for both directions: it selects the on-device
            // recognizer and restricts synthesis to voices that need no connection.
            // Splitting it would allow the nonsensical mix of offline speech with
            // online recognition.
            SettingsToggle(
                title = stringResource(R.string.process_on_device),
                checked = binding.requireOnDeviceVoice,
                onCheckedChange = { enabled ->
                    if (!enabled) {
                        binding.saveRequireOnDeviceVoice(false)
                    } else if (!isOnDeviceRecognitionSupported(context)) {
                        // Supported by the platform but no recognizer installed — the
                        // alert deep-links to the OS voice-input settings, where a
                        // language-pack download does fix it.
                        showOfflineUnavailableAlert = true
                    } else {
                        binding.saveRequireOnDeviceVoice(true)
                        // A stored voice that needs the network would silently keep being
                        // used, so swap to an offline one (or fall back to the engine default).
                        scope.launch {
                            val voices = app.ttsService
                                .getAvailableVoices(binding.language, binding.useSystemTts)
                            val current = voices.firstOrNull { it.name == binding.voice }
                            if (current != null && current.isNetworkConnectionRequired) {
                                val offline = voices.firstOrNull { !it.isNetworkConnectionRequired }
                                binding.saveVoice(offline?.name ?: "")
                            }
                        }
                    }
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }

        SettingsRow(
            title = stringResource(R.string.recognition_engine),
            value = stringResource(
                if (binding.useVosk) R.string.recognition_offline else R.string.recognition_system
            ),
            onClick = { showRecognitionPicker = true }
        )
    }

    Spacer(Modifier.height(24.dp))

    // Speech Output Section (text-to-speech)
    SectionHeader(stringResource(R.string.speech_output))

    SettingsCard {
        SettingsRow(
            title = stringResource(R.string.speech_engine),
            value = when (binding.ttsMode) {
                TtsMode.EXTERNAL -> stringResource(R.string.engine_external)
                TtsMode.SYSTEM -> stringResource(R.string.engine_system)
                TtsMode.AUTO -> stringResource(R.string.engine_app)
            },
            onClick = { showSpeechEnginePicker = true }
        )

        when (binding.ttsMode) {
            // Only under the system engine, where voice and rate genuinely live in the
            // OS. There is nothing to open for the in-app voice.
            TtsMode.SYSTEM -> {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                SettingsRow(
                    title = stringResource(R.string.open_system_settings),
                    value = "",
                    subtitle = stringResource(R.string.open_system_settings_subtitle),
                    onClick = {
                        // No public constant exists for the text-to-speech screen, so use
                        // the historical action and fall back to top-level Settings on OEM
                        // builds that don't register it. startActivity ignores package
                        // visibility, so try/catch beats resolveActivity here.
                        val opened = runCatching {
                            context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                        }.isSuccess
                        if (!opened) runCatching {
                            context.startActivity(Intent(Settings.ACTION_SETTINGS))
                        }
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                SettingsRow(
                    title = stringResource(R.string.voice),
                    value = binding.voice.ifEmpty { stringResource(R.string.system_default) },
                    // Stays available in system-TTS mode: the picker lists the bound
                    // engine's own voices, and "System default" leaves it untouched.
                    onClick = { showVoicePicker = true }
                )
            }

            // External speech: the OpenAI settings rows. The Server row edits the external-speech
            // server — its own URL and token, separate from the chat's OpenAI endpoint
            // (inference and speech may live on different hosts, so this row neither reads
            // nor writes `apiUrl`/`apiKey`), and per-thread overridable like the voice.
            // The standard Voice/Voice Speed rows are hidden because they configure the
            // local engine that is not used.
            TtsMode.EXTERNAL -> {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                SettingsRow(
                    title = stringResource(R.string.server),
                    value = binding.externalTtsAPI.ifEmpty { stringResource(R.string.not_configured) },
                    isError = binding.externalTtsAPI.isEmpty(),
                    onClick = { showExternalServerEditor = true }
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                SettingsRow(
                    title = stringResource(R.string.voice),
                    value = binding.externalTtsVoice.ifEmpty { stringResource(R.string.system_default) },
                    onClick = { showExternalVoiceEditor = true }
                )
            }

            TtsMode.AUTO -> {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                SettingsRow(
                    title = stringResource(R.string.voice),
                    value = binding.voice.ifEmpty { stringResource(R.string.system_default) },
                    onClick = { showVoicePicker = true }
                )
            }
        }
    }

    Spacer(Modifier.height(24.dp))

    // Miscellaneous Section
    SectionHeader(stringResource(R.string.miscellaneous))

    SettingsCard {
        SettingsToggle(
            title = stringResource(R.string.enable_time_embedding),
            checked = binding.embedTime,
            onCheckedChange = { binding.saveEmbedTime(it) }
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        SettingsToggle(
            title = stringResource(R.string.keep_last_attachment),
            checked = binding.keepLastAttachment,
            onCheckedChange = { binding.saveKeepLastAttachment(it) }
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        SettingsToggle(
            title = stringResource(R.string.streaming_mode),
            subtitle = stringResource(R.string.streaming_mode_subtitle),
            checked = binding.streamingMode,
            onCheckedChange = { binding.saveStreamingMode(it) }
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        SettingsToggle(
            title = stringResource(R.string.allow_interruptions),
            subtitle = stringResource(R.string.allow_interruptions_subtitle),
            checked = binding.allowInterruptions,
            enabled = binding.useVosk,
            onCheckedChange = { binding.saveAllowInterruptions(it) }
        )
    }

    // --- Sheets & Dialogs ---

    if (showOfflineUnavailableAlert) {
        // The message points at the *device's* settings ("download an offline language
        // pack"), not this app's — so deep-link to the OS voice-input screen rather than
        // the in-app Settings. Falls back to OK-only where that screen doesn't exist.
        SystemSettingsDialog(
            textRes = R.string.offline_recognition_unavailable_desc,
            action = Settings.ACTION_VOICE_INPUT_SETTINGS,
            onDismiss = { showOfflineUnavailableAlert = false }
        )
    }

    if (showVoskNotAvailableAlert) {
        // Nothing the user can change: their language has no VOSK model yet.
        InfoDialog(R.string.vosk_not_available_desc) { showVoskNotAvailableAlert = false }
    }

    // Confirm-before-download: shows size in MB and offers Cancel / Download.
    val pending = pendingVoskModel
    if (pending != null && !showVoskDownloadSheet) {
        ConfirmDialog(
            text = stringResource(
                R.string.vosk_download_required_desc,
                (pending.sizeBytes / 1_048_576L).toInt()
            ),
            confirmLabel = stringResource(R.string.download),
            onConfirm = {
                // Clear any stale terminal state from a prior attempt so the
                // sheet's state observer doesn't immediately fire on Failed.
                app.voskModelDownloader.reset()
                showVoskDownloadSheet = true
            },
            onDismiss = { pendingVoskModel = null }
        )
    }

    if (showVoskCorruptedAlert) {
        InfoDialog(R.string.vosk_download_corrupted_desc) { showVoskCorruptedAlert = false }
    }

    if (showVoskDownloadSheet && pending != null) {
        VoskDownloadSheet(
            model = pending,
            downloader = app.voskModelDownloader,
            onSuccess = {
                // Model verified + extracted; flip the setting on and close out.
                binding.saveUseVoskEnabled()
                showVoskDownloadSheet = false
                pendingVoskModel = null
            },
            onCorrupted = {
                showVoskDownloadSheet = false
                pendingVoskModel = null
                showVoskCorruptedAlert = true
            },
            onCancelled = {
                showVoskDownloadSheet = false
                pendingVoskModel = null
            }
        )
    }

    if (showServerEditor) {
        ServerSheet(
            url = binding.apiUrl,
            token = binding.apiKey,
            // Global, not per-thread: the consent is about what remote mode does, which
            // does not change per conversation. Both screens read and write the one flag.
            hasAcceptedDataSharing = app.settingsManager.hasAcceptedDataSharing.collectAsState().value,
            onAcceptDataSharing = { app.settingsManager.setHasAcceptedDataSharing(true) },
            onDismiss = { showServerEditor = false },
            onSave = { url, token ->
                binding.saveServer(url, token)
                showServerEditor = false
            }
        )
    }

    if (showModelPicker) {
        ModelPickerSheet(
            app = app,
            apiUrl = binding.apiUrl,
            apiKey = binding.apiKey,
            currentModel = binding.model,
            onDismiss = { showModelPicker = false },
            onModelSelected = {
                binding.saveModel(it)
                showModelPicker = false
            }
        )
    }

    if (showLanguagePicker) {
        LanguagePickerSheet(
            currentLanguage = binding.language,
            onDismiss = { showLanguagePicker = false },
            onLanguageSelected = { lang ->
                val sttLocale = SettingsManager.localeForSTT[lang]
                val ready = sttLocale != null && app.voskService.isModelReady(sttLocale)
                binding.saveLanguage(lang, voskReady = ready)
                showLanguagePicker = false
            }
        )
    }

    if (showSpeechEnginePicker) {
        TtsEnginePickerSheet(
            titleRes = R.string.speech_engine,
            options = buildList {
                // No descriptions: "Auto", "System" and "External (OpenAI)" already
                // say it, and the row that opens this sheet shows the same words.
                add(TtsEngineOption(TtsMode.AUTO, R.string.engine_app))
                add(TtsEngineOption(TtsMode.SYSTEM, R.string.engine_system))
                add(TtsEngineOption(TtsMode.EXTERNAL, R.string.engine_external))
            },
            selected = binding.ttsMode,
            onDismiss = { showSpeechEnginePicker = false },
            onSelected = { mode ->
                showSpeechEnginePicker = false
                binding.saveTtsMode(mode)
            }
        )
    }

    if (showExternalServerEditor) {
        // Edits the external-speech server — the *speech* endpoint and token, separate
        // from the chat's: changing it must not touch the inference endpoint, token, or
        // selected model. Binds through `SettingsBinding`, so the per-thread screen writes
        // its own `custom*` override columns while the global screen writes the global
        // preferences. Unlike the chat server, the token is optional here too but the URL
        // alone gates the save — see `saveExternalTtsServer`.
        ServerSheet(
            url = binding.externalTtsAPI,
            token = binding.externalTtsKey,
            // Same Data-Sharing consent the OpenAI section asks once, before any phone
            // call is placed. It is global about remote mode, so one flag serves both.
            hasAcceptedDataSharing = app.settingsManager.hasAcceptedDataSharing.collectAsState().value,
            onAcceptDataSharing = { app.settingsManager.setHasAcceptedDataSharing(true) },
            onDismiss = { showExternalServerEditor = false },
            onSave = { url, token ->
                binding.saveExternalTtsServer(url, token)
                showExternalServerEditor = false
                pendingVoiceServerProbe = url to token
                gateVoiceServerProbe()
            }
        )
    }

    if (showExternalVoiceEditor) {
        // The voice id is free-form because it is a server-side name — any endpoint may
        // offer names the picker has never heard of, so a text field beats a static
        // list. Empty is allowed and means "let the server pick". The sheet persists
        // through `saveExternalTtsVoice` at Save and holds itself up for the preview —
        // spinner for the request, the greeting, dismissal on heard, the mapped error
        // in place on failure — so nothing about the save is ever left to guess at
        // from a dialog after the sheet has gone.
        ExternalVoiceSheet(
            app = app,
            title = stringResource(R.string.voice),
            value = binding.externalTtsVoice,
            placeholder = stringResource(R.string.external_voice_placeholder),
            language = binding.language,
            endpoint = binding.externalTtsAPI,
            token = binding.externalTtsKey,
            onDismiss = { showExternalVoiceEditor = false },
            onSave = { name -> binding.saveExternalTtsVoice(name) }
        )
    }

    if (showRecognitionPicker) {
        RecognitionEnginePickerSheet(
            titleRes = R.string.recognition_engine,
            options = listOf(
                EngineOption(false, R.string.recognition_system, R.string.recognition_system_desc),
                EngineOption(true, R.string.recognition_offline, R.string.recognition_offline_desc)
            ),
            selected = binding.useVosk,
            onDismiss = { showRecognitionPicker = false },
            onSelected = { offline ->
                showRecognitionPicker = false
                if (offline) {
                    // Same gating the old toggle used: a locale without a catalog
                    // entry can't run offline at all, and an entry that isn't
                    // extracted yet needs the user to accept the download first.
                    val sttLocale = SettingsManager.localeForSTT[binding.language]
                    val model = sttLocale?.let { VoskModels.get(it) }
                    when {
                        model == null -> showVoskNotAvailableAlert = true
                        app.voskService.isModelReady(sttLocale) -> binding.saveUseVoskEnabled()
                        else -> pendingVoskModel = model
                    }
                } else {
                    binding.saveUseVoskDisabled()
                }
            }
        )
    }

    if (showHuggingFaceTokenEditor) {
        // Global, not per-thread: the token identifies the account, not the conversation.
        TextFieldSheet(
            title = stringResource(R.string.huggingface_token),
            value = app.settingsManager.huggingFaceToken.collectAsState().value,
            placeholder = "hf_...",
            isPassword = true,
            onDismiss = { showHuggingFaceTokenEditor = false },
            onSave = {
                app.settingsManager.setHuggingFaceToken(it)
                showHuggingFaceTokenEditor = false
            }
        )
    }

    if (showInferenceEnginePicker) {
        // The on-device engine is off the table below Android 12: the hardware of that
        // era cannot be assumed to run a model. The option is hidden rather than shown
        // disabled, and both the highlight and the write are clamped, so a choice that
        // would be stored cannot even be committed.
        EnginePickerSheet(
            titleRes = R.string.engine,
            options = buildList {
                if (onDeviceInferenceSupported) {
                    add(EngineOption(true, R.string.engine_device, R.string.engine_device_desc, Icons.Default.PhoneAndroid))
                }
                add(EngineOption(false, R.string.engine_remote, R.string.engine_remote_desc, Icons.Default.Cloud))
            },
            selected = binding.useOnDevice && onDeviceInferenceSupported,
            onDismiss = { showInferenceEnginePicker = false },
            onSelected = { onDevice ->
                showInferenceEnginePicker = false
                binding.saveUseOnDevice(onDevice && onDeviceInferenceSupported)
            }
        )
    }

    if (showMessageLimitPicker) {
        MessageLimitSheet(
            currentLimit = binding.messageLimit,
            isOnDevice = binding.useOnDevice,
            onDismiss = { showMessageLimitPicker = false },
            onLimitSelected = {
                binding.saveMessageLimit(it)
                showMessageLimitPicker = false
            }
        )
    }

    if (showReasoningPicker) {
        ReasoningPickerSheet(
            current = binding.reasoningEffort,
            onDismiss = { showReasoningPicker = false },
            onSelected = {
                binding.saveReasoningEffort(it)
                showReasoningPicker = false
            }
        )
    }

    if (showVoicePicker) {
        VoicePickerSheet(
            app = app,
            language = binding.language,
            currentVoice = binding.voice,
            requireOnDeviceVoice = binding.requireOnDeviceVoice,
            useSystemTts = binding.useSystemTts,
            onDismiss = { showVoicePicker = false },
            onVoiceSelected = { name ->
                binding.saveVoice(name)
                showVoicePicker = false
            }
        )
    }
}

/**
 * The global Tools section — one row leading to the endpoint registry.
 *
 * Global only. The row counts *configured* endpoints, never enabled ones: nothing is enabled
 * from here. Whether a given chat may call one is decided in that chat's own Tools section
 * (see [ModelSettingsScreen]), which is why this composable takes no [SettingsBinding] —
 * there is no per-scope variant of it left.
 */
@Composable
internal fun ToolsSection(app: RolesWitchApp, onNavigateToTools: () -> Unit) {
    SectionHeader(stringResource(R.string.tools))

    val allTools by app.database.mcpToolDao().observeAllTools().collectAsState(initial = emptyList())

    SettingsCard {
        SettingsRow(
            title = stringResource(R.string.tools_manage),
            value = if (allTools.isEmpty()) stringResource(R.string.tools_none_configured)
            else pluralStringResource(R.plurals.tools_configured_count, allTools.size, allTools.size),
            onClick = onNavigateToTools
        )
    }
}
