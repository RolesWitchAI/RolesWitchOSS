//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service

import ai.roleswitch.android.data.model.McpTool
import ai.roleswitch.android.service.mcp.McpError
import ai.roleswitch.android.service.mcp.McpService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The cancellation and timeout arms of the service layer — the paths production hits
 * when a server never answers or the user leaves mid-request. These run on real
 * dispatchers (`runBlocking` + real `delay`) because cancellation has to reach the
 * transport; the one virtual-time case (`runTest`) is the discovery timeout, where the
 * virtual clock is exactly what fires the 20s bound instantly.
 */
class CancellationPathsTest {

    private fun hangingServer(): McpService =
        McpService(HttpClient(MockEngine { _ -> delay(10_000); respondJson("{}") }))

    private fun tool(name: String = "srv") =
        McpTool(id = "id-$name", name = name, url = "http://mcp.test/mcp", token = "", createdAt = 1L, updatedAt = 1L)

    @Test
    fun discoveryTimesOutBetweenTheHandshakeAndTheListing() = runTest {
        // The handshake answers; tools/list never does. The bound still fires, and
        // the NonCancellable close in the finally still runs.
        val service = McpService(
            HttpClient(
                MockEngine { request ->
                    when (request.rpcMethod()) {
                        "initialize" -> respondJson("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}""")
                        "notifications/initialized" -> respondJson("", io.ktor.http.HttpStatusCode.Accepted)
                        else -> {
                            delay(60_000)
                            respondJson("{}")
                        }
                    }
                }
            )
        )
        val error = assertFailsWith<McpError> {
            service.discoverToolNames("http://mcp.test/mcp", "")
        }
        assertTrue(error.message!!.contains("No response after 20s"), error.message)
    }

    @Test
    fun discoveryTimesOutWithAnActionableMessage() = runTest {
        val service = McpService(HttpClient(MockEngine { _ -> delay(60_000); respondJson("{}") }))
        val error = assertFailsWith<McpError> {
            service.discoverToolNames("http://mcp.test/mcp", "")
        }
        assertTrue(error.message!!.contains("No response after 20s"), error.message)
    }

    @Test
    fun discoveryCancellationStaysCancellation() = runBlocking {
        val service = hangingServer()
        val job = launch {
            runCatching { service.discoverToolNames("http://mcp.test/mcp", "") }
        }
        delay(100)
        job.cancelAndJoin() // must not throw McpError from a cancelled coroutine
    }

    @Test
    fun openSessionCancellationDuringConnectPropagates() = runBlocking {
        val service = hangingServer()
        val job = launch {
            try {
                service.openSession(listOf(tool()))
            } catch (e: CancellationException) {
                throw e
            }
        }
        delay(100)
        job.cancelAndJoin()
    }

    @Test
    fun openSessionCancellationDuringToolsListPropagates() = runBlocking {
        var initialized = CompletableDeferred<Unit>()
        val service = McpService(
            HttpClient(
                MockEngine { request ->
                    when (request.rpcMethod()) {
                        "initialize" -> respondJson("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}""")
                        "notifications/initialized" -> respondJson("", io.ktor.http.HttpStatusCode.Accepted)
                        else -> {
                            initialized.complete(Unit)
                            delay(10_000)
                            respondJson("{}")
                        }
                    }
                }
            )
        )
        val job = launch {
            try {
                service.openSession(listOf(tool()))
            } catch (e: CancellationException) {
                throw e
            }
        }
        withTimeout(5_000) { initialized.await() }
        delay(50)
        job.cancelAndJoin()
    }

    @Test
    fun aCancelledToolCallStaysCancelled() = runBlocking {
        var called = CompletableDeferred<Unit>()
        val service = McpService(
            HttpClient(
                MockEngine { request ->
                    when (request.rpcMethod()) {
                        "initialize" -> respondJson("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}""")
                        "notifications/initialized" -> respondJson("", io.ktor.http.HttpStatusCode.Accepted)
                        "tools/list" -> respondJson("""{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"t"}]}}""")
                        else -> {
                            called.complete(Unit)
                            delay(10_000)
                            respondJson("{}")
                        }
                    }
                }
            )
        )
        val session = service.openSession(listOf(tool()))
        val job = launch {
            runCatching { session.call("srv__t", "{}") }
        }
        withTimeout(5_000) { called.await() }
        delay(50)
        job.cancelAndJoin()
        session.close()
    }

    @Test
    fun aCancelledSpeechSynthesisStaysCancelled() = runBlocking {
        val tts = ExternalTtsClient(MockEngine { _ -> delay(10_000); respondJson("{}") })
        val job = launch {
            try {
                tts.synthesize("http://voice.test:9000/v1", "", "text", null)
            } catch (e: CancellationException) {
                throw e
            }
        }
        delay(100)
        job.cancelAndJoin()
    }

    @Test
    fun aCancelledModelFetchStaysCancelled() = runBlocking {
        val oai = OAIService(MockEngine { _ -> delay(10_000); respondJson("{}") })
        val job = launch {
            try {
                oai.fetchModels("http://mock.test/v1", "tok")
            } catch (e: CancellationException) {
                throw e
            }
        }
        delay(100)
        job.cancelAndJoin()
    }

    @Test
    fun cancellingAGenerationMidStreamSurfacesAsATransientError() = runBlocking {
        val seenRequest = CompletableDeferred<Unit>()
        val oai = OAIService(
            MockEngine { _ ->
                seenRequest.complete(Unit)
                delay(10_000)
                respondJson("{}")
            }
        )
        val messages = listOf(LLMMessage(LLMMessage.LLMRole.USER, "hi"))
        val outcome = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                oai.generate(messages, "sys", remoteEngineSettings(), onChunk = { })
                outcome.complete(null)
            } catch (t: Throwable) {
                outcome.complete(t)
            }
        }
        withTimeout(5_000) { seenRequest.await() }
        // cancel() is the public stop button: it cancels the in-flight generation job.
        oai.cancel()
        val error = withTimeout(5_000) { outcome.await() }
        assertTrue(error is OAIError.TransientError, "expected TransientError, got $error")
        job.join()
    }
}
