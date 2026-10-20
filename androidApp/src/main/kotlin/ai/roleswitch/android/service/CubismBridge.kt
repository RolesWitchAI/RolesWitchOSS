package ai.roleswitch.android.service

import android.content.Context
import java.io.IOException

object CubismBridge {
    private var context: Context? = null

    fun setContext(ctx: Context) {
        context = ctx
    }

    init {
        System.loadLibrary("cubism-android")
    }

    /**
     * Called from JNI to load asset files.
     */
    @JvmStatic
    fun LoadFile(filePath: String): ByteArray? {
        return try {
            val stream = context?.assets?.open(filePath) ?: return null
            val size = stream.available()
            val buffer = ByteArray(size)
            stream.read(buffer, 0, size)
            stream.close()
            buffer
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
}
