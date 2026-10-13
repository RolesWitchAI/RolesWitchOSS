//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.roleswitch.android.R
import ai.roleswitch.android.RolesWitchApp
import ai.roleswitch.android.ui.components.KeepScreenOn
import ai.roleswitch.android.ui.components.appOutlinedTextFieldColors
import ai.roleswitch.android.ui.components.rememberLocalNetworkGate
import ai.roleswitch.android.service.VoskModel
import ai.roleswitch.android.service.VoskModelDownloader
import ai.roleswitch.android.service.ExternalTtsNotConfiguredException
import ai.roleswitch.android.settings.SettingsManager
import ai.roleswitch.android.settings.TtsMode
import ai.roleswitch.android.util.normalizeApiUrl
import java.net.URL
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import ai.roleswitch.android.ui.components.ConfirmDialog
import ai.roleswitch.android.ui.util.PRIVACY_POLICY_URL
import ai.roleswitch.android.ui.util.openUrl
import ai.roleswitch.android.util.hostnameFromApiUrl

// --- Primitive layout components shared by SettingsScreen and ModelSettingsScreen ---

@Composable
internal fun SectionHeader(title: String) {
    Text(
        title,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

@Composable
internal fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        content()
    }
}

/**
 * One size for every line of secondary text on the settings screens — row values,
 * subtitles, and the descriptions inside the picker sheets. These had drifted to a
 * mix of 12sp and 14sp, which read as an inconsistency rather than a hierarchy.
 */
private val SecondaryTextSize = 14.sp
private val SecondaryLineHeight = 20.sp

@Composable
internal fun SettingsRow(
    title: String,
    value: String,
    isError: Boolean = false,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val valueColor = if (isError) MaterialTheme.colorScheme.error
        else if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontSize = SecondaryTextSize,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                    lineHeight = SecondaryLineHeight
                )
            }
            // An empty string still lays out a line, so a row that carries only a
            // subtitle (Advanced, Acknowledgements) came out a whole line taller than
            // its neighbours.
            if (value.isNotEmpty()) {
                Text(
                    value,
                    fontSize = SecondaryTextSize,
                    color = valueColor,
                    // Same metrics as the subtitle above, so a row reads the same height
                    // whichever of the two it carries. Without it the value kept the
                    // ambient 24sp leading under 14sp text and ran 4sp taller.
                    lineHeight = SecondaryLineHeight,
                    maxLines = 1
                )
            }
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        )
    }
}

@Composable
internal fun SettingsToggle(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontSize = SecondaryTextSize,
                    lineHeight = SecondaryLineHeight,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = { if (enabled) onCheckedChange(it) },
            enabled = enabled
        )
    }
}

// --- Bottom sheets ---

