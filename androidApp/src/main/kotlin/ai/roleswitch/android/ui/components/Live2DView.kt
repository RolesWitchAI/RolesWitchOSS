//
// Ported from RolesWitchAndroid (Live2D character feature).
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.ui.components

import android.opengl.GLSurfaceView
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import ai.roleswitch.android.service.CubismBridge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** A bundled Live2D character the user can put on a call instead of a photo avatar. */
data class Live2dCharacter(
    /** Asset directory name under `assets/`, and the value stored in `MessageThread.cubismId`. */
    val id: String,
    /** Name shown in the character picker. */
    val displayName: String
)

/**
 * The character catalogue. **The order must stay index-aligned with `ModelDir[]` in
 * `androidApp/src/main/cpp/cubism/LAppDefine.cpp` and with [modelMotionConfigs] below** —
 * the native side only knows indices, and the stored `cubismId` is meaningless without
 * this mapping. `shizuku` ships in the old repo's assets but was never selectable; it is
 * not ported.
 */
object Live2dCharacters {
    val all = listOf(
        Live2dCharacter("hibiki", "Hibiki"),
        Live2dCharacter("haru", "Haru"),
        Live2dCharacter("hiyori_pro_en", "Hiyori")
    )

    /** Index into the native `ModelDir[]`, or -1 when [id] is not a known character. */
    fun indexOf(id: String?): Int {
        if (id == null) return -1
        return all.indexOfFirst { it.id == id }
    }
}

/**
 * Per-model motion configuration.
 * Each motion entry is (group, count) — count is the number of motions in that group.
 */
private data class MotionConfig(
    val speaking: List<Pair<String, Int>>,   // motions to loop while speaking
    val thinking: List<Pair<String, Int>>,   // motions to loop while thinking
    val listening: List<Pair<String, Int>>,  // motions to loop while listening
    val callStart: Pair<String, Int>?,       // motion to play when call starts
    val tap: Pair<String, Int>?,             // motion for tap interaction
    val speakingDelayMs: Long = 3000,
    val thinkingDelayMs: Long = 4000,
    val listeningDelayMs: Long = 5000
)

// Order matches Live2dCharacters.all and LAppDefine.cpp: hibiki=0
private val modelMotionConfigs = listOf(
    // hibiki: Idle(3), Flick(1), TapBody(1)
    MotionConfig(
        speaking = listOf("TapBody" to 1, "Flick" to 1),
        thinking = listOf("Flick" to 1, "Idle" to 3),
        listening = listOf("TapBody" to 1, "Idle" to 3),
        callStart = "Flick" to 0,
        tap = "TapBody" to 0,
        thinkingDelayMs = 3500,
        listeningDelayMs = 4000
    ),
    // haru: Idle(3), TapBody(6), Flick(3), FlickRight(3), Flick3(3), FlickLeft(3), Shake(2)
    MotionConfig(
        speaking = listOf("TapBody" to 6),
        thinking = listOf("Idle" to 3, "Flick" to 3),
        listening = listOf("Idle" to 3, "TapBody" to 6),
        callStart = "TapBody" to 0,
        tap = "TapBody" to 0,
        speakingDelayMs = 3000,
        thinkingDelayMs = 4000,
        listeningDelayMs = 5000
    ),
    // hiyori_pro_en: Idle(3), TapBody(3), Flick(1), FlickDown(1), FlickUp(1), Flick@Body(1)
    MotionConfig(
        speaking = listOf("TapBody" to 3),
        thinking = listOf("Idle" to 3),
        listening = listOf("Idle" to 3),
        callStart = "TapBody" to 0,
        tap = "TapBody" to 0,
        speakingDelayMs = 3000,
        thinkingDelayMs = 4000,
        listeningDelayMs = 5000
    ),
)

private fun getMotionConfig(modelIndex: Int): MotionConfig {
    val index = ((modelIndex % modelMotionConfigs.size) + modelMotionConfigs.size) % modelMotionConfigs.size
    return modelMotionConfigs[index]
}

