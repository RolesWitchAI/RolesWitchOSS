//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service.mcp

import ai.roleswitch.android.service.respondErrorJson
import ai.roleswitch.android.service.respondJson
import ai.roleswitch.android.service.rpcMethod
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class McpClientTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun rpcResult(id: Int, result: String) =
        """{"jsonrpc":"2.0","id":$id,"result":$result}"""

    private fun client(
        token: String = "",
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
    ): Pair<McpClient, MockEngine> {
        val engine = MockEngine { request -> handler(request) }
        return McpClient(HttpClient(engine), "http://mcp.test/mcp", token, json) to engine
    }

    /** A stateful server: initialize → session header, then per-method canned replies. */
    private class ServerScript {
        val requests = mutableListOf<HttpRequestData>()
        var sessionHeader: String? = null
        var toolsListReply: String = rpc(1, """{"tools":[]}""")
        var callReply: String = rpc(2, """{"content":[]}""")
        var initStatus: HttpStatusCode = HttpStatusCode.OK

        companion object {
            fun rpc(id: Int, result: String) = """{"jsonrpc":"2.0","id":$id,"result":$result}"""
        }
    }

    private fun scripted(script: ServerScript): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { request ->
        script.requests += request
        if (request.method.value == "DELETE") {
            // Session teardown has no body to route on.
            respondJson("", HttpStatusCode.OK)
        } else {
            when (request.rpcMethod()) {
                "initialize" -> respondJson(
                    ServerScript.rpc(1, """{"protocolVersion":"2025-03-26","capabilities":{}}"""),
                    extraHeaders = headersOf("Mcp-Session-Id", "sess-1")
                )
                "notifications/initialized" -> respondJson("", HttpStatusCode.Accepted)
                "tools/list" -> respondJson(script.toolsListReply)
                "tools/call" -> respondJson(script.callReply)
                else -> respondErrorJson(HttpStatusCode.BadRequest, """{"error":{"code":-32601,"message":"no method"}}""")
            }
        }
    }

    @Test
    fun initializeCapturesTheSessionAndNegotiatedProtocol() = runTest {
        val script = ServerScript()
        val (client, engine) = client(handler = scripted(script))
        client.initialize(kotlin.time.Duration.parse("30s"))
        client.callTool("t", Json.parseToJsonElement("{}").let { it as kotlinx.serialization.json.JsonObject }, kotlin.time.Duration.parse("60s"))

        // The session and protocol headers the spec requires on later requests.
        val call = engine.requestHistory.last()
        assertEquals("sess-1", call.headers["Mcp-Session-Id"])
        assertEquals("2025-03-26", call.headers["MCP-Protocol-Version"])
        // The initialize request itself must NOT carry the version header (too early).
        val init = engine.requestHistory.first()
        assertEquals(null, init.headers["MCP-Protocol-Version"])
    }

    @Test
    fun aBearerTokenIsSentOnlyWhenPresent() = runTest {
        val script = ServerScript()
        val (withToken, engine) = client(token = "secret", handler = scripted(script))
        withToken.initialize(kotlin.time.Duration.parse("30s"))
        assertEquals("Bearer secret", engine.requestHistory.first().headers["Authorization"])
    }

    @Test
    fun toolsListFollowsPaginationCursors() = runTest {
        var page = 0
        val (client, _) = client(token = "") { request ->
            when (request.rpcMethod()) {
                "initialize" -> respondJson(rpcResult(1, """{"protocolVersion":"2025-06-18"}"""))
                "notifications/initialized" -> respondJson("", HttpStatusCode.Accepted)
                "tools/list" -> {
                    page++
                    if (page == 1) respondJson(
                        rpcResult(
                            2,
                            """{"tools":[{"name":"alpha","description":"a","inputSchema":{"type":"object"}}],"nextCursor":"page2"}"""
                        )
                    )
                    else respondJson(
                        rpcResult(
                            3,
                            """{"tools":[{"name":"beta"},{"name":"gamma"}]}"""
                        )
                    )
                }
                else -> respondErrorJson(HttpStatusCode.BadRequest, "{}")
            }
        }
        client.initialize(kotlin.time.Duration.parse("30s"))
        val listed = client.listTools(kotlin.time.Duration.parse("20s"))
        assertEquals(listOf("alpha", "beta", "gamma"), listed.map { it.name })
        // A tool entry with no object schema still gets a valid stand-in.
        assertTrue(listed[2].inputSchema.containsKey("type"))
    }

    @Test
    fun namelessEntriesAreSkippedNotFatal() = runTest {
        val (client, _) = client(token = "") { request ->
            when (request.rpcMethod()) {
                "initialize" -> respondJson(rpcResult(1, """{"protocolVersion":"2025-06-18"}"""))
                "notifications/initialized" -> respondJson("", HttpStatusCode.Accepted)
                "tools/list" -> respondJson(
                    rpcResult(
                        2,
                        """{"tools":[{"name":"","description":"broken"},{"name":"good"},"not-an-object"]}"""
                    )
                )
                else -> respondErrorJson(HttpStatusCode.BadRequest, "{}")
            }
        }
        client.initialize(kotlin.time.Duration.parse("30s"))
        assertEquals(listOf("good"), client.listTools(kotlin.time.Duration.parse("20s")).map { it.name })
    }

    @Test
    fun sseShapedRepliesAreParsedLikeJsonOnes() = runTest {
        val reply = rpcResult(2, """{"tools":[{"name":"via-sse"}]}""")
        val (client, _) = client(token = "") { request ->
            when (request.rpcMethod()) {
                "initialize" -> respondJson(rpcResult(1, """{"protocolVersion":"2025-06-18"}"""))
                "notifications/initialized" -> respondJson("", HttpStatusCode.Accepted)
                "tools/list" -> respond(
                    "data: $reply\n\n",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/event-stream")
                )
                else -> respondErrorJson(HttpStatusCode.BadRequest, "{}")
            }
        }
        client.initialize(kotlin.time.Duration.parse("30s"))
        assertEquals(listOf("via-sse"), client.listTools(kotlin.time.Duration.parse("20s")).map { it.name })
    }

    @Test
    fun aJsonRpcErrorSurfacesAsMcpErrorWithItsCode() = runTest {
        val (client, _) = client(token = "") { request ->
            when (request.rpcMethod()) {
                "initialize" -> respondJson(rpcResult(1, """{"protocolVersion":"2025-06-18"}"""))
                "notifications/initialized" -> respondJson("", HttpStatusCode.Accepted)
                "tools/list" -> respondJson(
                    """{"jsonrpc":"2.0","id":2,"error":{"code":-32601,"message":"no such method"}}"""
                )
                else -> respondErrorJson(HttpStatusCode.BadRequest, "{}")
            }
        }
        client.initialize(kotlin.time.Duration.parse("30s"))
        val error = assertFailsWith<McpError> { client.listTools(kotlin.time.Duration.parse("20s")) }
        assertTrue(error.message!!.contains("no such method"), error.message)
        assertTrue(error.message!!.contains("-32601"), error.message)
    }

    @Test
    fun anHttpFailureSurfacesAsMcpError() = runTest {
        val (client, _) = client(token = "") { request ->
            when (request.rpcMethod()) {
                "initialize" -> respondJson(rpcResult(1, """{"protocolVersion":"2025-06-18"}"""))
                "notifications/initialized" -> respondJson("", HttpStatusCode.Accepted)
                else -> respondErrorJson(HttpStatusCode.InternalServerError, "upstream exploded")
            }
        }
        client.initialize(kotlin.time.Duration.parse("30s"))
        val error = assertFailsWith<McpError> { client.listTools(kotlin.time.Duration.parse("20s")) }
        assertTrue(error.message!!.contains("HTTP 500"), error.message)
        assertTrue(error.message!!.contains("upstream exploded"), error.message)
    }

    @Test
    fun a404WithASessionMeansTheSessionExpired() = runTest {
        val (client, _) = client(token = "") { request ->
            when (request.rpcMethod()) {
                "initialize" -> respondJson(
                    rpcResult(1, """{"protocolVersion":"2025-06-18"}"""),
                    extraHeaders = headersOf("Mcp-Session-Id", "sess-9")
                )
                "notifications/initialized" -> respondJson("", HttpStatusCode.Accepted)
                else -> respondErrorJson(HttpStatusCode.NotFound, "")
            }
        }
        client.initialize(kotlin.time.Duration.parse("30s"))
        val error = assertFailsWith<McpError> { client.listTools(kotlin.time.Duration.parse("20s")) }
        assertTrue(error.message!!.contains("session expired"), error.message)
    }

    @Test
    fun aNonJsonReplySurfacesAsMcpError() = runTest {
        val (client, _) = client(token = "") { request ->
            when (request.rpcMethod()) {
                "initialize" -> respondJson(rpcResult(1, """{"protocolVersion":"2025-06-18"}"""))
                "notifications/initialized" -> respondJson("", HttpStatusCode.Accepted)
                else -> respondJson("<html>not json</html>")
            }
        }
        client.initialize(kotlin.time.Duration.parse("30s"))
        val error = assertFailsWith<McpError> { client.listTools(kotlin.time.Duration.parse("20s")) }
        assertTrue(error.message!!.contains("did not answer with JSON"), error.message)
    }

    @Test
    fun aReplyThatNeverCarriesOurIdMeansNoAnswer() = runTest {
        val (client, _) = client(token = "") { request ->
            when (request.rpcMethod()) {
                "initialize" -> respondJson(rpcResult(1, """{"protocolVersion":"2025-06-18"}"""))
                "notifications/initialized" -> respondJson("", HttpStatusCode.Accepted)
                // Answers some *other* id.
                else -> respondJson(rpcResult(999, """{"tools":[]}"""))
            }
        }
        client.initialize(kotlin.time.Duration.parse("30s"))
        val error = assertFailsWith<McpError> { client.listTools(kotlin.time.Duration.parse("20s")) }
        assertTrue(error.message!!.contains("without answering"), error.message)
    }

    @Test
    fun sseRepliesIgnoreCommentsEventsAndIdsAndJoinSplitData() = runTest {
        val reply = rpcResult(2, """{"tools":[{"name":"split"}]}""")
        val (client, _) = client(token = "") { request ->
            when (request.rpcMethod()) {
                "initialize" -> respondJson(rpcResult(1, """{"protocolVersion":"2025-06-18"}"""))
                "notifications/initialized" -> respondJson("", HttpStatusCode.Accepted)
                "tools/list" -> respond(
                    ": keep-alive comment\n" +
                        "event: message\n" +
                        "id: 7\n" +
                        "retry: 100\n" +
                        "data: {\"jsonrpc\":\"2.0\",\n" +
                        "data: \"id\":2,\"result\":{\"tools\":[{\"name\":\"split\"}]}}\n\n",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/event-stream")
                )
                else -> respondErrorJson(HttpStatusCode.BadRequest, "{}")
            }
        }
        client.initialize(kotlin.time.Duration.parse("30s"))
        assertEquals(listOf("split"), client.listTools(kotlin.time.Duration.parse("20s")).map { it.name })
    }

    @Test
    fun aBatchArrayReplyIsSearchedForOurId() = runTest {
        val (client, _) = client(token = "") { request ->
            when (request.rpcMethod()) {
                "initialize" -> respondJson(rpcResult(1, """{"protocolVersion":"2025-06-18"}"""))
                "notifications/initialized" -> respondJson("", HttpStatusCode.Accepted)
                "tools/list" -> respondJson(
                    """[{"jsonrpc":"2.0","id":999,"result":{"tools":[]}},{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"from-batch"}]}}]"""
                )
                else -> respondErrorJson(HttpStatusCode.BadRequest, "{}")
            }
        }
        client.initialize(kotlin.time.Duration.parse("30s"))
        assertEquals(listOf("from-batch"), client.listTools(kotlin.time.Duration.parse("20s")).map { it.name })
    }

    @Test
    fun closeWithoutASessionSendsNothing() = runTest {
        val (client, engine) = client(token = "") { request ->
            respondJson(rpcResult(1, """{"protocolVersion":"2025-06-18"}"""))
        }
        client.close() // stateless server: no session id was ever assigned
        assertEquals(0, engine.requestHistory.size)
    }

    @Test
    fun closeSendsADeleteForTheSession() = runTest {
        val script = ServerScript()
        val (client, engine) = client(handler = scripted(script))
        client.initialize(kotlin.time.Duration.parse("30s"))
        client.close()
        val delete = engine.requestHistory.lastOrNull { it.method.value == "DELETE" }
        assertEquals("sess-1", delete?.headers?.get("Mcp-Session-Id"))
    }
}
