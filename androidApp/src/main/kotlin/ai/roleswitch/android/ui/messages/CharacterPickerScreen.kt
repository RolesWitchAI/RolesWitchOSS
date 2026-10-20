//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.roleswitch.android.R
import ai.roleswitch.android.RolesWitchApp
import ai.roleswitch.android.data.model.MessageThread
import ai.roleswitch.android.ui.components.Live2DView
import ai.roleswitch.android.ui.components.Live2dCharacters
import kotlinx.coroutines.launch

/**
 * Picks the Live2D character a thread presents itself through on a call, from the
 * models bundled in `assets/`. Reached from the Profile screen's avatar sheet
 * ("Use Character"). Characters are swiped through left/right; the check button in
 * the top bar commits (persisting then, like Profile's Save) and returns; back
 * discards, which for a thread that never confirmed a character means it simply keeps
 * its photo.
 *
 * There is one GL surface for the whole screen, not one per page: the native manager
 * is a process-wide singleton holding a single model, so the pager's pages are
 * transparent gesture carriers laid over the surface, and settling on a page switches
 * the model in place. The pager is composed *after* [Live2DView] so it wins touch
 * dispatch — input follows the view hierarchy, whatever the surface's Z order does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterPickerScreen(
    app: RolesWitchApp,
    threadId: String,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var thread by remember { mutableStateOf<MessageThread?>(null) }

    LaunchedEffect(threadId) {
        app.database.messageThreadDao().observeThreadById(threadId).collect {
            thread = it
        }
    }

    // The pager's opening page is decided once, from the stored character — so this
    // screen's state must not be built until the row has arrived (Room emits a frame
    // or two late). A plain backdrop for that gap, the same shape MainActivity's
    // ThreadScreenFrame gives the thread-scoped model routes: without it the pager
    // starts at page 0, a stored character silently restages as the first one, and the
    // check button would save the wrong id.
    if (thread == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
        )
        return
    }

    // An id that no longer maps to a known character (registry changed, older build's
    // value) reads as "no character" on this screen — the call screen applies the same
    // rule — without rewriting the stored value behind the user's back.
    val storedId = thread?.cubismId
        ?.takeIf { Live2dCharacters.indexOf(it) >= 0 }

    val pagerState = rememberPagerState(
        initialPage = Live2dCharacters.indexOf(storedId).takeIf { it >= 0 } ?: 0
    ) { Live2dCharacters.all.size }

    val stagedId = Live2dCharacters.all[pagerState.settledPage].id
    val dirty = stagedId != storedId

    fun save() {
        val current = thread ?: return
        scope.launch {
            app.database.updateThreadCubismId(current, stagedId)
            onBack()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.character)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    // Commit-and-leave, exactly like the Profile screen's check: hidden
                    // while the staging matches what is stored, so back always means
                    // "leave without changing anything".
                    if (dirty) {
                        IconButton(onClick = ::save) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = stringResource(R.string.save)
                            )
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
        ) {
            // Preview. The GL surface draws over the window inside its own bounds
            // (setZOrderOnTop), so the pager laid on top of it carries the gestures
            // while staying visually transparent — nothing but the character itself
            // is seen.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Live2DView(
                    modifier = Modifier.fillMaxSize(),
                    modelIndex = pagerState.settledPage
                )

                // Transparent pages: swipe affordance and gesture carrier. Composed
                // after the surface so it is on top of it for input.
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { _ ->
                    Box(modifier = Modifier.fillMaxSize())
                }
            }

            // Name + position dots for the staged character.
            Text(
                text = Live2dCharacters.all[pagerState.settledPage].displayName,
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 24.dp)
            ) {
                repeat(Live2dCharacters.all.size) { index ->
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == pagerState.settledPage) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                }
                            )
                    )
                }
            }
        }
    }
}
