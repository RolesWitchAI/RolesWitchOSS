//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

private const val TAG = "VoskModelDownloader"
private const val BUFFER_SIZE = 8 * 1024

/**
 * Streaming download + CRC32 verification + zip extraction for a [VoskModel]
 * that is not bundled in the APK. Drives the Settings download sheet via
 * [state].
 *
 * On any failure (network error, CRC mismatch, zip extraction error) or
 * cancellation, both the partial zip in [cacheDir] and any half-extracted
 * directory in [filesDir] are deleted so the next attempt starts clean — per
 * the project's "delete everything on failure" contract.
 *
 * HttpURLConnection is used directly (no extra dependency); the existing Ktor
 * client in `:sharedLogic` is wired to the OpenAI API and not used here.
 */
class VoskModelDownloader(private val context: Context) {

    sealed interface State {
        data class Downloading(val downloaded: Long, val total: Long) : State
        data object Verifying : State
        data object Extracting : State
        data object Done : State
        data class Failed(val message: String, val crcMismatch: Boolean) : State
    }

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start(model: VoskModel, onSuccess: () -> Unit) {
        cancel()
        _state.value = State.Downloading(downloaded = 0L, total = model.sizeBytes)
        job = scope.launch {
            try {
                downloadVerifyExtract(model)
                _state.value = State.Done
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: CancellationException) {
                cleanup(model)
                _state.value = null
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download/extract failed for ${model.dirName}: ${e.message}")
                cleanup(model)
                _state.value = State.Failed(
                    message = e.message ?: "Unknown error",
                    crcMismatch = e is CrcMismatchException
                )
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    /** Reset terminal state back to idle so the sheet can be reopened cleanly. */
    fun reset() {
        cancel()
        _state.value = null
    }

    private suspend fun downloadVerifyExtract(model: VoskModel) =
        withContext(Dispatchers.IO) {
            val zipFile = File(context.cacheDir, "${model.dirName}.zip")
            val destDir = File(context.filesDir, model.dirName)

            // 1. Download with byte-level progress (cancellation-aware).
            downloadTo(model, zipFile)

            // 2. Verify CRC32 over the downloaded zip bytes.
            currentCoroutineContext().ensureActive()
            _state.value = State.Verifying
            val actual = computeCrc32(zipFile)
            if (actual != model.crc32) {
                throw CrcMismatchException(
                    expected = model.crc32,
                    actual = actual
                )
            }

            // 3. Extract into filesDir/. Vosk zips carry a single top-level
            //    directory whose name matches [VoskModel.dirName], so we
            //    preserve paths and assert the layout afterward.
            currentCoroutineContext().ensureActive()
            _state.value = State.Extracting
            if (destDir.exists()) destDir.deleteRecursively()
            extractZip(zipFile, context.filesDir)
            if (!destDir.isDirectory) {
                throw IOException("Zip layout unexpected: $destDir missing after extract")
            }

            // 4. Drop the zip — only the extracted tree is needed.
            zipFile.delete()
        }

    private suspend fun downloadTo(model: VoskModel, dest: File) {
        // readTimeout is intentionally short so the cancellation check below
        // responds within a few seconds even if the socket stalls — coroutine
        // cancellation does not interrupt a thread blocked in read().
        val connection = (URL(model.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 5_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            connect()
        }
        try {
            // Bail out quickly if the job was already cancelled before connect.
            currentCoroutineContext().ensureActive()
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code downloading ${model.dirName}")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: model.sizeBytes
            dest.parentFile?.mkdirs()
            var downloaded = 0L
            connection.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        // Check the *current* coroutine's job, not the scope's
                        // SupervisorJob (which is never cancelled). Throws
                        // CancellationException immediately when the user taps
                        // Cancel — prevents concurrent writes to the same zip
                        // if the user reopens the sheet before the stale
                        // download finishes.
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        _state.value = State.Downloading(downloaded = downloaded, total = total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun computeCrc32(file: File): Long {
        val crc = CRC32()
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }

    private fun extractZip(zipFile: File, destDir: File) {
        ZipInputStream(FileInputStream(zipFile).buffered()).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                // Guard against zip-slip: the resolved path must remain under destDir.
                val resolved = outFile.canonicalPath
                if (!resolved.startsWith(destDir.canonicalPath + File.separator) &&
                    resolved != destDir.canonicalPath) {
                    throw IOException("Zip-slip detected for entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = zis.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun cleanup(model: VoskModel) {
        File(context.cacheDir, "${model.dirName}.zip").delete()
        File(context.filesDir, model.dirName).deleteRecursively()
    }

    private class CrcMismatchException(val expected: Long, val actual: Long) :
        IOException("CRC32 mismatch: expected=$expected actual=$actual")
}