/**
 * The endpoint and its token, edited together — they are one credential, and a URL
 * saved without the token it needs fails on the next request with nothing on screen
 * to explain why. Saving is all-or-nothing: [onSave] reports both, so the caller can
 * write them in a single update (see `SettingsBinding.saveServer`).
 *
 * The token stays optional; only the URL gates Save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServerSheet(
    url: String,
    token: String,
    hasAcceptedDataSharing: Boolean,
    onAcceptDataSharing: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var urlText by remember { mutableStateOf(url) }
    var tokenText by remember { mutableStateOf(token) }
    var error by remember { mutableStateOf<String?>(null) }
    // The normalized URL that passed validation, held while the Data Sharing alert is up.
    var pendingUrl by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val invalidUrlFormat = stringResource(R.string.invalid_url_format)
    val destination = hostnameFromApiUrl(urlText)
        ?: stringResource(R.string.data_sharing_destination)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Opens fully expanded, the way the MCP editor does: at half height the keyboard
        // left the Cancel/Save row off-screen and reaching it meant scrolling a sheet
        // that also drags.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                // Keeps the Cancel/Save row above the keyboard instead of behind it.
                .imePadding()
        ) {
            // The same header the picker sheets carry, naming the Settings row this was
            // opened from. It names the *sheet*, not either field, so the labels below
            // still have their own work to do — this one holds two fields and no single
            // heading could stand in for both.
            Text(stringResource(R.string.server), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = urlText,
                onValueChange = {
                    urlText = it
                    error = null
                },
                // The fields name themselves, the way the tool editor's do — a bold
                // "API URL" heading above would only repeat the label one line further
                // down. The sheet's own header says "Server", which neither field does.
                label = { Text(stringResource(R.string.api_url)) },
                placeholder = { Text(stringResource(R.string.api_url_placeholder)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { state ->
                        if (!state.isFocused && urlText.isNotBlank()) {
                            val normalized = normalizeApiUrl(urlText)
                            if (normalized != urlText) {
                                urlText = normalized
                                error = null
                            }
                        }
                    },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                isError = error != null,
                colors = appOutlinedTextFieldColors()
            )

            if (error != null) {
                Spacer(Modifier.height(4.dp))
                Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = tokenText,
                onValueChange = { tokenText = it },
                label = { Text(stringResource(R.string.api_token)) },
                placeholder = { Text(stringResource(R.string.your_token)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = appOutlinedTextFieldColors()
            )

            Spacer(Modifier.height(16.dp))

            // Names the host the user is about to send their conversations to, and
            // re-reads it as they type — a warning that says "a remote server" teaches
            // nobody which one they just configured.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.PrivacyTip,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.data_sharing),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    stringResource(R.string.data_sharing_notice, destination),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    stringResource(R.string.read_privacy_policy),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { openUrl(context, PRIVACY_POLICY_URL) }
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        val normalized = normalizeApiUrl(urlText)
                        try {
                            URL(normalized)
                        } catch (_: Exception) {
                            error = invalidUrlFormat
                            return@TextButton
                        }
                        // First save on a fresh install has to pass the Data Sharing alert.
                        // Declining leaves the sheet exactly as it is, edits intact.
                        if (hasAcceptedDataSharing) onSave(normalized, tokenText)
                        else pendingUrl = normalized
                    },
                    // The token is optional, so only the URL can block the save.
                    enabled = urlText.isNotBlank()
                ) { Text(stringResource(R.string.save)) }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    pendingUrl?.let { normalized ->
        ConfirmDialog(
            title = stringResource(R.string.data_sharing),
            text = stringResource(R.string.data_sharing_alert, destination),
            confirmLabel = stringResource(R.string.data_sharing_accept),
            onConfirm = {
                pendingUrl = null
                onAcceptDataSharing()
                onSave(normalized, tokenText)
            },
            onDismiss = { pendingUrl = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TextFieldSheet(
    title: String,
    value: String,
    placeholder: String,
    isPassword: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(value) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Same reason as the Server sheet and the MCP editor: fully expanded so the
        // keyboard never leaves the Cancel/Save row off-screen.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
        ) {
            // The header every picker sheet in Settings carries. It had been dropped in
            // favour of naming the field with a `label`, which left this the one sheet
            // that opens straight onto a bare text box with nothing at the top saying
            // what it is. The label goes in its place rather than beside it, so the title
            // is still said exactly once — the shape `AddModelByUrlDialog` already uses,
            // where a titled dialog holds a field with only a placeholder.
            Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(placeholder) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = if (isPassword) KeyboardOptions(keyboardType = KeyboardType.Password)
                else KeyboardOptions.Default,
                colors = appOutlinedTextFieldColors()
            )

            Spacer(Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))

                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { onSave(text) },
                    enabled = isPassword || text.isNotEmpty()
                ) { Text(stringResource(R.string.save)) }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The external speech voice id, edited in a sheet that proves the voice before it lets
 * the user go — the model picker's contract applied to a save: the request runs with
 * the sheet up, not behind it.
 *
 * The voice is free-form because it is a server-side name — any endpoint may offer
 * names no static list has heard of — and empty is allowed, meaning "let the server
 * pick". The value persists through [onSave] the moment Save is tapped, *before* the
 * request: a permission prompt or denial must not throw the edit away. The greeting is
 * then fetched from the same server the chat targets — [isTesting] spells that out as
 * a spinner exactly how [ModelPickerSheet] spells a model fetch — and the sheet holds
 * until the greeting has been heard, then dismisses itself. A failed request never
 * dismisses anything: the mapped reason — "Voice is not configured" when the endpoint
 * is blank, "Server error" otherwise — stays in the sheet with Save re-armed for a
 * corrected retry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExternalVoiceSheet(
    app: RolesWitchApp,
    title: String,
    value: String,
    placeholder: String,
    language: String,
    endpoint: String,
    token: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(value) }
    // The synthesis request is in flight. Set inside the gate's callback rather than at
    // Save: a denied permission never starts the request, and a spinner set before the
    // gate would have nothing left to clear it.
    var isTesting by remember { mutableStateOf(false) }
    // The greeting is playing — the request succeeded and the utterance has yet to end.
    var isPlaying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val notConfigured = stringResource(R.string.tts_voice_not_configured)
    val serverError = stringResource(R.string.tts_server_error)

    // Saving is what initiates the request to the user-supplied server, so this is where
    // the local-network permission is checked (Android 17, targetSdk 37) — the same
    // moment the save-then-preview flow this sheet replaced used.
    val gateLocalNetwork = rememberLocalNetworkGate {
        isTesting = true
        app.ttsService.speak(
            text = app.ttsService.greeting(language),
            language = language,
            useSystemTts = false,
            useExternalTts = true,
            externalVoice = text.ifEmpty { null },
            externalEndpoint = endpoint,
            externalToken = token,
            onError = { t ->
                error = if (t is ExternalTtsNotConfiguredException) notConfigured else serverError
                isTesting = false
            }
        )
    }

    // Success has two beats: `isSpeaking` rising means the player is prepared — the
    // request is over and the spinner with it — and falling again means the greeting
    // has been heard, which is when the sheet lets go. Two effects because a state
    // write in between would restart a single one and cancel the second wait.
    LaunchedEffect(isTesting) {
        if (!isTesting) return@LaunchedEffect
        app.ttsService.isSpeaking.first { it }
        isTesting = false
        isPlaying = true
    }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        app.ttsService.isSpeaking.first { !it }
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Same reason as the Server sheet and the MCP editor: fully expanded so the
        // keyboard never leaves the Cancel/Save row off-screen.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
        ) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(placeholder) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = appOutlinedTextFieldColors()
            )

            if (isTesting) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.testing_voice),
                        fontSize = SecondaryTextSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = SecondaryTextSize)
            }

            Spacer(Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))

                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        onSave(text)
                        error = null
                        gateLocalNetwork()
                    },
                    enabled = !isTesting
                ) { Text(stringResource(R.string.save)) }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerSheet(
    app: RolesWitchApp,
    apiUrl: String,
    apiKey: String,
    currentModel: String,
    onDismiss: () -> Unit,
    onModelSelected: (String) -> Unit
) {
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var isFetching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    // Hidden by default: a self-hosted endpoint typically offers a handful of models,
    // where a permanent search field is just clutter. Revealed from the header button.
    var isSearching by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val sheetState = rememberModalBottomSheetState()

    // Take the sheet to full height the moment search opens. Otherwise the sheet keeps
    // sizing itself to its content, so every keystroke that changes the match count
    // resizes it and the whole sheet jumps under the finger.
    LaunchedEffect(isSearching) {
        if (isSearching) sheetState.expand()
    }

    // OpenRouter returns hundreds of ids, so scrolling to find one is impractical.
    // Substring match on the whole id: the interesting part is as often the vendor
    // prefix ("anthropic/") as the model name, so anchoring to either end would miss.
    val visibleModels = remember(models, query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) models
        else models.filter { it.contains(trimmed, ignoreCase = true) }
    }

    val canFetch = apiUrl.isNotEmpty()
    val cannotReachServer = stringResource(R.string.cannot_reach_server)
    val scope = rememberCoroutineScope()

    fun fetchModels() {
        scope.launch {
            isFetching = true
            error = null
            try {
                models = app.oaiService.fetchModels(apiUrl, apiKey.ifEmpty { "none" })
            } catch (e: Exception) {
                error = e.message ?: cannotReachServer
            }
            isFetching = false
        }
    }

    // Opening the sheet is what initiates the request to the user-supplied server, so
    // this is where the local-network permission is checked (Android 17, targetSdk 37):
    // one system prompt per install, afterwards an alert pointing at app settings.
    val gateLocalNetwork = rememberLocalNetworkGate { fetchModels() }

    // Deliberately outside the ModalBottomSheet content lambda: LocalView must resolve to
    // the host screen's window, not the sheet's own dialog window. Applies wherever this
    // sheet is opened from — onboarding and Settings alike.
    KeepScreenOn(enabled = isFetching)

    LaunchedEffect(Unit) {
        if (!canFetch) return@LaunchedEffect
        gateLocalNetwork()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // No imePadding() here: ModalBottomSheet already wraps its scrim + content in
        // Modifier.fillMaxSize().imePadding(), so adding it again inflated this Column by
        // a second keyboard height. The sheet sizes to its content, so that pushed the
        // content taller than the screen and the drag anchors overshot — swiping up with
        // the search field focused threw the sheet off the top.
        Column(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.select_model),
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                // Only worth offering once there is a list to narrow down.
                if (models.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            isSearching = !isSearching
                            // Dropping the query on close avoids a hidden filter silently
                            // shortening the list.
                            if (!isSearching) query = ""
                        }
                    ) {
                        Icon(
                            imageVector = if (isSearching) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = stringResource(
                                if (isSearching) R.string.hide_search else R.string.search_models
                            )
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            if (!canFetch) {
                Text(
                    stringResource(R.string.configure_connection_desc),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (isFetching) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.loading_models), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (error != null) {
                Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
            } else if (models.isNotEmpty()) {
                if (isSearching) {
                    // Focus on reveal, so the button is one tap rather than two.
                    LaunchedEffect(Unit) { searchFocusRequester.requestFocus() }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(searchFocusRequester),
                        placeholder = { Text(stringResource(R.string.search_models)) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.clear))
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Search
                        ),
                        colors = appOutlinedTextFieldColors()
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // While searching the results occupy a fixed slot — all the height the
                // expanded sheet has left — so narrowing from 300 matches to 2 changes
                // what is in the list, never how tall it is. Idle, it still wraps its
                // content up to 400dp so a short list doesn't leave a blank slab.
                Box(
                    modifier = if (isSearching) {
                        Modifier.weight(1f).fillMaxWidth()
                    } else {
                        Modifier.heightIn(max = 400.dp).fillMaxWidth()
                    }
                ) {
                    if (visibleModels.isEmpty()) {
                        Text(
                            stringResource(R.string.no_models_match, query.trim()),
                            modifier = Modifier.padding(vertical = 16.dp),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn {
                            items(visibleModels, key = { it }) { model ->
                                val isSelected = model == currentModel
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onModelSelected(model) }
                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(selected = isSelected, onClick = null)
                                    Spacer(Modifier.width(12.dp))
                                    Text(model, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * One labelled option in a boolean engine sheet — [EnginePickerSheet] (inference) or
 * [RecognitionEnginePickerSheet] (recognition); [value] is the boolean it stores.
 *
 * [descRes] is optional: it earns its place only where the two options differ in a
 * way the titles cannot carry, as with continuous on-device recognition versus
 * tap-to-speak. Where the titles already say it, a description is noise.
 * [icon] is optional the same way: the inference sheet gives each engine a glyph
 * (cloud, phone) as iOS's `ModelRow` does; a picker with no such metaphor leaves it
 * null and its row renders text-only.
 */
