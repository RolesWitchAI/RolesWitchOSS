//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.ui.messages

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import ai.roleswitch.android.R
import ai.roleswitch.android.RolesWitchApp
import ai.roleswitch.android.service.LitertModelStore
import ai.roleswitch.android.service.LitertModels
import ai.roleswitch.android.service.LiteRtService
import ai.roleswitch.android.data.model.Message
import ai.roleswitch.android.data.model.MessageThread
import ai.roleswitch.android.data.model.DocumentInput
import ai.roleswitch.android.settings.EffectiveSettings
import ai.roleswitch.android.settings.TtsMode
import ai.roleswitch.android.service.OAIError
import ai.roleswitch.android.ui.assistant.CameraCaptureScreen
import ai.roleswitch.android.ui.components.BubbleMessage
import ai.roleswitch.android.ui.components.rememberPermissionRequester
import ai.roleswitch.android.ui.components.ConfirmDialog
import ai.roleswitch.android.ui.components.navigateBasedOnThread
import ai.roleswitch.android.ui.components.InfoDialog
import ai.roleswitch.android.ui.components.KeepScreenOn
import ai.roleswitch.android.ui.components.LitertEngineSyncEffect
import ai.roleswitch.android.ui.components.LitertWarmUpFailureBanner
import ai.roleswitch.android.ui.components.LitertWarmUpOverlay
import ai.roleswitch.android.ui.components.rememberLitertInitializing
import ai.roleswitch.android.ui.components.ModelNotConfiguredDialog
import ai.roleswitch.android.ui.components.PermissionDeniedDialog
import ai.roleswitch.android.ui.components.ThreadAvatar
import ai.roleswitch.android.ui.components.composerTextFieldColors
import ai.roleswitch.android.ui.components.TypingBubble
import ai.roleswitch.android.ui.util.IMAGE_ONLY_MESSAGE
import ai.roleswitch.android.ui.util.DOCUMENT_ONLY_MESSAGE
import ai.roleswitch.android.ui.util.uriToCompressedBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.navigation.NavController



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    app: RolesWitchApp,
    navController: NavController,
    threadId: String,
    onBack: () -> Unit,
    onNavigateToCall: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    onNavigateToModel: () -> Unit = {}
) {
    val settingsManager = app.settingsManager
    val llmService = app.llmService
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboard.current
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val isThinking by llmService.isThinking.collectAsState()
    val isCallingTool by llmService.isCallingTool.collectAsState()
    val isStreaming by llmService.isStreaming.collectAsState()
    val streamingResponse by llmService.streamingResponse.collectAsState()

    // System back (edge-swipe or the nav-bar button) must run the same guard as the
    // on-screen back arrow: while the model is thinking, leaving pops the screen and
    // silently discards the request. `onBack` already routes through that guard.
    BackHandler(onBack = onBack)

    // Leading whitespace is not content. Models routinely open with " " or "\n", and a raw
    // isNotEmpty() check on that first delta swaps the typing dots for a bubble holding
    // nothing — an empty pill. Trimming the start also keeps MarkdownText from reading four
    // or more leading spaces as an indented code block. Every display-side read of the
    // stream goes through this.
    val streamedText = streamingResponse.trimStart()

    var messages by remember { mutableStateOf(listOf<Message>()) }
    var bufferedStreamingContent by remember { mutableStateOf("") }
    var currentThread by remember { mutableStateOf<MessageThread?>(null) }

    // Effective settings: per-thread overrides when enabled, otherwise the global values.
    // Recomputed when the thread changes; global settings are edited in a separate screen.
    val effective = remember(currentThread) {
        EffectiveSettings.resolve(currentThread ?: MessageThread(threadId = threadId, title = ""), settingsManager)
    }
    // [effective] again, but only once the thread row is really loaded — null before that.
    // The placeholder resolves every override to the *global* value, which is fine for the
    // gate and the vision check (both re-read on the next redraw) but not for anything that
    // acts once on it: the engine below it. See the engine sync's own note.
    val resolvedSettings: EffectiveSettings? = currentThread?.let { thread ->
        EffectiveSettings.resolve(thread, settingsManager)
    }
    // Loads the on-device model in the background as soon as the thread opens, when
    // this thread is set to run on device. No-op for the remote engine.
    // `eager = false`: the model loads when the user sends, not when they open the
    // thread. Entering only drops a model this thread cannot use.
    LitertEngineSyncEffect(app, resolvedSettings, eager = false)
    val isPreparingModel = rememberLitertInitializing(app)

    var inputText by remember { mutableStateOf("") }
    var editingMessage by remember { mutableStateOf<Message?>(null) }
    var showErrorAlert by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var showMenuDropdown by remember { mutableStateOf(false) }
    var showClearHistoryAlert by remember { mutableStateOf(false) }
    var selectedMessageForMenu by remember { mutableStateOf<Message?>(null) }
    var showReasoningSheet by remember { mutableStateOf(false) }
    var reasoningText by remember { mutableStateOf("") }
    var showFavoritesSheet by remember { mutableStateOf(false) }
    var pendingScrollToMessageId by remember { mutableStateOf<String?>(null) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var showCameraCapture by remember { mutableStateOf(false) }
    // One-shot "Some models does not support multiple attachments" gate. Fires when a
    // second attachment is about to be staged and the user has not yet accepted multi-attach;
    // while the alert is up, [pendingAttachmentAppend] holds the staging that OK commits and
    // Cancel drops. Once OK is tapped the preference latches and the composer never asks again.
    var showMultiAttachGateAlert by remember { mutableStateOf(false) }
    var pendingAttachmentAppend by remember { mutableStateOf<(() -> Unit)?>(null) }

    // What the resident engine actually brought up, as against what the settings ask for.
    // Only meaningful once something *is* resident, hence the Ready check below.
    val visionActive by app.liteRtService.visionActive.collectAsState()
    val engineReady by app.liteRtService.initState.collectAsState()
    var showModelNotConfiguredAlert by remember { mutableStateOf(false) }
    var showMicPermissionAlert by remember { mutableStateOf(false) }
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedMessageIds = remember { mutableStateListOf<String>() }
    val pendingImages = remember { mutableStateListOf<ByteArray>() }
    val pendingDocuments = remember { mutableStateListOf<DocumentInput>() }

    // Every way into the composer's attachment lists goes through here. The first
    // attachment stages freely; a *second* one (images and documents share one budget)
    // stages directly once the one-shot gate has been accepted, and otherwise parks the
    // staging behind the gate alert — OK latches the preference and commits it, Cancel
    // drops the pick. `settingsManager` is the app singletons' shared instance, and the
    // lists are mutable state lists, so the pending count read here is live.
    val addAttachment: ((() -> Unit) -> Unit) = { append ->
        if (pendingImages.size + pendingDocuments.size >= 1 &&
            !settingsManager.hasAcceptedMultipleAttachments.value
        ) {
            pendingAttachmentAppend = append
            showMultiAttachGateAlert = true
        } else {
            append()
        }
    }

    KeepScreenOn(enabled = isThinking || isStreaming)

    val listState = rememberLazyListState()

    // Tracks the last seen message id so auto-scroll fires only on genuine new
    // messages (not on reload/restore/delete). initialHandled distinguishes the
    // first load (preserve restored position) from later appends (jump to end).
    var lastSeenId by remember { mutableStateOf<String?>(null) }
    var initialHandled by remember { mutableStateOf(false) }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val bytes = uriToCompressedBytes(context, it)
            if (bytes != null) addAttachment { pendingImages.add(bytes) }
        }
    }

    // The system document picker (Files), filtered to PDFs. The whole file is read into
    // memory up front — extraction needs the bytes, and staging keeps them only until
    // send; they never touch the database or the network.
    val documentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val displayName = queryDisplayName(context, it) ?: "document.pdf"
            val bytes = readUriBytes(context, it)
            if (bytes != null) addAttachment { pendingDocuments.add(DocumentInput(displayName, bytes)) }
        }
    }

    // In-process capture: the camera is bound inside this process (CameraX), so there is
    // no second, camera-sized process for this one to hold its weights alongside. The
    // release that used to precede the system camera is gone with the intent that needed it.
    val launchCamera = {
        showCameraCapture = true
    }

    val requestCamera = rememberPermissionRequester(Manifest.permission.CAMERA) { launchCamera() }

    // Engine-aware: on device there is no endpoint or token to check, only a model — and
    // the model is only as configured as its weights on disk. A selection whose model was
    // deleted reads as configured to the registry but would die in the engine, so the
    // guard catches it here and routes to settings instead, exactly as on iOS.
    val hasValidEffectiveModel = effective.isConfigured && (!effective.useOnDevice ||
        LitertModels.get(effective.litertModel)
            ?.let { LitertModelStore.isDownloaded(context, it) } == true)

    // The call speaks through the external (OpenAI) engine when the thread resolves to it,
    // and that engine is only as real as its server: a blank speech endpoint would open a
    // call whose every reply died in "Voice is not configured". Every other mode — system,
    // in-app, on-device — needs nothing here.
    val hasValidVoice = effective.ttsMode != TtsMode.EXTERNAL || effective.externalTtsAPI.isNotEmpty()

    // Warm-up belongs to this screen, on both paths that commit the user to an answer:
    // sending (which loads inside `onSend`) and starting a call. The call screen opens the
    // mic the moment it appears, so navigating first meant the first turn was spoken into a
    // model that had not finished loading — transcribed with nothing there to answer it.
    // Loading here puts the wait behind this screen's own warm-up overlay and a failure
    // behind its retry banner, and the call opens only once there is an engine to take it.
    //
    // `resolvedSettings`, not `effective`: a tap can land in the frames before the thread row
    // arrives, and the placeholder resolves the engine to the *global* choice — so a thread
    // that overrides the engine to remote would have loaded the global model before the call
    // that would never use it. Without a row there is no engine to pre-warm for, so the call
    // just opens; the call screen re-checks `syncTo` under the blocking overlay before the
    // mic goes live, so a genuine on-device thread still waits for its engine, one screen later.
    val startCall: () -> Unit = {
        val settings = resolvedSettings
        if (settings?.useOnDevice == true) {
            scope.launch { if (app.liteRtService.syncTo(settings)) onNavigateToCall() }
        } else {
            onNavigateToCall()
        }
    }

    val requestMic = rememberPermissionRequester(
        permission = Manifest.permission.RECORD_AUDIO,
        onDenied = { showMicPermissionAlert = true }
    ) {
        // One guard for both call prerequisites — model and, new, the external speech
        // server — and one alert for either. The alert keeps the model's wording: the
        // fix (the Settings screen its button opens) reaches both halves.
        if (!hasValidEffectiveModel || !hasValidVoice) {
            showModelNotConfiguredAlert = true
        } else {
            startCall()
        }
    }

    val checkModelAndSend = {
        if (hasValidEffectiveModel) {
            onSend(
                app, threadId, inputText, pendingImages.toList(), pendingDocuments.toList(), scope,
                onClear = {
                    inputText = ""
                    if (!effective.keepLastAttachment) {
                        pendingImages.clear()
                        pendingDocuments.clear()
                    }
                },
                onError = { _, m -> errorMessage = m; showErrorAlert = true }
            )
        } else {
            showModelNotConfiguredAlert = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            llmService.cancelRequest()
            pendingImages.clear()
            pendingDocuments.clear()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        settingsManager.setHasSeenNotificationPrompt(true)
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !settingsManager.hasSeenNotificationPrompt.value &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(threadId) {
        app.database.messageThreadDao().observeThreadById(threadId).collect {
            currentThread = it
        }
    }
    LaunchedEffect(threadId) {
        app.database.messageDao().observeMessagesByThread(threadId).collect {
            messages = it
        }
    }

    // Holds the last streamed text across the gap between the stream ending and
    // the assistant row arriving from Room, so the bubble doesn't blink out.
    // Cleared once that row lands.
    LaunchedEffect(isStreaming, streamedText, messages) {
        if (isStreaming && streamedText.isNotEmpty()) {
            bufferedStreamingContent = streamedText
        } else if (!isStreaming && bufferedStreamingContent.isNotEmpty()) {
            val lastMsg = messages.lastOrNull()
            if (lastMsg != null && !lastMsg.isUser) {
                bufferedStreamingContent = ""
            }
        }
    }

    // ...but on a turn that never persists a row — stop button, error, empty completion —
    // no assistant message is coming, so the check above can never clear the buffer and
    // the bubble would be left showing partial text no Message backs (not long-pressable,
    // and stale for the rest of the session).
    LaunchedEffect(threadId) {
        llmService.responseDiscarded.collect { discardedThreadId ->
            if (discardedThreadId == threadId) bufferedStreamingContent = ""
        }
    }

    // Whether a bubble trails the last message, and which one. Computed here rather than
    // inside the list builder because scrollToEnd needs the same answer: the bubble is an
    // extra item, so scrolling only as far as the last message leaves it below the fold.
    // (The buffer itself is maintained by a LaunchedEffect above — writing state from
    // inside the list builder would be a side effect at composition time.)
    val showStreamingBubble = streamedText.isNotEmpty() ||
        (bufferedStreamingContent.isNotEmpty() && (messages.lastOrNull()?.isUser != false))
    val showTypingBubble = !showStreamingBubble && (isThinking || isCallingTool)
    // Whether the composer is live. In streaming mode a turn in flight locks it — the
    // text field, the attach menu, and the messages' long-press menus (edit/delete would
    // rewrite history the reply is still being written into); in one-shot mode (Streaming
    // mode off) it stays live so a new message can supersede the in-flight turn — sending
    // cancels it and starts another.
    val composerLive = !effective.streamingMode || (!isStreaming && !isThinking)
    // Whether a reply is being generated right now — the same thinking/streaming pair the
    // composer lock reads, but unconditional on Streaming mode: one-shot keeps the composer
    // itself live so a new message can supersede the in-flight turn, yet the model is still
    // generating. While it holds, the message bubbles are read-only: tapping an image does
    // not open the full-size viewer and the long-press menu is withheld with the same guard.
    val bubblesLocked = isThinking || isStreaming

    suspend fun scrollToEnd() {
        // Item layout: leading spacer, the messages, the optional bubble, trailing spacer.
        val trailingSpacerIndex =
            messages.size + 1 + if (showStreamingBubble || showTypingBubble) 1 else 0
        listState.scrollToItem(trailingSpacerIndex, scrollOffset = Int.MAX_VALUE)
    }

    // Auto-scroll: jump to the latest when a new message arrives, but don't snap
    // on reload/restore (preserves the user's scroll position when the process
    // is recreated in the background).
    LaunchedEffect(messages) {
        val lastId = messages.lastOrNull()?.id
        if (!initialHandled) {
            initialHandled = true
            // Fresh entry (no meaningful restored position) -> show the latest.
            // A restored listState sits at a non-zero index/offset and is left as-is.
            if (messages.isNotEmpty() &&
                listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset == 0
            ) {
                scrollToEnd()
            }
        } else if (lastId != null && lastId != lastSeenId) {
            // A new message arrived -> always jump to the latest.
            scrollToEnd()
        }
        lastSeenId = lastId
    }

    LaunchedEffect(streamedText) {
        if (isStreaming && streamedText.isNotEmpty()) {
            // Scroll to the bottom of the streaming bubble as it grows
            scrollToEnd()
        }
    }

    // The dots appear a beat after the user's message has already been scrolled to —
    // isThinking only flips once the request starts — and they add a list item below it.
    // Without this the list stays where the message put it and the dots sit off-screen
    // on any thread already tall enough to fill the viewport.
    LaunchedEffect(showTypingBubble) {
        if (showTypingBubble) scrollToEnd()
    }

    // The IME pushes the list up (imePadding below) but never resets where it is scrolled:
    // on a thread tall enough to fill the screen, the newest message ends the keyboard's
    // arrival clipped below the fold. Re-aim at the latest the moment the keyboard appears.
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0.dp) scrollToEnd()
    }

    // Scroll to a specific message (from favorites sheet)
    LaunchedEffect(pendingScrollToMessageId) {
        val targetId = pendingScrollToMessageId ?: return@LaunchedEffect
        val index = messages.indexOfFirst { it.id == targetId }
        if (index >= 0) {
            // +1 for the leading spacer item, otherwise the message above lands at the top.
            listState.scrollToItem(index + 1)
        }
        pendingScrollToMessageId = null
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    val titleText = currentThread?.title ?: stringResource(R.string.tab_messages)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onNavigateToProfile() }
                    ) {
                        ThreadAvatar(
                            name = titleText,
                            avatarBytes = currentThread?.avatarBytes,
                            size = 40.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                titleText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    lineHeightStyle = LineHeightStyle(
                                        alignment = LineHeightStyle.Alignment.Center,
                                        trim = LineHeightStyle.Trim.Both
                                    )
                                )
                            )
                            Text(
                                // A running tool takes priority: it is the one state where
                                // nothing is streaming and the wait is not the model's.
                                when {
                                    isCallingTool -> stringResource(R.string.using_tool)
                                    isThinking -> stringResource(R.string.thinking)
                                    else -> stringResource(R.string.ai_disclaimer)
                                },
                                // Ellipsized like the title above it. These strings are long
                                // in ru ("Использую инструмент…") and wrapped to a second line
                                // on narrow screens, which pushed the bar taller mid-turn.
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 12.sp,
                                    lineHeight = 14.sp,
                                    lineHeightStyle = LineHeightStyle(
                                        alignment = LineHeightStyle.Alignment.Center,
                                        trim = LineHeightStyle.Trim.Both
                                    )
                                )
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = requestMic) {
                        Icon(Icons.Default.Call, stringResource(R.string.start_call))
                    }
                    Box {
                        IconButton(onClick = { showMenuDropdown = true }) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.menu))
                        }
                        DropdownMenu(
                            expanded = showMenuDropdown,
                            onDismissRequest = { showMenuDropdown = false }
                        ) {
                            // Every item carries its glyph, as the per-message sheet below
                            // already does and as the iOS menu does — `person.crop.circle`,
                            // `slider.horizontal.3`, `star` and `trash`, in their Material
                            // equivalents. This menu was the one place they were missing.
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.profile)) },
                                leadingIcon = { Icon(Icons.Default.AccountCircle, null) },
                                onClick = {
                                    showMenuDropdown = false
                                    onNavigateToProfile()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.configure_model)) },
                                leadingIcon = { Icon(Icons.Default.Tune, null) },
                                onClick = {
                                    showMenuDropdown = false
                                    onNavigateToModel()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.show_favorites)) },
                                leadingIcon = { Icon(Icons.Default.Star, null) },
                                onClick = {
                                    showMenuDropdown = false
                                    showFavoritesSheet = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.clear_history), color = MaterialTheme.colorScheme.error) },
                                // Tinted with the label, the way the per-message Delete item
                                // is — a destructive row is red in both halves or neither.
                                leadingIcon = {
                                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                                },
                                onClick = {
                                    showMenuDropdown = false
                                    showClearHistoryAlert = true
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        // Box, not Column, so the on-device warm-up overlay can paint over the whole
        // conversation while the model loads. imePadding rides on the adjustResize in the
        // manifest: as the IME slides in, the insets it reports animate the list and the
        // composer up in sync — without it the window jumped instantly and the keyboard
        // then slid over a bare background strip at the bottom.
        Box(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Messages list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { Spacer(Modifier.height(8.dp)) }

                items(messages, key = { it.id }) { message ->
                    if (isSelectionMode) {
                        val isSelected = message.id in selectedMessageIds
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (isSelected) selectedMessageIds.remove(message.id)
                                else selectedMessageIds.add(message.id)
                            }
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = {
                                    if (it) selectedMessageIds.add(message.id)
                                    else selectedMessageIds.remove(message.id)
                                },
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Box(Modifier.weight(1f)) {
                                BubbleMessage(message = message)
                            }
                        }
                    } else {
                        BubbleMessage(
                            message = message,
                            imagePreviewEnabled = !bubblesLocked,
                            // Same guard as the image preview: while a reply is being
                            // generated the bubble is read-only — edit/delete would
                            // rewrite history the reply is still being written into.
                            onLongPress = { if (!bubblesLocked) selectedMessageForMenu = message }
                        )
                    }
                }

                // showStreamingBubble keeps the bubble alive during the gap between
                // isStreaming flipping false and the assistant row arriving from Room.
                if (showStreamingBubble) {
                    item(key = "streaming") {
                        BubbleMessage(
                            content = if (streamedText.isNotEmpty()) streamedText else bufferedStreamingContent,
                            isUser = false,
                            isStreaming = true
                        )
                    }
                } else if (showTypingBubble) {
                    // Nothing has streamed yet. With tools that gap is long — the model's
                    // first round and the tool call both finish before a single token of
                    // the answer arrives — so the list would otherwise sit empty for tens
                    // of seconds after sending. The dots say "working"; the header says
                    // what kind of work it is.
                    item(key = "typing") { TypingBubble() }
                }

                item { Spacer(Modifier.height(8.dp)) }
            }

            if (isSelectionMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        isSelectionMode = false
                        selectedMessageIds.clear()
                    }) {
                        Text(stringResource(R.string.cancel))
                    }
                    Text(
                        stringResource(R.string.messages_selected, selectedMessageIds.size),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = {
                            val idsToDelete = selectedMessageIds.toSet()
                            val msgsToDelete = messages.filter { it.id in idsToDelete }
                            messages = messages.filter { it.id !in idsToDelete }
                            selectedMessageIds.clear()
                            isSelectionMode = false
                            scope.launch {
                                msgsToDelete.forEach { app.database.messageDao().delete(it) }
                            }
                        },
                        enabled = selectedMessageIds.isNotEmpty()
                    ) {
                        Text(
                            stringResource(R.string.delete),
                            color = if (selectedMessageIds.isNotEmpty()) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Image previews
            if (!isSelectionMode && pendingImages.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pendingImages.forEachIndexed { index, bytes ->
                        Box {
                            val bitmap = remember(bytes) {
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = stringResource(R.string.attached_image),
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            IconButton(
                                onClick = { pendingImages.removeAt(index) },
                                modifier = Modifier
                                    .size(20.dp)
                                    .align(Alignment.TopEnd)
                            ) {
                                Icon(
                                    Icons.Default.Close, stringResource(R.string.remove),
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            // Document previews: filename chips, one per staged file. No text is shown —
            // extraction happens on send — just what was picked and a way to drop it.
            if (!isSelectionMode && pendingDocuments.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pendingDocuments.forEachIndexed { index, doc ->
                        androidx.compose.material3.Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp)
                            ) {
                                Icon(
                                    Icons.Default.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = doc.fileName,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 180.dp)
                                )
                                IconButton(
                                    onClick = { pendingDocuments.removeAt(index) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        stringResource(R.string.remove),
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Message input
            if (!isSelectionMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // On device the tower may have failed to build even though the setting
                    // is on, and the model is only loaded once the user sends — so this
                    // cannot be known before the attempt.
                    val visionUnsupported = effective.useOnDevice &&
                        effective.visionAvailable && !visionActive &&
                        engineReady is LiteRtService.InitState.Ready
                    // Whether this thread can attach *images*: vision off, or an on-device
                    // checkpoint that cannot read images, leaves nothing for the photo and
                    // camera entries to do. Documents never need vision, so the clip itself
                    // is always offered and only these image entries grey out without it.
                    val canAttachVision = effective.visionAvailable && !visionUnsupported
                    // The states that only grey it out — they pass on their own. There is no
                    // attachment-count cap any more: the composer accepts any number of
                    // attachments, with the one-shot multi-attach gate guarding the second one
                    // (see [addAttachment]).
                    val canAttach = composerLive && !isPreparingModel
                    val canAttachImage = canAttach && canAttachVision

                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        // The one filled field in the app, and the only one with no label:
                        // the composer is a single unnamed input in the middle of a
                        // conversation, not a form row. See composerTextFieldColors.
                        placeholder = { Text(stringResource(R.string.type_message)) },
                        shape = RoundedCornerShape(24.dp),
                        colors = composerTextFieldColors(),
                        // Also blocked while the on-device model loads: there is
                        // nothing to send to yet.
                        enabled = composerLive && !isPreparingModel,
                        // Enter inserts a newline instead of sending: the send button
                        // already sits on screen next to the field. On a multiline field
                        // Compose maps Default to IME_FLAG_NO_ENTER_ACTION (plus
                        // TYPE_TEXT_FLAG_MULTI_LINE), which is precisely the keyboard's
                        // plain Enter key — there is no ImeAction.NewLine in Compose.
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        // Documents need no vision model, so the slot is always present; only
                        // the entries it can offer vary (photo/camera need vision, document
                        // does not). Material takes the field's start padding from whether this
                        // slot is *present*, never from what it measures, so an absent slot
                        // would indent the text as though an icon were sitting there.
                        leadingIcon = {
                            if (editingMessage != null) {
                                IconButton(onClick = {
                                    editingMessage = null
                                    inputText = ""
                                }) {
                                    Icon(Icons.Default.Close, stringResource(R.string.cancel_edit))
                                }
                            } else {
                                Box {
                                    IconButton(
                                        onClick = { if (canAttach) showAttachMenu = true }
                                    ) {
                                        Icon(
                                            Icons.Default.AttachFile,
                                            stringResource(R.string.attach),
                                            tint = if (canAttach) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                        )
                                    }
                                    if (canAttach) {
                                        DropdownMenu(
                                            expanded = showAttachMenu,
                                            onDismissRequest = { showAttachMenu = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.photo_library)) },
                                                leadingIcon = { Icon(Icons.Default.PhotoLibrary, null) },
                                                enabled = canAttachImage,
                                                onClick = {
                                                    showAttachMenu = false
                                                    galleryLauncher.launch("image/*")
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.take_photo)) },
                                                leadingIcon = { Icon(Icons.Default.CameraAlt, null) },
                                                enabled = canAttachImage,
                                                onClick = {
                                                    showAttachMenu = false
                                                    requestCamera()
                                                }
                                            )
                                            // The system Files picker, filtered to PDFs — the
                                            // attach that never needs a vision model.
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.document)) },
                                                leadingIcon = { Icon(Icons.Default.Description, null) },
                                                onClick = {
                                                    showAttachMenu = false
                                                    documentLauncher.launch(arrayOf("application/pdf"))
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    )

                    Spacer(Modifier.width(8.dp))

                    // One-shot mode has nothing to stop mid-flight — the reply is not being
                    // streamed, and a new message is how you interrupt. Only streaming mode
                    // replaces Send with Stop while a turn is in flight.
                    if (isStreaming && effective.streamingMode) {
                        IconButton(
                            onClick = {
                                scope.launch { llmService.cancelRequest() }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Stop, stringResource(R.string.stop), tint = MaterialTheme.colorScheme.onError)
                        }
                    } else {
                        val canSend = (inputText.isNotBlank() || pendingImages.isNotEmpty() || pendingDocuments.isNotEmpty()) &&
                            !isPreparingModel
                        IconButton(
                            onClick = {
                                keyboardController?.hide()
                                if (editingMessage != null) {
                                    onSaveEdit(app, editingMessage!!, inputText, scope) {
                                        editingMessage = null; inputText = ""
                                    }
                                } else {
                                    checkModelAndSend()
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(
                                    if (canSend) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                        ) {
                            Icon(
                                if (editingMessage != null) Icons.Default.Check
                                else Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(
                                    if (editingMessage != null) R.string.save else R.string.send
                                ),
                                tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
        LitertWarmUpFailureBanner(
            app = app,
            settings = resolvedSettings,
            modifier = Modifier.align(Alignment.TopCenter)
        )
        LitertWarmUpOverlay(app)
        }
    }

    // Message context menu
    selectedMessageForMenu?.let { message ->
        ModalBottomSheet(
            onDismissRequest = { selectedMessageForMenu = null },
            sheetState = rememberModalBottomSheetState()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                DropdownMenuItem(
                    text = { Text(if (message.favorite) stringResource(R.string.unmark_favorite) else stringResource(R.string.mark_favorite)) },
                    leadingIcon = {
                        Icon(
                            if (message.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            null
                        )
                    },
                    onClick = {
                        scope.launch {
                            app.database.setMessageFavorite(message, !message.favorite)
                        }
                        selectedMessageForMenu = null
                    }
                )
                if (message.reasoning.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.show_reasoning)) },
                        leadingIcon = { Icon(Icons.Default.Psychology, null) },
                        onClick = {
                            reasoningText = message.reasoning
                            showReasoningSheet = true
                            selectedMessageForMenu = null
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.copy)) },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                    onClick = {
                        scope.launch { clipboardManager.setClipEntry(ClipEntry(ClipData.newPlainText("label", message.content))) }
                        selectedMessageForMenu = null
                    }
                )
                // Share offers the message text only — images never leave with it. An
                // image-only/document-only/blank message has nothing worth sharing, so the
                // option is hidden there (same rule as the bubble's body text).
                if (message.content != IMAGE_ONLY_MESSAGE && message.content != DOCUMENT_ONLY_MESSAGE && message.content.isNotBlank()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.share)) },
                        leadingIcon = { Icon(Icons.Default.Share, null) },
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, message.content)
                            }
                            runCatching { context.startActivity(Intent.createChooser(shareIntent, null)) }
                            selectedMessageForMenu = null
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.edit)) },
                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                    onClick = {
                        editingMessage = message
                        // An image-only/document-only message stores the sentinel as its
                        // content, and the bubble never renders it — the composer must not
                        // show it either. Start from empty text instead; with nothing typed
                        // the Save button stays disabled, so an untouched edit can never
                        // wipe the message.
                        inputText =
                            if (message.content == IMAGE_ONLY_MESSAGE || message.content == DOCUMENT_ONLY_MESSAGE) ""
                            else message.content
                        selectedMessageForMenu = null
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.select)) },
                    leadingIcon = { Icon(Icons.Default.CheckCircle, null) },
                    onClick = {
                        isSelectionMode = true
                        selectedMessageIds.clear()
                        selectedMessageIds.add(message.id)
                        selectedMessageForMenu = null
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = {
                        selectedMessageForMenu = null
                        messages = messages.filter { it.id != message.id }
                        scope.launch {
                            app.database.messageDao().delete(message)
                        }
                    }
                )
            }
        }
    }

    if (showReasoningSheet) {
        ModalBottomSheet(
            onDismissRequest = { showReasoningSheet = false },
            // Same recipe as the favorites sheet below, which scrolls correctly.
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                Text(stringResource(R.string.reasoning), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(12.dp))
                // Reasoning runs long — the model's own thinking plus the tool trace —
                // and this used to be a bare Text in an unbounded Column, so anything
                // past the sheet's height was simply clipped with no way to reach it.
                // The explicit height is what gives verticalScroll a viewport: the sheet
                // measures its content unbounded, and a scrollable with no height cap
                // just grows to fit and never scrolls.
                Column(
                    modifier = Modifier
                        .height(400.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(reasoningText, fontSize = 14.sp)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showClearHistoryAlert) {
        ConfirmDialog(
            text = stringResource(R.string.clear_all_history),
            confirmLabel = stringResource(R.string.clear),
            destructive = true,
            onConfirm = {
                scope.launch { app.database.messageDao().deleteByThread(threadId) }
                showClearHistoryAlert = false
            },
            onDismiss = { showClearHistoryAlert = false }
        )
    }

    if (showFavoritesSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFavoritesSheet = false },
            // Opens fully expanded. The content is a scrolling list, and at the
            // partially-expanded anchor a drag on that list is ambiguous — the sheet
            // claims it and collapses instead of scrolling, so trying to read down the
            // favorites swiped the sheet away. One anchor removes the ambiguity.
            // Same reason VoicePickerSheet and ToolEditorSheet skip it.
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            FavoritesContent(
                app = app,
                threadId = threadId,
                onMessageClick = { messageId ->
                    showFavoritesSheet = false
                    pendingScrollToMessageId = messageId
                }
            )
        }
    }

    if (showErrorAlert) {
        InfoDialog(errorMessage) { showErrorAlert = false }
    }

    if (showMultiAttachGateAlert) {
        ConfirmDialog(
            text = stringResource(R.string.multi_attach_gate_message),
            confirmLabel = stringResource(R.string.ok),
            onConfirm = {
                settingsManager.setHasAcceptedMultipleAttachments(true)
                showMultiAttachGateAlert = false
                pendingAttachmentAppend?.invoke()
                pendingAttachmentAppend = null
            },
            onDismiss = {
                showMultiAttachGateAlert = false
                pendingAttachmentAppend = null
            }
        )
    }

    if (showMicPermissionAlert) {
        PermissionDeniedDialog(R.string.mic_permission_required) { showMicPermissionAlert = false }
    }

    if (showModelNotConfiguredAlert) {
        ModelNotConfiguredDialog(
            onDismiss = { showModelNotConfiguredAlert = false },
            onGoToSettings = { navController.navigateBasedOnThread(threadId, currentThread) }
        )
    }

    if (showCameraCapture) {
        CameraCaptureScreen(
            onClose = { showCameraCapture = false },
            onCapture = { bytes -> addAttachment { pendingImages.add(bytes) } }
        )
    }

}

