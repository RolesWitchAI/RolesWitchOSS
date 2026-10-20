//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service.mcp

import ai.roleswitch.android.data.model.McpTool
import ai.roleswitch.android.service.rpcMethod
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replays the captured behaviour of a real Streamable-HTTP MCP server
 * (`https://search.parallel.ai/mcp`, Parallel Web Search v1.27.0, no credentials).
 * The bodies below are trimmed for size but keep the real wire shapes: plain-JSON
 * replies (not SSE), a `mcp-session-id` assigned on `initialize`, `202` for the
 * `notifications/initialized` acknowledgement, and `tools/call` results carrying both
 * `content` and `structuredContent`. The fixtures were captured with curl against the
 * live server; the tests themselves run fully offline.
 */
private const val PARALLEL_SESSION = "34ac4da0-cce6-412a-a949-d5e7b0160178"

private val capturedInitialize = """
    {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18",
    "capabilities":{"experimental":{},"prompts":{"listChanged":false},
    "resources":{"subscribe":false,"listChanged":false},"tools":{"listChanged":false}},
    "serverInfo":{"name":"Parallel Web Search MCP Server","version":"1.27.0",
    "websiteUrl":"https://parallel.ai"}}}
""".trimIndent().replace("\n", "")

private val capturedToolsList = """
    {"jsonrpc":"2.0","id":2,"result":{"tools":[
    {"name":"web_search",
     "description":"Purpose: Perform web searches and return LLM-friendly results, including excerpts.",
     "inputSchema":{"title":"web_searchArguments","type":"object",
        "properties":{"objective":{"type":"string"},
          "search_queries":{"items":{"type":"string"},"type":"array"},
          "session_id":{"anyOf":[{"type":"string"},{"type":"null"}],"default":null},
          "model_name":{"default":"parallel-sonar","type":null}},
        "required":["objective","search_queries"]}},
    {"name":"web_fetch",
     "description":"Purpose: Fetch and extract relevant content from specific web URLs.",
     "inputSchema":{"title":"web_fetchArguments","type":"object",
        "properties":{"urls":{"items":{"type":"string"},"type":"array"},
          "objective":{"type":"string"},
          "search_queries":{"items":{"type":"string"},"type":"array"},
          "full_content":{"default":false,"type":"boolean"},
          "allow_live_fetch":{"default":true,"type":"boolean"},
          "session_id":{"anyOf":[{"type":"string"},{"type":"null"}],"default":null},
          "model_name":{"default":"parallel-sonar","type":null}},
        "required":["urls"]}}
    ]}}
""".trimIndent().replace("\n", "")

private val capturedSearchText =
    """{   "search_id": "search_4eb26509ce33ce99954fd6dc4e678190",   "results": [     {       "url": "https://www.britannica.com/place/France",       "title": "France | History, Maps, Flag, Population, Cities, Capital, & Facts | Britannica",       "excerpts": [         "France, officially French Republic, country of northwestern Europe."       ]     }   ] }"""

private val capturedCall = kotlinx.serialization.json.buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", 3)
    putJsonObject("result") {
        put(
            "content",
            kotlinx.serialization.json.buildJsonArray {
                add(
                    kotlinx.serialization.json.buildJsonObject {
                        put("type", "text")
                        put("text", capturedSearchText)
                    }
                )
            }
        )
        putJsonObject("structuredContent") {
            put("search_id", "search_4eb26509ce33ce99954fd6dc4e678190")
        }
        put("isError", false)
    }
}.toString()

private fun capturedServer(): McpService {
    val engine = MockEngine { request ->
        if (request.method.value == "DELETE") {
            return@MockEngine respond("", HttpStatusCode.OK)
        }
        when (request.rpcMethod()) {
            "initialize" -> respond(
                capturedInitialize,
                HttpStatusCode.OK,
                Headers.build {
                    append(HttpHeaders.ContentType, "application/json")
                    append("mcp-session-id", PARALLEL_SESSION)
                }
            )
            "notifications/initialized" -> respond("", HttpStatusCode.Accepted)
            "tools/list" -> respond(
                capturedToolsList,
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
            "tools/call" -> respond(
                capturedCall,
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
            else -> respond("""{"jsonrpc":"2.0","id":99,"error":{"code":-32601,"message":"method not found"}}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }
    return McpService(HttpClient(engine))
}

class CapturedParallelServerTest {

    @Test
    fun theCapturedHandshakeYieldsBothTools() = kotlinx.coroutines.runBlocking {
        val service = capturedServer()
        val names = service.discoverToolNames("https://search.parallel.ai/mcp", "")
        assertEquals(listOf("web_search", "web_fetch"), names)
    }

    @Test
    fun aSessionExposesNamespacedFunctionsAndCallsThrough() = runTest {
        val service = capturedServer()
        val session = service.openSession(
            listOf(McpTool(id = "p1", name = "Parallel", url = "https://search.parallel.ai/mcp", token = "", createdAt = 1L, updatedAt = 1L))
        )

        assertEquals(listOf("Parallel__web_search", "Parallel__web_fetch"), session.specs.map { it.functionName })
        // The real schema shape survives the $schema-less capture: type + properties.
        val schema = session.specs[0].parametersSchema
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        assertTrue("objective" in schema["properties"]!!.jsonObject.keys)

        assertEquals("Parallel/web_search", session.describe("Parallel__web_search"))

        val result = session.call(
            "Parallel__web_search",
            """{"objective":"What is the capital of France?","search_queries":["capital of France"]}"""
        )
        assertTrue(result.contains("britannica.com/place/France"), result.take(120))

        session.close()
    }

    @Test
    fun theClientCarriesTheSessionHeaderTheServerAssigned() = runTest {
        val engine = MockEngine { request ->
            if (request.method.value == "DELETE") return@MockEngine respond("", HttpStatusCode.OK)
            when (request.rpcMethod()) {
                "initialize" -> respond(
                    capturedInitialize,
                    HttpStatusCode.OK,
                    Headers.build {
                        append(HttpHeaders.ContentType, "application/json")
                        append("mcp-session-id", PARALLEL_SESSION)
                    }
                )
                "notifications/initialized" -> respond("", HttpStatusCode.Accepted)
                else -> respond(capturedToolsList, HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
        val client = McpClient(HttpClient(engine), "https://search.parallel.ai/mcp", "", json)
        client.initialize(kotlin.time.Duration.parse("30s"))
        client.listTools(kotlin.time.Duration.parse("20s"))
        client.close()

        val posts = engine.requestHistory.filter { it.method.value == "POST" }
        // The initialize POST cannot carry a session yet — every later one must.
        val afterHandshake = posts.drop(1)
        assertTrue(afterHandshake.isNotEmpty())
        assertTrue(afterHandshake.all { it.headers["Mcp-Session-Id"] == PARALLEL_SESSION })
        assertTrue(engine.requestHistory.any { it.method.value == "DELETE" && it.headers["Mcp-Session-Id"] == PARALLEL_SESSION })
    }
}
