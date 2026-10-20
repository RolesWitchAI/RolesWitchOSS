//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalTtsClientTest {

    private fun client(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
    ): ExternalTtsClient = ExternalTtsClient(MockEngine { request -> handler(request) })

    private fun MockRequestHandleScope.audioResponse(bytes: ByteArray) = respond(
        bytes,
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, "audio/aac")
    )

    @Test
    fun synthesizeReturnsTheAudioAndSendsTheDocumentedRequest() = runTest {
        val audio = byteArrayOf(1, 2, 3, 4)
        var body: String? = null
        var seenAuth: String? = null
        var seenPath: String? = null
        val tts = client { request ->
            body = (request.body as TextContent).text
            seenAuth = request.headers["Authorization"]
            seenPath = request.url.encodedPath
            audioResponse(audio)
        }
        val out = tts.synthesize(
            endpoint = "http://voice.test:9000/v1",
            token = "voice-token",
            input = "Hello there",
            voice = "alloy"
        )
        assertTrue(audio.contentEquals(out))

        val json = body!!
        assertTrue(json.contains("\"tts-1\""), json)
        assertTrue(json.contains("Hello there"), json)
        assertTrue(json.contains("\"alloy\""), json)
        assertTrue(json.contains("\"aac\""), json)
        assertEquals("Bearer voice-token", seenAuth)
        assertEquals("/v1/audio/speech", seenPath)
    }

    @Test
    fun aBlankVoiceIsLeftToTheServer() = runTest {
        var body: String? = null
        val tts = client { request ->
            body = (request.body as TextContent).text
            audioResponse(byteArrayOf(9))
        }
        tts.synthesize("http://voice.test:9000/v1", "", "text", voice = "  ")
        val json = body!!
        assertFalse(json.contains("voice"), json)
    }

    @Test
    fun aBlankEndpointIsNotConfigured() = runTest {
        val tts = client { _ -> respondError(HttpStatusCode.OK, "") }
        assertFailsWith<ExternalTtsNotConfiguredException> {
            tts.synthesize("   ", "tok", "text", voice = "alloy")
        }
    }

    @Test
    fun anUnparseableEndpointIsRejectedUpFront() = runTest {
        val tts = client { _ -> respondError(HttpStatusCode.OK, "") }
        assertFailsWith<IllegalStateException> {
            tts.synthesize("http://host:port/", "tok", "text", voice = null)
        }
    }

    @Test
    fun aServerErrorPropagates() = runTest {
        val tts = client { _ ->
            respondError(
                HttpStatusCode.InternalServerError,
                """{"error":{"message":"no such voice"}}"""
            )
        }
        val error = assertFailsWith<Exception> {
            tts.synthesize("http://voice.test:9000/v1", "tok", "text", voice = "nope")
        }
        assertFalse(error is ExternalTtsNotConfiguredException)
    }
}
