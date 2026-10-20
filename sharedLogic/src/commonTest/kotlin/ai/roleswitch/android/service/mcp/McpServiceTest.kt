//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service.mcp

import ai.roleswitch.android.data.model.McpTool
import ai.roleswitch.android.service.respondErrorJson
import ai.roleswitch.android.service.respondJson
import ai.roleswitch.android.service.rpcMethod
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class McpServiceTest {

    /** A Streamable-HTTP MCP server speaking JSON replies over MockEngine. */
    private fun mcpService(
        toolsBody: String = """{"tools":[{"name":"weather lookup","description":"weather","inputSchema":{"${'$'}schema":"https://example.com/schema","properties":{"city":{"type":"string"}}}}]}""",
        callBody: String = """{"content":[{"type":"text","text":"21C and clear"}]}""",
        initializeStatus: HttpStatusCode = HttpStatusCode.OK
    ): McpService = McpService(
        HttpClient(
            MockEngine { request ->
                when (request.rpcMethod()) {
                    "initialize" -> respondJson(
                        """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{}}}""",
                        initializeStatus,
                        extraHeaders = headersOf("Mcp-Session-Id", "sess-1")
                    )
                    "notifications/initialized" -> respondJson("", HttpStatusCode.Accepted)
                    "tools/list" -> respondJson("""{"jsonrpc":"2.0","id":2,"result":$toolsBody}""")
                    "tools/call" -> respondJson("""{"jsonrpc":"2.0","id":3,"result":$callBody}""")
                    else -> respondErrorJson(HttpStatusCode.BadRequest, """{"error":{"code":-32601,"message":"no method"}}""")
                }
            }
        )
    )

    private fun tool(name: String, url: String = "http://mcp.test/mcp") =
        McpTool(id = "id-$name", name = name, url = url, token = "", createdAt = 1L, updatedAt = 1L)

    @Test
    fun openSessionExposesSanitizedNamespacedFunctions() = runTest {
        val service = mcpService()
        val session = service.openSession(listOf(tool("My Server!")))

        assertEquals(1, session.specs.size)
        val spec = session.specs[0]
        assertEquals("My_Server__weather_lookup", spec.functionName)
        assertEquals("weather", spec.description)
        assertEquals("My Server!", spec.serverName)
        // `$schema` is stripped and the object type is guaranteed present.
        assertTrue(!spec.parametersSchema.containsKey("\$schema"), spec.parametersSchema.toString())
        assertEquals("object", spec.parametersSchema["type"]!!.jsonPrimitiveContent())
        // describe() maps the sanitized name back to server/tool for the trace.
        assertEquals("My Server!/weather lookup", session.describe(spec.functionName))
        assertEquals("unknown__fn", session.describe("unknown__fn"))
        session.close()
    }

    @Test
    fun collidingFunctionNamesGetCounterSuffixes() = runTest {
        val service = mcpService(
            toolsBody = """{"tools":[{"name":"search"},{"name":"search"}]}"""
        )
        val session = service.openSession(listOf(tool("srv")))
        assertEquals(listOf("srv__search", "srv__search_2"), session.specs.map { it.functionName })
        session.close()
    }

    @Test
    fun anUnreachableEndpointIsSkippedWhileOthersStillWork() = runTest {
        val good = mcpService()
        val bad = mcpService(initializeStatus = HttpStatusCode.InternalServerError)
        // One McpService is bound to one engine; use two separate services to prove the
        // skip logic by driving openSession with a mix of their tools is not possible —
        // instead drive it directly with the failing one alone, then the working one.
        val failed = bad.openSession(listOf(tool("down")))
        assertTrue(failed.isEmpty)
        failed.close()

        val working = good.openSession(listOf(tool("up")))
        assertEquals(1, working.specs.size)
        working.close()
    }

    // These two run on real time: discoverToolNames bounds the exchange with
    // withTimeout, and runTest's virtual clock would expire it before the (real)
    // mock-engine round trip lands.
    @Test
    fun discoverToolNamesListsWhatTheServerAdvertises() = kotlinx.coroutines.runBlocking {
        val service = mcpService(toolsBody = """{"tools":[{"name":"a"},{"name":"b"}]}""")
        assertEquals(listOf("a", "b"), service.discoverToolNames("http://mcp.test/mcp", ""))
    }

    @Test
    fun discoverToolNamesWrapsFailuresAsMcpError() = kotlinx.coroutines.runBlocking {
        val service = mcpService(initializeStatus = HttpStatusCode.ServiceUnavailable)
        val error = assertFailsWith<McpError> {
            service.discoverToolNames("http://mcp.test/mcp", "")
        }
        assertTrue(error.message!!.contains("503"), error.message)
    }

    @Test
    fun aCallReturnsTheServersText() = runTest {
        val service = mcpService()
        val session = service.openSession(listOf(tool("srv")))
        assertEquals("21C and clear", session.call("srv__weather_lookup", """{"city":"kyiv"}"""))
        session.close()
    }

    @Test
    fun callingAnUnknownFunctionReportsAnErrorAsText() = runTest {
        val service = mcpService()
        val session = service.openSession(listOf(tool("srv")))
        assertEquals("Error: unknown tool 'nope'.", session.call("nope", "{}"))
        session.close()
    }

    @Test
    fun argumentsThatAreNotAJsonObjectReportAnErrorAsText() = runTest {
        val service = mcpService()
        val session = service.openSession(listOf(tool("srv")))
        val result = session.call("srv__weather_lookup", "not json at all")
        assertTrue(result.startsWith("Error: arguments were not a JSON object"), result)
        session.close()
    }

    @Test
    fun aServerFlaggedErrorResultIsPrefixed() = runTest {
        val service = mcpService(callBody = """{"content":[{"type":"text","text":"city unknown"}],"isError":true}""")
        val session = service.openSession(listOf(tool("srv")))
        assertEquals("Error: city unknown", session.call("srv__weather_lookup", "{}"))
        session.close()
    }

    @Test
    fun longResultsAreTruncatedForTheModel() = runTest {
        val huge = "x".repeat(9_000)
        val service = mcpService(callBody = """{"content":[{"type":"text","text":"$huge"}]}""")
        val session = service.openSession(listOf(tool("srv")))
        val result = session.call("srv__weather_lookup", "{}")
        // The 8000-char body plus the truncation notice.
        assertTrue(result.length in 8001..8100, "length was ${result.length}")
        assertTrue(result.endsWith("…[truncated, 1000 more characters]"), result.takeLast(80))
        session.close()
    }

    @Test
    fun nonTextContentFallsBackToTheStructuredPayload() = runTest {
        val service = mcpService(callBody = """{"content":[{"type":"image","data":"..."}],"structuredContent":{"answer":42}}""")
        val session = service.openSession(listOf(tool("srv")))
        val result = session.call("srv__weather_lookup", "{}")
        assertTrue(result.contains("\"answer\""), result)
        session.close()
    }

    @Test
    fun aResultWithNothingAtAllSaysSo() = runTest {
        val service = mcpService(callBody = """{"content":[]}""")
        val session = service.openSession(listOf(tool("srv")))
        assertEquals("(tool returned no text content)", session.call("srv__weather_lookup", "{}"))
        session.close()
    }

    @Test
    fun aTransportFailureDuringTheCallComesBackAsErrorText() = runTest {
        val service = McpService(
            HttpClient(
                MockEngine { request ->
                    when (request.rpcMethod()) {
                        "initialize" -> respondJson("""{"jsonrpc":"2.0","id":1,"result":{}}""")
                        "notifications/initialized" -> respondJson("", HttpStatusCode.Accepted)
                        "tools/list" -> respondJson("""{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"t"}]}}""")
                        else -> respondErrorJson(HttpStatusCode.BadGateway, "upstream")
                    }
                }
            )
        )
        val session = service.openSession(listOf(tool("srv")))
        val result = session.call("srv__t", "{}")
        assertTrue(result.startsWith("Error: tool call failed"), result)
        session.close()
    }

    @Test
    fun blankArgumentsAreSentAsAnEmptyObject() = runTest {
        val service = mcpService()
        val session = service.openSession(listOf(tool("srv")))
        assertEquals("21C and clear", session.call("srv__weather_lookup", ""))
        session.close()
    }

    @Test
    fun nonStringContentFieldsAreSkippedNotFatal() = runTest {
        val service = mcpService(
            callBody = """{"content":[{"type":5},{"type":"text","text":null},{"type":"text","text":"real text"}]}"""
        )
        val session = service.openSession(listOf(tool("srv")))
        assertEquals("real text", session.call("srv__weather_lookup", "{}"))
        session.close()
    }

    @Test
    fun aFailingToolsListAfterAGoodHandshakeSkipsTheServer() = runTest {
        val service = McpService(
            HttpClient(
                MockEngine { request ->
                    when (request.rpcMethod()) {
                        "initialize" -> respondJson("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}""")
                        "notifications/initialized" -> respondJson("", HttpStatusCode.Accepted)
                        else -> respondErrorJson(HttpStatusCode.InternalServerError, "listing broken")
                    }
                }
            )
        )
        val session = service.openSession(listOf(tool("half-up")))
        assertTrue(session.isEmpty) // handshake fine, tools/list broken: skipped, not fatal
        session.close()
    }

    @Test
    fun aFailureWithNoMessageIsNamedByItsClass() = kotlinx.coroutines.runBlocking {
        val service = McpService(
            HttpClient(MockEngine { _ -> throw IllegalStateException() })
        )
        val error = assertFailsWith<McpError> {
            service.discoverToolNames("http://mcp.test/mcp", "")
        }
        assertTrue(error.message!!.contains("IllegalStateException"), error.message)
    }

    @Test
    fun hostileLabelsFallBackToNeutralNames() = runTest {
        val service = mcpService(toolsBody = """{"tools":[{"name":"!!!"}]}""")
        val session = service.openSession(listOf(tool("***")))
        assertEquals(listOf("mcp__tool"), session.specs.map { it.functionName })
        session.close()
    }

    @Test
    fun functionNamesAreCappedAtTheWireLimit() = runTest {
        val longServer = "s".repeat(40)
        val longTool = "t".repeat(60)
        val service = mcpService(toolsBody = """{"tools":[{"name":"$longTool"},{"name":"$longTool"},{"name":"$longTool"}]}""")
        val session = service.openSession(listOf(tool(longServer)))
        session.specs.forEach { spec -> assertTrue(spec.functionName.length <= 64, spec.functionName) }
        // The collision suffixes keep them apart within the cap.
        assertEquals(3, session.specs.map { it.functionName }.distinct().size)
        session.close()
    }

    @Test
    fun shutdownClosesTheSharedClientWithoutThrowing() {
        val service = mcpService()
        service.shutdown()
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveContent(): String =
    (this as kotlinx.serialization.json.JsonPrimitive).content