/** Pick a motion from a list of (group, count) pairs */
private fun pickMotion(motions: List<Pair<String, Int>>, index: Int): Pair<String, Int> {
    if (motions.isEmpty()) return "Idle" to 0
    // Flatten all motions across groups and pick by index
    val all = motions.flatMap { (group, count) ->
        (0 until count).map { group to it }
    }
    if (all.isEmpty()) return "Idle" to 0
    return all[index % all.size]
}

/**
 * Renders the Live2D character for [modelIndex] (a `Live2dCharacters` index) on a
 * translucent GL surface layered over the Compose content.
 *
 * Unlike the old app there is no long-press model cycling: the character is chosen once
 * on the Profile screen (`MessageThread.cubismId`) and simply shown here. A change of
 * [modelIndex] while the view is alive (the picker preview) is applied on the GL thread —
 * the native manager is a process-wide singleton, and loading a model off the GL thread
 * while `OnUpdate` renders races it.
 */
@Composable
fun Live2DView(
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    modelIndex: Int,
    isRecording: Boolean = false,
    isSpeaking: Boolean = false,
    isThinking: Boolean = false,
    isListening: Boolean = false
) {
    var glSurfaceView by remember { mutableStateOf<GLSurfaceView?>(null) }
    var motionIndex by remember { mutableIntStateOf(0) }

    // Bumped from onSurfaceCreated. Every native call that would build or touch the
    // model manager must wait for it: a queued runnable can run on the GL thread
    // *before* onSurfaceCreated, and the manager's constructor allocates through the
    // framework allocator that CubismFramework::StartUp only registers there —
    // building it earlier is a native null-deref (seen on-device as a SIGSEGV in
    // `operator new(CubismAllocationTag)` when a model switch raced surface creation).
    // A counter rather than a boolean so a destroyed-and-recreated surface re-applies
    // the model too.
    var surfaceGeneration by remember { mutableIntStateOf(0) }

    // Hide/show GL surface to prevent flash
    LaunchedEffect(isActive) {
        glSurfaceView?.visibility = if (isActive) android.view.View.VISIBLE else android.view.View.INVISIBLE
    }

    // The surface is composited above the whole window (setZOrderOnTop) and does not
    // take part in navigation transitions, nor is it destroyed the moment composition
    // ends — `onDispose` below only runs when a pop transition *finishes*, and until
    // then the character kept floating over the incoming screen ("ghosting").
    // Navigation lowers the outgoing entry's lifecycle at the *start* of the pop, so
    // following it hides the surface (and destroys it via the relayout) one animation
    // early. ON_START restores it, e.g. after the app was backgrounded on this screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP ->
                    glSurfaceView?.visibility = android.view.View.GONE
                Lifecycle.Event.ON_START ->
                    glSurfaceView?.visibility = android.view.View.VISIBLE
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Seed the model the native manager starts with. Also applied through
    // [surfaceGeneration] below: the manager is a process-wide singleton, so a view
    // composed after another screen picked a different character must switch to
    // it explicitly, and `SwitchModel` no-ops when the index already matches.
    LaunchedEffect(Unit) {
        CubismBridge.nativeSetInitialModelIndex(modelIndex)
    }
    LaunchedEffect(modelIndex, surfaceGeneration) {
        if (surfaceGeneration > 0) {
            glSurfaceView?.queueEvent {
                CubismBridge.nativeSetModelIndex(modelIndex)
            }
        }
    }
    // A model swap invalidates the running motion loop's counters.
    LaunchedEffect(modelIndex) {
        motionIndex = 0
    }

    // Lip-sync animation during speaking
    LaunchedEffect(isSpeaking, surfaceGeneration) {
        if (surfaceGeneration == 0) return@LaunchedEffect
        if (isSpeaking) {
            try {
                while (true) {
                    val mouthOpenValue = ((System.currentTimeMillis() % 300) / 300f) * 0.8f
                    glSurfaceView?.queueEvent {
                        CubismBridge.nativeSetLipSyncValue(mouthOpenValue)
                    }
                    delay(50)
                }
            } finally {
                // The loop is cancelled — not completed — when isSpeaking flips, so a
                // reset placed after the loop would never run; the last random value
                // would stay in the native side and hold the mouth open after the
                // reply ended.
                glSurfaceView?.queueEvent {
                    CubismBridge.nativeSetLipSyncValue(0f)
                }
            }
        }
    }

    // State-driven motions using per-model config
    LaunchedEffect(isSpeaking, isThinking, isListening, isRecording, modelIndex, surfaceGeneration) {
        if (surfaceGeneration == 0) return@LaunchedEffect
        val config = getMotionConfig(modelIndex)
        val state = when {
            isSpeaking -> "speaking"
            isThinking -> "thinking"
            isListening -> "listening"
            isRecording -> "in_call"
            else -> "idle"
        }

        when (state) {
            "speaking" -> {
                while (true) {
                    motionIndex++
                    val (group, no) = pickMotion(config.speaking, motionIndex)
                    glSurfaceView?.queueEvent {
                        CubismBridge.nativeStartMotion(group, no, 3)
                    }
                    delay(config.speakingDelayMs)
                }
            }
            "thinking" -> {
                while (true) {
                    motionIndex++
                    val (group, no) = pickMotion(config.thinking, motionIndex)
                    glSurfaceView?.queueEvent {
                        CubismBridge.nativeStartMotion(group, no, 2)
                    }
                    delay(config.thinkingDelayMs)
                }
            }
            "listening" -> {
                // Initial reaction motion
                val (startGroup, startNo) = pickMotion(config.listening, 0)
                glSurfaceView?.queueEvent {
                    CubismBridge.nativeStartMotion(startGroup, startNo, 3)
                }
                delay(config.listeningDelayMs)
                // Then loop
                while (true) {
                    motionIndex++
                    val (group, no) = pickMotion(config.listening, motionIndex)
                    glSurfaceView?.queueEvent {
                        CubismBridge.nativeStartMotion(group, no, 1)
                    }
                    delay(config.listeningDelayMs)
                }
            }
            "in_call" -> {
                config.callStart?.let { (group, no) ->
                    glSurfaceView?.queueEvent {
                        CubismBridge.nativeStartMotion(group, no, 3)
                    }
                }
            }
            // "idle" — C++ idle loop handles it
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            glSurfaceView?.visibility = android.view.View.GONE
            glSurfaceView?.queueEvent {
                CubismBridge.nativeOnStop()
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            CubismBridge.setContext(ctx)

            GLSurfaceView(ctx).apply {
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
                setZOrderOnTop(true)
                setEGLContextClientVersion(2)
                setRenderer(object : GLSurfaceView.Renderer {
                    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                        CubismBridge.nativeOnSurfaceCreated()
                        surfaceGeneration++
                    }
                    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                        CubismBridge.nativeOnSurfaceChanged(width, height)
                    }
                    override fun onDrawFrame(gl: GL10?) {
                        CubismBridge.nativeOnDrawFrame()
                    }
                })
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

                setOnTouchListener { v, event ->
                    val pointX = event.x
                    val pointY = event.y
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            queueEvent { CubismBridge.nativeOnTouchesBegan(pointX, pointY) }
                        }
                        MotionEvent.ACTION_UP -> {
                            queueEvent { CubismBridge.nativeOnTouchesEnded(pointX, pointY) }
                            // Short tap: play the loaded model's tap motion. (The old
                            // long-press-to-cycle-model behaviour is gone — the
                            // character is chosen on the Profile screen.) The index is
                            // read on the GL thread so a mid-switch model is not
                            // tapped with a stale config.
                            queueEvent {
                                val tap = getMotionConfig(CubismBridge.nativeGetModelIndex()).tap
                                if (tap != null) {
                                    CubismBridge.nativeStartMotion(tap.first, 0, 3)
                                }
                            }
                        }
                        MotionEvent.ACTION_MOVE -> {
                            queueEvent { CubismBridge.nativeOnTouchesMoved(pointX, pointY) }
                        }
                    }
                    v.performClick()
                    true
                }

                glSurfaceView = this
            }
        }
    )
}
