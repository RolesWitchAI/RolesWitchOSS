package ai.roleswitch.android.service

import android.content.Context
import java.io.File
import java.io.IOException

object CubismBridge {
    private var context: Context? = null

    /**
     * Root of the on-disk texture overrides written by the character customization
     * sheet. Files under it mirror the asset paths exactly
     * (`<id>/<id>.<res>/texture_NN.png`), and [LoadFile] prefers them over the
     * bundled assets — that is the whole persistence mechanism for customized
     * characters: the native side re-reads through `LoadFile` on every model load,
     * so a saved palette shows up on the call screen and after a restart with no
     * native changes.
     */
    private val overridesRoot: File?
        get() = context?.let { File(it.filesDir, "live2d_custom") }

    fun setContext(ctx: Context) {
        context = ctx
    }

    init {
        System.loadLibrary("cubism-android")
    }

    /**
     * Called from JNI to load model files. Customized textures (see [overridesRoot])
     * win over the bundled asset of the same name; everything else falls through to
     * `assets/` untouched.
     */
    @JvmStatic
    fun LoadFile(filePath: String): ByteArray? {
        val ctx = context ?: return null
        val override = File(overridesRoot, filePath)
        if (override.isFile) {
            try {
                return override.readBytes()
            } catch (_: IOException) {
                // Fall through to the asset rather than failing the model load.
            }
        }
        return try {
            ctx.assets.open(filePath).use { it.readBytes() }
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    // Lifecycle
    external fun nativeOnStart()
    external fun nativeOnStop()
    external fun nativeOnDestroy()

    // GL surface
    external fun nativeOnSurfaceCreated()
    external fun nativeOnSurfaceChanged(width: Int, height: Int)
    external fun nativeOnDrawFrame()

    // Touch
    external fun nativeOnTouchesBegan(x: Float, y: Float)
    external fun nativeOnTouchesEnded(x: Float, y: Float)
    external fun nativeOnTouchesMoved(x: Float, y: Float)

    // App integration
    external fun nativeStartMotion(group: String, no: Int, priority: Int)
    external fun nativeSetLipSyncValue(value: Float)  // 0.0 to 1.0 for mouth opening
    external fun nativeOnPause()
    external fun nativeOnResume()

    // Model switching
    external fun nativeSwitchModel()
    external fun nativeSetModelIndex(index: Int)
    external fun nativeGetModelIndex(): Int
    external fun nativeSetInitialModelIndex(index: Int)

    /**
     * Replaces the pixels of the current model's texture [textureIndex] (order in the
     * model's `model3.json` `Textures` array) with the given PNG bytes, in place.
     * Must be queued onto the GL thread (`GLSurfaceView.queueEvent`) — it touches GL
     * objects that only exist there.
     */
    external fun nativeUpdateTexture(png: ByteArray, textureIndex: Int)
}