internal data class EngineOption(
    val value: Boolean,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int? = null,
    val icon: ImageVector? = null
)

/**
 * Picks the inference engine — on device versus remote. The one boolean picker still
 * rendered as [EngineOptionCard]s: its options carry glyphs (cloud, phone) the way iOS's
 * `ModelRow` does, which the card shape exists to frame. Recognition moved to
 * [RecognitionEnginePickerSheet] and synthesis to [TtsEnginePickerSheet], both plain
 * radio rows, leaving this the inference sheet alone.
 *
 * Selection commits immediately; the caller owns anything that follows, such as the
 * jump to system TTS settings when leaving on device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EnginePickerSheet(
    @StringRes titleRes: Int,
    options: List<EngineOption>,
    selected: Boolean,
    onDismiss: () -> Unit,
    onSelected: (Boolean) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Text(stringResource(titleRes), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(12.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                options.forEach { option ->
                    EngineOptionCard(
                        title = stringResource(option.titleRes),
                        description = option.descRes?.let { stringResource(it) }.orEmpty(),
                        icon = option.icon,
                        isSelected = option.value == selected,
                        onTap = { onSelected(option.value) }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * One option of [EnginePickerSheet] as a selectable card — the LiteRT catalogue's
 * `ModelCard` shape carrying iOS `EnginePickerSheet`'s `ModelRow` elements: a tinted
 * icon circle, and the selected option stroked in the theme colour with a tick. The
 * whole card taps; a null [icon] drops the circle and the row renders text-only.
 */
