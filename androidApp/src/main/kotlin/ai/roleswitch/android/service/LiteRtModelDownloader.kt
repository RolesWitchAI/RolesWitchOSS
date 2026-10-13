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
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "LiteRtModelDownloader"
private const val BUFFER_SIZE = 64 * 1024

/** How often the progress state is refreshed. Byte-level updates would thrash the UI. */
private const val PROGRESS_INTERVAL_MS = 250L

/**
 * Streaming, **resumable** download of a [LitertModel] from HuggingFace, with SHA-256
 * verification. Drives the model screen through [state].
 *
 * This is [VoskModelDownloader]'s shape with the things a 2.6 GB transfer needs that a
 * 40 MB one does not:
 *
 *  - **Resume.** Bytes land in `<file>.part`; a restart sends `Range: bytes=<n>-` and
 *    appends. A user will background the app during a download this long, and starting
 *    over each time would make large models effectively undownloadable.
 *  - **`Accept-Encoding: identity`.** Without it a transcoding proxy may gzip the
 *    response, and the byte offsets a ranged request is built on stop meaning anything.
 *  - **Speed and ETA**, because a progress bar alone says nothing over ten minutes.
 *  - **SHA-256 over the finished file**, checked against the value pinned in
 *    [LitertModels] — the same guarantee [VoskModels]'s CRC32 gives, and the reason a
 *    corrupt download surfaces here instead of as a native crash at load time.
 *
 * On any failure or cancellation the partial file is left in place so the next attempt
 * resumes; only a *verification* failure deletes it, since those bytes are known bad.
 */
class LiteRtModelDownloader(private val context: Context) {

    sealed interface State {
        /** [total] is the full file size, including bytes carried over from a previous run. */
        data class Downloading(
            val downloaded: Long,
            val total: Long,
            val bytesPerSecond: Long,
            val remainingMs: Long
        ) : State

        data object Verifying : State
        data object Done : State
        /**
         * @param corrupted the bytes on disk are known bad and have been deleted.
         * @param needsToken the repository refused access (401/403). The only reliable
         *   signal that a model is gated: HuggingFace can gate or un-gate a repository at
         *   any time, so asking the server beats trusting a flag compiled into the app.
         */
        data class Failed(
            val message: String,
            val corrupted: Boolean,
            val needsToken: Boolean = false
        ) : State
    }

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    /** The model currently being downloaded, so the UI can attribute [state] to a row. */
    private val _activeModelId = MutableStateFlow<String?>(null)
    val activeModelId: StateFlow<String?> = _activeModelId.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /**
     * Bumped whenever the active download is superseded or cancelled, so a coroutine that
     * is on its way out cannot publish state belonging to a newer one.
     *
     * Cancellation is a *request*: [cancel] returns while the old coroutine is still
     * between two `ensureActive()` checks, and its `catch (CancellationException)` arm
     * then runs — after a subsequent [start] has already published the new download's
     * state. Without this guard that arm reset `_state`/`_activeModelId` to null, and the
     * download that had just started showed no progress and no way back.
     */
    private val generation = AtomicInteger(0)

    /** True while a transfer is in flight. Drives the model list's disabled buttons. */
    val isDownloading: Boolean get() = job?.isActive == true

    /**
     * @param accessToken HuggingFace token, sent as a bearer credential. Required for
     *   [LitertModel.gated] repositories and harmless (ignored) for the rest.
     */
    /**
     * Starts (or resumes) a download. Returns false — doing nothing — when one is already
     * running: two transfers would share a single `.part` file and two `FileOutputStream`s
     * appending to it produce a file that can only fail verification. The model list
     * disables the other rows' buttons for the same reason; this is the backstop.
     */
    fun start(model: LitertModel, accessToken: String = "", onSuccess: () -> Unit): Boolean {
        if (isDownloading) return false
        // Already cancelled by `cancel()`, but not necessarily finished — joined below so
        // the outgoing coroutine's file handle is closed before this one opens its own.
        val previous = job
        val gen = generation.incrementAndGet()
        _activeModelId.value = model.id
        _state.value = State.Downloading(
            downloaded = LitertModelStore.partFileFor(context, model).length(),
            total = model.sizeBytes,
            bytesPerSecond = 0L,
            remainingMs = 0L
        )
        job = scope.launch {
            previous?.join()
            try {
                downloadAndVerify(model, accessToken)
                if (generation.get() == gen) {
                    _state.value = State.Done
                    _activeModelId.value = null
                }
                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: CancellationException) {
                // Partial bytes are kept on purpose: cancelling is "pause", and the next
                // start resumes. Deleting the model is a separate, explicit action.
                // State is cleared by `cancel()` itself, synchronously — see [generation].
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for ${model.id}: ${e.message}")
                val corrupted = e is ChecksumMismatchException
                if (corrupted) LitertModelStore.partFileFor(context, model).delete()
                if (generation.get() == gen) {
                    _state.value = State.Failed(
                        message = e.message ?: "Unknown error",
                        corrupted = corrupted,
                        needsToken = e is GatedModelException
                    )
                }
            }
        }
        return true
    }