@Composable
private fun FavoritesContent(app: RolesWitchApp, threadId: String, onMessageClick: (String) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var favorites by remember { mutableStateOf<List<Message>>(emptyList()) }

    LaunchedEffect(threadId) {
        app.database.messageDao().observeMessagesByThread(threadId).collect { msgs ->
            favorites = msgs.filter { it.favorite }
        }
    }

    Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
        Text(
            stringResource(R.string.favorites),
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            fontSize = 18.sp
        )
        Spacer(Modifier.height(12.dp))

        if (favorites.isEmpty()) {
            Text(
                stringResource(R.string.no_favorites_yet),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
        } else {
            LazyColumn(
                modifier = Modifier.height(400.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(favorites, key = { it.id }) { message ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(Modifier.weight(1f).clickable { onMessageClick(message.id) }) {
                            BubbleMessage(message = message, showFavoriteIcon = false)
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    app.database.setMessageFavorite(message, false)
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                stringResource(R.string.remove_from_favorites),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

private fun onSend(
    app: RolesWitchApp,
    threadId: String,
    text: String,
    images: List<ByteArray> = emptyList(),
    documents: List<DocumentInput> = emptyList(),
    scope: kotlinx.coroutines.CoroutineScope,
    onClear: () -> Unit,
    onError: (Exception, String) -> Unit
) {
    val trimmed = text.trim()
    if (trimmed.isEmpty() && images.isEmpty() && documents.isEmpty()) return

    scope.launch {
        app.llmService.cancelRequest()
        // Extract the PDF text off the UI thread before persisting, so the message row
        // carries its attachment inline for the engine and every later surface (the
        // placeholder body above lives only when the file alone was "sent").
        val attachments = app.llmService.extractDocuments(documents)
        app.database.messageDao().insert(
            Message(
                content = trimmed.ifEmpty {
                    if (images.isNotEmpty()) IMAGE_ONLY_MESSAGE else DOCUMENT_ONLY_MESSAGE
                },
                isUser = true,
                threadId = threadId,
                imageData = images,
                documents = attachments
            )
        )
        onClear()

        try {
            app.llmService.processLLMResponse(userText = trimmed, threadId = threadId, images = images)
        } catch (_: CancellationException) {
            // ignore
        } catch (e: OAIError) {
            if (!e.isSilent) onError(e, e.message ?: "Unknown error")
        } catch (e: Exception) {
            onError(e, e.message ?: "Unknown error")
        }
    }
}

/** Reads a picked document's display name via the system document picker contract. */
private fun queryDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(
        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        } else null
    }

/** Reads a picked document's bytes in full. */
private fun readUriBytes(context: Context, uri: Uri): ByteArray? =
    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }

private fun onSaveEdit(
    app: RolesWitchApp,
    message: Message,
    newText: String,
    scope: kotlinx.coroutines.CoroutineScope,
    onDone: () -> Unit
) {
    val trimmed = newText.trim()
    if (trimmed.isEmpty()) return

    scope.launch {
        app.database.updateMessageContent(message, trimmed)
        onDone()
    }
}
