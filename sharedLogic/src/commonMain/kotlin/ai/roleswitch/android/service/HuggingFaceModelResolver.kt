//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns a HuggingFace repository URL into a [LitertModel] the app can download.
 *
 * Lives in `commonMain` because both platforms offer "add by URL" from their LiteRT model
 * pickers, and the resolution rules — which URL shapes are accepted, which checkpoint is
 * picked when a repository ships several, what gets pinned — must not drift between them.
 *
 * Only `.litertlm` checkpoints are accepted. That is not a policy choice but a hard
 * limit: the LiteRT services hand the file straight to the native runtime, and anything
 * else — a GGUF, a `.task`, a safetensors directory — fails inside `Engine.initialize()`
 * with an error the user cannot act on. Refusing here, by name, is the only place the
 * reason can still be explained.
 *
 * Everything is resolved against the repository's *current* commit and then pinned, so a
 * later re-upload cannot silently change what a model in the list points at — the same
 * guarantee [LitertModels] gets from its hardcoded `commitHash`.
 */
object HuggingFaceModelResolver {

    private const val API_BASE = "https://huggingface.co/api/models/"
    private const val LITERT_EXTENSION = ".litertlm"
    private const val TIMEOUT_MS = 30_000L

    /** A resolution that failed for a reason worth showing the user verbatim. */
    class ResolveException(message: String) : Exception(message)