    /**
     * Cancels the running download and clears the state here and now, rather than leaving
     * it to the coroutine's cancellation handler — that arm can run arbitrarily late, and
     * by then the state may belong to a download the user has since started.
     *
     * [job] deliberately keeps its reference: the next [start] joins it so the outgoing
     * transfer's file handle is closed before a new one opens the same `.part` file.
     */
    fun cancel() {
        generation.incrementAndGet()
        job?.cancel()
        _state.value = null
        _activeModelId.value = null
    }

    /** Returns to idle so the screen can be reopened cleanly after a terminal state. */
    fun reset() = cancel()

    private suspend fun downloadAndVerify(model: LitertModel, accessToken: String) = withContext(Dispatchers.IO) {
        val missing = LitertModelStore.missingSpaceBytes(context, model)
        if (missing > 0) {
            throw IOException("Not enough free space — about ${missing / (1024 * 1024)} MB more is needed.")
        }

        val dir = LitertModelStore.dirFor(context, model)
        dir.mkdirs()
        val partFile = LitertModelStore.partFileFor(context, model)
        val destFile = LitertModelStore.fileFor(context, model)

        if (partFile.length() < model.sizeBytes) {
            downloadTo(model, partFile, accessToken)
        }

        currentCoroutineContext().ensureActive()
        if (partFile.length() != model.sizeBytes) {
            throw ChecksumMismatchException(
                "Downloaded ${partFile.length()} bytes, expected ${model.sizeBytes}."
            )
        }

        // Gated repositories hide their LFS pointer, so there is no hash to pin for
        // those and the size check above is the whole verification. Everything else is
        // hashed — see LitertModel.sha256.
        model.sha256?.let { expected ->
            _state.value = State.Verifying
            val actual = sha256(partFile)
            if (!actual.equals(expected, ignoreCase = true)) {
                throw ChecksumMismatchException("Checksum mismatch: expected $expected, got $actual.")
            }
        }

        if (destFile.exists()) destFile.delete()
        if (!partFile.renameTo(destFile)) {
            throw IOException("Could not finalize the downloaded model file.")
        }
    }

    private suspend fun downloadTo(model: LitertModel, dest: File, accessToken: String) {
        val alreadyHave = dest.length()
        val connection = (URL(model.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            // Generous compared with VoskModelDownloader's 5 s: this is a long-haul
            // transfer on mobile data, and the cancellation check below no longer
            // depends on the socket returning quickly — the read loop polls it.
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            if (accessToken.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $accessToken")
            }
            // Ranged requests are meaningless against a re-encoded body.
            setRequestProperty("Accept-Encoding", "identity")
            if (alreadyHave > 0) setRequestProperty("Range", "bytes=$alreadyHave-")
            connect()
        }
        try {
            currentCoroutineContext().ensureActive()
            val code = connection.responseCode
            if (code !in 200..299) {
                // 401/403 on a gated repo is not a transport failure, it is a missing or
                // insufficient token — say so, because "HTTP 403" sends the user nowhere.
                if (code == HttpURLConnection.HTTP_UNAUTHORIZED ||
                    code == HttpURLConnection.HTTP_FORBIDDEN
                ) {
                    throw GatedModelException(
                        if (accessToken.isBlank()) {
                            "This model requires a HuggingFace token. Add one in Settings."
                        } else {
                            "Your HuggingFace account does not have access to ${model.repoId}. " +
                                "Accept the model's terms on huggingface.co, then try again."
                        }
                    )
                }
                throw IOException("HTTP $code downloading ${model.displayName}")
            }
            // 200 to a ranged request means the server ignored it and is sending the
            // whole file, so the existing bytes have to be thrown away rather than
            // appended to — otherwise the file silently ends up with a duplicated head.
            val resuming = alreadyHave > 0 && code == HttpURLConnection.HTTP_PARTIAL
            var downloaded = if (resuming) alreadyHave else 0L

            connection.inputStream.use { input ->
                FileOutputStream(dest, resuming).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    val startedAt = System.currentTimeMillis()
                    val startedFrom = downloaded
                    var lastPublish = 0L
                    while (true) {
                        // Check the *current* job, not the scope's SupervisorJob, so a
                        // cancel takes effect immediately instead of after the transfer.
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        if (now - lastPublish >= PROGRESS_INTERVAL_MS) {
                            lastPublish = now
                            val elapsed = (now - startedAt).coerceAtLeast(1L)
                            val speed = (downloaded - startedFrom) * 1000 / elapsed
                            _state.value = State.Downloading(
                                downloaded = downloaded,
                                total = model.sizeBytes,
                                bytesPerSecond = speed,
                                remainingMs = if (speed > 0) {
                                    (model.sizeBytes - downloaded) * 1000 / speed
                                } else {
                                    0L
                                }
                            )
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class ChecksumMismatchException(message: String) : IOException(message)

    /** Access was refused: no token, a bad one, or terms not accepted for the repo. */
    class GatedModelException(message: String) : IOException(message)
}
