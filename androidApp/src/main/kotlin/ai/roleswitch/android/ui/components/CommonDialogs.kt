//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import ai.roleswitch.android.R
import ai.roleswitch.android.data.model.MessageThread
import androidx.navigation.NavController

/** OK-only informational dialog (e.g. "vision is disabled for this thread"). */
@Composable
fun InfoDialog(@StringRes textRes: Int, onDismiss: () -> Unit) {
    InfoDialog(stringResource(textRes), onDismiss)
}

/** [InfoDialog] for messages that aren't string resources — server errors and the like. */
@Composable
fun InfoDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        }
    )
}

/**
 * The shared "[Cancel] [action]" shape.
 *
 * Every two-button alert in the app should go through this rather than spelling out
 * `confirmButton`/`dismissButton` inline. Material3 renders `dismissButton` before
 * `confirmButton` (`AlertDialog.kt`), so the action is always the right-hand button —
 * hard-coding that here is what stops per-screen copies from drifting into opposite
 * orders, which is exactly what had happened to the Yes/No and Go-to-Settings dialogs.
 *
 * Set [destructive] for actions that throw work away; it tints the action label with
 * the error colour, matching Delete / Clear / Reset.
 */
@Composable
fun ConfirmDialog(
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    cancelLabel: String = stringResource(R.string.cancel),
    /** Optional heading, for alerts whose body alone does not say what they are about. */
    title: String? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = title?.let { { Text(it) } },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                if (destructive) {
                    Text(confirmLabel, color = MaterialTheme.colorScheme.error)
                } else {
                    Text(confirmLabel)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(cancelLabel) }
        }
    )
}

/**
 * Shown after a runtime permission was denied. The action deep-links to the app's system
 * settings page (the only way to re-grant a "don't ask again" denial).
 */
@Composable
fun PermissionDeniedDialog(@StringRes textRes: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ConfirmDialog(
        text = stringResource(textRes),
        confirmLabel = stringResource(R.string.open_settings),
        onConfirm = {
            onDismiss()
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
            context.startActivity(intent)
        },
        onDismiss = onDismiss
    )
}

/**
 * Message plus a jump to the app's own Settings screen, for states the user can only
 * clear by changing a setting ("image recognition is disabled", "enable VOSK", …).
 *
 * Only use this where Settings genuinely fixes the condition. An alert about an in-call
 * mute toggle or an unsupported language stays an [InfoDialog] — offering Settings there
 * sends the user somewhere that cannot help.
 */
@Composable
fun GoToSettingsDialog(
    @StringRes textRes: Int,
    onDismiss: () -> Unit,
    onGoToSettings: () -> Unit
) {
    ConfirmDialog(
        text = stringResource(textRes),
        confirmLabel = stringResource(R.string.go_to_settings),
        onConfirm = {
            onDismiss()
            onGoToSettings()
        },
        onDismiss = onDismiss
    )
}

/**
 * Message plus a jump to a *system* settings screen, for conditions only the device can
 * clear — e.g. downloading an offline speech language pack, which lives in the OS rather
 * than in this app.
 *
 * Degrades to an OK-only [InfoDialog] when nothing on the device handles [action]. These
 * system screens are not guaranteed to exist on every OEM build, and package-visibility
 * rules can hide them besides; a plain message beats a button that does nothing.
 */
@Composable
fun SystemSettingsDialog(
    @StringRes textRes: Int,
    action: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val intent = remember(action) { Intent(action) }
    val resolves = remember(intent) { intent.resolveActivity(context.packageManager) != null }

    if (!resolves) {
        InfoDialog(textRes, onDismiss)
        return
    }
    ConfirmDialog(
        text = stringResource(textRes),
        confirmLabel = stringResource(R.string.open_settings),
        onConfirm = {
            onDismiss()
            context.startActivity(intent)
        },
        onDismiss = onDismiss
    )
}

/** "Intelligence not configured" dialog offering a jump to the Settings screen. */
@Composable
fun ModelNotConfiguredDialog(onDismiss: () -> Unit, onGoToSettings: () -> Unit) {
    GoToSettingsDialog(
        textRes = R.string.intelligence_not_configured_message,
        onDismiss = onDismiss,
        onGoToSettings = onGoToSettings
    )
}

/**
 * Navigate to the correct settings screen for the current thread: per-thread model settings
 * when the thread has custom overrides enabled, global settings otherwise.
 */
fun NavController.navigateBasedOnThread(threadId: String, currentThread: MessageThread?) {
    val route = if (currentThread?.useCustomSettings == true) "model/$threadId" else "settings"
    navigate(route) { launchSingleTop = true }
}

/**
 * Pop back only when there is a visible destination beneath the current one. The framework
 * guards its system-back handling this way (`BackHandler(currentBackStack.size > 1)` in
 * NavHost), but direct `popBackStack()` calls from in-app back buttons bypass that guard — a
 * fast double-tap would pop the start destination off the stack and leave a blank screen that
 * requires restarting the app.
 */
fun NavController.safePopBackStack() {
    if (previousBackStackEntry != null) {
        popBackStack()
    }
}