    /**
     * One client for the resolver. Ktor is main-safe, so unlike the Android-only
     * predecessor there is no `Dispatchers.IO` hop: the two requests per resolve are
     * small, and the caller is a UI dialog that is already suspended awaiting the result.
     */
    private val http = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = TIMEOUT_MS
            requestTimeoutMillis = TIMEOUT_MS
        }
    }

    /**
     * @param url anything identifying a repository — `huggingface.co/owner/name`, a
     *   `/tree/` or `/blob/` link, or a direct `/resolve/` file link.
     * @param accessToken optional; required for gated repositories, ignored otherwise.
     *
     * `@Throws` is load-bearing on iOS: an exception that is not declared here does not
     * reach Swift as an `NSError`, it terminates the process (see
     * `McpService.discoverToolNames`, which carries the same declaration for the same
     * reason).
     */
    @Throws(Exception::class)
    suspend fun resolve(url: String, accessToken: String = ""): LitertModel {
        val repoId = repoIdFrom(url)
            ?: throw ResolveException("That is not a HuggingFace model link.")

        val info = fetchJson("$API_BASE$repoId", accessToken)
        val commitHash = info["sha"]?.jsonPrimitive?.content
            ?: throw ResolveException("The repository did not report a commit to pin.")

        val files = info["siblings"]?.jsonArray
            ?.mapNotNull { it.jsonObject["rfilename"]?.jsonPrimitive?.content }
            .orEmpty()

        // A repo may ship several quantisations; the largest is the least lossy, and
        // picking it beats picking whichever the file listing happened to return first.
        val fileName = files.filter { it.endsWith(LITERT_EXTENSION, ignoreCase = true) }
            .maxByOrNull { it.length }
            ?: throw ResolveException(
                "No $LITERT_EXTENSION file in that repository — only LiteRT models can run on device."
            )

        val downloadUrl = "https://huggingface.co/$repoId/resolve/$commitHash/$fileName"
        val sizeBytes = fetchSize(downloadUrl, accessToken)
        if (sizeBytes <= 0) {
            throw ResolveException("Could not read the model's size from the repository.")
        }

        return LitertModel(
            id = CUSTOM_ID_PREFIX + repoId,
            displayName = repoId.substringAfterLast('/'),
            repoId = repoId,
            fileName = fileName,
            commitHash = commitHash,
            sizeBytes = sizeBytes,
            // No pinned digest for a model the app has never seen: a hash fetched from
            // the same repository being checked would verify nothing. The size check in
            // the downloader is the whole verification here.
            sha256 = null,
            // The checkpoint does not publish what it needs, so this is derived from
            // the download: weights plus working memory. Advisory only — the list warns,
            // it does not block.
            minDeviceMemoryGb = estimateRequiredMemoryGb(sizeBytes),
            // Assumed text-only. Offering images for a checkpoint with no vision tower
            // produces a load failure rather than a refusal, so the safe default is the
            // one that cannot break; `visionActive` reports what actually loaded.
            supportsImage = false,
            // Assumed *present*, unlike vision, because the two cost different things
            // to get wrong. `visionBackend` is part of EngineConfig, so guessing wrong
            // there fails the load outright. `enable_thinking` is one key in the
            // per-turn extra-context map, which a checkpoint without a thinking mode
            // simply ignores — so the worst case is an inert switch, against the
            // certainty of hiding the feature on a derivative that does support it.
            // The user who went and fetched a specific checkpoint knows what it is
            // better than a default does; the toggle still starts off.
            supportsThinking = true,
            gated = false,
            licenseName = repoId,
            licenseUrl = "https://huggingface.co/$repoId",
            defaultTopK = 64,
            defaultTopP = 0.95f,
            defaultTemperature = 1.0f,
            defaultMaxTokens = 4096,
            maxContextLength = null,
            defaultAccelerator = "cpu",
            visionAccelerator = "gpu"
        )
    }

    /**
     * A rough "how much RAM does this need" for a model the app has never seen.
     *
     * Rounded **up to an even number of gigabytes**, because that is how phones are
     * actually built: 4, 6, 8, 12, 16. An estimate of 7 GB names a configuration that
     * does not ship, so it reads as a measurement rather than the guess it is, and no
     * device can ever match it exactly.
     *
     * A guess is all it can be. The built-in catalogue's figures come from Google's
     * allowlist, curated per model, and they are not a function of file size — Gemma 4
     * E4B and Gemma 3n E2B are both 3.66 GB but want 12 GB and 8 GB respectively. What
     * a checkpoint needs depends on its context window, KV cache and vision tower, none
     * of which the repository publishes.
     */
    internal fun estimateRequiredMemoryGb(sizeBytes: Long): Int {
        val weightsGb = sizeBytes / 1_000_000_000.0
        val estimate = kotlin.math.ceil(weightsGb * 2).toInt()
        val rounded = estimate + (estimate % 2)
        return rounded.coerceIn(4, 24)
    }

    /**
     * Extracts `owner/name` from any of the URL shapes a user is likely to paste — the
     * repository page, a file inside it, or the bare id typed by hand.
     */
    internal fun repoIdFrom(raw: String): String? {
        val trimmed = raw.trim().removeSuffix("/")
        if (trimmed.isEmpty()) return null
        val path = trimmed
            .substringAfter("huggingface.co/", trimmed)
            .substringBefore('?')
            .substringBefore('#')
        val segments = path.split('/').filter { it.isNotBlank() }
        // Repos live at the root; `/tree/`, `/blob/` and `/resolve/` all come after the id.
        if (segments.size < 2) return null
        val owner = segments[0]
        val name = segments[1]
        if (owner in setOf("datasets", "spaces", "collections")) return null
        return "$owner/$name"
    }

    private suspend fun fetchJson(url: String, accessToken: String) =
        Json.parseToJsonElement(get(url, accessToken)).jsonObject

    private suspend fun get(url: String, accessToken: String): String {
        val response = http.get(url) { authorize(accessToken) }
        checkResponse(response)
        return response.bodyAsText()
    }

    /**
     * The file's size without downloading it.
     *
     * `x-linked-size` first: HuggingFace serves LFS pointers through a redirect, and
     * `Content-Length` on the final response describes whatever the CDN sent, which for a
     * pointer is a few hundred bytes rather than the gigabytes the file actually is.
     */
    private suspend fun fetchSize(url: String, accessToken: String): Long {
        val response = http.head(url) { authorize(accessToken) }
        checkResponse(response)
        response.headers["x-linked-size"]?.toLongOrNull()?.let { return it }
        return response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: -1L
    }

    private fun HttpRequestBuilder.authorize(accessToken: String) {
        if (accessToken.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $accessToken")
    }

    private fun checkResponse(response: HttpResponse) {
        when (val code = response.status.value) {
            in 200..299 -> Unit
            404 -> throw ResolveException("No such model on HuggingFace — check the link.")
            401, 403 -> throw ResolveException(
                "That repository is gated. Add a HuggingFace token in Settings and accept " +
                    "its terms on huggingface.co, then try again."
            )
            else -> throw ResolveException("HuggingFace returned HTTP $code.")
        }
    }
}
