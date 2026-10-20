//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service

import ai.roleswitch.android.service.mcp.McpToolSpec
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OAIServiceTest {

    private val messages = listOf(
        LLMMessage(role = LLMMessage.LLMRole.USER, content = "Hello"),
        LLMMessage(role = LLMMessage.LLMRole.ASSISTANT, content = "Hi"),
        LLMMessage(role = LLMMessage.LLMRole.USER, content = "What is 2+2?")
    )

    private fun service(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): OAIService =
        OAIService(MockEngine { request -> handler(request) })

    @Test
    fun streamedContentIsAssembledAndDeliveredPerChunk() = runTest {
        val seen = mutableListOf<String>()
        var seenPath: String? = null
        var seenAuth: String? = null
        val oai = service { request ->
            seenPath = request.url.encodedPath
            seenAuth = request.headers["Authorization"]
            respondSse(sseBody("""{"role":"assistant"}""", """{"content":"Four"}""", """{"content":"."}"""))
        }
        val result = oai.generate(messages, "You are a test.", remoteEngineSettings(), onChunk = { seen += it })

        assertEquals("Four.", result.content)
        assertEquals("", result.reasoning)
        assertEquals(listOf("Four", "."), seen)
        assertEquals("/v1/chat/completions", seenPath)
        assertEquals("Bearer test-token", seenAuth)
    }

    @Test
    fun everyGenerationParameterReachesTheRequestBody() = runTest {
        var body: String? = null
        val oai = service { request ->
            body = (request.body as io.ktor.http.content.TextContent).text
            respondSse(sseBody("""{"content":"ok"}"""))
        }
        oai.generate(messages, "sys", remoteEngineSettings(), onChunk = { })

        val json = assertNotNull(body).let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject }
        assertEquals("test-model", json["model"]!!.jsonPrimitive.content)
        assertEquals(0.7, json["temperature"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals(0.9, json["top_p"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals(2, json["n"]!!.jsonPrimitive.content.toInt())
        assertEquals(256, json["max_completion_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(0.1, json["presence_penalty"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals(0.2, json["frequency_penalty"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals("low", json["reasoning_effort"]!!.jsonPrimitive.content)
        // The stream flag is what the SSE reply is for.
        assertTrue(json["stream"]!!.jsonPrimitive.content == "true")
    }

    @Test
    fun blankParametersAreOmittedFromTheRequest() = runTest {
        var body: String? = null
        val oai = service { request ->
            body = (request.body as io.ktor.http.content.TextContent).text
            respondSse(sseBody("""{"content":"ok"}"""))
        }
        val bare = remoteEngineSettings(
            temperature = "", topP = "", n = "", maxCompletionTokens = "",
            presencePenalty = "", frequencyPenalty = "", reasoningEffort = ""
        )
        oai.generate(messages, "sys", bare, onChunk = { })

        val json = assertNotNull(body)!!
        assertTrue(!json.contains("temperature"), "temperature should be absent: $json")
        assertTrue(!json.contains("top_p"))
        assertTrue(!json.contains("\"n\""))
        assertTrue(!json.contains("max_completion_tokens"))
        assertTrue(!json.contains("presence_penalty"))
        assertTrue(!json.contains("frequency_penalty"))
        assertTrue(!json.contains("reasoning_effort"))
        assertTrue(!json.contains("tools"))
    }

    @Test
    fun unparseableNumericParametersAreOmittedToo() = runTest {
        var body: String? = null
        val oai = service { request ->
            body = (request.body as io.ktor.http.content.TextContent).text
            respondSse(sseBody(contentDelta("ok")))
        }
        val garbage = remoteEngineSettings(
            temperature = "hot",
            topP = "ninety",
            n = "1.5",
            maxCompletionTokens = "many",
            presencePenalty = "-",
            frequencyPenalty = "n/a"
        )
        oai.generate(messages, "sys", garbage, onChunk = { })

        val json = assertNotNull(body)!!
        assertTrue(!json.contains("temperature"), json)
        assertTrue(!json.contains("top_p"), json)
        assertTrue(!json.contains("\"n\""), json)
        assertTrue(!json.contains("max_completion_tokens"), json)
        assertTrue(!json.contains("presence_penalty"), json)
        assertTrue(!json.contains("frequency_penalty"), json)
    }

    @Test
    fun theSystemPromptLeadsTheConversation() = runTest {
        var body: String? = null
        val oai = service { request ->
            body = (request.body as io.ktor.http.content.TextContent).text
            respondSse(sseBody("""{"content":"ok"}"""))
        }
        oai.generate(messages, "SYSTEM-PROMPT", remoteEngineSettings(), onChunk = { })

        val json = assertNotNull(body)!!.let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject }
        val wireMessages = json["messages"]!!.jsonArray
        assertEquals("system", wireMessages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("SYSTEM-PROMPT", wireMessages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(4, wireMessages.size)
    }

    @Test
    fun imagesBecomeDataUrlPartsWhenVisionIsEnabled() = runTest {
        val bytes = byteArrayOf(1, 2, 3, 4)
        var body: String? = null
        val oai = service { request ->
            body = (request.body as io.ktor.http.content.TextContent).text
            respondSse(sseBody("""{"content":"saw it"}"""))
        }
        val withImage = listOf(LLMMessage(LLMMessage.LLMRole.USER, "look", listOf(bytes)))
        oai.generate(withImage, "sys", remoteEngineSettings(enableVision = true), onChunk = { })

        val json = assertNotNull(body)!!
        assertTrue(json.contains("image_url"), "expected an image part: $json")
        assertTrue(json.contains("data:image/jpeg;base64,${Base64.encode(bytes)}"))
    }

    @Test
    fun imagesAreDroppedWhenVisionIsUnavailable() = runTest {
        val bytes = byteArrayOf(9, 9)
        var body: String? = null
        val oai = service { request ->
            body = (request.body as io.ktor.http.content.TextContent).text
            respondSse(sseBody("""{"content":"no image"}"""))
        }
        val withImage = listOf(LLMMessage(LLMMessage.LLMRole.USER, "look", listOf(bytes)))
        oai.generate(withImage, "sys", remoteEngineSettings(enableVision = false), onChunk = { })

        val json = assertNotNull(body)!!
        assertTrue(!json.contains("image_url"), "image must not be sent: $json")
    }

    @Test
    fun reasoningContentIsAccumulated() = runTest {
        val oai = service { _ ->
            respondSse(
                sseBody("""{"reasoning_content":"thinking "}""", """{"reasoning_content":"hard"}""", """{"content":"answer"}""")
            )
        }
        val result = oai.generate(messages, "sys", remoteEngineSettings(), onChunk = { })
        assertEquals("answer", result.content)
        assertEquals("thinking hard", result.reasoning)
    }

    @Test
    fun toolCallFragmentsAreReassembledAndTheLoopFeedsResultsBack() = runTest {
        var requestCount = 0
        val bodies = mutableListOf<String>()
        val oai = service { request ->
            bodies += (request.body as io.ktor.http.content.TextContent).text
            requestCount++
            if (requestCount == 1) {
                respondSse(
                    sseBody(
                        contentDelta("Let me check."),
                        // The fragments concatenate to exactly `{"q":"kyiv"}` — the
                        // string value's quotes are split across fragments the way
                        // servers actually stream them.
                        toolCallDelta(index = 0, id = "call_1", name = "srv__lookup", arguments = "{\"q\":"),
                        toolCallDelta(index = 0, id = null, name = null, arguments = "\"kyiv\"}")
                    )
                )
            } else {
                respondSse(sseBody(contentDelta("It is sunny.")))
            }
        }
        val calls = mutableListOf<Pair<String, String>>()
        val toolEdges = mutableListOf<Boolean>()
        val spec = McpToolSpec(
            functionName = "srv__lookup",
            description = "looks things up",
            parametersSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { put("q", buildJsonObject { put("type", "string") }) }
            },
            serverName = "srv"
        )
        val result = oai.generate(
            messages, "sys", remoteEngineSettings(),
            onChunk = { },
            toolSpecs = listOf(spec),
            onToolCall = { toolEdges += it },
            callTool = { name, args ->
                calls += name to args
                "RESULT: 21C"
            }
        )

        assertEquals(listOf("srv__lookup" to """{"q":"kyiv"}"""), calls)
        // Only the final round's text is the answer — the pre-tool narration is dropped.
        assertEquals("It is sunny.", result.content)
        assertEquals(1, result.toolCalls.size)
        assertEquals("srv__lookup", result.toolCalls[0].label)
        assertEquals("""{"q":"kyiv"}""", result.toolCalls[0].arguments)
        assertEquals("RESULT: 21C", result.toolCalls[0].result)
        assertEquals(listOf(true, false), toolEdges)

        // The second request must carry the assistant tool call plus its result.
        val second = kotlinx.serialization.json.Json.parseToJsonElement(bodies[1]).jsonObject
        val wireMessages = second["messages"]!!.jsonArray
        val last = wireMessages.last().jsonObject
        assertEquals("tool", last["role"]!!.jsonPrimitive.content)
        assertTrue(last["content"]!!.jsonPrimitive.content.contains("RESULT: 21C"))
        // And the first request advertised exactly one function.
        val first = kotlinx.serialization.json.Json.parseToJsonElement(bodies[0]).jsonObject
        assertEquals(1, first["tools"]!!.jsonArray.size)
        assertEquals("srv__lookup", first["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun aToolCallWithoutAnIdIsNotACall() = runTest {
        val oai = service { _ ->
            respondSse(
                sseBody(
                    toolCallDelta(index = 0, id = null, name = "no_id", arguments = "{}"),
                    contentDelta("plain answer")
                )
            )
        }
        val result = oai.generate(
            messages, "sys", remoteEngineSettings(),
            onChunk = { }, toolSpecs = emptyList(), onToolCall = null,
            callTool = { _, _ -> "unused" }
        )
        assertEquals("plain answer", result.content)
        assertEquals(emptyList(), result.toolCalls)
    }

    @Test
    fun toolSpecsWithoutAnExecutorAreNotAdvertised() = runTest {
        var body: String? = null
        val oai = service { request ->
            body = (request.body as io.ktor.http.content.TextContent).text
            respondSse(sseBody("""{"content":"ok"}"""))
        }
        val spec = McpToolSpec("fn", "desc", buildJsonObject { put("type", "object") }, "srv")
        oai.generate(
            messages, "sys", remoteEngineSettings(),
            onChunk = { }, toolSpecs = listOf(spec), onToolCall = null, callTool = null
        )
        assertTrue(!assertNotNull(body)!!.contains("tools"))
    }

    @Test
    fun theToolLoopStopsAtTheRoundCapAndAsksTheModelToConclude() = runTest {
        var requestCount = 0
        val bodies = mutableListOf<String>()
        val oai = service { request ->
            bodies += (request.body as io.ktor.http.content.TextContent).text
            requestCount++
            if (requestCount <= 9) {
                // Nine consecutive rounds asking for the same tool.
                respondSse(
                    sseBody(toolCallDelta(index = 0, id = "call_$requestCount", name = "loop_tool", arguments = "{}"))
                )
            } else {
                respondSse(sseBody(contentDelta("final")))
            }
        }
        val result = oai.generate(
            messages, "sys", remoteEngineSettings(),
            onChunk = { }, toolSpecs = listOf(McpToolSpec("loop_tool", "d", buildJsonObject { put("type", "object") }, "srv")),
            onToolCall = null,
            callTool = { _, _ -> "x" }
        )

        assertEquals(10, requestCount)
        assertEquals("final", result.content)
        // The concluding request must advertise no tools at all — that is the guarantee
        // the loop terminates.
        val lastBody = kotlinx.serialization.json.Json.parseToJsonElement(bodies.last()).jsonObject
        assertTrue(!lastBody.containsKey("tools"), "the cap-hit round must send no tools")
    }

    @Test
    fun responseMarkersFromTextualToolProtocolsAreScrubbed() = runTest {
        val oai = service { _ ->
            respondSse(
                sseBody(
                    contentDelta("The answer is 4"),
                    contentDelta("[END_TOOL_RESULT] and [TOOL_REQUEST]done</TOOL_RESULT>")
                )
            )
        }
        val result = oai.generate(messages, "sys", remoteEngineSettings(), onChunk = { })
        assertEquals("The answer is 4 and done", result.content)
    }

    @Test
    fun anUnparseableEndpointThrowsInvalidUrl() = runTest {
        val oai = service { _ -> respondSse(sseBody(contentDelta("x"))) }
        val error = assertFailsWith<OAIError.InvalidURL> {
            // A non-numeric port is the shape Ktor's parser actually rejects.
            oai.generate(messages, "sys", remoteEngineSettings(openaiAPI = "http://host:port/"), onChunk = { })
        }
        // A bad address is not a server problem: it maps to the silent variant, which
        // the UI declines to show as an alert.
    }

    @Test
    fun anOpenAiShapedErrorSurfacesAsAnInvalidResponseNamingTheProblem() = runTest {
        val oai = service { _ ->
            respondErrorJson(
                HttpStatusCode.InternalServerError,
                """{"error":{"message":"model overloaded","type":"server_error","param":null,"code":null}}"""
            )
        }
        val error = assertFailsWith<OAIError.InvalidResponse> {
            oai.generate(messages, "sys", remoteEngineSettings(), onChunk = { })
        }
        // The SSE path wraps server failures without an API-shaped status; the reason
        // must still name what happened.
        assertTrue(error.message!!.contains("model overloaded"), error.message)
        assertTrue(!error.isSilent)
    }

    @Test
    fun anAssistantMessageRightAfterTheSystemPromptIsDropped() = runTest {
        var body: String? = null
        val oai = service { request ->
            body = (request.body as io.ktor.http.content.TextContent).text
            respondSse(sseBody(contentDelta("ok")))
        }
        // History whose first consolidated turn is the assistant's greeting — the
        // request must not open with two consecutive system-adjacent voices.
        val assistantFirst = listOf(
            LLMMessage(LLMMessage.LLMRole.ASSISTANT, "hello!"),
            LLMMessage(LLMMessage.LLMRole.USER, "again")
        )
        oai.generate(assistantFirst, "sys", remoteEngineSettings(), onChunk = { })
        val wire = kotlinx.serialization.json.Json.parseToJsonElement(body!!).jsonObject["messages"]!!.jsonArray
        assertEquals(2, wire.size) // system + user; the stale greeting was removed
        assertEquals("user", wire[1].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun aWellFormed4xxMapsToItsStatusAndTheServersMessage() = runTest {
        val oai = service { _ ->
            respondErrorJson(
                HttpStatusCode.BadRequest,
                """{"error":{"message":"max_tokens is too large","type":"invalid_request_error","param":null,"code":null}}"""
            )
        }
        val error = assertFailsWith<OAIError.InvalidResponse> {
            oai.generate(messages, "sys", remoteEngineSettings(), onChunk = { })
        }
        assertEquals(400, error.statusCode)
        assertTrue(error.message!!.contains("max_tokens is too large"), error.message)
    }

    @Test
    fun aToolCallFragmentWithAnEmptyNameIsNotACall() = runTest {
        val oai = service { _ ->
            respondSse(
                sseBody(
                    toolCallDelta(index = 0, id = "call_x", name = "", arguments = "{}"),
                    contentDelta("plain")
                )
            )
        }
        val result = oai.generate(messages, "sys", remoteEngineSettings(), onChunk = { })
        assertEquals("plain", result.content)
        assertEquals(emptyList(), result.toolCalls)
    }

    @Test
    fun anErrorCodeWithoutAMessageBecomesTheReason() = runTest {
        val oai = service { _ ->
            respondErrorJson(
                HttpStatusCode.BadRequest,
                """{"error":{"code":"context_length_exceeded","message":null,"param":null}}"""
            )
        }
        val error = assertFailsWith<OAIError.InvalidResponse> {
            oai.generate(messages, "sys", remoteEngineSettings(), onChunk = { })
        }
        assertTrue(error.message!!.contains("context_length_exceeded"), error.message)
    }

    @Test
    fun anErrorBodyWithoutADetailFallsBackToTheTransportMessage() = runTest {
        val oai = service { _ ->
            respondErrorJson(HttpStatusCode.Unauthorized, """{"error":{}}""")
        }
        val error = assertFailsWith<OAIError.InvalidResponse> {
            oai.generate(messages, "sys", remoteEngineSettings(), onChunk = { })
        }
        assertEquals(401, error.statusCode)
        assertTrue(error.message!!.isNotBlank(), error.message)
    }

    @Test
    fun chunksWithoutDeltasAreIgnored() = runTest {
        val oai = service { _ ->
            respondSse(
                sseBody(
                    """{"id":"1","object":"chat.completion.chunk","created":0,"model":"test-model","choices":[]}""",
                    """{"id":"1","object":"chat.completion.chunk","created":0,"model":"test-model","choices":[{"index":0,"delta":null,"finish_reason":"stop"}]}""",
                    contentDelta("after silence")
                )
            )
        }
        val result = oai.generate(messages, "sys", remoteEngineSettings(), onChunk = { })
        assertEquals("after silence", result.content)
    }

    @Test
    fun aToolCallWithoutAnExecutorEndsTheRoundWithTheNarration() = runTest {
        // The server asks for a tool but this turn has no executor — the requested
        // calls are dropped and the streamed text becomes the answer.
        val oai = service { _ ->
            respondSse(
                sseBody(
                    contentDelta("I would look that up, but"),
                    toolCallDelta(index = 0, id = "call_1", name = "fn", arguments = "{}")
                )
            )
        }
        val result = oai.generate(
            messages, "sys", remoteEngineSettings(),
            onChunk = { },
            toolSpecs = listOf(
                ai.roleswitch.android.service.mcp.McpToolSpec(
                    functionName = "fn",
                    description = "d",
                    parametersSchema = kotlinx.serialization.json.buildJsonObject { put("type", "object") },
                    serverName = "s"
                )
            ),
            onToolCall = null,
            callTool = null
        )
        assertEquals("I would look that up, but", result.content)
        assertEquals(emptyList(), result.toolCalls)
    }

    @Test
    fun aToolRoundWithNoNarrationSendsANullAssistantContent() = runTest {
        var requestCount = 0
        val bodies = mutableListOf<String>()
        val oai = service { request ->
            bodies += (request.body as io.ktor.http.content.TextContent).text
            requestCount++
            if (requestCount == 1) {
                // A tool call with no accompanying text at all.
                respondSse(sseBody(toolCallDelta(index = 0, id = "call_1", name = "fn", arguments = "{}")))
            } else {
                respondSse(sseBody(contentDelta("done")))
            }
        }
        val spec = ai.roleswitch.android.service.mcp.McpToolSpec(
            functionName = "fn",
            description = "d",
            parametersSchema = kotlinx.serialization.json.buildJsonObject { put("type", "object") },
            serverName = "s"
        )
        oai.generate(
            messages, "sys", remoteEngineSettings(),
            onChunk = { }, toolSpecs = listOf(spec), onToolCall = null,
            callTool = { _, _ -> "ok" }
        )
        val second = kotlinx.serialization.json.Json.parseToJsonElement(bodies[1]).jsonObject
        val assistantMessage = second["messages"]!!.jsonArray[second["messages"]!!.jsonArray.size - 2].jsonObject
        assertEquals("assistant", assistantMessage["role"]!!.jsonPrimitive.content)
        assertTrue(!assistantMessage.containsKey("content"), "empty narration must serialize as absent: $assistantMessage")
    }

    @Test
    fun aBlankTransportErrorFallsBackToTheWrapperClassName() = runTest {
        val oai = OAIService(
            MockEngine { _ -> throw RuntimeException("") }
        )
        val error = assertFailsWith<OAIError.InvalidResponse> {
            oai.generate(messages, "sys", remoteEngineSettings(), onChunk = { })
        }
        // The client wraps handler failures in its own exception; a blank message
        // means the reason falls back to that wrapper's class name.
        assertEquals("OpenAIHttpException", error.reason)
    }

    @Test
    fun aProxyStyleErrorBodyStillSurfacesAsAnInvalidResponse() = runTest {
        val oai = service { _ ->
            respondErrorJson(HttpStatusCode.Forbidden, "<html>blocked by cloudflare</html>")
        }
        val error = assertFailsWith<OAIError.InvalidResponse> {
            oai.generate(messages, "sys", remoteEngineSettings(), onChunk = { })
        }
        assertTrue(!error.isSilent)
    }

    @Test
    fun fetchModelsListsTheServersModelIds() = runTest {
        var seenAuth: String? = null
        val oai = service { request ->
            seenAuth = request.headers["Authorization"]
            respondJson("""{"object":"list","data":[{"id":"model-a"},{"id":"model-b"}]}""")
        }
        assertEquals(listOf("model-a", "model-b"), oai.fetchModels("http://mock.test/v1", "tok"))
        assertEquals("Bearer tok", seenAuth)
    }

    @Test
    fun fetchModelsWrapsAnyFailureAsAnInvalidResponse() = runTest {
        val oai = service { _ ->
            respondErrorJson(HttpStatusCode.Unauthorized, """{"error":{"message":"bad key","type":"invalid_request_error"}}""")
        }
        val error = assertFailsWith<OAIError.InvalidResponse> {
            oai.fetchModels("http://mock.test/v1", "bad")
        }
        assertTrue(error.message!!.contains("bad key"), error.message)
    }

    @Test
    fun fetchModelsRejectsAnUnparseableEndpoint() = runTest {
        val oai = service { _ -> respondJson("{}") }
        assertFailsWith<OAIError.InvalidURL> {
            oai.fetchModels("http://host:port/", "tok")
        }
    }

    // ---- URL helpers ------------------------------------------------------------------

    @Test
    fun parseBaseUrlAndPathSplitsSchemeHostAndProxyPath() {
        assertEquals("https://api.openai.com" to "v1", parseBaseUrlAndPath("https://api.openai.com/v1"))
        assertEquals("http://localhost:1234" to "v1", parseBaseUrlAndPath("http://localhost:1234/v1"))
        assertEquals("https://proxy.example.com" to "openai/v1", parseBaseUrlAndPath("https://proxy.example.com/openai/v1"))
        assertEquals("https://api.openai.com" to "", parseBaseUrlAndPath("https://api.openai.com"))
        // The default port is not repeated.
        assertEquals("https://api.openai.com" to "v1", parseBaseUrlAndPath("https://api.openai.com:443/v1"))
        // A non-numeric port is what the parser actually rejects.
        assertEquals(null, parseBaseUrlAndPath("http://host:port/"))
        assertEquals(null, parseBaseUrlAndPath("://"))
    }

    @Test
    fun buildHostUrlAppendsTheProxyPathWithTrailingSlashes() {
        assertEquals("https://api.openai.com/", buildHostUrl("https://api.openai.com", ""))
        assertEquals("https://api.openai.com/", buildHostUrl("https://api.openai.com", "/"))
        assertEquals("https://proxy.example.com/openai/v1/", buildHostUrl("https://proxy.example.com", "openai/v1"))
    }

    @Test
    fun theRequestConfigOmitsNothingThatIsBlankAndKeepsEverythingThatIsNot() {
        // The exact shape OAIService derives from a settings row: blank numeric fields
        // become nulls so the request omits them.
        val full = OAIModelConfig(
            endpoint = "http://e/v1",
            token = "t",
            modelName = "m",
            enableVision = true,
            temperature = 0.7,
            topP = 0.9,
            n = 2,
            maxCompletionTokens = 128,
            presencePenalty = 0.1,
            frequencyPenalty = 0.2,
            reasoningEffort = "low"
        )
        assertEquals(0.7, full.temperature!!, 1e-9)
        assertEquals("low", full.reasoningEffort)

        val bare = OAIModelConfig(endpoint = "http://e/v1", token = "t", modelName = "m", enableVision = false)
        assertNull(bare.temperature)
        assertNull(bare.topP)
        assertNull(bare.n)
        assertNull(bare.maxCompletionTokens)
        assertNull(bare.presencePenalty)
        assertNull(bare.frequencyPenalty)
        assertNull(bare.reasoningEffort)
    }
}
