//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import ai.roleswitch.android.R

/**
 * The repeated runtime-permission dance as one helper: returns a request
 * function that runs [onGranted] immediately when [permission] is already
 * held, and otherwise launches the system prompt, dispatching to [onGranted]
 * or [onDenied] with the user's answer.
 */
@Composable
fun rememberPermissionRequester(
    permission: String,
    onDenied: () -> Unit = {},
    onGranted: () -> Unit
): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onGranted() else onDenied()
    }
    return {
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            launcher.launch(permission)
        }
    }
}

private const val PERMISSION_PREFS = "system_permissions"
private const val KEY_ASKED_LOCAL_NETWORK = "asked_local_network"

/**
 * Whether the app may open sockets to local-network addresses. Android 17 enforces
 * Local Network Protection for apps targeting SDK 37 — which this app does — and
 * silently drops such packets until `ACCESS_LOCAL_NETWORK` is granted; the app sees
 * a plain connect timeout, indistinguishable from a dead server. Below API 36 the
 * permission does not exist and local network access is unrestricted.
 */
fun isLocalNetworkAccessGranted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 36) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_LOCAL_NETWORK
    ) == PackageManager.PERMISSION_GRANTED
}

private fun hasAskedForLocalNetwork(context: Context): Boolean =
    context.getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_ASKED_LOCAL_NETWORK, false)

private fun markAskedForLocalNetwork(context: Context) {
    context.getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_ASKED_LOCAL_NETWORK, true)
        .apply()
}

/**
 * Gate for the flows whose server may live on the local network — fetching models
 * from a user-supplied endpoint, previewing the external (OpenAI) speech voice —
 * where Android 17's Local Network Protection otherwise reads as a dead server.
 * Returns the function to invoke at the moment the user initiates the action; it
 * runs [onProceed] when the grant is held, shows the system prompt on the first
 * ever initiation, and afterwards a missing grant surfaces only as the in-app
 * alert whose button opens the app's system settings page.
 *
 * The system prompt therefore appears at most once per install. The asked flag
 * lives in plain `SharedPreferences` rather than `SettingsManager` on purpose:
 * it is permission bookkeeping, not user data, and Settings → Reset must not
 * re-arm the prompt.
 */
@Composable
fun rememberLocalNetworkGate(onProceed: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val currentOnProceed by rememberUpdatedState(onProceed)
    var showSettingsAlert by remember { mutableStateOf(false) }

    val requestPermission = rememberPermissionRequester(
        permission = Manifest.permission.ACCESS_LOCAL_NETWORK,
        onDenied = {
            markAskedForLocalNetwork(context)
            showSettingsAlert = true
        }
    ) {
        currentOnProceed()
    }

    if (showSettingsAlert) {
        PermissionDeniedDialog(
            textRes = R.string.local_network_permission_alert,
            onDismiss = { showSettingsAlert = false }
        )
    }

    return {
        when {
            isLocalNetworkAccessGranted(context) -> currentOnProceed()
            !hasAskedForLocalNetwork(context) -> {
                // One system prompt per install; a denial lands in the alert above.
                markAskedForLocalNetwork(context)
                requestPermission()
            }
            else -> showSettingsAlert = true
        }
    }
}
