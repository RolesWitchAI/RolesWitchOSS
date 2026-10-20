//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HuggingFaceModelResolverTest {

    companion object {
        /**
         * The resolver's HTTP client is a process-wide lazy created from
         * [HuggingFaceModelResolver.testEngine] exactly once per test run; every test
         * therefore drives the one engine created first, and swaps this shared handler
         * to serve its own fixtures.
         */
        private var handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { _ -> respondJson("{}") }

        private val sharedEngine = MockEngine { request -> handler(request) }
    }

    private fun serve(body: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
        handler = body
    }

    @BeforeTest
    fun setUp() {
        HuggingFaceModelResolver.testEngine = sharedEngine
        serve { _ -> respondJson("{}") }
    }

    @AfterTest
    fun tearDown() {
        HuggingFaceModelResolver.testEngine = null
    }

    private fun apiFixture(
        sha: String? = "commit123",
        siblings: String = """[{"rfilename":"readme.md"},{"rfilename":"model.litertlm"}]"""
    ): String {
        val shaPart = sha?.let { """"sha":"$it",""" } ?: ""
        return """{$shaPart"siblings":$siblings}"""
    }

    private val resolveUrl = "https://huggingface.co/api/models/owner/repo"
    private val headUrl = "https://huggingface.co/owner/repo/resolve/commit123/model.litertlm"

    @Test
    fun resolvePinsTheCommitAndReadsTheSizeFromTheLfsPointer() = runTest {
        var headedUrl: String? = null
        var seenAuth: String? = null
        serve { request ->
            when (request.method.value) {
                "GET" -> {
                    seenAuth = request.headers["Authorization"]
                    respondJson(apiFixture())
                }
                "HEAD" -> {
                    headedUrl = request.url.toString()
                    respondJson(
                        "",
                        HttpStatusCode.OK,
                        extraHeaders = headersOf("x-linked-size", "1234567890")
                    )
                }
                else -> respondErrorJson(HttpStatusCode.BadRequest, "")
            }
        }
        val model = HuggingFaceModelResolver.resolve("https://huggingface.co/owner/repo/blob/main/model.litertlm")

        assertEquals("custom:owner/repo", model.id)
        assertEquals("repo", model.displayName)
        assertEquals("owner/repo", model.repoId)
        assertEquals("model.litertlm", model.fileName)
        assertEquals("commit123", model.commitHash)
        assertEquals(1_234_567_890L, model.sizeBytes)
        assertNull(model.sha256)
        assertEquals(false, model.supportsImage)
        assertEquals(true, model.supportsThinking)
        assertEquals(true, model.isCustom)
        assertTrue(headedUrl!!.startsWith("https://huggingface.co/owner/repo/resolve/commit123/"), headedUrl)
        assertNull(seenAuth)
        assertEquals(
            "https://huggingface.co/owner/repo/resolve/commit123/model.litertlm?download=true",
            model.downloadUrl
        )
    }

    @Test
    fun aTokenIsSentAsABearerHeader() = runTest {
        var seenAuth: String? = null
        serve { request ->
            if (request.method.value == "GET") {
                seenAuth = request.headers["Authorization"]
                respondJson(apiFixture())
            } else {
                respondJson("", HttpStatusCode.OK, extraHeaders = headersOf("x-linked-size", "1"))
            }
        }
        HuggingFaceModelResolver.resolve("owner/repo", accessToken = "hf_tok")
        assertEquals("Bearer hf_tok", seenAuth)
    }

    @Test
    fun theLargestLitertlmCheckpointWinsWhenARepoShipsSeveral() = runTest {
        serve { request ->
            if (request.method.value == "GET") {
                respondJson(
                    apiFixture(
                        siblings = """[{"rfilename":"a.litertlm"},{"rfilename":"longer-name-model.litertlm"},{"rfilename":"notes.txt"}]"""
                    )
                )
            } else {
                respondJson("", HttpStatusCode.OK, extraHeaders = headersOf("x-linked-size", "5"))
            }
        }
        val model = HuggingFaceModelResolver.resolve("owner/repo")
        assertEquals("longer-name-model.litertlm", model.fileName)
    }

    @Test
    fun aRepositoryWithoutACommitToPinIsRejected() = runTest {
        serve { request ->
            if (request.method.value == "GET") respondJson(apiFixture(sha = null))
            else respondJson("", HttpStatusCode.OK)
        }
        val error = awaitResolveError("owner/repo")
        assertTrue(error.message!!.contains("did not report a commit"), error.message)
    }

    @Test
    fun aRepositoryWithoutALitertlmFileIsRejected() = runTest {
        serve { request ->
            if (request.method.value == "GET") {
                respondJson(apiFixture(siblings = """[{"rfilename":"model.gguf"},{"rfilename":"readme.md"}]"""))
            } else respondJson("", HttpStatusCode.OK)
        }
        val error = awaitResolveError("owner/repo")
        assertTrue(error.message!!.contains("only LiteRT models"), error.message)
    }

    @Test
    fun aModelWhoseSizeCannotBeReadIsRejected() = runTest {
        serve { request ->
            if (request.method.value == "GET") respondJson(apiFixture())
            // Neither x-linked-size nor content-length.
            else respondJson("", HttpStatusCode.OK)
        }
        val error = awaitResolveError("owner/repo")
        assertTrue(error.message!!.contains("size"), error.message)
    }

    @Test
    fun aMissingRepositoryIsRejectedWithActionableText() = runTest {
        serve { _ -> respondErrorJson(HttpStatusCode.NotFound, """{"error":"Repository not found"}""") }
        val error = awaitResolveError("owner/gone")
        assertTrue(error.message!!.contains("No such model"), error.message)
    }

    @Test
    fun aGatedRepositoryAsksForAToken() = runTest {
        serve { _ -> respondErrorJson(HttpStatusCode.Unauthorized, """{"error":"gated repo"}""") }
        val error = awaitResolveError("owner/gated")
        assertTrue(error.message!!.contains("gated"), error.message)
        assertTrue(error.message!!.contains("token"), error.message)
    }

    @Test
    fun aNonHuggingFaceLinkFailsAtTheRepositoryLookup() = runTest {
        var requests = 0
        serve { _ ->
            requests++
            respondErrorJson(HttpStatusCode.NotFound, """{"error":"Repository not found"}""")
        }
        // The resolver does not police the host: an off-domain link becomes a repo id
        // and fails at the API call, with an error the user can act on.
        val error = awaitResolveError("https://example.com/owner/name")
        assertTrue(error.message!!.contains("No such model"), error.message)
        assertEquals(1, requests)
    }

    @Test
    fun theSizeFallsBackToContentLengthWithoutTheLfsHeader() = runTest {
        serve { request ->
            if (request.method.value == "GET") respondJson(apiFixture())
            else respondJson(
                "",
                HttpStatusCode.OK,
                extraHeaders = headersOf(io.ktor.http.HttpHeaders.ContentLength, "777")
            )
        }
        val model = HuggingFaceModelResolver.resolve("owner/repo")
        assertEquals(777L, model.sizeBytes)
    }

    private suspend fun awaitResolveError(url: String): HuggingFaceModelResolver.ResolveException =
        kotlin.test.assertFailsWith<HuggingFaceModelResolver.ResolveException> {
            HuggingFaceModelResolver.resolve(url)
        }

    // ---- repoIdFrom: every URL shape a user might paste ------------------------------

    @Test
    fun repositoryPagesResolveToOwnerSlashName() {
        assertEquals("owner/name", HuggingFaceModelResolver.repoIdFrom("https://huggingface.co/owner/name"))
        assertEquals("owner/name", HuggingFaceModelResolver.repoIdFrom("huggingface.co/owner/name"))
        assertEquals("owner/name", HuggingFaceModelResolver.repoIdFrom("owner/name"))
        assertEquals("owner/name", HuggingFaceModelResolver.repoIdFrom("https://huggingface.co/owner/name/"))
    }

    @Test
    fun treeBlobAndResolveLinksKeepOnlyTheFirstTwoSegments() {
        assertEquals("owner/name", HuggingFaceModelResolver.repoIdFrom("https://huggingface.co/owner/name/tree/main"))
        assertEquals("owner/name", HuggingFaceModelResolver.repoIdFrom("https://huggingface.co/owner/name/blob/main/model.litertlm"))
        assertEquals("owner/name", HuggingFaceModelResolver.repoIdFrom("https://huggingface.co/owner/name/resolve/main/model.litertlm?download=true"))
        assertEquals("owner/name", HuggingFaceModelResolver.repoIdFrom("https://huggingface.co/owner/name#section"))
    }

    @Test
    fun nonRepositoryShapesAreRejected() {
        assertNull(HuggingFaceModelResolver.repoIdFrom(""))
        assertNull(HuggingFaceModelResolver.repoIdFrom("   "))
        assertNull(HuggingFaceModelResolver.repoIdFrom("owner"))
        assertNull(HuggingFaceModelResolver.repoIdFrom("https://huggingface.co/owner"))
        assertNull(HuggingFaceModelResolver.repoIdFrom("https://huggingface.co/datasets/owner/name"))
        assertNull(HuggingFaceModelResolver.repoIdFrom("https://huggingface.co/spaces/owner/name"))
        assertNull(HuggingFaceModelResolver.repoIdFrom("https://huggingface.co/collections/owner/slug"))
        // A non-HuggingFace domain with two path segments is *accepted* here — the
        // resolver does not police the host, and the API call that follows fails with
        // a 404 the user can act on.
    }

    // ---- estimateRequiredMemoryGb ------------------------------------------------------

    @Test
    fun memoryEstimatesRoundUpToEvenGigabytesAndStayInsideTheFloorAndCeiling() {
        // ceil(weights*2) rounded up to an even number, clamped to [4, 24].
        assertEquals(4, HuggingFaceModelResolver.estimateRequiredMemoryGb(1_000_000_000L))
        assertEquals(6, HuggingFaceModelResolver.estimateRequiredMemoryGb(2_500_000_000L))
        assertEquals(8, HuggingFaceModelResolver.estimateRequiredMemoryGb(3_660_000_000L))
        assertEquals(10, HuggingFaceModelResolver.estimateRequiredMemoryGb(5_000_000_000L))
        assertEquals(24, HuggingFaceModelResolver.estimateRequiredMemoryGb(12_500_000_000L))
        assertEquals(4, HuggingFaceModelResolver.estimateRequiredMemoryGb(100_000_000L))
    }
}
