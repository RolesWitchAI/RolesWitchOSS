//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/** Up to two uppercase initials for [name] ("Ada Lovelace" -> "AL"). */
private fun extractInitials(name: String): String {
    val words = name.split("\\s+".toRegex()).filter { it.isNotEmpty() }
    return words.take(2).map { it.first().uppercase() }.joinToString("")
}

/** Stable avatar hue for [string], as degrees in `[0, 360)`. */
private fun avatarHue(string: String): Float {
    val hash = string.map { it.code }.sum()
    return (abs(hash) % 360).toFloat()
}

/** Stable per-name avatar colour. */
private fun colorFromString(string: String): Color = Color.hsl(avatarHue(string), 0.6f, 0.5f)

/** Circular fallback avatar showing up to two initials over a name-derived colour. */
@Composable
fun InitialsCircle(
    name: String,
    size: Dp = 40.dp,
    backgroundColor: Color = colorFromString(name)
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = extractInitials(name),
            color = Color.White,
            fontSize = (size.value * 0.4f).sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * A thread's avatar. Priority:
 *  1. [avatarBytes] — decoded bitmap, cropped into a circle;
 *  2. otherwise — a colored [InitialsCircle] derived from [name].
 */
@Composable
fun ThreadAvatar(
    name: String,
    avatarBytes: ByteArray? = null,
    size: Dp = 40.dp
) {
    val bitmap = avatarBytes?.let { bytes ->
        remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape),
        contentAlignment = Alignment.Center
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(size),
                contentScale = ContentScale.Crop
            )
            else -> InitialsCircle(name = name, size = size)
        }
    }
}
