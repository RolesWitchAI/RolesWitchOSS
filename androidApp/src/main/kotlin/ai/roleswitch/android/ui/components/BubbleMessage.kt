//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.ui.components

import ai.roleswitch.android.ui.util.IMAGE_ONLY_MESSAGE
import ai.roleswitch.android.ui.util.DOCUMENT_ONLY_MESSAGE

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.stringResource
import ai.roleswitch.android.R
import ai.roleswitch.android.data.model.DocumentAttachment
import ai.roleswitch.android.data.model.Message
import dev.jeziellago.compose.markdowntext.MarkdownText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BubbleMessage(
    message: Message,
    modifier: Modifier = Modifier,
    showFavoriteIcon: Boolean = true,
    // Whether tapping an image may open the full-size viewer. Locked while a reply is
    // being generated — the same guard that withholds the long-press menu (see
    // MessagesScreen's `bubblesLocked`).
    imagePreviewEnabled: Boolean = true,
    onLongPress: (() -> Unit)? = null
) {
    BubbleMessageContent(
        content = message.content,
        isUser = message.isUser,
        isFavorite = showFavoriteIcon && message.favorite,
        timestamp = message.createdAt,
        isStreaming = false,
        imageData = message.imageData,
        documents = message.documents,
        imagePreviewEnabled = imagePreviewEnabled,
        onLongPress = onLongPress,
        modifier = modifier
    )
}

@Composable
fun BubbleMessage(
    content: String,
    isUser: Boolean,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier
) {
    BubbleMessageContent(
        content = content,
        isUser = isUser,
        isFavorite = false,
        timestamp = null,
        isStreaming = isStreaming,
        modifier = modifier
    )
}

@Composable
private fun BubbleMessageContent(
    content: String,
    isUser: Boolean,
    isFavorite: Boolean,
    timestamp: Long?,
    isStreaming: Boolean,
    imageData: List<ByteArray> = emptyList(),
    documents: List<DocumentAttachment> = emptyList(),
    imagePreviewEnabled: Boolean = true,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var fullscreenImage by remember { mutableStateOf<ByteArray?>(null) }

    // Decoded up front rather than inside the layout: bytes that fail to decode are dropped
    // silently, so the count below — not imageData.size — is what actually gets drawn.
    val bitmaps = remember(imageData) {
        imageData.mapNotNull { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bytes to it }
        }
    }
    val hasText = content.isNotBlank() &&
        content != IMAGE_ONLY_MESSAGE &&
        content != DOCUMENT_ONLY_MESSAGE

    // The chrome below (background + padding) is unconditional, so a bubble with neither
    // text, nor a decodable image, nor a document renders as an empty rounded pill.
    // Draw nothing instead.
    if (!hasText && bitmaps.isEmpty() && documents.isEmpty()) return

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isUser && isFavorite) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = stringResource(R.string.favorite),
                        tint = Color(0xFFFFD700),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.size(4.dp))
                }

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            if (isUser) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .then(
                            if (onLongPress != null) {
                                Modifier.pointerInput(onLongPress) {
                                    detectTapGestures(onLongPress = { onLongPress() })
                                }
                            } else Modifier
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    if (bitmaps.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            bitmaps.forEach { (bytes, bitmap) ->
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = stringResource(R.string.photo),
                                    modifier = Modifier
                                        .size(120.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        // The pill's own long-press detector never sees a
                                        // gesture that starts here: the image's handler wins
                                        // the pointer arena, so an image-only message's menu
                                        // was reachable only from the border. Detect both —
                                        // tap opens the viewer, long press opens the message
                                        // menu, exactly as the pill does around it.
                                        .pointerInput(onLongPress, imagePreviewEnabled) {
                                            detectTapGestures(
                                                onTap = { if (imagePreviewEnabled) fullscreenImage = bytes },
                                                onLongPress = { onLongPress?.invoke() }
                                            )
                                        },
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    }

                    // Document chips: filename rows, not thumbnails — the extracted text never
                    // renders here (the engine already read it), only the file's identity.
                    if (documents.isNotEmpty()) {
                        val chipColor = if (isUser) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            documents.forEach { doc ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.InsertDriveFile,
                                        contentDescription = stringResource(R.string.attached_document),
                                        tint = chipColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.size(6.dp))
                                    Text(
                                        text = doc.fileName,
                                        color = chipColor,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    if (hasText && (bitmaps.isNotEmpty() || documents.isNotEmpty())) {
                        Spacer(Modifier.height(6.dp))
                    }

                    if (hasText) {
                        if (isUser) {
                            Text(
                                text = content,
                                color = Color.White,
                                fontSize = 15.sp
                            )
                        } else {
                            MarkdownText(
                                markdown = content,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 15.sp
                                )
                            )
                        }
                    }
                }

                if (!isUser && isFavorite) {
                    Spacer(Modifier.size(4.dp))
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = stringResource(R.string.favorite),
                        tint = Color(0xFFFFD700),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            if (timestamp != null) {
                Text(
                    text = formatTimestamp(timestamp),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }

    // Fullscreen image viewer
    fullscreenImage?.let { bytes ->
        FullscreenImageViewer(
            imageBytes = bytes,
            onDismiss = { fullscreenImage = null }
        )
    }
}

@Composable
private fun FullscreenImageViewer(
    imageBytes: ByteArray,
    onDismiss: () -> Unit
) {
    val bitmap = remember(imageBytes) {
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    } ?: return

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        if (scale > 1f) {
                            offset += pan
                        } else {
                            offset = Offset.Zero
                        }
                    }
                }
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.full_size_photo),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    ),
                contentScale = ContentScale.Fit
            )

            // Close button
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Default.Close, stringResource(R.string.close), tint = Color.White)
            }
        }
    }
}

private fun formatTimestamp(millis: Long): String {
    val formatter = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    return formatter.format(Date(millis))
}
