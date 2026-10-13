//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** One full cycle of the three dots, in milliseconds. */
private const val CYCLE_MS = 1200

/** How far into the cycle each dot starts, so they ripple instead of blinking together. */
private val DOT_DELAYS_MS = listOf(0, 160, 320)

/**
 * The "assistant is working" placeholder: an assistant-side bubble with three pulsing dots.
 *
 * Deliberately wordless. What the app is waiting for — thinking, or a running tool — is said
 * once, in the thread header; repeating it here is what made the screen read as saying the
 * same thing twice. This only has to show that something is happening, which matters most
 * with tools: nothing streams until the model has finished calling them, which can be tens
 * of seconds after the message is sent.
 */
@Composable
fun TypingBubble(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typing")

    Row(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DOT_DELAYS_MS.forEach { delay ->
                val alpha by transition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 0.3f,
                    animationSpec = infiniteRepeatable(
                        animation = keyframes {
                            durationMillis = CYCLE_MS
                            0.3f at delay using LinearEasing
                            1f at delay + 200 using LinearEasing
                            0.3f at delay + 400 using LinearEasing
                        },
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "dot$delay"
                )
                Row(
                    modifier = Modifier
                        .size(7.dp)
                        .alpha(alpha)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant)
                ) {}
            }
        }
    }
}
