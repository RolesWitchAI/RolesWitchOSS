//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import java.util.WeakHashMap

/**
 * Holds the display awake while [enabled], releasing it as soon as that flips back
 * or the caller leaves the composition.
 *
 * Reference-counted per window, because `FLAG_KEEP_SCREEN_ON` is one bit shared by every
 * screen in a single-Activity app and the last writer used to win. Two callers overlap
 * routinely: a navigation transition composes the incoming screen while the outgoing one
 * is still on the back stack, so a call started from the chat screen had the call screen
 * ask for the display and then the chat screen's disposal clear it a few hundred
 * milliseconds later — the call ran on with the flag off and the display dimmed and
 * locked at the system inactivity timeout. Counting holds means only the last one
 * standing clears the flag.
 *
 * Set on the Activity's window rather than through [android.view.View.keepScreenOn] so it
 * cannot land on the wrong window: inside a dialog or `ModalBottomSheet` content lambda
 * `LocalView` resolves to that transient window, while `LocalContext` still resolves to
 * the Activity.
 *
 * The hold is released in `onDispose` whatever the reason for leaving, so an abandoned
 * screen can never strand the display on.
 */
@Composable
fun KeepScreenOn(enabled: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, enabled) {
        if (activity == null || !enabled) return@DisposableEffect onDispose {}
        activity.acquireScreenOn()
        onDispose { activity.releaseScreenOn() }
    }
}

/**
 * Live holds per window. Plain map, no synchronisation: composition effects run on the
 * main thread, which is the only place [KeepScreenOn] touches this.
 *
 * Weak keys so a destroyed Activity (a rotation, say) is not retained by a hold that its
 * disposal somehow never balanced.
 */
private val screenOnHolds = WeakHashMap<Activity, Int>()

private fun Activity.acquireScreenOn() {
    val held = (screenOnHolds[this] ?: 0) + 1
    screenOnHolds[this] = held
    if (held == 1) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}

private fun Activity.releaseScreenOn() {
    val held = (screenOnHolds[this] ?: 0) - 1
    if (held > 0) {
        screenOnHolds[this] = held
    } else {
        screenOnHolds.remove(this)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

/** The Activity behind a Compose `LocalContext`, which is usually a `ContextThemeWrapper`. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
