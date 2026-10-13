//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.roleswitch.android.BuildConfig
import ai.roleswitch.android.R
import ai.roleswitch.android.RolesWitchApp
import ai.roleswitch.android.ui.components.ConfirmDialog
import ai.roleswitch.android.ui.util.PRIVACY_POLICY_URL
import ai.roleswitch.android.ui.util.SUPPORT_EMAIL
import ai.roleswitch.android.ui.util.TERMS_URL
import ai.roleswitch.android.ui.util.openUrl
import ai.roleswitch.android.ui.util.sendMail
import ai.roleswitch.android.settings.SettingsManager
import ai.roleswitch.android.settings.TtsMode
import kotlinx.coroutines.launch

/** [SettingsBinding] backed by the app-wide [SettingsManager]. */
private class GlobalSettingsBinding(private val sm: SettingsManager) : SettingsBinding {
    override val apiUrl: String get() = sm.openaiAPI.value
    override val apiKey: String get() = sm.openaiKey.value
    override val model: String get() = sm.selectedModel.value
    override val enableVision: Boolean get() = sm.enableVision.value
    override val messageLimit: Int get() = sm.messageLimit.value
    override val language: String get() = sm.language.value
    override val voice: String get() = sm.voice.value
    override val useVosk: Boolean get() = sm.useVosk.value
    override val allowInterruptions: Boolean get() = sm.allowInterruptions.value
    override val requireOnDeviceVoice: Boolean get() = sm.requireOnDeviceVoice.value
    override val embedTime: Boolean get() = sm.embedTime.value
    override val keepLastAttachment: Boolean get() = sm.keepLastAttachment.value
    override val useSystemTts: Boolean get() = sm.useSystemTts.value
    override val useExternalTts: Boolean get() = sm.useExternalTts.value
    override val externalTtsVoice: String get() = sm.externalTtsVoice.value
    override val externalTtsAPI: String get() = sm.externalTtsAPI.value
    override val externalTtsKey: String get() = sm.externalTtsKey.value
    override val streamingMode: Boolean get() = sm.streamingMode.value
    override val reasoningEffort: String get() = sm.reasoningEffort.value
    override val useOnDevice: Boolean get() = sm.useOnDevice.value
    override val litertModel: String get() = sm.litertModel.value
    override val litertEnableThinking: Boolean get() = sm.litertEnableThinking.value

    // Globally the selection *is* each endpoint's own enabled flag — there is no id list.

    override fun saveServer(url: String, key: String) {
        val changed = url != sm.openaiAPI.value
        sm.setOpenaiAPI(url)
        sm.setOpenaiKey(key)
        if (changed) sm.setSelectedModel("")
    }
    override fun saveModel(model: String) = sm.setSelectedModel(model)
    override fun saveEnableVision(value: Boolean) = sm.setEnableVision(value)
    override fun saveMessageLimit(value: Int) = sm.setMessageLimit(value)

    override fun saveLanguage(lang: String, voskReady: Boolean) {
        sm.setLanguage(lang)
        if (!voskReady) sm.setUseVosk(false)
        sm.setAllowInterruptions(false)
        sm.setRequireOnDeviceVoice(false)
        sm.setVoice("")
    }

    override fun saveVoice(name: String) = sm.setVoice(name)
    override fun saveUseVoskEnabled() = sm.setUseVosk(true)

    override fun saveUseVoskDisabled() {
        sm.setUseVosk(false)
        if (sm.allowInterruptions.value) sm.setAllowInterruptions(false)
    }

    override fun saveAllowInterruptions(value: Boolean) = sm.setAllowInterruptions(value)
    override fun saveRequireOnDeviceVoice(value: Boolean) = sm.setRequireOnDeviceVoice(value)
    override fun saveEmbedTime(value: Boolean) = sm.setEmbedTime(value)
    override fun saveKeepLastAttachment(value: Boolean) = sm.setKeepLastAttachment(value)
    override fun saveReasoningEffort(value: String) = sm.setReasoningEffort(value)

    override fun saveUseOnDevice(value: Boolean) = sm.setUseOnDevice(value)
    override fun saveLitertEnableThinking(value: Boolean) = sm.setLitertEnableThinking(value)

    override fun saveTtsMode(mode: TtsMode) {
        sm.setUseSystemTts(mode == TtsMode.SYSTEM)
        sm.setUseExternalTts(mode == TtsMode.EXTERNAL)
        // Voice names belong to the engine that reported them, so a stored one is
        // meaningless against the engine we are about to bind. Same reasoning as
        // the language change above. The external voice id is a server-side name
        // that survives engine switches, so it is deliberately not cleared here.
        sm.setVoice("")
    }

    override fun saveExternalTtsVoice(value: String) = sm.setExternalTtsVoice(value)

    /** The global speech server: writes the global preferences, never the chat's settings. */
    override fun saveExternalTtsServer(url: String, key: String) {
        sm.setExternalTtsAPI(url)
        sm.setExternalTtsKey(key)
    }

