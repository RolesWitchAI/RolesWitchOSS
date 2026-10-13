//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.ui.settings

import ai.roleswitch.android.R
import ai.roleswitch.android.RolesWitchApp
import ai.roleswitch.android.service.LiteRtModelDownloader
import ai.roleswitch.android.service.HuggingFaceModelResolver
import ai.roleswitch.android.service.LitertModel
import ai.roleswitch.android.service.LitertModelStore
import ai.roleswitch.android.service.LitertModels
import ai.roleswitch.android.ui.components.ConfirmDialog
import ai.roleswitch.android.ui.components.InfoDialog
import ai.roleswitch.android.ui.components.KeepScreenOn
import ai.roleswitch.android.ui.components.appOutlinedTextFieldColors
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Picks, downloads and removes the on-device models in [LitertModels].
 *
 * The model list is the threads screen's card carrying the iOS picker's selection
 * elements: a tinted icon circle, name, size/RAM line, an "Installed"/size footnote, and a
 * selection tick with a stroked border — the whole card taps, downloaded models select
 * outright, and a not-yet-downloaded one raises iOS's "Download required" confirm, the
 * single confirmation a download asks for. Delete is the threads screen's long-press
 * context menu, confirmed by dialog.
 *
 * A download never runs in the list: it takes over the screen with [LitertDownloadSheet],
 * a non-dismissable sheet with progress, speed and a Cancel button. The user cannot leave
 * it — or this screen — until the model is downloaded and verified or they cancel, so
 * there is no state in which a transfer is running unseen underneath the catalogue.
 *
 * [onSelect] writes the chosen model id into the right scope (global settings, or a
 * thread's `customLitertModel`), which is why this screen never touches settings itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LitertModelScreen(
    app: RolesWitchApp,
    selectedModelId: String,
    onSelect: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloader = app.liteRtModelDownloader

    val downloadState by downloader.state.collectAsState()
    val activeModelId by downloader.activeModelId.collectAsState()

    // Bumped whenever a download finishes or a model is deleted, to re-run the
    // on-disk checks below: the filesystem has no flow to observe.
    var diskGeneration by remember { mutableIntStateOf(0) }

    var pendingDownload by remember { mutableStateOf<LitertModel?>(null) }
    var pendingTokenRequired by remember { mutableStateOf<LitertModel?>(null) }
    var pendingLowMemory by remember { mutableStateOf<LitertModel?>(null) }
    var pendingDelete by remember { mutableStateOf<LitertModel?>(null) }
    var modelForContextMenu by remember { mutableStateOf<LitertModel?>(null) }
    var showUrlDialog by remember { mutableStateOf(false) }

    // Built-ins plus whatever the user has added by URL. Collected rather than read from
    // `LitertModels.all` directly so adding or removing one recomposes the list.
    val customModels by app.settingsManager.customLitertModels.collectAsState()
    val models = LitertModels.registry + customModels

    // A terminal state left over from a previous visit would otherwise re-open the
    // download sheet for a frame the moment the screen opens.
    LaunchedEffect(Unit) {
        if (downloadState is LiteRtModelDownloader.State.Done ||
            downloadState is LiteRtModelDownloader.State.Failed
        ) {
            downloader.reset()
        }
    }

    // A refusal arrives from the network, so the dialog it raises has to be driven by
    // state rather than opened inline at the tap. Looked up in `models`, not the
    // registry: a URL-added model can be gated too.
    LaunchedEffect(downloadState, activeModelId) {
        val failure = downloadState as? LiteRtModelDownloader.State.Failed ?: return@LaunchedEffect
        if (!failure.needsToken) return@LaunchedEffect
        val model = activeModelId?.let { id -> models.firstOrNull { it.id == id } }
        if (model != null) {
            pendingTokenRequired = model
            // Closed along with the download sheet, so dismissing the dialog leaves the
            // catalogue clean rather than the failure restating itself underneath.
            downloader.reset()
        }
    }

    fun beginDownload(model: LitertModel) {
        downloader.start(model, app.settingsManager.huggingFaceToken.value) {
            diskGeneration++
            // Selecting the model the user just waited ten minutes for is what they
            // meant by downloading it; making them tap it again would be busywork.
            onSelect(model.id)
        }
    }

    /**
     * Runs the device-memory gate, then starts the download. No licence gate: iOS has
     * none either, and the "Download required" confirm — with its size and
     * keep-this-screen-open warning — is the one dialog a first download asks for.
     *
     * There is deliberately no "is this model gated" gate here either. Whether a repository
     * needs a token is HuggingFace's to decide and it can change without an app release,
     * so the download is attempted and a 401/403 — surfaced as
     * [LiteRtModelDownloader.State.Failed.needsToken] — is what raises the token alert.
     * A flag compiled into the app would keep demanding a token for a model that had
     * since been opened up, with no way for the user to get past it.
     */
    fun downloadThroughGates(model: LitertModel) {
        when {
            LitertModelStore.deviceMemoryGb(context) < model.minDeviceMemoryGb ->
                pendingLowMemory = model
            // Re-checked here, not just at the card: the gates above are dialogs, and a
            // download can have started while one of them was open.
            downloader.isDownloading -> Unit
            else -> beginDownload(model)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.litert_models_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    // Adding a model by URL is the exception, not a catalogue entry, so
                    // it lives in the top bar rather than the list: a "+" next to the
                    // title, opening the same dialog the old list-button did.
                    IconButton(onClick = { showUrlDialog = true }) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.litert_add_from_url)
                        )
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
                // The threads screen's gutter, so the cards sit in the same inset its
                // list uses rather than running into the screen edges.
                .padding(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                models.forEach { model ->
                    val downloaded = remember(model.id, diskGeneration) {
                        LitertModelStore.isDownloaded(context, model)
                    }
                    ModelCard(
                        model = model,
                        // Ticked only while the stored selection is backed by weights on
                        // disk: a model deleted after being selected is "Not configured",
                        // not selected — the settings row says the same.
                        isSelected = model.id == selectedModelId && downloaded,
                        isDownloaded = downloaded,
                        onTap = {
                            // A downloaded card selects outright; anything else asks
                            // first, because downloading costs gigabytes.
                            if (downloaded) onSelect(model.id) else pendingDownload = model
                        },
                        onLongPress = {
                            // Weights on disk → they can be deleted. A URL-added model
                            // without them → the card itself is what needs deleting, or a
                            // failed/cancelled download strands the entry with no way off
                            // the screen (its `.part`, if any, goes with it).
                            if (downloaded || model.isCustom) modelForContextMenu = model
                        }
                    )
                }
            }

            // The iOS sheet's footnote, verbatim in wording: where the models run, and
            // that each carries its own licence — the same terms the licence dialog
            // offers to read before a download.
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.litert_models_footer),
                fontSize = 12.sp,
                // Explicit, ~1.25x: Compose's default leading for a bare fontSize is
                // closer to 1.7x here, which is what made the two lines sit far apart.
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            )
        }
    }

    // The download sheet is derived from the downloader's own flows rather than a local
    // boolean: it has to survive a rotation (the transfer does), reappear for whatever is
    // still in flight, and never be closable while a transfer runs.
    val sheetModel = activeModelId?.let { id -> models.firstOrNull { it.id == id } }
    if (downloadState != null && sheetModel != null) {
        LitertDownloadSheet(
            model = sheetModel,
            state = downloadState,
            onCancel = {
                // Pause, not discard: the partial file stays and the next Download
                // resumes from it.
                downloader.cancel()
            },
            onRetry = { beginDownload(sheetModel) },
            onClose = { downloader.reset() }
        )
    }

    // iOS's alert verbatim in wording and shape: a title, one line saying what is about
    // to happen, and Cancel/Download. The licence text and its Read button are gone —
    // the screen's footer already points at huggingface.co for the terms, as on iOS.
    pendingDownload?.let { model ->
        ConfirmDialog(
            title = stringResource(R.string.litert_download_required_title),
            text = stringResource(
                R.string.litert_download_required_desc,
                model.displayName,
                formatGb(model.sizeBytes, stringResource(R.string.unit_gb))
            ),
            confirmLabel = stringResource(R.string.litert_download),
            onConfirm = {
                pendingDownload = null
                downloadThroughGates(model)
            },
            onDismiss = { pendingDownload = null }
        )
    }

    pendingTokenRequired?.let { model ->
        // OK-only: there is nothing to decide here, only a missing token to go and add —
        // a Cancel button that did the same thing as OK was a second button for nothing.
        InfoDialog(stringResource(R.string.litert_token_required_message, model.displayName)) {
            pendingTokenRequired = null
        }
    }

    pendingLowMemory?.let { model ->
        ConfirmDialog(
            text = stringResource(
                R.string.litert_low_memory_message,
                model.minDeviceMemoryGb,
                LitertModelStore.deviceMemoryGb(context)
            ),
            confirmLabel = stringResource(R.string.litert_download_anyway),
            onConfirm = {
                pendingLowMemory = null
                beginDownload(model)
            },
            onDismiss = { pendingLowMemory = null }
        )
    }

    if (showUrlDialog) {
        AddModelByUrlDialog(
            app = app,
            onDismiss = { showUrlDialog = false },
            onResolved = { model ->
                showUrlDialog = false
                app.settingsManager.addCustomLitertModel(model)
                // Straight to the gates, without the Download-required confirm: the
                // dialog's own Download button already carried that confirmation, and
                // the user has just pasted a link they want.
                downloadThroughGates(model)
            }
        )
    }

    // The threads screen's context menu, verbatim in shape: long-press opens the sheet,
    // the Delete item opens the confirm dialog below.
    modelForContextMenu?.let { model ->
        ModalBottomSheet(onDismissRequest = { modelForContextMenu = null }) {
            Column(modifier = Modifier.padding(16.dp)) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = {
                        pendingDelete = model
                        modelForContextMenu = null
                    }
                )
            }
        }
    }

    pendingDelete?.let { model ->
        ConfirmDialog(
            text = stringResource(R.string.litert_delete_message, model.displayName),
            confirmLabel = stringResource(R.string.delete),
            destructive = true,
            onConfirm = {
                pendingDelete = null
                scope.launch {
                    app.liteRtService.deleteModel(model)
                    // A built-in stays on screen ready to be downloaded again; a custom
                    // one has nowhere to come back from, so deleting it removes the card.
                    if (model.isCustom) app.settingsManager.removeCustomLitertModel(model.id)
                    diskGeneration++
                    if (selectedModelId == model.id) onSelect("")
                }
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

/**
 * One model as a selectable card — the threads screen's card (same shape, fill and
 * elevation) carrying the iOS picker's selection elements: a tick and stroked border on
 * the selected model, and an "Installed"/size footnote.
 *
 * The whole card taps; a downloaded model selects outright, a not-yet-downloaded one goes
 * straight to the caller's gates, where the "Download required" confirm — the same alert
 * iOS raises — is the only confirmation standing between a tap and a transfer. Delete is
 * the threads screen's gesture too — long-press opens the context menu — and only for a
 * downloaded model or a URL-added one whose card would otherwise be stranded; a built-in
 * that was never downloaded has nothing to remove.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModelCard(
    model: LitertModel,
    isSelected: Boolean,
    isDownloaded: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit
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
                .combinedClickable(onClick = onTap, onLongClick = onLongPress)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
                // A globe, the icon the iOS card gives every LiteRT model: it stands for
                // where the model comes from, and says nothing the card's own text does
                // not already say better (vision, RAM — all of it is on the size line).
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Public,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(model.displayName, fontWeight = FontWeight.SemiBold)
                    // Download size and the RAM it wants, nothing else: these are the two
                    // facts that decide whether the model is worth starting on this phone.
                    Text(
                        buildString {
                            append(formatGb(model.sizeBytes, stringResource(R.string.unit_gb)))
                            append(" · ")
                            append(stringResource(R.string.litert_needs_ram, model.minDeviceMemoryGb))
                        },
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // The size line above already says what it costs; this line only says
                    // which side of "on disk" the model is on.
                    Text(
                        stringResource(
                            if (isDownloaded) R.string.litert_installed
                            else R.string.litert_not_installed
                        ),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                if (isSelected) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.litert_selected),
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
 * The download in progress, as a blocking bottom sheet.
 *
 * Non-dismissable by any gesture or key while the transfer runs: the swipe settle is
 * vetoed (the same trick [ai.roleswitch.android.ui.settings.VoskDownloadSheet] uses),
 * and [ModalBottomSheet.onDismissRequest] — scrim tap, back key — is a no-op until the
 * state is terminal. The only way out of a download is its Cancel button (a pause: the
 * partial file stays), and of a failure its Retry/Close buttons. Success closes the
 * sheet itself: the downloader clears `activeModelId`, which is what the sheet's
 * visibility is derived from.
 *
 * The screen underneath is unreachable for as long as this is up, which is the point:
 * a multi-gigabyte transfer must not run unseen behind the catalogue, and the user must
 * not be able to wander off and only later learn the screen had to stay open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LitertDownloadSheet(
    model: LitertModel,
    state: LiteRtModelDownloader.State?,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden }
    )
    // A multi-gigabyte download outlasts the display timeout, and the transfer does not
    // survive the app being suspended — the same reason iOS keeps the screen awake here.
    KeepScreenOn(enabled = state !is LiteRtModelDownloader.State.Failed)

    ModalBottomSheet(
        onDismissRequest = {
            // Deliberately a no-op while a transfer is in flight: back and scrim cannot
            // cancel it. Only a Failed sheet may be dismissed this way, matching Close.
            if (state is LiteRtModelDownloader.State.Failed) onClose()
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
                is LiteRtModelDownloader.State.Downloading ->
                    R.string.litert_downloading_model
                LiteRtModelDownloader.State.Verifying -> R.string.litert_verifying
                is LiteRtModelDownloader.State.Failed -> R.string.litert_download_failed_title
                // Success never renders: the downloader clears `activeModelId`, the sheet
                // leaves composition, and the success callback selects the model.
                LiteRtModelDownloader.State.Done -> R.string.litert_downloading_model
                null -> R.string.litert_downloading_model
            }
            Text(
                stringResource(titleRes, model.displayName),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            when (val s = state) {
                is LiteRtModelDownloader.State.Downloading -> {
                    val total = s.total.coerceAtLeast(1L)
                    val pct = (s.downloaded.toFloat() / total).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { pct },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(
                            R.string.litert_download_progress,
                            (pct * 100).roundToInt(),
                            formatSpeed(
                                s.bytesPerSecond,
                                stringResource(R.string.unit_mb),
                                stringResource(R.string.unit_kb)
                            ),
                            formatEta(s.remainingMs)
                        ),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${s.downloaded / 1_048_576} / ${total / 1_048_576} " +
                            stringResource(R.string.unit_mb),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(24.dp))
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.cancel)) }
                }

                LiteRtModelDownloader.State.Verifying -> {
                    // No Cancel here: the bytes are already paid for and the hash of a
                    // few gigabytes takes seconds, not minutes.
                    CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                }

                is LiteRtModelDownloader.State.Failed -> {
                    Text(
                        stringResource(
                            if (s.corrupted) {
                                R.string.litert_download_corrupted
                            } else {
                                R.string.litert_download_failed
                            },
                            s.message
                        ),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.retry)) }
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.close)) }
                }

                LiteRtModelDownloader.State.Done,
                null -> {}
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

// Units are passed in rather than hardcoded: the row reads "2,6 GB · Требуется 8 ГБ ОЗУ"
// otherwise, mixing a Latin unit into a Russian sentence that already localises its own.
private fun formatGb(bytes: Long, unit: String): String =
    "%.1f %s".format(bytes / 1_000_000_000.0, unit)

private fun formatSpeed(bytesPerSecond: Long, mb: String, kb: String): String = when {
    bytesPerSecond <= 0 -> "—"
    bytesPerSecond >= 1_000_000 -> "%.1f %s/s".format(bytesPerSecond / 1_000_000.0, mb)
    else -> "%d %s/s".format(bytesPerSecond / 1000, kb)
}

/**
 * Remaining time as `m:ss`, growing to `h:mm:ss` once there is an hour left.
 *
 * The hours field is not padded and only appears when it is non-zero, so a short wait
 * reads as `4:07` rather than `0:04:07` — but a slow connection on a five-gigabyte model
 * cannot silently roll over and report `95:12`, which is indistinguishable from minutes
 * and seconds at a glance.
 */
private fun formatEta(remainingMs: Long): String {
    if (remainingMs <= 0) return "—"
    val totalSeconds = remainingMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/**
 * Asks for a HuggingFace link and resolves it to a model before letting the caller add it.
 *
 * Resolution is a network round trip, so the dialog stays open with a spinner and reports
 * failure in place: a link that turns out to be a GGUF, or a repo that needs a token, is
 * something the user can fix by editing the text they already typed, and dismissing the
 * dialog to say so would throw that text away.
 */
@Composable
private fun AddModelByUrlDialog(
    app: RolesWitchApp,
    onDismiss: () -> Unit,
    onResolved: (LitertModel) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var resolving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!resolving) onDismiss() },
        title = { Text(stringResource(R.string.litert_add_from_url)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.litert_add_from_url_message),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !resolving,
                    isError = error != null,
                    shape = RoundedCornerShape(14.dp),
                    placeholder = { Text("https://huggingface.co/…", maxLines = 1) },
                    colors = appOutlinedTextFieldColors()
                )
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                if (resolving) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank() && !resolving,
                onClick = {
                    resolving = true
                    error = null
                    scope.launch {
                        runCatching {
                            HuggingFaceModelResolver.resolve(
                                url = url,
                                accessToken = app.settingsManager.huggingFaceToken.value
                            )
                        }.onSuccess { model ->
                            resolving = false
                            onResolved(model)
                        }.onFailure { e ->
                            resolving = false
                            error = e.message ?: "Could not read that link."
                        }
                    }
                }
            ) {
                Text(stringResource(R.string.litert_download))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !resolving) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
