//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.roleswitch.android.R
import ai.roleswitch.android.RolesWitchApp
import ai.roleswitch.android.data.model.McpTool
import ai.roleswitch.android.ui.components.ConfirmDialog
import ai.roleswitch.android.ui.components.appOutlinedTextFieldColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

/**
 * Settings → Tools. Manages the MCP endpoints the assistant may call.
 *
 * One row is one endpoint (which usually exposes several callable functions — those are
 * discovered per request, not stored). This screen is purely a registry: adding a row here
 * does not let anything call it. That is decided per chat, in the Tools section of the
 * chat's Model settings, so one endpoint can serve one conversation and not another.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolsScreen(app: RolesWitchApp, onBack: () -> Unit) {
    val dao = app.database.mcpToolDao()
    val tools by dao.observeAllTools().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    // null = closed. A row for editing, or a blank draft for "Add".
    var editing by remember { mutableStateOf<McpTool?>(null) }
    // The threads screen's two-step delete: long-press opens the context menu, the
    // Delete item opens the confirm dialog.
    var toolForContextMenu by remember { mutableStateOf<McpTool?>(null) }
    var toolToDelete by remember { mutableStateOf<McpTool?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tools)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
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
                .padding(16.dp)
        ) {
            Text(
                stringResource(R.string.tools_description),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 16.dp)
            )

            if (tools.isNotEmpty()) {
                SettingsCard {
                    tools.forEachIndexed { index, tool ->
                        if (index > 0) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                        ToolRow(tool = tool, onClick = { editing = tool }, onLongClick = {
                            toolForContextMenu = tool
                        })
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editing = McpTool(name = "", url = "") }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.tools_add),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    val draft = editing
    if (draft != null) {
        ToolEditorSheet(
            app = app,
            tool = draft,
            isNew = tools.none { it.id == draft.id },
            onDismiss = { editing = null },
            onSave = { saved ->
                scope.launch {
                    if (tools.none { it.id == saved.id }) dao.insert(saved) else dao.update(saved)
                }
                editing = null
            }
        )
    }

    // The threads screen's two-step delete: long-press opens the context menu, the
    // Delete item opens the confirm dialog.
    toolForContextMenu?.let { tool ->
        ModalBottomSheet(onDismissRequest = { toolForContextMenu = null }) {
            Column(modifier = Modifier.padding(16.dp)) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = {
                        toolToDelete = tool
                        toolForContextMenu = null
                    }
                )
            }
        }
    }

    toolToDelete?.let { tool ->
        ConfirmDialog(
            text = stringResource(R.string.tools_delete_confirm, tool.name),
            confirmLabel = stringResource(R.string.delete),
            destructive = true,
            onDismiss = { toolToDelete = null },
            onConfirm = {
                scope.launch { dao.delete(tool) }
                toolToDelete = null
            }
        )
    }
}

/**
 * A registry entry: tapping anywhere opens the editor; long-pressing opens the context
 * menu, where Delete lives — the threads screen's gesture, keeping destructive actions
 * out of the editing form. There is no switch here — whether an endpoint takes part is
 * decided per chat, on that chat's Model settings screen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolRow(tool: McpTool, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(16.dp)
    ) {
        Text(tool.name, color = MaterialTheme.colorScheme.onSurface)
        Text(
            tool.url,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/**
 * Add/edit sheet for one endpoint.
 *
 * Saving runs a real `tools/list` against the entered address first, so a row can only be
 * stored once it is known to work — the user finds out here rather than mid-conversation.
 * This used to be a separate "Test connection" button, which was easy to skip and left the
 * save path unchecked; folding it into Save means the check always happens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolEditorSheet(
    app: RolesWitchApp,
    tool: McpTool,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (McpTool) -> Unit
) {
    var name by remember { mutableStateOf(tool.name) }
    var url by remember { mutableStateOf(tool.url) }
    var token by remember { mutableStateOf(tool.token) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val failedPrefix = stringResource(R.string.tools_test_failed)
    val noToolsOffered = stringResource(R.string.tools_test_empty)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Opens fully expanded rather than at the half-height detent. This is a form
        // ending in Save: at half height the keyboard left the buttons off-screen and
        // reaching them meant scrolling a sheet that also drags.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                // Keeps the action row above the keyboard instead of behind it.
                .imePadding()
        ) {
            Text(
                stringResource(if (isNew) R.string.tools_add else R.string.tools_edit),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.tools_name)) },
                placeholder = { Text(stringResource(R.string.tools_name_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = appOutlinedTextFieldColors()
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = url,
                onValueChange = { url = it; error = null },
                label = { Text(stringResource(R.string.tools_endpoint)) },
                placeholder = { Text(stringResource(R.string.tools_endpoint_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = appOutlinedTextFieldColors()
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = token,
                onValueChange = { token = it; error = null },
                label = { Text(stringResource(R.string.tools_token)) },
                placeholder = { Text(stringResource(R.string.optional)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = appOutlinedTextFieldColors()
            )

            Spacer(Modifier.height(16.dp))

            // Mirrors the model picker's two states: a spinner with a label while the
            // request is out, the failure in error red once it comes back.
            if (checking) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.tools_checking),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            error?.let { message ->
                Text(
                    message,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(16.dp))
            }

            // Delete is not here any more: it lives on the row's long-press, which keeps
            // this form about the endpoint itself and the destructive action one
            // deliberate step away from editing.
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                // Left enabled during the check: a slow or unreachable endpoint should
                // never trap the user in the sheet until it times out.
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    enabled = name.isNotBlank() && url.isNotBlank() && !checking,
                    onClick = {
                        checking = true
                        error = null
                        val normalizedUrl = normalizeMcpUrl(url)
                        val trimmedToken = token.trim()
                        scope.launch {
                            try {
                                val names = withContext(Dispatchers.IO) {
                                    app.mcpService.discoverToolNames(normalizedUrl, trimmedToken)
                                }
                                // An endpoint that lists nothing is reachable but useless
                                // to the model, and the sheet closing on save is the last
                                // chance to say so.
                                if (names.isEmpty()) {
                                    error = noToolsOffered
                                } else {
                                    onSave(
                                        tool.copy(
                                            name = name.trim(),
                                            url = normalizedUrl,
                                            token = trimmedToken,
                                            updatedAt = Clock.System.now().toEpochMilliseconds()
                                        )
                                    )
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                error = "$failedPrefix ${e.message ?: e::class.simpleName}"
                            } finally {
                                checking = false
                            }
                        }
                    }
                ) { Text(stringResource(R.string.save)) }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Trims the address and assumes `https://` when no scheme was typed.
 *
 * Deliberately *not* `normalizeApiUrl`: that one is tuned for local LM Studio endpoints
 * (default port 1234, appends `/v1`), which would corrupt an MCP address. Remote MCP
 * servers are effectively always TLS, so https is the right default here.
 */
private fun normalizeMcpUrl(input: String): String {
    val raw = input.trim()
    if (raw.isEmpty()) return raw
    return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
}
