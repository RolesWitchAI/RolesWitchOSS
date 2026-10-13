//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.ui.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream

/**
 * Decodes a content [Uri] into a compressed JPEG [ByteArray], applying EXIF rotation and
 * downscaling so that the longest edge is at most [maxDimension] pixels.
 *
 * Returns null on any decode failure (the caller is expected to gracefully ignore).
 */
fun uriToCompressedBytes(context: Context, uri: Uri, maxDimension: Int = 1024): ByteArray? {
    return try {
        val rotation = context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f

        // use{}: decodeStream can throw (OOM on a huge image), and the outer
        // catch would otherwise swallow it and leak the stream.
        val original = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: return null

        val rotated = if (rotation != 0f && original != null) {
            val matrix = Matrix().apply { postRotate(rotation) }
            Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
        } else original

        if (rotated == null) return null

        val scale = minOf(
            maxDimension.toFloat() / rotated.width,
            maxDimension.toFloat() / rotated.height,
            1f
        )
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                rotated,
                (rotated.width * scale).toInt(),
                (rotated.height * scale).toInt(),
                true
            )
        } else rotated

        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        baos.toByteArray()
    } catch (_: Exception) {
        null
    }
}