    override fun saveStreamingMode(value: Boolean) = sm.setStreamingMode(value)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    app: RolesWitchApp,
    onBack: () -> Unit = {},
    onNavigateToAdvanced: () -> Unit = {},
    onNavigateToAcknowledgements: () -> Unit = {},
    onNavigateToTools: () -> Unit = {},
    onNavigateToLitertModel: () -> Unit = {}
) {
    val settingsManager = app.settingsManager
    val context = LocalContext.current

    // Collected only to drive recomposition; the binding reads the live values.
    settingsManager.selectedModel.collectAsState().value
    settingsManager.openaiAPI.collectAsState().value
    settingsManager.openaiKey.collectAsState().value
    settingsManager.enableVision.collectAsState().value
    settingsManager.embedTime.collectAsState().value
    settingsManager.keepLastAttachment.collectAsState().value
    settingsManager.messageLimit.collectAsState().value
    settingsManager.language.collectAsState().value
    settingsManager.voice.collectAsState().value
    settingsManager.useVosk.collectAsState().value
    settingsManager.allowInterruptions.collectAsState().value
    settingsManager.requireOnDeviceVoice.collectAsState().value
    settingsManager.useSystemTts.collectAsState().value
    settingsManager.useExternalTts.collectAsState().value
    settingsManager.externalTtsVoice.collectAsState().value
    settingsManager.streamingMode.collectAsState().value
    settingsManager.reasoningEffort.collectAsState().value
    settingsManager.useOnDevice.collectAsState().value
    settingsManager.litertModel.collectAsState().value
    settingsManager.litertEnableThinking.collectAsState().value

    // Deliberately NOT remembered. `SettingsBinding` is an interface, so Compose treats it
    // as unstable and — under strong skipping — compares it by instance identity. A
    // remembered binding keeps the same identity forever, so `SettingsSections` was skipped
    // on every recomposition: the collectAsState reads above re-ran this screen, the toggles
    // never re-rendered, and the new state only appeared after leaving and coming back.
    // A fresh instance per composition is what makes the reads above reach the UI, and it
    // matches ModelSettingsScreen, whose binding is constructed the same way and works.
    val binding = GlobalSettingsBinding(settingsManager)
    var showResetAlert by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    // Contact / Privacy / Terms live in this overflow menu rather than in a
                    // body section, mirroring iOS SettingsView's toolbar menu. They are not
                    // gated on anything: Play requires the privacy policy to stay reachable
                    // from inside the app at all times.
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.menu))
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.contact_us)) },
                                leadingIcon = { Icon(Icons.Outlined.Email, null) },
                                onClick = {
                                    showMenu = false
                                    sendMail(context, SUPPORT_EMAIL, "RolesWitch Android Feedback")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.privacy_policy)) },
                                leadingIcon = { Icon(Icons.Outlined.Shield, null) },
                                onClick = {
                                    showMenu = false
                                    openUrl(context, PRIVACY_POLICY_URL)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.terms_and_conditions)) },
                                leadingIcon = { Icon(Icons.Outlined.Description, null) },
                                onClick = {
                                    showMenu = false
                                    openUrl(context, TERMS_URL)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.acknowledgements)) },
                                leadingIcon = { Icon(Icons.Outlined.Info, null) },
                                onClick = {
                                    showMenu = false
                                    onNavigateToAcknowledgements()
                                }
                            )
                        }
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
            SettingsSections(
                app = app,
                binding = binding,
                onNavigateToAdvanced = onNavigateToAdvanced,
                onNavigateToLitertModel = onNavigateToLitertModel
            )

            Spacer(Modifier.height(24.dp))

            ToolsSection(app = app, onNavigateToTools = onNavigateToTools)

            Spacer(Modifier.height(24.dp))

            TextButton(
                onClick = { showResetAlert = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.reset_settings), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(16.dp))

            Text(
                stringResource(R.string.version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showResetAlert) {
        ConfirmDialog(
            text = stringResource(R.string.reset_confirm),
            confirmLabel = stringResource(R.string.reset),
            destructive = true,
            onDismiss = { showResetAlert = false },
            onConfirm = {
                // Runs on the application scope, not this screen's: reset()
                // sends the user back to onboarding, which tears this
                // composition (and a rememberCoroutineScope) down mid-flight.
                app.appScope.launch {
                    app.ttsService.stop()
                    app.sttService.stopRecording()
                    app.database.clearAllData()
                    // Custom models are erased, built-in ones are not, and the
                    // difference is whether the user can get back to the file.
                    // A Gemma from the catalogue survives reset as a download
                    // they keep — it is still listed, still on disk, ready to
                    // use. A model added by URL is only known through the
                    // settings this is about to clear, so leaving its file
                    // behind strands gigabytes with nothing left to delete
                    // them from. Erased *before* reset(), which forgets the
                    // entries that name the files.
                    settingsManager.customLitertModels.value.forEach { model ->
                        app.liteRtService.deleteModel(model)
                    }
                    // No re-seed here: reset() clears hasCompletedOnboarding,
                    // and OnboardingScreen's LaunchedEffect is the one and only
                    // seeding site. Seeding here too used to overlap with it —
                    // both callers saw an empty message_threads, both inserted
                    // the fixed DEFAULT_THREAD_ID, and the loser threw
                    // "UNIQUE constraint failed".
                    settingsManager.reset()
                }
                showResetAlert = false
            }
        )
    }
}
