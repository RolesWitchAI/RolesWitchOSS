//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.ui.messages

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import ai.roleswitch.android.R
import ai.roleswitch.android.RolesWitchApp
import ai.roleswitch.android.ui.assistant.CameraCaptureScreen
import ai.roleswitch.android.ui.components.ConfirmDialog
import ai.roleswitch.android.ui.components.rememberPermissionRequester
import ai.roleswitch.android.data.model.MessageThread
import ai.roleswitch.android.ui.components.Live2dCharacters
import ai.roleswitch.android.ui.components.ThreadAvatar
import ai.roleswitch.android.ui.components.appOutlinedTextFieldColors
import ai.roleswitch.android.ui.util.uriToCompressedBytes
import androidx.core.content.FileProvider
import androidx.compose.ui.draw.clip
import java.io.File
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadProfileScreen(
    app: RolesWitchApp,
    threadId: String,
    onBack: () -> Unit,
    onNavigateToCharacter: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var thread by remember { mutableStateOf<MessageThread?>(null) }
    var threads by remember { mutableStateOf<List<MessageThread>>(emptyList()) }
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var originalTitle by remember { mutableStateOf("") }
    var originalPrompt by remember { mutableStateOf("") }
    var showSourcePicker by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showCameraCapture by remember { mutableStateOf(false) }

    LaunchedEffect(threadId) {
        val loaded = app.database.messageThreadDao().getThreadById(threadId)
        thread = loaded
        loaded?.let {
            title = it.title
            prompt = it.prompt
            originalTitle = it.title
            originalPrompt = it.prompt
        }
    }

    LaunchedEffect(Unit) {
        app.database.messageThreadDao().observeAllThreads().collect { threads = it }
    }

    val nameError = title.isNotBlank() && threads.any {
        it.threadId != threadId && it.title.trim().equals(title.trim(), ignoreCase = true)
    }
    val dirty = title.trim() != originalTitle.trim() || prompt.trim() != originalPrompt.trim()
    val canSave = dirty && title.isNotBlank() && prompt.isNotBlank() && !nameError

    val cropLauncher = rememberLauncherForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) {
            val croppedUri = result.uriContent ?: return@rememberLauncherForActivityResult
            val bytes = uriToCompressedBytes(context, croppedUri, maxDimension = 512)
            if (bytes != null) {
                val current = thread ?: return@rememberLauncherForActivityResult
                scope.launch {
                    app.database.updateThreadAvatar(current, bytes)
                    thread = current.copy(avatarBytes = bytes)
                }
            }
        }
    }

    val squareCropOptions = remember {
        CropImageOptions(
            aspectRatioX = 1,
            aspectRatioY = 1,
            fixAspectRatio = true,
            imageSourceIncludeGallery = false,
            imageSourceIncludeCamera = false,
            activityBackgroundColor = Color.Black.toArgb()
        )
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) cropLauncher.launch(
            CropImageContractOptions(uri = uri, cropImageOptions = squareCropOptions)
        )
    }

    val launchCameraCapture = rememberPermissionRequester(Manifest.permission.CAMERA) {
        showCameraCapture = true
    }

    fun launchGallery() {
        showSourcePicker = false
        galleryLauncher.launch("image/*")
    }

    fun requestCamera() {
        showSourcePicker = false
        launchCameraCapture()
    }

    fun removeAvatar() {
        showSourcePicker = false
        val current = thread ?: return
        scope.launch {
            app.database.updateThreadAvatar(current, null)
            thread = current.copy(avatarBytes = null)
        }
    }

    fun disableCharacter() {
        showSourcePicker = false
        val current = thread ?: return
        scope.launch {
            app.database.updateThreadCubismId(current, null)
            thread = current.copy(cubismId = null)
        }
    }

    fun save() {
        scope.launch {
            thread?.let { app.database.updateThreadTitleAndPrompt(it, title.trim(), prompt.trim()) }
            onBack()
        }
    }

    fun handleBack() {
        if (dirty) showDiscardDialog = true else onBack()
    }

    // A character set on this thread takes over the call visuals; the photo stays
    // underneath (it still shows in lists and chat headers) and comes back when the
    // character is disabled from the avatar sheet. An id that no longer maps to a
    // known character counts as "no character", like everywhere else.
    val hasCharacter = Live2dCharacters.indexOf(thread?.cubismId) >= 0

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                // Fixed, not the thread's title: the name is already the first thing the
                // form shows, and echoing it here made the bar jump as you typed.
                title = { Text(stringResource(R.string.profile)) },
                navigationIcon = {
                    IconButton(onClick = ::handleBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    if (dirty) {
                        IconButton(onClick = ::save, enabled = canSave) {
                            Icon(Icons.Default.Check, stringResource(R.string.save))
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
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(Modifier.height(32.dp))

            // A character set on this thread takes over the call visuals; the photo
            // stays underneath (it still shows in lists and chat headers).
            Box {
                ThreadAvatar(
                    name = title.ifBlank { thread?.title ?: "" },
                    avatarBytes = thread?.avatarBytes,
                    size = 160.dp
                )
                if (hasCharacter) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .border(3.dp, MaterialTheme.colorScheme.surface, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.TheaterComedy,
                            contentDescription = stringResource(R.string.character),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            TextButton(onClick = { showSourcePicker = true }) {
                Text(
                    stringResource(R.string.change_image),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(16.dp))

            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    // The field names itself, as in the tool editor and the API sheets.
                    // This was a static caption above the box, which does not move out of
                    // the way and left the two forms looking like different components.
                    label = { Text(stringResource(R.string.name_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    isError = nameError,
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = appOutlinedTextFieldColors()
                )
                if (title.isBlank()) {
                    Text(
                        stringResource(R.string.name_empty),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 14.sp
                    )
                } else if (nameError) {
                    Text(
                        stringResource(R.string.name_already_exists),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text(stringResource(R.string.prompt)) },
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = appOutlinedTextFieldColors()
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showSourcePicker) {
        ModalBottomSheet(
            onDismissRequest = { showSourcePicker = false },
            sheetState = rememberModalBottomSheetState()
        ) {
            Column(modifier = Modifier.padding(bottom = 16.dp)) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.photo_library)) },
                    leadingIcon = { Icon(Icons.Default.PhotoLibrary, null) },
                    onClick = ::launchGallery
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.take_photo)) },
                    leadingIcon = { Icon(Icons.Default.CameraAlt, null) },
                    onClick = ::requestCamera
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.use_character)) },
                    leadingIcon = { Icon(Icons.Default.TheaterComedy, null) },
                    onClick = {
                        showSourcePicker = false
                        onNavigateToCharacter()
                    }
                )
                // The off switch, mirroring "Remove avatar": offered only while a
                // character is actually set, and neutral rather than destructive —
                // the photo underneath is kept, so "Use Character" simply reverses it.
                if (hasCharacter) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.disable_character)) },
                        leadingIcon = { Icon(Icons.Default.TheaterComedy, null) },
                        onClick = ::disableCharacter
                    )
                }
                if (thread?.avatarBytes != null) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.remove_avatar),
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = ::removeAvatar
                    )
                }
            }
        }
    }

    if (showDiscardDialog) {
        // destructive: discarding throws away the user's edits, same as Delete/Clear/Reset
        // elsewhere — it just wasn't coloured to match.
        ConfirmDialog(
            text = stringResource(R.string.discard_changes),
            confirmLabel = stringResource(R.string.discard),
            destructive = true,
            onConfirm = {
                showDiscardDialog = false
                onBack()
            },
            onDismiss = { showDiscardDialog = false }
        )
    }

    if (showCameraCapture) {
        CameraCaptureScreen(
            onClose = { showCameraCapture = false },
            onCapture = { bytes ->
                // The cropper needs a Uri, and the capture gives bytes: stage them in
                // cache exactly like the system camera used to, then crop as before.
                val file = File(context.cacheDir, "avatar_${System.currentTimeMillis()}.jpg")
                file.writeBytes(bytes)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                cropLauncher.launch(CropImageContractOptions(uri = uri, cropImageOptions = squareCropOptions))
            }
        )
    }
}
