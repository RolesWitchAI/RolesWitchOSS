//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.ui.components

import ai.roleswitch.android.R
import ai.roleswitch.android.RolesWitchApp
import ai.roleswitch.android.service.LiteRtService
import ai.roleswitch.android.settings.EffectiveSettings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Keeps the resident on-device model in step with the screen the user is on.
 *
 * Loads eagerly, so the (tens of seconds) wait happens while they read the thread rather
 * than after they hit send — and unloads just as eagerly when the thread they opened uses
 * the remote engine or a different model. Re-entering a thread that wants the model
 * already loaded is free.
 */
@Composable
fun LitertEngineSyncEffect(
    app: RolesWitchApp,
    /**
     * This scope's resolved settings, or `null` while the thread row is still loading.
     *
     * Callers pass the *resolved* value, never the placeholder-backed one both screens
     * also compute: the placeholder has `useCustomSettings = false`, so every override
     * falls through to the global value, and a thread that overrides the engine to remote
     * reads — for as long as it takes Room to emit — like a thread wanting the global
     * on-device model. Acting on that is what pulled gigabytes into a call the thread was
     * never going to run them for. See the null guard in the effect below.
     */
    settings: EffectiveSettings?,
    /**
     * Whether to *load* the model on entering, or only to drop one this scope cannot use.
     *
     * False for the chat screen: opening a thread is not a decision to run anything, and
     * a user flicking between conversations to read them should not be made to wait for
     * gigabytes they never asked for. The load happens when they send, which is the point
     * at which they have committed to an answer.
     *
     * True for a voice call, where the first turn arrives as speech and there is no send
     * button to hang the wait on.
     */
    eager: Boolean = true
) {
    val foregrounded by app.isAppForegrounded.collectAsState()

    // Keyed on every field EngineKey is built from, so a change to any of them re-syncs
    // rather than being noticed only at send time — and on the foreground flag, because
    // the model must never be *loaded* into a backgrounded process. Without that, popping
    // a screen while backgrounded would pull gigabytes in exactly when the app is most
    // likely to be killed for holding them.
    //
    // Backgrounding does **not** unload. Leaving the app is not leaving the conversation:
    // taking a photo, picking one from the gallery, or answering a permission dialog all
    // background the process for a few seconds, and dropping the weights there cost the
    // user a full multi-second reload on return for no benefit. What unloads is leaving
    // the thread, or pointing it at a different model — which this effect already does,
    // via `syncTo` and `releaseIfNotFor`. There is no system-memory path any more; see
    // the `LiteRtService` class doc for why the `onTrimMemory` override was removed.
    LaunchedEffect(
        foregrounded,
        settings?.useOnDevice,
        settings?.litertModel,
        settings?.litertAccelerator,
        settings?.litertMaxTokens,
        settings?.visionAvailable
    ) {
        if (!foregrounded) return@LaunchedEffect
        // `null` means "this screen has not loaded its thread row yet", which is not the
        // same as "this screen wants the global settings". The placeholder resolution both
        // screens fall back to reads the *global* engine choice, so an eager call on a thread
        // that overrides the engine to remote used to load the global model behind a blocking
        // overlay — and `ensureEngine` is NonCancellable, so the subsequent unload could only
        // queue behind a load it had no way to abandon. Keying on the nullable fields makes the
        // key change when the row lands, so the sync re-fires with the real answer.
        val target = settings ?: return@LaunchedEffect
        if (eager) app.liteRtService.syncTo(target) else app.liteRtService.releaseIfNotFor(target)
    }
}

/**
 * Covers the screen while the on-device model loads.
 *
 * Deliberately blocking rather than a status line: until the weights are in memory the
 * assistant genuinely cannot do anything, and a 30-second unexplained pause after
 * hitting send is the worst version of that. Callers place it last inside a [Box] so it
 * paints over the message list, and disable their composer while it is showing.
 */
@Composable
fun LitertWarmUpOverlay(app: RolesWitchApp, modifier: Modifier = Modifier) {
    val state by app.liteRtService.initState.collectAsState()
    val initializing = state as? LiteRtService.InitState.Initializing

    AnimatedVisibility(
        visible = initializing != null,
        enter = fadeIn() + scaleIn(initialScale = 0.9f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                // Swallows taps so nothing underneath can be pressed through the
                // overlay — the controls it covers have no model to act on yet.
                .pointerInput(Unit) { detectTapGestures { } }
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator()
                Text(
                    stringResource(R.string.litert_preparing_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    // `initializing` is captured before the animation so the text does
                    // not blank out mid fade-out.
                    stringResource(
                        R.string.litert_preparing_message,
                        initializing?.displayName.orEmpty()
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Non-blocking banner shown when the model failed to load — most often a device whose
 * GPU the runtime cannot use, which the Advanced screen's accelerator picker fixes.
 *
 * Deliberately *not* part of [LitertWarmUpOverlay]: a failure is a durable state, and a
 * full-screen cover would lock the user out of the conversation (and out of the settings
 * that would resolve it) with no way back.
 */
@Composable
fun LitertWarmUpFailureBanner(
    app: RolesWitchApp,
    /**
     * The settings Retry should re-run the load with — the resolved ones, nullable on the
     * same terms as [LitertEngineSyncEffect]. A failure is left on screen by whatever came
     * before it, so Retry can outlive the resolution that caused it; with the
     * placeholder-backed value it would load the *global* model into a thread that overrides
     * the engine, and the load would report success next to a failure banner.
     */
    settings: EffectiveSettings?,
    modifier: Modifier = Modifier
) {
    val state by app.liteRtService.initState.collectAsState()
    val failed = state as? LiteRtService.InitState.Failed ?: return
    val scope = rememberCoroutineScope()

    Surface(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.litert_prepare_failed, failed.message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                // Nothing to retry *to* until the thread row is in: the button stays, so the
                // banner does not jump, but it does nothing rather than guess an engine.
                enabled = settings != null,
                onClick = { settings?.let { s -> scope.launch { app.liteRtService.syncTo(s) } } }
            ) {
                Text(stringResource(R.string.retry))
            }
        }
    }
}

/** True while the on-device model is loading — used to disable input and back-navigation. */
@Composable
fun rememberLitertInitializing(app: RolesWitchApp): Boolean {
    val state by app.liteRtService.initState.collectAsState()
    return state is LiteRtService.InitState.Initializing
}
