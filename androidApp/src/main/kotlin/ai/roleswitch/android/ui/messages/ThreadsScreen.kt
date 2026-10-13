//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.ui.messages

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add

import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.roleswitch.android.R
import ai.roleswitch.android.RolesWitchApp
import ai.roleswitch.android.ui.components.ConfirmDialog
import ai.roleswitch.android.ui.components.appOutlinedTextFieldColors
import ai.roleswitch.android.data.model.Message
import ai.roleswitch.android.data.model.MessageThread
import ai.roleswitch.android.data.resolveSeedLocale
import ai.roleswitch.android.data.seed.NewThreadDefaults
import ai.roleswitch.android.ui.components.ThreadAvatar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Collapses every run of whitespace in a card preview to one space. The preview is two
 * lines now, and a message's own newlines would spend one of them on a blank — the text
 * should wrap because it is long, not because it was formatted.
 */
private val WHITESPACE_RUN = Regex("\\s+")

/** How much of a message the card preview considers, before it is wrapped and ellipsized. */
private const val PREVIEW_CHARS = 200

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadsScreen(
    app: RolesWitchApp,
    onNavigateToMessages: (String) -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    var threads by remember { mutableStateOf<List<MessageThread>>(emptyList()) }
    var lastMessages by remember { mutableStateOf<Map<String, Message>>(emptyMap()) }

    var threadToDelete by remember { mutableStateOf<MessageThread?>(null) }
    var threadForContextMenu by remember { mutableStateOf<MessageThread?>(null) }

    var showPromptEntry by remember { mutableStateOf(false) }

    // The one template left: every new conversation starts from the Virtual Character
    // default in the current app language, not from a role the user picks.
    val newThreadDefaults = remember { NewThreadDefaults.forLanguage(resolveSeedLocale()) }
    var threadTitle by remember { mutableStateOf(newThreadDefaults.name) }
    var promptText by remember { mutableStateOf(newThreadDefaults.prompt) }

    val nameError = threadTitle.trim().let { trimmed ->
        trimmed.isNotEmpty() &&
            threads.any { t -> t.title.trim().equals(trimmed, ignoreCase = true) }
    }

    // The list starts out empty and is filled a frame or two later by Room. Without
    // knowing whether that has happened yet, the empty state would flash on every
    // visit to the screen — including for users who have threads.
    var threadsLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        app.database.messageThreadDao().observeAllThreads().collect {
            threads = it
            threadsLoaded = true
        }
    }

    LaunchedEffect(Unit) {
        app.database.messageDao().observeLastMessagePerThread().collect { messages ->
            lastMessages = messages.associateBy { it.threadId ?: "" }
        }
    }

    // Relative timestamps are derived from "now", so without a ticker a row keeps
    // reading "1 min. ago" until something unrelated recomposes it. One minute
    // matches the resolution formatRelativeTime renders at — anything finer would
    // recompose without changing a single label.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }

    fun createThread(title: String, prompt: String) {
        scope.launch {
            // The default's greeting, frozen in the language the default was resolved in —
            // not a string resolved against whatever the system language happens to be now.
            val newThread = MessageThread(title = title, prompt = prompt)
            app.database.messageThreadDao().insert(newThread)
            if (newThreadDefaults.greeting.isNotEmpty()) {
                app.database.messageDao().insert(Message(content = newThreadDefaults.greeting, isUser = false, threadId = newThread.threadId))
            }
            onNavigateToMessages(newThread.threadId)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_messages)) },
                actions = {
                    // Contact / Privacy / Terms used to sit in an overflow menu here; they
                    // now live in the Settings "About" section, matching iOS. With only one
                    // destination left, a bare gear beats a one-item dropdown.
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, stringResource(R.string.settings))
                    }
                }
            )
        },
        floatingActionButton = {
            // There are no templates to choose from any more: the sheet opens here,
            // pre-filled with the Virtual Character default.
            FloatingActionButton(onClick = { showPromptEntry = true }) {
                Icon(
                    Icons.Default.Add,
                    stringResource(R.string.new_thread)
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (threadsLoaded && threads.isEmpty()) {
                EmptyThreadsNote(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(padding)
                        .padding(horizontal = 48.dp)
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(threads, key = { it.threadId }) { thread ->
                    val lastMessage = lastMessages[thread.threadId]

                    @OptIn(ExperimentalFoundationApi::class)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shadowElevation = 2.dp,
                        tonalElevation = 1.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = {
                                        onNavigateToMessages(thread.threadId)
                                    },
                                    // Protected threads are deletable too — protection
                                    // covers editing the name/prompt/avatar, not keeping
                                    // the thread around. Reset re-seeds the default one.
                                    onLongClick = { threadForContextMenu = thread }
                                )
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ThreadAvatar(
                                name = thread.title,
                                avatarBytes = thread.avatarBytes,
                                size = 44.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.Top) {
                                    Text(
                                        thread.title,
                                        modifier = Modifier.weight(1f),
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 16.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (lastMessage != null) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            formatRelativeTime(lastMessage.createdAt, now),
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Spacer(Modifier.height(3.dp))
                                val preview = remember(lastMessage?.content) {
                                    lastMessage?.content
                                        ?.replace(WHITESPACE_RUN, " ")
                                        ?.trim()
                                        ?.take(PREVIEW_CHARS)
                                        ?.takeIf { it.isNotEmpty() }
                                }
                                Text(
                                    preview ?: stringResource(R.string.no_messages),
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    // Pinned to exactly two lines, not just capped at two:
                                    // a one-line preview would otherwise make its card
                                    // shorter than its neighbours and leave the list ragged.
                                    minLines = 2,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPromptEntry) {
        ModalBottomSheet(
            onDismissRequest = { showPromptEntry = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    // Keeps the Create button above the keyboard — same recipe as the
                    // MCP editor and the settings sheets.
                    .imePadding()
            ) {
                OutlinedTextField(
                    value = threadTitle,
                    onValueChange = { threadTitle = it },
                    // Floating label rather than a static caption above the box, so this
                    // form matches the API sheets and the tool editor.
                    label = { Text(stringResource(R.string.name_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.enter_role_name)) },
                    isError = nameError,
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = appOutlinedTextFieldColors()
                )
                if (nameError) {
                    Text(stringResource(R.string.name_already_exists), color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                }

                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    label = { Text(stringResource(R.string.prompt)) },
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = appOutlinedTextFieldColors()
                )

                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        val trimmed = promptText.trim()
                        if (trimmed.isNotEmpty()) {
                            createThread(threadTitle.trim(), trimmed)
                            showPromptEntry = false
                        }
                    },
                    enabled = promptText.trim().isNotEmpty() && threadTitle.trim().isNotEmpty() && !nameError,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(stringResource(R.string.create), modifier = Modifier.padding(vertical = 8.dp))
                }
                Spacer(Modifier.height(12.dp))
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    threadForContextMenu?.let { thread ->
        ModalBottomSheet(onDismissRequest = { threadForContextMenu = null }) {
            Column(modifier = Modifier.padding(16.dp)) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = {
                        threadToDelete = thread
                        threadForContextMenu = null
                    }
                )
            }
        }
    }

    threadToDelete?.let { thread ->
        ConfirmDialog(
            text = stringResource(R.string.delete_thread_confirm),
            confirmLabel = stringResource(R.string.delete),
            destructive = true,
            onConfirm = {
                scope.launch {
                    app.database.deleteThread(thread)
                }
                threadToDelete = null
            },
            onDismiss = { threadToDelete = null }
        )
    }
}

/**
 * Shown in place of the thread list while there is nothing to list: a tonal circle
 * with a pair of speech bubbles, and a line explaining what would go here. The
 * iOS counterpart is `ThreadsView.emptyState`.
 */
@Composable
private fun EmptyThreadsNote(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Forum,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.no_threads_yet),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Platform-localized relative timestamp (e.g. "1 min. ago", "Yesterday", date).
 *
 * @param now taken from a caller-owned ticker rather than read here, so the label
 *   is a function of state Compose can observe and re-renders as time passes.
 */
private fun formatRelativeTime(epochMillis: Long, now: Long): String =
    DateUtils.getRelativeTimeSpanString(
        epochMillis,
        now,
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()
