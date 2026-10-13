//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.ui.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import ai.roleswitch.android.R
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.launch

private const val TAG = "CameraPreview"

/**
 * Longest-edge cap for captured photos. Above this the chat, the on-device vision tower
 * and the remote request all resize anyway, so a bigger file buys nothing but bandwidth;
 * below it a full-sensor capture starts looking soft next to a 1080p preview.
 * `preview` on most devices is 1080p, so 1440 beats what the user saw on screen before
 * shooting without becoming a multi-megabyte BLOB.
 */
private const val CAPTURE_MAX_DIM = 1440

class CameraManager(private val context: Context) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var preview: Preview? = null
    private var useFrontCamera = false
    private var zoomRatio = 1f
    private val executor = Executors.newSingleThreadExecutor()

    fun getCameraSelector(): CameraSelector {
        return if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
        else CameraSelector.DEFAULT_BACK_CAMERA
    }

    fun toggleCamera() {
        useFrontCamera = !useFrontCamera
        // Reset so they get re-created for new camera
        preview = null
        imageCapture = null
        boundCamera = null
        zoomRatio = 1f
    }

    /**
     * Applies a pinch-scale delta to the camera zoom, clamped to the current
     * camera's min/max range. No-op until a camera is actually bound.
     */
    fun zoomBy(scaleFactor: Float) {
        val camera = boundCamera ?: return
        val zoomState = camera.cameraInfo.zoomState.value ?: return
        val target = (zoomRatio * scaleFactor).coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        if (target != zoomRatio) {
            camera.cameraControl.setZoomRatio(target)
            zoomRatio = target
        }
    }

    fun getImageCapture(): ImageCapture {
        if (imageCapture == null) {
            // Quality over latency: this feeds a still that the user is going to look at.
            // MINIMIZE_LATENCY (the default) trades a smaller, softer capture buffer for
            // shutter speed, which is exactly the "saved image worse than the preview"
            // complaint once the photo is a first-class product again.
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
        }
        return imageCapture!!
    }

    fun getPreview(): Preview {
        if (preview == null) {
            preview = Preview.Builder().build()
        }
        return preview!!
    }

    suspend fun capture(): List<ByteArray> = suspendCoroutine { cont ->
        val capture = imageCapture
        if (capture == null) {
            cont.resume(emptyList())
            return@suspendCoroutine
        }

        capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val rotation = image.imageInfo.rotationDegrees

                    // The buffer format is JPEG (the default since CameraX 1.4), so decode it
                    // straight to a downsampled bitmap rather than materialising the full
                    // sensor frame. Sampling during decode keeps a max-quality 12MP+ capture
                    // from becoming a ~50-200MB RGBA intermediate just to be shrunk again.
                    val rawBitmap = if (image.format == ImageFormat.JPEG) {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                        var sample = 1
                        val longestEdge = maxOf(image.width, image.height)
                        while (longestEdge / (sample * 2) >= CAPTURE_MAX_DIM) sample *= 2
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    } else {
                        image.toBitmap()
                    }
                    if (rawBitmap == null) {
                        cont.resume(emptyList())
                        return@onCaptureSuccess
                    }

                    // Apply rotation and mirror front camera
                    val matrix = Matrix()
                    if (rotation != 0) matrix.postRotate(rotation.toFloat())
                    if (useFrontCamera) matrix.preScale(-1f, 1f)
                    val finalBitmap = if (rotation != 0 || useFrontCamera) {
                        Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, false)
                    } else rawBitmap

                    // Scale down
                    val scale = minOf(CAPTURE_MAX_DIM.toFloat() / finalBitmap.width, CAPTURE_MAX_DIM.toFloat() / finalBitmap.height, 1f)
                    val scaled = if (scale < 1f) {
                        Bitmap.createScaledBitmap(finalBitmap, (finalBitmap.width * scale).toInt(), (finalBitmap.height * scale).toInt(), true)
                    } else finalBitmap

                    val baos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 90, baos)
                    cont.resume(listOf(baos.toByteArray()))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process capture: ${e.message}")
                    cont.resume(emptyList())
                } finally {
                    image.close()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "Capture failed: ${exception.message}")
                cont.resume(emptyList())
            }
        })
    }

    /** Records the provider and camera the preview bound to, so they can be used later. */
    fun onBound(provider: ProcessCameraProvider, camera: Camera) {
        cameraProvider = provider
        boundCamera = camera
    }

    /** Releases the camera but keeps this manager usable for a later session. */
    fun unbind() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        boundCamera = null
        preview = null
        imageCapture = null
    }

    fun shutdown() {
        unbind()
        executor.shutdown()
    }
}

@Composable
fun CameraPreviewScreen(
    cameraManager: CameraManager,
    onClose: () -> Unit,
    onCapture: ((ByteArray) -> Unit)? = null
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var cameraKey by remember { mutableStateOf(0) }
    var capturing by remember { mutableStateOf(false) }
    val transformableState = rememberTransformableState { zoomChange, _, _ ->
        cameraManager.zoomBy(zoomChange)
    }

    // Leaving the video overlay must release the camera: bindToLifecycle keeps
    // it bound to the (still-resumed) activity otherwise, and the in-use
    // indicator stays lit until the whole screen goes away.
    DisposableEffect(Unit) {
        onDispose { cameraManager.unbind() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .transformable(transformableState)
    ) {
        key(cameraKey) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val provider = cameraProviderFuture.get()
                        provider.unbindAll()

                        val preview = cameraManager.getPreview()
                        preview.surfaceProvider = previewView.surfaceProvider

                        val imageCapture = cameraManager.getImageCapture()

                        try {
                            val camera = provider.bindToLifecycle(
                                lifecycleOwner,
                                cameraManager.getCameraSelector(),
                                preview,
                                imageCapture
                            )
                            cameraManager.onBound(provider, camera)
                        } catch (e: Exception) {
                            Log.e(TAG, "Camera bind failed: ${e.message}")
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Controls overlay
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Close button
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Default.Close, stringResource(R.string.close_video), tint = Color.White)
            }

            // Switch camera button
            IconButton(
                onClick = {
                    cameraManager.toggleCamera()
                    cameraKey++
                },
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Default.Cameraswitch, stringResource(R.string.switch_camera), tint = Color.White)
            }
        }

        // Capture button, shown only when a photo is being taken
        onCapture?.let { onCaptured ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(bottom = 24.dp)
            ) {
                IconButton(
                    onClick = {
                        if (capturing) return@IconButton
                        capturing = true
                        scope.launch {
                            val bytes = cameraManager.capture().firstOrNull()
                            capturing = false
                            if (bytes != null) onCaptured(bytes)
                        }
                    },
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.9f))
                ) {
                    Icon(
                        Icons.Default.PhotoCamera,
                        stringResource(R.string.take_photo),
                        tint = Color.Black,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
        }
    }
}

/**
 * Full-screen in-process photo capture dialog. Binds the camera with the same
 * [CameraManager] the video call uses, so taking a photo no longer hands off to
 * the separate system camera process.
 */
@Composable
fun CameraCaptureScreen(
    onClose: () -> Unit,
    onCapture: (ByteArray) -> Unit
) {
    val context = LocalContext.current
    val cameraManager = remember { CameraManager(context) }

    DisposableEffect(Unit) {
        onDispose { cameraManager.shutdown() }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(Modifier.fillMaxSize()) {
            CameraPreviewScreen(
                cameraManager = cameraManager,
                onClose = onClose,
                onCapture = { bytes ->
                    onCapture(bytes)
                    onClose()
                }
            )
        }
    }
}