@Composable
private fun EngineOptionCard(
    title: String,
    description: String,
    icon: ImageVector?,
    isSelected: Boolean,
    onTap: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 2.dp,
        tonalElevation = 1.dp,
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onTap)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                if (description.isNotEmpty()) {
                    Text(
                        description,
                        fontSize = SecondaryTextSize,
                        lineHeight = SecondaryLineHeight,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            if (isSelected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Icon(
                    Icons.Outlined.Circle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/**
 * Picks the recognition engine — System versus offline (Vosk) — in the
 * [TtsEnginePickerSheet] row style: a radio button per option, with the description as
 * the subtitle. The card shape of [EnginePickerSheet] stays the inference sheet's, whose
 * options carry glyphs; recognition's two options have none and read as plain rows.
 *
 * Selection commits immediately; the caller owns anything that follows, such as the
 * model download for offline recognition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecognitionEnginePickerSheet(
    @StringRes titleRes: Int,
    options: List<EngineOption>,
    selected: Boolean,
    onDismiss: () -> Unit,
    onSelected: (Boolean) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Text(stringResource(titleRes), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(12.dp))

            options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelected(option.value) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = option.value == selected, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(option.titleRes))
                        option.descRes?.let {
                            Text(
                                stringResource(it),
                                fontSize = SecondaryTextSize,
                                lineHeight = SecondaryLineHeight,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * One labelled option in a [TtsEnginePickerSheet]. [mode] is the [TtsMode] it selects.
 */
internal data class TtsEngineOption(
    val mode: TtsMode,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int? = null
)

/**
 * Picks between the three speech engines — Auto, System, External (OpenAI) — unlike
 * [EnginePickerSheet], which is boolean-only. The engine is an either/or choice across
 * *two* stored flags, and callers pass explicitly the options that apply (the external
 * row is dropped while \"Process on device\" is on), so the sheet itself never guesses.
 *
 * Selection commits immediately; the caller owns saving through `saveTtsMode`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TtsEnginePickerSheet(
    @StringRes titleRes: Int,
    options: List<TtsEngineOption>,
    selected: TtsMode,
    onDismiss: () -> Unit,
    onSelected: (TtsMode) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Text(stringResource(titleRes), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(12.dp))

            options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelected(option.mode) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = option.mode == selected, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(option.titleRes))
                        option.descRes?.let {
                            Text(
                                stringResource(it),
                                fontSize = SecondaryTextSize,
                                lineHeight = SecondaryLineHeight,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * One option in [ReasoningPickerSheet]. [value] is the literal `reasoning_effort` sent to
 * the server, so it must stay in the API's vocabulary — except for `""`, which is this
 * app's own marker for "send nothing".
 */
internal data class ReasoningOption(
    val value: String,
    @StringRes val titleRes: Int
)

/**
 * The Reasoning choices, in ascending order of effort.
 *
 * `""` leads deliberately: it is the default, and it is the only one that changes nothing
 * about the request. The named levels are what the Chat Completions API documents for
 * `reasoning_effort`; `"none"` is the newer addition that turns reasoning off outright, and
 * a server that does not implement it will reject it rather than quietly ignore it — which
 * is why Auto sends nothing at all rather than picking `"medium"` on the user's behalf.
 */
internal val reasoningOptions = listOf(
    ReasoningOption("", R.string.reasoning_auto),
    ReasoningOption("none", R.string.reasoning_none),
    ReasoningOption("low", R.string.reasoning_low),
    ReasoningOption("medium", R.string.reasoning_medium),
    ReasoningOption("high", R.string.reasoning_high)
)

/**
 * Label for a stored Reasoning value. An unrecognised one shows verbatim rather than
 * falling back to "Auto" — a thread carrying a value this build has no name for is
 * still sending it, and the row must not claim otherwise.
 */
@Composable
internal fun reasoningLabel(value: String): String {
    val titleRes = reasoningOptions.firstOrNull { it.value == value }?.titleRes
    return if (titleRes != null) stringResource(titleRes) else value
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReasoningPickerSheet(
    current: String,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Text(stringResource(R.string.reasoning_mode), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(12.dp))

            reasoningOptions.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelected(option.value) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = option.value == current, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(option.titleRes), modifier = Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguagePickerSheet(
    currentLanguage: String,
    onDismiss: () -> Unit,
    onLanguageSelected: (String) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Text(stringResource(R.string.language), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(12.dp))

            SettingsManager.defaultLanguages.entries.sortedBy { it.value }.forEach { (key, name) ->
                val isSelected = key == currentLanguage
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onLanguageSelected(key) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = isSelected, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(name, modifier = Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageLimitSheet(
    currentLimit: Int,
    onDismiss: () -> Unit,
    onLimitSelected: (Int) -> Unit,
    isOnDevice: Boolean = false
) {
    val options = buildList {
        add(20 to "20")
        add(50 to "50")
        add(80 to "80")
        add(120 to "120")
        // "Unlimited" would hang an on-device turn: the conversation is re-prefilled
        // every turn, so the on-device engine caps at 120 instead (see LLMService).
        if (!isOnDevice) add(100000 to stringResource(R.string.unlimited))
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Text(stringResource(R.string.messages_to_process), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(12.dp))

            options.forEach { (limit, label) ->
                val isSelected = limit == currentLimit
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onLimitSelected(limit) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = isSelected, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(label, modifier = Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoicePickerSheet(
    app: RolesWitchApp,
    language: String,
    currentVoice: String,
    requireOnDeviceVoice: Boolean,
    useSystemTts: Boolean,
    onDismiss: () -> Unit,
    onVoiceSelected: (String) -> Unit
) {
    // Queried every time the sheet opens rather than cached: getAvailableVoices
    // resolves the engine system Settings names right now, re-binding first if the
    // user changed it while we were backgrounded, so the list is never stale.
    //
    // null means "still resolving", which is distinct from a resolved-but-empty
    // list: after an engine change the query has to await the new engine's bind,
    // and treating that gap as "no voices" flashed the empty-state message.
    var allVoices by remember {
        mutableStateOf<List<android.speech.tts.Voice>?>(null)
    }
    // Keyed on currentVoice so a sheet composed across a voice reset (switching
    // engines clears the stored voice) doesn't keep showing the old selection.
    var selected by remember(currentVoice) { mutableStateOf(currentVoice) }
    LaunchedEffect(language, useSystemTts) {
        // Back to loading: the list on screen belongs to the previous engine.
        allVoices = null
        val resolved = app.ttsService.getAvailableVoices(language, useSystemTts)
        allVoices = resolved
        // A different engine may not offer the stored voice; don't let Save persist
        // a name it no longer has. Empty means "System default", always valid.
        if (selected.isNotEmpty() && resolved.none { it.name == selected }) selected = ""
    }
    val voices = remember(allVoices, requireOnDeviceVoice) {
        allVoices?.let { loaded ->
            if (requireOnDeviceVoice) loaded.filter { !it.isNetworkConnectionRequired }
            else loaded
        }
    }
    val greetingHello = app.ttsService.greeting(language)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(top = 16.dp).fillMaxWidth()) {
            Text(stringResource(R.string.select_voice), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(12.dp))

            // The list is already narrowed to offline voices when "Prefer offline
            // processing" is on — that toggle lives in the Audio section, since it
            // governs recognition as well and the two shouldn't disagree.

            // System default
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        selected = ""
                        app.ttsService.speak(greetingHello, language, "", useSystemTts)
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selected.isEmpty(), onClick = null)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.system_default), modifier = Modifier.weight(1f))
            }

            if (voices == null) {
                // Same height as the list below, so the sheet doesn't resize when
                // the voices arrive.
                Box(
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                }
            } else if (voices.isEmpty()) {
                Text(
                    stringResource(R.string.no_voices_available),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(modifier = Modifier.height(300.dp)) {
                    items(voices.size) { index ->
                        val v = voices[index]
                        val isSelected = v.name == selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = v.name
                                    app.ttsService.speak(greetingHello, language, v.name, useSystemTts)
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = isSelected, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(v.name)
                                Text(
                                    "${v.locale.displayLanguage} (${v.locale.displayCountry})",
                                    fontSize = SecondaryTextSize,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                 Spacer(Modifier.width(8.dp))
                 TextButton(onClick = { onVoiceSelected(selected) }) { Text(stringResource(R.string.save)) }
             }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Non-dismissable (except via Cancel) bottom sheet that streams a Vosk model
 * zip from alphacephei.com, verifies CRC32, and extracts it into `filesDir`.
 * Mirrors the project's `ModalBottomSheet` pattern but drops the drag handle
 * and vetoes the swipe-to-dismiss settle, so the only exits are the buttons
 * and scrim/back, which route through [VoskModelDownloader.cancel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoskDownloadSheet(
    model: VoskModel,
    downloader: VoskModelDownloader,
    onSuccess: () -> Unit,
    onCorrupted: () -> Unit,
    onCancelled: () -> Unit
) {
    val state by downloader.state.collectAsState()
    // Vetoing Hidden is what actually blocks the swipe-to-dismiss gesture:
    // dragHandle = null only removes the visual handle, and a drag past the
    // bottom anchor otherwise settles Hidden -> onDismissRequest -> cancel.
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden }
    )

    // Trigger the download once on first composition.
    LaunchedEffect(model.dirName) {
        downloader.start(model, onSuccess = onSuccess)
    }

    // React to terminal states emitted by the downloader.
    //
    // Only the corrupt case leaves the sheet: it has an alert of its own, which says more
    // than a CRC message would. Every other failure — no network, an HTTP error, no room on
    // disk — stays put and is *shown*. Closing on those was silent: the download stopped,
    // the sheet vanished, the offline-voice toggle stayed off, and nothing said why. It is
    // also what left the `Failed` branch below unreachable.
    //
    // Cancellation does not come through here at all: it sets the state back to null, so a
    // `Failed` is always a real failure.
    LaunchedEffect(state) {
        val failed = state as? VoskModelDownloader.State.Failed ?: return@LaunchedEffect
        if (failed.crcMismatch) onCorrupted()
    }

    ModalBottomSheet(
        onDismissRequest = {
            // Scrim tap / back press -> cancel the in-flight download.
            downloader.cancel()
            onCancelled()
        },
        sheetState = sheetState,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 28.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val titleRes = when (state) {
                is VoskModelDownloader.State.Downloading -> R.string.downloading_vosk_model
                VoskModelDownloader.State.Verifying -> R.string.verifying_download
                VoskModelDownloader.State.Extracting -> R.string.extracting_model
                VoskModelDownloader.State.Done -> R.string.vosk_ready
                is VoskModelDownloader.State.Failed -> R.string.vosk_download_failed
                null -> R.string.downloading_vosk_model
            }
            Text(stringResource(titleRes), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(16.dp))

            when (val s = state) {
                is VoskModelDownloader.State.Downloading -> {
                    val total = s.total.coerceAtLeast(1L)
                    val pct = (s.downloaded.toFloat() / total).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { pct },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${s.downloaded / 1_048_576} / ${s.total / 1_048_576} MB  (${(pct * 100).toInt()}%)",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                VoskModelDownloader.State.Verifying,
                VoskModelDownloader.State.Extracting -> {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                }
                VoskModelDownloader.State.Done -> {
                    // The LaunchedEffect above calls onSuccess via the downloader callback;
                    // no inline UI needed here.
                }
                is VoskModelDownloader.State.Failed -> {
                    Text(
                        s.message,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp
                    )
                }
                null -> {}
            }

            Spacer(Modifier.height(24.dp))

            // After a failure there is nothing in flight to cancel, so the buttons become
            // the two things left to do: try the same download again, or give up and close.
            if (state is VoskModelDownloader.State.Failed) {
                TextButton(
                    onClick = { downloader.start(model, onSuccess = onSuccess) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.retry)) }
                TextButton(
                    onClick = onCancelled,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.close)) }
            } else {
                TextButton(
                    onClick = {
                        downloader.cancel()
                        onCancelled()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.cancel)) }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
